package com.github.lechandonga.localcodeindexerjava.indexer;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 索引生命周期管理：
 * <ul>
 *   <li>同一时间最多一个刷新任务在跑，由 {@link #refreshPermit}（单许可信号量）保证；</li>
 *   <li>刷新在独立单线程中执行，扫描完成后用 volatile 引用原子替换快照，
 *       查询永远读到完整的旧快照或完整的新快照；</li>
 *   <li>刷新失败时保留旧快照，查询不受影响。</li>
 * </ul>
 */
@Service
public class IndexManager {

    private static final Logger log = LoggerFactory.getLogger(IndexManager.class);

    private final IndexerProperties properties;
    private final CodeScanner scanner;

    /** 单许可信号量：许可从提交线程获取、由执行扫描的工作线程释放，跨整个扫描周期占位。 */
    private final Semaphore refreshPermit = new Semaphore(1);
    private final ExecutorService refreshExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "local-indexer-refresh");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicLong generationSequence = new AtomicLong(0);
    private final AtomicReference<IndexSnapshot> snapshotRef = new AtomicReference<>();
    private volatile CompletableFuture<IndexSnapshot> runningFuture;
    private volatile MutableStatus currentStatus;

    public IndexManager(IndexerProperties properties, CodeScanner scanner) {
        this.properties = properties;
        this.scanner = scanner;
        this.currentStatus = new MutableStatus(RefreshState.IDLE, 0,
                safeRootDisplay(), 0L, null, null, null);
    }

    /**
     * 触发一次刷新。
     *
     * @param wait true 时阻塞等待刷新结束并返回最终状态；false 时进行中则立即返回 RUNNING 状态
     * @return 刷新状态；若复用了进行中的任务，joinedExisting 为 true
     */
   public RefreshStatus triggerRefresh(boolean wait) {
        // 先尝试占位：任何时刻最多一个刷新任务，校验失败也算一次已结束的刷新
        MutableStatus status;
        CompletableFuture<IndexSnapshot> future;
        boolean joined = false;

        if (!refreshPermit.tryAcquire()) {
            CompletableFuture<IndexSnapshot> existing = runningFuture;
            if (existing != null
                    && properties.getRefreshConflictPolicy() == IndexerProperties.RefreshConflictPolicy.join) {
                future = existing;
                joined = true;
            } else {
                throw conflictException();
            }
        } else {
            try {
                long generation = generationSequence.incrementAndGet();
                long startedAt = System.currentTimeMillis();
                Path root;
                try {
                    root = validateRoot();
                } catch (IndexerException validationError) {
                    status = new MutableStatus(RefreshState.FAILED, generation, safeRootDisplay(),
                            startedAt, startedAt, 0L, null);
                    status.errorCode = validationError.errorCode().code();
                    status.errorMessage = safeMessage(validationError);
                    currentStatus = status;
                    log.warn("刷新被拒绝 generation={} code={} message={}",
                            generation, status.errorCode, status.errorMessage);
                    throw validationError;
                }
                status = new MutableStatus(RefreshState.RUNNING, generation, root.toString(),
                        startedAt, null, null, null);
                currentStatus = status;
                IndexSnapshot previous = snapshotRef.get();
                future = CompletableFuture.supplyAsync(() -> runScanHoldingLock(root, generation, previous, status),
                        refreshExecutor);
                runningFuture = future;
            } catch (RuntimeException e) {
                refreshPermit.release();
                throw e;
            }
        }

        if (wait) {
            try {
                future.join();
            } catch (java.util.concurrent.CompletionException e) {
                // 错误已归一化记录在状态中
            }
        }
        return statusView(joined);
    }

    private IndexerException conflictException() {
        return new IndexerException(IndexerErrorCode.REFRESH_ALREADY_RUNNING,
                "已有刷新任务正在执行，同一时间只允许一个刷新任务");
    }

    /**
     * 工作线程在整个扫描期间持有刷新许可，保证“同一时间只有一个刷新在跑”，
     * 完成后原子切换快照并释放许可。
     */
    private IndexSnapshot runScanHoldingLock(Path root, long generation, IndexSnapshot previous,
                                             MutableStatus status) {
        try {
            IndexSnapshot scanned = scanner.scan(root, generation, previous);
            long finishedAt = System.currentTimeMillis();
            snapshotRef.set(scanned);
            status.finishedAtMillis = finishedAt;
            status.durationMillis = finishedAt - status.startedAtMillis;
            status.state = RefreshState.SUCCEEDED;
            status.snapshot = scanned;
            runningFuture = null;
            return scanned;
        } catch (IndexerException e) {
            long finishedAt = System.currentTimeMillis();
            status.finishedAtMillis = finishedAt;
            status.durationMillis = finishedAt - status.startedAtMillis;
            status.state = RefreshState.FAILED;
            status.errorCode = e.errorCode().code();
            status.errorMessage = safeMessage(e);
            runningFuture = null;
            log.warn("刷新失败 generation={} code={} message={}", generation, status.errorCode,
                    status.errorMessage);
            throw new CompletionExceptionWrapper(e);
        } catch (RuntimeException e) {
            long finishedAt = System.currentTimeMillis();
            status.finishedAtMillis = finishedAt;
            status.durationMillis = finishedAt - status.startedAtMillis;
            status.state = RefreshState.FAILED;
            status.errorCode = IndexerErrorCode.REFRESH_FAILED.code();
            status.errorMessage = "刷新过程中发生内部错误";
            runningFuture = null;
            log.warn("刷新失败(已归一化) generation={}", generation, e);
            throw new CompletionExceptionWrapper(e);
        } finally {
            refreshPermit.release();
        }
    }

    public RefreshStatus statusView(boolean joined) {
        MutableStatus status = currentStatus;
        IndexSnapshot snapshot = status.snapshot;
        int indexed = snapshot == null ? 0 : snapshot.processedCount();
        int skipped = snapshot == null ? 0 : snapshot.skippedCount();
        long reprocessed = snapshot == null ? 0 : snapshot.reprocessedCount();
        long reused = snapshot == null ? 0 : snapshot.reusedCount();
        List<SkippedFile> skippedFiles = snapshot == null ? List.of()
                : limitSkipped(snapshot.skipped());
        Long duration = status.durationMillis;
        if (status.state == RefreshState.RUNNING) {
            duration = System.currentTimeMillis() - status.startedAtMillis;
        }
        return new RefreshStatus(
                status.state.name().toLowerCase(Locale.ROOT),
                status.generation, status.rootPath,
                status.startedAtMillis, status.finishedAtMillis, duration,
                indexed, skipped, reprocessed, reused, skippedFiles,
                status.errorCode, status.errorMessage, joined);
    }

    private List<SkippedFile> limitSkipped(List<SkippedFile> all) {
        int cap = 200;
        return all.size() > cap ? List.copyOf(all.subList(0, cap)) : all;
    }

    public IndexSnapshot currentSnapshot() {
        return snapshotRef.get();
    }

    private Path validateRoot() {
        String configured = properties.getRootPath();
        if (configured == null || configured.isBlank()) {
            throw new IndexerException(IndexerErrorCode.INVALID_ARGUMENT,
                    "local-indexer.root-path 未配置");
        }
        Path root = Path.of(configured).toAbsolutePath().normalize();
        if (!Files.exists(root)) {
            throw new IndexerException(IndexerErrorCode.ROOT_NOT_FOUND,
                    "索引根目录不存在: " + root);
        }
        if (!Files.isDirectory(root)) {
            throw new IndexerException(IndexerErrorCode.ROOT_NOT_DIRECTORY,
                    "索引根路径不是目录: " + root);
        }
        return root;
    }

    private String safeRootDisplay() {
        try {
            return Path.of(properties.getRootPath()).toAbsolutePath().normalize().toString();
        } catch (RuntimeException e) {
            return properties.getRootPath();
        }
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null ? throwable.getClass().getSimpleName() : message;
    }

    @PreDestroy
    public void shutdown() {
        refreshExecutor.shutdownNow();
    }

    /** 刷新线程内的受控失败包装，避免把任意底层异常直接透传给 future 的调用方。 */
    private static final class CompletionExceptionWrapper extends RuntimeException {
        private CompletionExceptionWrapper(Throwable cause) {
            super(cause);
        }
    }

    private static final class MutableStatus {
        private volatile RefreshState state;
        private final long generation;
        private final String rootPath;
        private final long startedAtMillis;
        private volatile Long finishedAtMillis;
        private volatile Long durationMillis;
        private volatile IndexSnapshot snapshot;
        private volatile String errorCode;
        private volatile String errorMessage;

        private MutableStatus(RefreshState state, long generation, String rootPath,
                              long startedAtMillis, Long finishedAtMillis, Long durationMillis,
                              IndexSnapshot snapshot) {
            this.state = state;
            this.generation = generation;
            this.rootPath = rootPath;
            this.startedAtMillis = startedAtMillis;
            this.finishedAtMillis = finishedAtMillis;
            this.durationMillis = durationMillis;
            this.snapshot = snapshot;
        }
    }
}
