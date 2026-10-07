package com.github.lechandonga.localcodeindexerjava.index;

import com.github.lechandonga.localcodeindexerjava.config.IndexerProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 索引核心服务。
 *
 * <p>并发语义：同一时间最多一个刷新任务在执行（single-flight）。{@code triggerRefresh}
 * 默认在已有任务进行中时复用该任务；显式要求拒绝时返回 {@link RefreshAlreadyRunningException}。
 * 已发布的 {@link IndexSnapshot} 不可变，原子替换，因此查询只会看到某一次刷新的完整结果。
 */
@Service
public class IndexService {

    private static final Logger log = LoggerFactory.getLogger(IndexService.class);

    private final IndexerProperties properties;
    private final CodeFileScanner scanner;

    private final AtomicReference<IndexSnapshot> snapshot = new AtomicReference<>(IndexSnapshot.empty());
    private final AtomicReference<RefreshStatus> lastStatus = new AtomicReference<>(RefreshStatus.neverRun());
    private final AtomicReference<CompletableFuture<RefreshStatus>> runningFuture = new AtomicReference<>();
    private final AtomicLong revisionSequence = new AtomicLong(0);

    @org.springframework.beans.factory.annotation.Autowired
    public IndexService(IndexerProperties properties) {
        this.properties = properties;
        this.scanner = new CodeFileScanner(properties);
    }

    /**
     * 测试用构造缝：允许注入受控扫描器（例如带屏障以模拟长时间刷新）。
     */
    public IndexService(IndexerProperties properties, CodeFileScanner scanner) {
        this.properties = properties;
        this.scanner = scanner;
    }

    @PostConstruct
    void refreshOnStartup() {
        if (!properties.isAutoRefreshOnStartup()) {
            return;
        }
        if (properties.getRoot() == null || properties.getRoot().isBlank()) {
            log.warn("code-indexer.root is not configured; skipping initial indexing");
            return;
        }
        try {
            triggerRefresh(true).whenComplete((status, error) -> {
                if (status != null) {
                    log.info("startup index refresh finished: state={}, indexed={}, skipped={}",
                            status.state(), status.indexedCount(), status.skippedCount());
                } else {
                    log.warn("startup index refresh failed: {}", String.valueOf(error));
                }
            });
        } catch (RefreshAlreadyRunningException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /**
     * 触发一次刷新。
     *
     * @param reuseRunning true 时复用进行中的任务；false 时进行中则抛出
     *                     {@link RefreshAlreadyRunningException}
     */
    public CompletableFuture<RefreshStatus> triggerRefresh(boolean reuseRunning) {
        CompletableFuture<RefreshStatus> existing = runningFuture.get();
        if (existing != null && !existing.isDone()) {
            if (!reuseRunning) {
                throw new RefreshAlreadyRunningException(
                        "another refresh is already in progress; query /api/index/status for progress");
            }
            return existing;
        }
        if (properties.getRoot() == null || properties.getRoot().isBlank()) {
            throw new IndexRefreshException("ROOT_NOT_CONFIGURED",
                    "code-indexer.root is not configured; set a local directory before refreshing");
        }
        CompletableFuture<RefreshStatus> created = new CompletableFuture<>();
        if (!runningFuture.compareAndSet(existing, created)) {
            CompletableFuture<RefreshStatus> winner = runningFuture.get();
            if (!reuseRunning && winner != null && !winner.isDone()) {
                throw new RefreshAlreadyRunningException(
                        "another refresh is already in progress; query /api/index/status for progress");
            }
            return winner != null ? winner : created;
        }
        long startMillis = System.currentTimeMillis();
        String startedAt = Instant.ofEpochMilli(startMillis).toString();
        long expectedRevision = revisionSequence.get() + 1;
        RefreshStatus running = new RefreshStatus("RUNNING", expectedRevision, startedAt, null, null,
                null, null, null, null, null, null, "refresh in progress", null, false);
        lastStatus.set(running);
        CompletableFuture.runAsync(() -> doRefresh(startMillis, startedAt, expectedRevision, created))
                .whenComplete((ignored, error) -> {
                    if (error != null) {
                        created.completeExceptionally(error);
                    }
                });
        return created;
    }

    private void doRefresh(long startMillis, String startedAt, long revision,
                           CompletableFuture<RefreshStatus> future) {
        RefreshStatus status = null;
        try {
            IndexSnapshot previous = snapshot.get();
            CodeFileScanner.ScanResult scanResult;
            try {
                scanResult = scanner.scan(Path.of(properties.getRoot()));
            } catch (IOException e) {
                throw new IndexRefreshException("ROOT_UNAVAILABLE",
                        "index root cannot be scanned; check that it exists and is a readable directory");
            } catch (SecurityException e) {
                throw new IndexRefreshException("ROOT_ACCESS_DENIED",
                        "index root access is denied by the security manager or filesystem");
            }

            Map<String, IndexedFile> nextFiles = new LinkedHashMap<>();
            long unchanged = 0;
            long reindexed = 0;
            for (IndexedFile scanned : scanResult.indexed()) {
                IndexedFile prior = previous.filesByRelativePath().get(scanned.relativePath());
                if (prior != null && prior.sizeBytes() == scanned.sizeBytes()
                        && prior.lastModified() == scanned.lastModified()) {
                    nextFiles.put(scanned.relativePath(), prior);
                    unchanged++;
                } else {
                    nextFiles.put(scanned.relativePath(), scanned);
                    reindexed++;
                }
            }
            long deleted = previous.filesByRelativePath().keySet().stream()
                    .filter(path -> !nextFiles.containsKey(path))
                    .count();

            IndexSnapshot nextSnapshot = new IndexSnapshot(
                    revisionSequence.incrementAndGet(),
                    properties.getRoot(),
                    Map.copyOf(nextFiles),
                    List.copyOf(scanResult.skipped()),
                    nextFiles.size(),
                    scanResult.skipped().size(),
                    System.currentTimeMillis());
            snapshot.set(nextSnapshot);

            long duration = System.currentTimeMillis() - startMillis;
            status = new RefreshStatus("SUCCESS", nextSnapshot.revision(), startedAt,
                    Instant.now().toString(), duration,
                    nextSnapshot.indexedCount(), nextSnapshot.skippedCount(),
                    unchanged, reindexed, deleted,
                    null, "refresh completed successfully",
                    nextSnapshot.skippedFiles(), false);
            lastStatus.set(status);
            future.complete(status);
            log.info("index refresh success revision={} indexed={} unchanged={} reindexed={} deleted={} skipped={} in {}ms",
                    nextSnapshot.revision(), nextSnapshot.indexedCount(), unchanged, reindexed,
                    deleted, nextSnapshot.skippedCount(), duration);
        } catch (IndexRefreshException e) {
            status = buildFailure(startMillis, startedAt, revision, e.getErrorCode(), e.getMessage());
            lastStatus.set(status);
            future.completeExceptionally(new IndexRefreshException(e.getErrorCode(), e.getMessage()));
            log.warn("index refresh failed code={} message={}", e.getErrorCode(), e.getMessage());
        } catch (RuntimeException e) {
            status = buildFailure(startMillis, startedAt, revision, "INTERNAL_ERROR",
                    "index refresh failed due to an unexpected internal error");
            lastStatus.set(status);
            future.completeExceptionally(new IndexRefreshException("INTERNAL_ERROR",
                    "index refresh failed due to an unexpected internal error"));
            log.warn("index refresh failed unexpectedly", e);
        } finally {
            runningFuture.set(null);
        }
    }

    private RefreshStatus buildFailure(long startMillis, String startedAt, long revision,
                                       String errorCode, String message) {
        return new RefreshStatus("FAILED", null, startedAt, Instant.now().toString(),
                System.currentTimeMillis() - startMillis,
                null, null, null, null, null,
                errorCode, message, List.of(), false);
    }

    /**
     * 标记进行中任务被复用，仅用于状态响应中的 reused 标志（状态本身不改变）。
     */
    public RefreshOutcome triggerRefreshWithOutcome(boolean reuseRunning) {
        CompletableFuture<RefreshStatus> existing = runningFuture.get();
        boolean reused = existing != null && !existing.isDone() && reuseRunning;
        try {
            return new RefreshOutcome(triggerRefresh(reuseRunning).join(), reused);
        } catch (java.util.concurrent.CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IndexRefreshException indexRefreshException) {
                throw indexRefreshException;
            }
            throw new IndexRefreshException("INTERNAL_ERROR",
                    "index refresh failed due to an unexpected internal error");
        }
    }

    public RefreshStatus currentStatus() {
        CompletableFuture<RefreshStatus> running = runningFuture.get();
        RefreshStatus status = lastStatus.get();
        if (running != null && !running.isDone()) {
            return status;
        }
        return status;
    }

    public boolean isRefreshRunning() {
        CompletableFuture<RefreshStatus> running = runningFuture.get();
        return running != null && !running.isDone();
    }

    public IndexSnapshot currentSnapshot() {
        return snapshot.get();
    }

    public List<SkippedFile> currentSkippedFiles() {
        return new ArrayList<>(snapshot.get().skippedFiles());
    }
}
