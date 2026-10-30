package com.kb.service;

import com.kb.common.BusinessException;
import com.kb.dto.Dtos.SettingsDTO;
import com.kb.dto.Dtos.SettingsUpdateRequest;
import com.kb.entity.AppSetting;
import com.kb.repository.AppSettingRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;

@Service
public class SettingService {

    public static final String KEY_INDEX_ROOT = "index.root";
    public static final String KEY_PAGE_SIZE = "page.size";
    public static final String KEY_LAST_LIBRARY_ID = "last.library.id";

    public static final String DEFAULT_INDEX_ROOT = "data/indexes";
    public static final int DEFAULT_PAGE_SIZE = 20;

    private static final Logger log = LoggerFactory.getLogger(SettingService.class);

    private final AppSettingRepository repo;

    public SettingService(AppSettingRepository repo) {
        this.repo = repo;
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

    @Transactional
    public void update(SettingsUpdateRequest req) {
        if (req.indexRoot() != null && !req.indexRoot().isBlank()) {
            String old = getOrDefault(KEY_INDEX_ROOT, DEFAULT_INDEX_ROOT);
            if (!old.equals(req.indexRoot().trim())) {
                Path oldRoot = Paths.get(old).toAbsolutePath().normalize();
                Path newRoot = Paths.get(req.indexRoot().trim()).toAbsolutePath().normalize();
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
    }

    public void saveLastLibrary(Long libraryId) {
        save(KEY_LAST_LIBRARY_ID, String.valueOf(libraryId));
    }

    public void clearLastLibrary() {
        save(KEY_LAST_LIBRARY_ID, "");
    }

    public SettingsDTO asDTO() {
        return new SettingsDTO(getOrDefault(KEY_INDEX_ROOT, DEFAULT_INDEX_ROOT), pageSize(), lastLibraryId());
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
