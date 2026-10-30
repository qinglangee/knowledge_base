package com.kb.service;

import com.kb.common.BusinessException;
import com.kb.dto.Dtos.DirectoryDTO;
import com.kb.dto.Dtos.LibraryDTO;
import com.kb.entity.DbLibrary;
import com.kb.entity.IndexedDirectory;
import com.kb.repository.DbLibraryRepository;
import com.kb.repository.IndexedDirectoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 索引库与目录的管理。
 */
@Service
public class LibraryService {

    private static final Logger log = LoggerFactory.getLogger(LibraryService.class);

    private final DbLibraryRepository libRepo;
    private final IndexedDirectoryRepository dirRepo;
    private final IndexService indexService;
    private final SettingService settingService;

    public LibraryService(DbLibraryRepository libRepo,
                          IndexedDirectoryRepository dirRepo,
                          IndexService indexService,
                          SettingService settingService) {
        this.libRepo = libRepo;
        this.dirRepo = dirRepo;
        this.indexService = indexService;
        this.settingService = settingService;
    }

    @Transactional
    public LibraryDTO create(String name) {
        if (name == null || name.isBlank()) {
            throw new BusinessException("库名称不能为空");
        }
        String trimmed = name.trim();
        if (trimmed.length() > 100) {
            throw new BusinessException("库名称过长");
        }
        if (libRepo.existsByName(trimmed)) {
            throw new BusinessException("已存在同名索引库: " + trimmed);
        }
        DbLibrary lib = new DbLibrary();
        lib.setName(trimmed);
        lib.setIndexRelativePath("lib_" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 8));
        lib = libRepo.save(lib);
        return toDTO(lib, 0);
    }

    @Transactional
    public LibraryDTO rename(Long id, String name) {
        if (name == null || name.isBlank()) {
            throw new BusinessException("库名称不能为空");
        }
        String trimmed = name.trim();
        DbLibrary lib = libRepo.findById(id).orElseThrow(() -> new BusinessException(404, "索引库不存在"));
        libRepo.findByName(trimmed)
                .filter(other -> !other.getId().equals(id))
                .ifPresent(other -> {
                    throw new BusinessException("已存在同名索引库: " + trimmed);
                });
        lib.setName(trimmed);
        lib = libRepo.save(lib);
        return toDTO(lib, dirRepo.findByLibraryIdOrderById(id).size());
    }

    public List<LibraryDTO> list() {
        List<DbLibrary> libs = libRepo.findAll();
        Map<Long, Long> counts = dirRepo.findAll().stream()
                .collect(Collectors.groupingBy(IndexedDirectory::getLibraryId, Collectors.counting()));
        return libs.stream()
                .map(l -> toDTO(l, counts.getOrDefault(l.getId(), 0L).intValue()))
                .toList();
    }

    @Transactional
    public void delete(Long id) {
        DbLibrary lib = libRepo.findById(id).orElseThrow(() -> new BusinessException(404, "索引库不存在"));
        dirRepo.deleteAll(dirRepo.findByLibraryIdOrderById(id));
        libRepo.delete(lib);
        if (id.equals(settingService.lastLibraryId())) {
            settingService.clearLastLibrary();
        }
        indexService.dropLibraryIndex(lib);
        log.info("索引库已删除: {} ({})", lib.getName(), lib.getId());
    }

    public List<DirectoryDTO> listDirs(Long libraryId) {
        libRepo.findById(libraryId).orElseThrow(() -> new BusinessException(404, "索引库不存在"));
        return dirRepo.findByLibraryIdOrderById(libraryId).stream().map(this::toDTO).toList();
    }

    /**
     * 注册目录并立即触发异步扫描。
     * 注意：这里不加事务，保证目录记录先落库，异步线程才能读到。
     */
    public DirectoryDTO addDirectory(Long libraryId, String pathStr) {
        DbLibrary lib = libRepo.findById(libraryId).orElseThrow(() -> new BusinessException(404, "索引库不存在"));
        if (pathStr == null || pathStr.isBlank()) {
            throw new BusinessException("目录路径不能为空");
        }
        Path p;
        try {
            p = Paths.get(pathStr.trim()).toAbsolutePath().normalize();
        } catch (Exception e) {
            throw new BusinessException("路径无效: " + pathStr);
        }
        if (!Files.isDirectory(p)) {
            throw new BusinessException("目录不存在或不是文件夹: " + p);
        }
        for (IndexedDirectory other : dirRepo.findAll()) {
            Path op = Paths.get(other.getPath());
            if (p.equals(op) || p.startsWith(op) || op.startsWith(p)) {
                throw new BusinessException("目录与已注册目录重叠: " + other.getPath());
            }
        }
        IndexedDirectory dir = new IndexedDirectory();
        dir.setLibraryId(lib.getId());
        dir.setPath(p.toString());
        dir.setStatus(IndexedDirectory.STATUS_PENDING);
        dir = dirRepo.save(dir);
        indexService.scanDirectoryAsync(dir.getId());
        return toDTO(dir);
    }

    @Transactional
    public void deleteDirectory(Long dirId) {
        IndexedDirectory dir = dirRepo.findById(dirId).orElseThrow(() -> new BusinessException(404, "目录不存在"));
        DbLibrary lib = libRepo.findById(dir.getLibraryId()).orElse(null);
        if (lib != null) {
            indexService.deleteDirectoryDocs(lib, dir.getPath());
        }
        dirRepo.delete(dir);
    }

    public void rescanDirectory(Long dirId) {
        dirRepo.findById(dirId).orElseThrow(() -> new BusinessException(404, "目录不存在"));
        indexService.scanDirectoryAsync(dirId);
    }

    public void rescanLibrary(Long libraryId) {
        libRepo.findById(libraryId).orElseThrow(() -> new BusinessException(404, "索引库不存在"));
        dirRepo.findByLibraryIdOrderById(libraryId).forEach(d -> indexService.scanDirectoryAsync(d.getId()));
    }

    private LibraryDTO toDTO(DbLibrary lib, int dirCount) {
        return new LibraryDTO(lib.getId(), lib.getName(), lib.getIndexRelativePath(), lib.getCreatedAt(), dirCount);
    }

    private DirectoryDTO toDTO(IndexedDirectory dir) {
        return new DirectoryDTO(dir.getId(), dir.getLibraryId(), dir.getPath(), dir.getStatus(),
                dir.getFileCount(), dir.getScannedAt());
    }
}
