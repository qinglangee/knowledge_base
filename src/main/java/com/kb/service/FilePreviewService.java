package com.kb.service;

import com.kb.common.BusinessException;
import com.kb.dto.Dtos.TextPreviewDTO;
import com.kb.entity.IndexedDirectory;
import com.kb.repository.IndexedDirectoryRepository;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Set;

/**
 * 文件预览服务。
 * 安全约束：仅允许访问已注册扫描目录内的文件。
 */
@Service
public class FilePreviewService {

    private static final Logger log = LoggerFactory.getLogger(FilePreviewService.class);

    private static final long MAX_RAW_BYTES = 50L * 1024 * 1024;
    private static final int MAX_TEXT_CHARS = 500_000;

    /** 可直接以原始字节流预览的类型（浏览器原生渲染） */
    private static final Set<String> RAW_IMAGE_EXTS = Set.of("png", "jpg", "jpeg", "gif", "bmp", "webp", "svg", "ico");
    private static final Set<String> TEXT_EXTS = Set.of(
            "txt", "md", "markdown", "csv", "tsv", "log", "json", "xml", "yaml", "yml",
            "html", "htm", "css", "js", "ts", "java", "py", "sql", "sh", "bat", "ps1",
            "properties", "conf", "ini", "cfg", "rst", "adoc");
    private static final Set<String> OFFICE_EXTS = Set.of("doc", "docx", "xls", "xlsx", "ppt", "pptx", "rtf",
            "odt", "ods", "odp", "epub");

    private final IndexedDirectoryRepository dirRepo;
    private final AutoDetectParser parser = new AutoDetectParser();

    public FilePreviewService(IndexedDirectoryRepository dirRepo) {
        this.dirRepo = dirRepo;
    }

    /**
     * 校验请求路径必须位于某个已注册扫描目录内，返回规范化后的路径。
     */
    public Path checkAccess(String pathStr) {
        if (pathStr == null || pathStr.isBlank()) {
            throw new BusinessException("缺少 path 参数");
        }
        Path p;
        try {
            p = Paths.get(pathStr.trim()).toAbsolutePath().normalize();
        } catch (Exception e) {
            throw new BusinessException("路径无效: " + pathStr);
        }
        try {
            if (Files.exists(p)) {
                p = p.toRealPath();
            }
        } catch (IOException ignored) {
        }
        for (IndexedDirectory d : dirRepo.findAll()) {
            Path root = Paths.get(d.getPath());
            try {
                if (Files.exists(root)) {
                    root = root.toRealPath();
                }
            } catch (IOException ignored) {
            }
            if (p.startsWith(root)) {
                return p;
            }
        }
        throw new BusinessException(403, "文件不在已注册的目录内");
    }

    public boolean isRawType(Path p) {
        String ext = extOf(p);
        return "pdf".equals(ext) || RAW_IMAGE_EXTS.contains(ext);
    }

    public record RawFile(String mimeType, byte[] bytes) {
    }

    public RawFile raw(Path p) {
        String ext = extOf(p);
        try {
            long size = Files.size(p);
            if (size > MAX_RAW_BYTES) {
                throw new BusinessException("文件过大，不支持预览: " + size + " 字节");
            }
            return new RawFile(mimeOf(ext), Files.readAllBytes(p));
        } catch (IOException e) {
            throw new BusinessException(500, "读取文件失败: " + e.getMessage());
        }
    }

    /**
     * 文本预览：文本类型直接解码；Office 等其它类型用 Tika 抽取纯文本（降级预览）。
     */
    public TextPreviewDTO text(Path p) {
        String ext = extOf(p);
        String fileName = p.getFileName() == null ? p.toString() : p.getFileName().toString();
        try {
            long size = Files.size(p);
            String content;
            String mime;
            if (TEXT_EXTS.contains(ext)) {
                byte[] bytes = Files.readAllBytes(p);
                content = decode(bytes);
                mime = "text/plain";
            } else {
                content = extractText(p);
                if (content == null || content.isBlank()) {
                    throw new BusinessException("该文件类型不支持预览（未能抽取到文本内容）");
                }
                mime = "text/plain";
            }
            boolean truncated = content.length() > MAX_TEXT_CHARS;
            if (truncated) {
                content = content.substring(0, MAX_TEXT_CHARS);
            }
            return new TextPreviewDTO("text", mime, fileName, size, content, truncated);
        } catch (IOException e) {
            throw new BusinessException(500, "读取文件失败: " + e.getMessage());
        }
    }

    private String extractText(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            Metadata md = new Metadata();
            md.set(TikaCoreProperties.RESOURCE_NAME_KEY, file.getFileName().toString());
            BodyContentHandler handler = new BodyContentHandler(-1);
            parser.parse(in, handler, md, new ParseContext());
            return handler.toString();
        } catch (Exception e) {
            log.debug("预览内容抽取失败: {} - {}", file, e.getMessage());
            return null;
        }
    }

    /**
     * 简单编码探测：先按 UTF-8 严格解码，失败则按 GBK，再失败按 ISO-8859-1。
     */
    private static String decode(byte[] bytes) {
        try {
            return Charset.forName("UTF-8").newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            try {
                return Charset.forName("GBK").newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            } catch (CharacterCodingException e2) {
                return new String(bytes, StandardCharsets.ISO_8859_1);
            }
        }
    }

    private static String extOf(Path p) {
        if (p.getFileName() == null) {
            return "";
        }
        String name = p.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String mimeOf(String ext) {
        return switch (ext) {
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "bmp" -> "image/bmp";
            case "webp" -> "image/webp";
            case "svg" -> "image/svg+xml";
            case "ico" -> "image/x-icon";
            case "pdf" -> "application/pdf";
            default -> "application/octet-stream";
        };
    }
}
