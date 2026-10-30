package com.kb.service;

import com.kb.entity.IndexedDirectory;
import com.kb.repository.DbLibraryRepository;
import com.kb.repository.IndexedDirectoryRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 文件变更监听：基于 WatchService 对已注册目录做递归监听，
 * 事件经 1.5s 防抖后逐文件增量更新索引；事件溢出时触发目录对账兜底。
 */
@Component
public class FileWatcherService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(FileWatcherService.class);
    private static final long DEBOUNCE_MS = 1500;

    private final IndexedDirectoryRepository dirRepo;
    private final DbLibraryRepository libRepo;
    private final IndexService indexService;

    private WatchService watchService;
    /** 已注册的子目录绝对路径 -> WatchKey */
    private final Map<String, WatchKey> regKeys = new ConcurrentHashMap<>();
    /** 监听根目录路径 -> 目录记录 id */
    private final Map<String, Long> rootDirIds = new ConcurrentHashMap<>();
    /** 防抖窗口内的待处理文件：文件路径 -> Job */
    private final Map<String, Job> pending = new ConcurrentHashMap<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "index-watcher-worker");
        t.setDaemon(true);
        return t;
    });
    private final ScheduledExecutorService tick = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "index-watcher-tick");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean running = true;

    private record Job(Long dirId, String path, long dueAt) {
    }

    public FileWatcherService(IndexedDirectoryRepository dirRepo,
                              DbLibraryRepository libRepo,
                              IndexService indexService) {
        this.dirRepo = dirRepo;
        this.libRepo = libRepo;
        this.indexService = indexService;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            watchService = FileSystems.getDefault().newWatchService();
        } catch (IOException e) {
            log.error("创建 WatchService 失败，增量监听不可用: {}", e.getMessage());
            return;
        }
        List<IndexedDirectory> dirs = dirRepo.findAll();
        for (IndexedDirectory dir : dirs) {
            register(dir.getId(), Paths.get(dir.getPath()));
        }
        // 启动补漏：对已完成扫描的目录做一次增量对账，覆盖停机期间的变更
        for (IndexedDirectory dir : dirs) {
            if (IndexedDirectory.STATUS_DONE.equals(dir.getStatus())) {
                indexService.reconcileDirectoryAsync(dir.getId());
            }
        }
        Thread loop = new Thread(this::loop, "index-watcher-loop");
        loop.setDaemon(true);
        loop.start();
        tick.scheduleWithFixedDelay(this::drainPending, 1, 1, TimeUnit.SECONDS);
        log.info("文件变更监听已启动，共监听 {} 个目录", dirs.size());
    }

    /**
     * 注册一个监听根目录（递归注册所有非点开头的子目录）。
     */
    public void register(Long dirId, Path root) {
        rootDirIds.put(root.toString(), dirId);
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) {
                    if (!d.equals(root) && d.getFileName().toString().startsWith(".")) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    try {
                        WatchKey key = d.register(watchService,
                                StandardWatchEventKinds.ENTRY_CREATE,
                                StandardWatchEventKinds.ENTRY_MODIFY,
                                StandardWatchEventKinds.ENTRY_DELETE);
                        regKeys.put(d.toString(), key);
                    } catch (IOException e) {
                        log.warn("注册监听失败: {} - {}", d, e.getMessage());
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.warn("遍历目录失败: {} - {}", root, e.getMessage());
        }
    }

    /**
     * 取消某个根目录及其全部子目录的监听，并丢弃其待处理任务。
     */
    public void unregister(Path root) {
        String rootStr = root.toString();
        rootDirIds.remove(rootStr);
        regKeys.entrySet().removeIf(e -> {
            boolean matches = e.getKey().equals(rootStr)
                    || e.getKey().startsWith(rootStr + File.separator);
            if (matches) {
                e.getValue().cancel();
            }
            return matches;
        });
        pending.keySet().removeIf(p -> p.startsWith(rootStr + File.separator));
    }

    private void loop() {
        while (running) {
            try {
                WatchKey key = watchService.take();
                processKey(key);
            } catch (ClosedWatchServiceException e) {
                break;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.warn("处理文件事件失败: {}", e.getMessage());
            }
        }
    }

    private void processKey(WatchKey key) {
        Path watched = (Path) key.watchable();
        for (WatchEvent<?> event : key.pollEvents()) {
            if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                Long dirId = resolveDirId(watched);
                if (dirId != null) {
                    log.warn("监听事件溢出，触发目录对账: {}", watched);
                    indexService.reconcileDirectoryAsync(dirId);
                }
                continue;
            }
            Path context = event.context() instanceof Path p ? p : null;
            if (context == null) {
                continue;
            }
            Path child = watched.resolve(context);
            String name = child.getFileName() != null ? child.getFileName().toString() : "";
            if (name.startsWith(".")) {
                continue;
            }
            if (Files.isDirectory(child)) {
                if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE) {
                    onDirectoryCreated(child);
                }
                continue;
            }
            Long dirId = resolveDirId(child);
            if (dirId != null) {
                schedule(dirId, child);
            }
        }
        boolean valid = key.reset();
        if (!valid) {
            regKeys.remove(watched.toString(), key);
            log.info("监听失效（目录可能已删除）: {}", watched);
        }
    }

    /**
     * 新建目录：注册其整个子树的监听，并调度其中全部文件。
     */
    private void onDirectoryCreated(Path dir) {
        Long dirId = resolveDirId(dir);
        if (dirId == null) {
            return;
        }
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) {
                    if (!d.equals(dir) && d.getFileName().toString().startsWith(".")) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    try {
                        WatchKey key = d.register(watchService,
                                StandardWatchEventKinds.ENTRY_CREATE,
                                StandardWatchEventKinds.ENTRY_MODIFY,
                                StandardWatchEventKinds.ENTRY_DELETE);
                        regKeys.put(d.toString(), key);
                    } catch (IOException e) {
                        log.warn("注册监听失败: {} - {}", d, e.getMessage());
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    schedule(dirId, file);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.warn("遍历新建目录失败: {} - {}", dir, e.getMessage());
        }
    }

    private void schedule(Long dirId, Path file) {
        pending.put(file.toString(), new Job(dirId, file.toString(),
                System.currentTimeMillis() + DEBOUNCE_MS));
    }

    /**
     * 每秒检查一次防抖队列，到期的文件提交给 worker 增量更新。
     */
    private void drainPending() {
        if (pending.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Job> e : pending.entrySet()) {
            Job job = e.getValue();
            if (job.dueAt() <= now && pending.remove(e.getKey(), job)) {
                try {
                    worker.submit(() -> indexService.updateSingleFile(job.dirId(), job.path()));
                } catch (Exception ex) {
                    log.debug("提交增量任务失败: {}", ex.getMessage());
                }
            }
        }
    }

    /**
     * 解析路径所属的监听根目录 id（取最长匹配前缀）。
     */
    private Long resolveDirId(Path p) {
        String s = p.toString();
        Long best = null;
        int bestLen = -1;
        for (Map.Entry<String, Long> e : rootDirIds.entrySet()) {
            String root = e.getKey();
            if ((s.equals(root) || s.startsWith(root + File.separator))
                    && root.length() > bestLen) {
                bestLen = root.length();
                best = e.getValue();
            }
        }
        return best;
    }

    @PreDestroy
    public void shutdown() {
        running = false;
        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException ignored) {
            }
        }
        tick.shutdownNow();
        worker.shutdownNow();
    }
}
