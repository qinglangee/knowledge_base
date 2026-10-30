package com.kb.service;

import com.kb.common.BusinessException;
import com.kb.dto.Dtos.SettingsDTO;
import com.kb.dto.Dtos.SettingsUpdateRequest;
import com.kb.entity.AppSetting;
import com.kb.repository.AppSettingRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Stream;

@Service
public class SettingService {

    public static final String KEY_INDEX_ROOT = "index.root";
    public static final String KEY_PAGE_SIZE = "page.size";
    public static final String KEY_LAST_LIBRARY_ID = "last.library.id";
    public static final String KEY_ANALYZER = "analyzer";

    public static final String DEFAULT_INDEX_ROOT = "data/indexes";
    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final String DEFAULT_ANALYZER = "smartcn";
    /** 应用级配置文件（server.port 等），优先级高于 application.yml */
    public static final Path APP_CONFIG_FILE = Paths.get("data", "app.properties");

    private static final Logger log = LoggerFactory.getLogger(SettingService.class);

    private final AppSettingRepository repo;
    private final IndexService indexService;
    /** 当前实际运行端口（启动时解析） */
    private final int runningPort;

    public SettingService(AppSettingRepository repo,
                          @Lazy IndexService indexService,
                          @Value("${server.port:8080}") int runningPort) {
        this.repo = repo;
        this.indexService = indexService;
        this.runningPort = runningPort;
    }

    @PostConstruct
    void init() {
        getOrDefault(KEY_INDEX_ROOT, DEFAULT_INDEX_ROOT);
        getOrDefault(KEY_PAGE_SIZE, String.valueOf(DEFAULT_PAGE_SIZE));
        try {
            Files.createDirectories(indexRootPath());
        } catch (IOException e) {
            throw new IllegalStateException("无法创建索引根目录: " + indexRootPath(), e);
        }
    }

    public String get(String key) {
        return repo.findById(key).map(AppSetting::getValue).orElse(null);
    }

    private String getOrDefault(String key, String def) {
        String v = get(key);
        return v == null || v.isBlank() ? def : v;
    }

    public Path indexRootPath() {
        return Paths.get(getOrDefault(KEY_INDEX_ROOT, DEFAULT_INDEX_ROOT)).toAbsolutePath().normalize();
    }

    public int pageSize() {
        try {
            return Integer.parseInt(getOrDefault(KEY_PAGE_SIZE, String.valueOf(DEFAULT_PAGE_SIZE)));
        } catch (NumberFormatException e) {
            return DEFAULT_PAGE_SIZE;
        }
    }

    public Long lastLibraryId() {
        String v = get(KEY_LAST_LIBRARY_ID);
        if (v == null || v.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public String analyzerName() {
        return getOrDefault(KEY_ANALYZER, DEFAULT_ANALYZER);
    }

    public int runningPort() {
        return runningPort;
    }

    /**
     * 读取配置文件中配置的服务端口，未配置时返回 null。
     */
    public Integer configuredPort() {
        String v = loadAppConfig().getProperty("server.port");
        if (v == null || v.isBlank()) {
            return null;
        }
        try {
            int p = Integer.parseInt(v.trim());
            return (p >= 1 && p <= 65535) ? p : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Transactional
    public void update(SettingsUpdateRequest req) {
        if (req.indexRoot() != null && !req.indexRoot().isBlank()) {
            Path newRoot = Paths.get(req.indexRoot().trim()).toAbsolutePath().normalize();
            Path oldRoot = indexRootPath();
            if (!oldRoot.equals(newRoot)) {
                requireEmptyDir(newRoot);
                indexService.closeWriters();
                moveIndexRoot(oldRoot, newRoot);
                save(KEY_INDEX_ROOT, req.indexRoot().trim());
                log.info("索引根目录已从 {} 迁移到 {}", oldRoot, newRoot);
            }
        }
        if (req.pageSize() != null) {
            if (req.pageSize() < 1 || req.pageSize() > 100) {
                throw new BusinessException("每页数量需在 1-100 之间");
            }
            save(KEY_PAGE_SIZE, String.valueOf(req.pageSize()));
        }
        if (req.lastLibraryId() != null) {
            save(KEY_LAST_LIBRARY_ID, String.valueOf(req.lastLibraryId()));
        }
        if (req.port() != null) {
            if (req.port() < 1 || req.port() > 65535) {
                throw new BusinessException("端口需在 1-65535 之间");
            }
            saveAppPort(req.port());
        }
        if (req.analyzer() != null && !req.analyzer().isBlank()) {
            String a = req.analyzer().trim().toLowerCase(Locale.ROOT);
            if (!Set.of("smartcn", "ik").contains(a)) {
                throw new BusinessException("不支持的分词器: " + req.analyzer());
            }
            if (!a.equals(analyzerName())) {
                save(KEY_ANALYZER, a);
                // 关闭已有写入器，让后续索引与搜索使用新分词器
                indexService.closeWriters();
            }
        }
    }

    public void saveLastLibrary(Long libraryId) {
        save(KEY_LAST_LIBRARY_ID, String.valueOf(libraryId));
    }

    public void clearLastLibrary() {
        save(KEY_LAST_LIBRARY_ID, "");
    }

    public SettingsDTO asDTO() {
        return new SettingsDTO(getOrDefault(KEY_INDEX_ROOT, DEFAULT_INDEX_ROOT), pageSize(),
                lastLibraryId(), configuredPort(), runningPort, analyzerName());
    }

    private void save(String key, String value) {
        AppSetting s = repo.findById(key).orElseGet(() -> {
            AppSetting n = new AppSetting();
            n.setKey(key);
            return n;
        });
        s.setValue(value);
        repo.save(s);
    }

    /**
     * 校验目标目录为空（不存在视为通过）。
     */
    private void requireEmptyDir(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        if (!Files.isDirectory(dir)) {
            throw new BusinessException("目标路径不是目录: " + dir);
        }
        try (Stream<Path> children = Files.list(dir)) {
            if (children.findAny().isPresent()) {
                throw new BusinessException("目标目录不是空的，请先清空后再修改: " + dir);
            }
        } catch (IOException e) {
            throw new BusinessException("无法读取目标目录: " + dir);
        }
    }

    /**
     * 把服务端口写入应用配置文件（data/app.properties），重启后生效。
     */
    private void saveAppPort(int port) {
        Path file = APP_CONFIG_FILE;
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Properties props = loadAppConfig();
            props.setProperty("server.port", String.valueOf(port));
            try (OutputStream out = Files.newOutputStream(file)) {
                props.store(out, "Knowledge Base config (server.port takes effect after restart)");
            }
            log.info("服务端口配置已保存: {}（重启后生效）", port);
        } catch (IOException e) {
            throw new BusinessException(500, "写入配置文件失败: " + e.getMessage());
        }
    }

    private Properties loadAppConfig() {
        Properties props = new Properties();
        if (Files.exists(APP_CONFIG_FILE)) {
            try (InputStream in = Files.newInputStream(APP_CONFIG_FILE)) {
                props.load(in);
            } catch (IOException e) {
                log.warn("读取应用配置文件失败: {}", APP_CONFIG_FILE, e);
            }
        }
        return props;
    }

    /**
     * 索引根目录变更时，把已有库的索引目录整体迁移到新位置。
     */
    private void moveIndexRoot(Path oldRoot, Path newRoot) {
        if (!Files.isDirectory(oldRoot)) {
            return;
        }
        try {
            Files.createDirectories(newRoot);
            try (Stream<Path> children = Files.list(oldRoot)) {
                children.forEach(child -> {
                    Path target = newRoot.resolve(child.getFileName());
                    if (Files.exists(target)) {
                        log.warn("目标已存在，跳过迁移: {}", target);
                        return;
                    }
                    try {
                        Files.move(child, target);
                    } catch (IOException e) {
                        // 跨盘符等场景 Files.move 会失败，降级为复制+删除
                        try {
                            copyRecursive(child, target);
                            deleteRecursive(child);
                        } catch (IOException e2) {
                            throw new RuntimeException(e2);
                        }
                    }
                });
            }
        } catch (IOException e) {
            throw new BusinessException(500, "迁移索引目录失败: " + e.getMessage());
        }
    }

    private void copyRecursive(Path src, Path dst) throws IOException {
        try (Stream<Path> walk = Files.walk(src)) {
            walk.forEach(source -> {
                Path target = dst.resolve(src.relativize(source));
                try {
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    private void deleteRecursive(Path path) throws IOException {
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }
}
