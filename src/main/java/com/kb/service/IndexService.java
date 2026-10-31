package com.kb.service;

import com.kb.entity.DbLibrary;
import com.kb.entity.IndexedDirectory;
import com.kb.entity.ScanError;
import com.kb.repository.DbLibraryRepository;
import com.kb.repository.IndexedDirectoryRepository;
import com.kb.repository.ScanErrorRepository;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.cn.smart.SmartChineseAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
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
import java.util.HashMap;
import java.util.HashSet;
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

    /** 回退用的默认中文分词器（SmartChinese，线程安全） */
    private static final Analyzer FALLBACK_ANALYZER = new SmartChineseAnalyzer();

    private static final Logger log = LoggerFactory.getLogger(IndexService.class);
    private static final long MAX_FILE_BYTES = 20L * 1024 * 1024;
    private static final long MAX_UNKNOWN_TEXT_BYTES = 1024 * 1024;
    private static final int MAX_INDEX_CHARS = 2_000_000;

    private final DbLibraryRepository libRepo;
    private final IndexedDirectoryRepository dirRepo;
    private final SettingService settingService;
    private final ScanErrorRepository scanErrorRepo;
    private final AutoDetectParser parser = new AutoDetectParser();
    private final Map<String, IndexWriter> writers = new ConcurrentHashMap<>();
    /** 扫描进度：目录 id -> [已处理数, 总数]，仅在扫描/对账进行期间存在 */
    private final Map<Long, long[]> progress = new ConcurrentHashMap<>();
    private volatile Analyzer currentAnalyzer;
    private volatile String analyzerName;

    public IndexService(DbLibraryRepository libRepo,
                        IndexedDirectoryRepository dirRepo,
                        SettingService settingService,
                        ScanErrorRepository scanErrorRepo) {
        this.libRepo = libRepo;
        this.dirRepo = dirRepo;
        this.settingService = settingService;
        this.scanErrorRepo = scanErrorRepo;
    }

    /**
     * 查询某目录当前的扫描进度，未在扫描时返回 null。
     */
    public long[] progressOf(Long directoryId) {
        return progress.get(directoryId);
    }

    /**
     * 按设置返回当前分词器（带缓存，设置变更后自动切换）。
     */
    public Analyzer analyzer() {
        String name = settingService.analyzerName();
        if (currentAnalyzer == null || !name.equals(analyzerName)) {
            synchronized (this) {
                if (currentAnalyzer == null || !name.equals(analyzerName)) {
                    currentAnalyzer = createAnalyzer(name);
                    analyzerName = name;
                }
            }
        }
        return currentAnalyzer;
    }

    /**
     * 创建分词器：IK 通过反射加载（依赖不可用时自动回退 SmartChinese）。
     */
    private static Analyzer createAnalyzer(String name) {
        if ("ik".equalsIgnoreCase(name)) {
            try {
                return (Analyzer) Class.forName("org.wltea.analyzer.lucene.IKAnalyzer")
                        .getConstructor(boolean.class).newInstance(true);
            } catch (Throwable t) {
                log.warn("IK 分词器加载失败，回退 SmartChinese: {}", t.toString());
            }
        }
        return FALLBACK_ANALYZER;
    }

    /**
     * 解析某个库的 Lucene 索引目录绝对路径。
     */
    public Path resolveLibIndexDir(DbLibrary lib) {
        return settingService.indexRootPath().resolve(lib.getIndexRelativePath());
    }

    /**
     * 异步扫描指定目录：先清除该目录旧文档，再遍历重建。
     * 第一遍统计可索引文件总数用于进度展示，第二遍执行索引。
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
        scanErrorRepo.deleteAll(scanErrorRepo.findByDirectoryId(directoryId));
        Counter counter = new Counter();
        try {
            Path root = Paths.get(dir.getPath()).toAbsolutePath().normalize();
            String rootNorm = root.toString();
            IndexWriter writer = writerFor(resolveLibIndexDir(lib));
            writer.deleteDocuments(new Term("rootPath", rootNorm));
            progress.put(directoryId, new long[]{0, countIndexable(root)});
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
                        recordError(directoryId, file, e.getMessage());
                        log.warn("索引文件失败: {} - {}", file, e.getMessage());
                    }
                    progress.computeIfPresent(directoryId, (k, v) -> new long[]{v[0] + 1, v[1]});
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    counter.fail++;
                    recordError(directoryId, file, exc.getMessage());
                    return FileVisitResult.CONTINUE;
                }
            });
            writer.commit();
            dir.setStatus(IndexedDirectory.STATUS_DONE);
        } catch (Exception e) {
            log.error("扫描目录失败: {}", dir.getPath(), e);
            dir.setStatus(IndexedDirectory.STATUS_FAILED);
        } finally {
            progress.remove(directoryId);
            dir.setFileCount(counter.ok);
            dir.setScannedAt(LocalDateTime.now());
            dirRepo.save(dir);
            log.info("目录扫描完成: {} 成功 {} 失败 {}", dir.getPath(), counter.ok, counter.fail);
        }
    }

    /**
     * 异步增量对账：比对磁盘文件与索引中已记录的 mtime/size，
     * 仅重新索引有变化的文件，并删除磁盘上已消失文件的文档。
     * 用于启动补漏和监听事件溢出时的兜底。
     */
    @Async("scanExecutor")
    public void reconcileDirectoryAsync(Long directoryId) {
        IndexedDirectory dir = dirRepo.findById(directoryId).orElse(null);
        if (dir == null) {
            return;
        }
        DbLibrary lib = libRepo.findById(dir.getLibraryId()).orElse(null);
        if (lib == null) {
            return;
        }
        Path root = Paths.get(dir.getPath()).toAbsolutePath().normalize();
        String rootNorm = root.toString();
        if (!Files.isDirectory(root)) {
            recordError(directoryId, root, "目录不存在或不可访问");
            dir.setStatus(IndexedDirectory.STATUS_FAILED);
            dirRepo.save(dir);
            return;
        }
        dir.setStatus(IndexedDirectory.STATUS_SCANNING);
        dirRepo.save(dir);
        scanErrorRepo.deleteAll(scanErrorRepo.findByDirectoryId(directoryId));
        Counter counter = new Counter();
        try {
            IndexWriter writer = writerFor(resolveLibIndexDir(lib));
            // 读取索引中该目录已有文档的 path -> [mtime, size]
            Map<String, long[]> existing = loadIndexedFiles(resolveLibIndexDir(lib), rootNorm);
            progress.put(directoryId, new long[]{0, countIndexable(root)});
            Set<String> seen = new HashSet<>();
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
                    seen.add(file.toString());
                    long[] old = existing.get(file.toString());
                    if (old != null
                            && old[0] == attrs.lastModifiedTime().toMillis()
                            && old[1] == attrs.size()) {
                        counter.ok++;
                    } else {
                        try {
                            indexFile(writer, lib, rootNorm, file, attrs);
                            counter.ok++;
                        } catch (Exception e) {
                            counter.fail++;
                            recordError(directoryId, file, e.getMessage());
                            log.warn("增量索引文件失败: {} - {}", file, e.getMessage());
                        }
                    }
                    progress.computeIfPresent(directoryId, (k, v) -> new long[]{v[0] + 1, v[1]});
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    counter.fail++;
                    recordError(directoryId, file, exc.getMessage());
                    return FileVisitResult.CONTINUE;
                }
            });
            // 删除磁盘上已消失的文件的文档
            for (String p : existing.keySet()) {
                if (!seen.contains(p)) {
                    writer.deleteDocuments(new Term("path", p));
                }
            }
            writer.commit();
            dir.setStatus(IndexedDirectory.STATUS_DONE);
        } catch (Exception e) {
            log.error("增量对账失败: {}", dir.getPath(), e);
            dir.setStatus(IndexedDirectory.STATUS_FAILED);
        } finally {
            progress.remove(directoryId);
            dir.setFileCount(counter.ok);
            dir.setScannedAt(LocalDateTime.now());
            dirRepo.save(dir);
            log.info("增量对账完成: {} 有效 {}", dir.getPath(), counter.ok);
        }
    }

    /**
     * 单文件增量更新（由文件监听触发，同步执行）：
     * 新增/修改则重新索引，删除或不再是可索引文件则从索引中移除。
     */
    public void updateSingleFile(Long directoryId, String filePath) {
        IndexedDirectory dir = dirRepo.findById(directoryId).orElse(null);
        if (dir == null) {
            return;
        }
        DbLibrary lib = libRepo.findById(dir.getLibraryId()).orElse(null);
        if (lib == null) {
            return;
        }
        try {
            Path p = Paths.get(filePath).toAbsolutePath().normalize();
            IndexWriter writer = writerFor(resolveLibIndexDir(lib));
            BasicFileAttributes attrs = null;
            try {
                attrs = Files.readAttributes(p, BasicFileAttributes.class);
            } catch (IOException ignored) {
                // 文件不存在，走删除分支
            }
            if (attrs == null || !indexable(p, attrs)) {
                writer.deleteDocuments(new Term("path", p.toString()));
                writer.commit();
                return;
            }
            indexFile(writer, lib, dir.getPath(), p, attrs);
            writer.commit();
        } catch (Exception e) {
            log.warn("增量更新文件失败: {} - {}", filePath, e.getMessage());
            recordError(directoryId, Paths.get(filePath), e.getMessage());
        }
    }

    /**
     * 从索引中读出属于指定根目录的全部文档的 path/mtime/size。
     */
    private Map<String, long[]> loadIndexedFiles(Path libIndexDir, String rootNorm) {
        Map<String, long[]> result = new HashMap<>();
        if (!Files.exists(libIndexDir)) {
            return result;
        }
        try (DirectoryReader reader = DirectoryReader.open(FSDirectory.open(libIndexDir))) {
            IndexSearcher searcher = new IndexSearcher(reader);
            StoredFields storedFields = searcher.storedFields();
            TopDocs hits = searcher.search(
                    new TermQuery(new Term("rootPath", rootNorm)), Integer.MAX_VALUE);
            for (ScoreDoc sd : hits.scoreDocs) {
                Document d = storedFields.document(sd.doc, Set.of("path", "mtime", "size"));
                String path = d.get("path");
                if (path == null) {
                    continue;
                }
                Number mtime = d.getField("mtime") != null ? d.getField("mtime").numericValue() : null;
                Number size = d.getField("size") != null ? d.getField("size").numericValue() : null;
                if (mtime != null && size != null) {
                    result.put(path, new long[]{mtime.longValue(), size.longValue()});
                }
            }
        } catch (Exception e) {
            log.warn("读取已有索引失败，将全量重建: {}", e.getMessage());
            result.clear();
        }
        return result;
    }

    /**
     * 统计某目录下可索引文件总数（跳过点开头目录）。
     */
    private long countIndexable(Path root) throws IOException {
        long[] total = {0};
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) {
                return d.getFileName().toString().startsWith(".")
                        ? FileVisitResult.SKIP_SUBTREE
                        : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (indexable(file, attrs)) {
                    total[0]++;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
        return total[0];
    }

    /**
     * 记录无法处理的文件错误（损坏、权限等），失败不影响主流程。
     */
    private void recordError(Long directoryId, Path file, String msg) {
        try {
            ScanError se = new ScanError();
            se.setDirectoryId(directoryId);
            se.setPath(truncate(file.toString(), 500));
            se.setError(truncate(msg == null ? "未知错误" : msg, 1000));
            se.setCreatedAt(LocalDateTime.now());
            scanErrorRepo.save(se);
        } catch (Exception e) {
            log.debug("记录扫描错误失败: {}", e.getMessage());
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

    /**
     * 关闭并清空全部索引写入器（换索引根目录/切换分词器时调用）。
     */
    public void closeWriters() {
        writers.values().forEach(w -> {
            try {
                w.close();
            } catch (IOException e) {
                log.warn("关闭索引写入器失败: {}", e.getMessage());
            }
        });
        writers.clear();
    }

    @PreDestroy
    public void shutdown() {
        closeWriters();
    }

    private IndexWriter writerFor(Path dir) {
        String key = dir.toString();
        try {
            return writers.computeIfAbsent(key, k -> {
                try {
                    Files.createDirectories(dir);
                    IndexWriterConfig cfg = new IndexWriterConfig(analyzer());
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
        return settingService.supportedExts().contains(ext);
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
