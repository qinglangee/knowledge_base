package com.kb.service;

import com.kb.entity.DbLibrary;
import com.kb.entity.IndexedDirectory;
import com.kb.repository.DbLibraryRepository;
import com.kb.repository.IndexedDirectoryRepository;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.cn.smart.SmartChineseAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.store.FSDirectory;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 索引服务：目录扫描、Tika 内容抽取、Lucene 建索引、索引目录删除。
 */
@Service
public class IndexService {

    /** 全局共享的中文分词器（线程安全） */
    public static final Analyzer ANALYZER = new SmartChineseAnalyzer();

    private static final Logger log = LoggerFactory.getLogger(IndexService.class);
    private static final long MAX_FILE_BYTES = 20L * 1024 * 1024;
    private static final long MAX_UNKNOWN_TEXT_BYTES = 1024 * 1024;
    private static final int MAX_INDEX_CHARS = 2_000_000;

    /** 参与内容索引的扩展名白名单 */
    private static final Set<String> SUPPORTED_EXTS = Set.of(
            "txt", "md", "markdown", "html", "htm", "xml", "json", "yaml", "yml", "csv", "tsv", "log",
            "java", "py", "js", "ts", "jsx", "tsx", "css", "scss", "sql", "sh", "bat", "ps1",
            "properties", "conf", "ini", "cfg", "rst", "adoc",
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "rtf", "odt", "ods", "odp", "epub");

    private final DbLibraryRepository libRepo;
    private final IndexedDirectoryRepository dirRepo;
    private final SettingService settingService;
    private final AutoDetectParser parser = new AutoDetectParser();
    private final Map<String, IndexWriter> writers = new ConcurrentHashMap<>();

    public IndexService(DbLibraryRepository libRepo,
                        IndexedDirectoryRepository dirRepo,
                        SettingService settingService) {
        this.libRepo = libRepo;
        this.dirRepo = dirRepo;
        this.settingService = settingService;
    }

    /**
     * 解析某个库的 Lucene 索引目录绝对路径。
     */
    public Path resolveLibIndexDir(DbLibrary lib) {
        return settingService.indexRootPath().resolve(lib.getIndexRelativePath());
    }

    /**
     * 异步扫描指定目录：先清除该目录旧文档，再遍历重建。
     */
    @Async("scanExecutor")
    public void scanDirectoryAsync(Long directoryId) {
        IndexedDirectory dir = dirRepo.findById(directoryId).orElse(null);
        if (dir == null) {
            return;
        }
        DbLibrary lib = libRepo.findById(dir.getLibraryId()).orElse(null);
        if (lib == null) {
            return;
        }
        dir.setStatus(IndexedDirectory.STATUS_SCANNING);
        dirRepo.save(dir);
        Counter counter = new Counter();
        try {
            Path root = Paths.get(dir.getPath()).toAbsolutePath().normalize();
            String rootNorm = root.toString();
            IndexWriter writer = writerFor(resolveLibIndexDir(lib));
            writer.deleteDocuments(new Term("rootPath", rootNorm));
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) {
                    return d.getFileName().toString().startsWith(".")
                            ? FileVisitResult.SKIP_SUBTREE
                            : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (!indexable(file, attrs)) {
                        return FileVisitResult.CONTINUE;
                    }
                    try {
                        indexFile(writer, lib, rootNorm, file, attrs);
                        counter.ok++;
                    } catch (Exception e) {
                        counter.fail++;
                        log.warn("索引文件失败: {} - {}", file, e.getMessage());
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
            writer.commit();
            dir.setStatus(IndexedDirectory.STATUS_DONE);
        } catch (Exception e) {
            log.error("扫描目录失败: {}", dir.getPath(), e);
            dir.setStatus(IndexedDirectory.STATUS_FAILED);
        } finally {
            dir.setFileCount(counter.ok);
            dir.setScannedAt(LocalDateTime.now());
            dirRepo.save(dir);
            log.info("目录扫描完成: {} 成功 {} 失败 {}", dir.getPath(), counter.ok, counter.fail);
        }
    }

    /**
     * 删除某个目录写入索引中的全部文档。
     */
    public void deleteDirectoryDocs(DbLibrary lib, String directoryPath) {
        try {
            IndexWriter writer = writerFor(resolveLibIndexDir(lib));
            String rootNorm = Paths.get(directoryPath).toAbsolutePath().normalize().toString();
            writer.deleteDocuments(new Term("rootPath", rootNorm));
            writer.commit();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 关闭并删除某个库的整个索引目录。
     */
    public void dropLibraryIndex(DbLibrary lib) {
        Path dir = resolveLibIndexDir(lib);
        IndexWriter writer = writers.remove(dir.toString());
        if (writer != null) {
            try {
                writer.close();
            } catch (IOException e) {
                log.warn("关闭索引写入器失败: {}", dir, e);
            }
        }
        if (Files.exists(dir)) {
            try (var walk = Files.walk(dir)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.delete(p);
                    } catch (IOException e) {
                        log.warn("删除索引文件失败: {}", p, e);
                    }
                });
            } catch (IOException e) {
                log.warn("删除索引目录失败: {}", dir, e);
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        writers.values().forEach(w -> {
            try {
                w.close();
            } catch (IOException ignored) {
            }
        });
        writers.clear();
    }

    private IndexWriter writerFor(Path dir) {
        String key = dir.toString();
        try {
            return writers.computeIfAbsent(key, k -> {
                try {
                    Files.createDirectories(dir);
                    IndexWriterConfig cfg = new IndexWriterConfig(ANALYZER);
                    cfg.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
                    return new IndexWriter(FSDirectory.open(dir), cfg);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException e) {
            throw new RuntimeException("打开索引写入器失败: " + dir, e);
        }
    }

    private boolean indexable(Path file, BasicFileAttributes attrs) {
        if (!attrs.isRegularFile() || attrs.size() > MAX_FILE_BYTES) {
            return false;
        }
        String ext = extOf(file);
        if (ext.isEmpty()) {
            // 无扩展名的小文件按文本尝试
            return attrs.size() <= MAX_UNKNOWN_TEXT_BYTES;
        }
        return SUPPORTED_EXTS.contains(ext);
    }

    private void indexFile(IndexWriter writer, DbLibrary lib, String rootNorm, Path file,
                           BasicFileAttributes attrs) throws Exception {
        String text = extractText(file);
        if (text == null || text.isBlank()) {
            return;
        }
        String path = file.toString();
        String uid = sha256(path);
        Document doc = new Document();
        doc.add(new StringField("uid", uid, Field.Store.NO));
        doc.add(new StringField("path", path, Field.Store.YES));
        doc.add(new TextField("name", file.getFileName().toString(), Field.Store.YES));
        doc.add(new StringField("ext", extOf(file), Field.Store.YES));
        doc.add(new StringField("rootPath", rootNorm, Field.Store.NO));
        doc.add(new StringField("libId", String.valueOf(lib.getId()), Field.Store.YES));
        doc.add(new TextField("content", truncate(text, MAX_INDEX_CHARS), Field.Store.YES));
        doc.add(new StoredField("size", attrs.size()));
        doc.add(new StoredField("mtime", attrs.lastModifiedTime().toMillis()));
        doc.add(new LongPoint("mtimePoint", attrs.lastModifiedTime().toMillis()));
        writer.updateDocument(new Term("uid", uid), doc);
    }

    private String extractText(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            Metadata md = new Metadata();
            md.set(TikaCoreProperties.RESOURCE_NAME_KEY, file.getFileName().toString());
            BodyContentHandler handler = new BodyContentHandler(-1);
            parser.parse(in, handler, md, new ParseContext());
            return handler.toString();
        } catch (Exception e) {
            log.debug("内容抽取失败: {} - {}", file, e.getMessage());
            return null;
        }
    }

    private static String extOf(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class Counter {
        int ok;
        int fail;
    }
}
