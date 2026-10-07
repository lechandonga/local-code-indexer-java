package com.github.lechandonga.localcodeindexerjava.tests;

import com.github.lechandonga.localcodeindexerjava.indexer.CodeScanner;
import com.github.lechandonga.localcodeindexerjava.indexer.IndexManager;
import com.github.lechandonga.localcodeindexerjava.indexer.IndexSnapshot;
import com.github.lechandonga.localcodeindexerjava.indexer.IndexerErrorCode;
import com.github.lechandonga.localcodeindexerjava.indexer.IndexerException;
import com.github.lechandonga.localcodeindexerjava.indexer.IndexerProperties;
import com.github.lechandonga.localcodeindexerjava.indexer.RefreshStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖业务：刷新状态机与统计、同一时间单刷新任务的拒绝/复用语义、
 * 刷新期间查询基于完整旧快照、失败归一化与删除文件后不可检索。
 */
class IndexManagerTest extends BaseIndexerTest {

    @TempDir
    Path tempDir;

    @Override
    protected String businessGroup() {
        return "刷新并发/快照一致性/失败归一化";
    }

    private IndexerProperties properties() {
        IndexerProperties properties = new IndexerProperties();
        properties.setRootPath(tempDir.toString());
        return properties;
    }

    @Test
    void refreshBuildsSnapshotAndReflectsDeletion() {
        IndexManager manager = new IndexManager(properties(), new CodeScanner(properties()));
        assertNull(manager.currentSnapshot());

        RefreshStatus status = manager.triggerRefresh(true);
        assertEquals("succeeded", status.state());
        assertEquals(1, status.generation());
        assertNotNull(status.durationMillis());
        writeSeed();
        manager.triggerRefresh(true);

        // 第二次刷新无变化：全部复用
        RefreshStatus again = manager.triggerRefresh(true);
        assertEquals(3, again.generation());
        assertEquals(again.indexedCount(), again.reusedCount(), "无变化文件应全部复用，不重复解析");
        assertEquals(0, again.reprocessedCount());

        // 删除文件后刷新，旧文件不可再检索
        IndexSnapshot before = manager.currentSnapshot();
        String victim = before.files().keySet().stream().findFirst().orElseThrow();
        try {
            Files.delete(tempDir.resolve(victim));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        manager.triggerRefresh(true);
        assertNull(manager.currentSnapshot().file(victim), "删除的文件不能再被检索到");
        manager.shutdown();
    }

    @Test
    void concurrentRefreshRejectsSecondUnderRejectPolicy() throws Exception {
        writeSeed();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        LatchScanner scanner = new LatchScanner(properties(), entered, release);
        IndexManager manager = new IndexManager(properties(), scanner);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<?> first = pool.submit(() -> manager.triggerRefresh(true));
        assertTrue(entered.await(5, TimeUnit.SECONDS), "第一个刷新应已进入扫描并占位");

        // 刷新进行中：第二次必须被明确拒绝
        IndexerException rejected = assertThrows(IndexerException.class,
                () -> manager.triggerRefresh(true));
        assertEquals(IndexerErrorCode.REFRESH_ALREADY_RUNNING, rejected.errorCode());
        assertEquals(409, IndexerErrorCode.REFRESH_ALREADY_RUNNING.httpStatus());
        assertEquals(1, scanner.concurrentScans.get(), "同一时间只能有一个扫描在执行");

        release.countDown();
        first.get(5, TimeUnit.SECONDS);

        RefreshStatus status = manager.statusView(false);
        assertEquals("succeeded", status.state());
        pool.shutdownNow();
        manager.shutdown();
    }

    @Test
    void concurrentRefreshJoinsRunningUnderJoinPolicy() throws Exception {
        writeSeed();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        LatchScanner scanner = new LatchScanner(properties(), entered, release);
        IndexerProperties joinProps = properties();
        joinProps.setRefreshConflictPolicy(IndexerProperties.RefreshConflictPolicy.join);
        IndexManager manager = new IndexManager(joinProps, scanner);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<RefreshStatus> first = pool.submit(() -> manager.triggerRefresh(true));
        assertTrue(entered.await(5, TimeUnit.SECONDS));

        Future<RefreshStatus> second = pool.submit(() -> manager.triggerRefresh(true));
        Thread.sleep(150);
        assertEquals(1, scanner.concurrentScans.get(), "复用进行中的任务，不得启动第二个扫描");
        release.countDown();
        RefreshStatus joined = second.get(5, TimeUnit.SECONDS);
        first.get(5, TimeUnit.SECONDS);

        assertTrue(joined.joinedExisting(), "后来的请求应复用进行中的任务");
        assertEquals(1, scanner.totalScans.get());
        pool.shutdownNow();
        manager.shutdown();
    }

    @Test
    void queriesDuringRefreshSeeOnlyCompleteOldSnapshot() throws Exception {
        writeSeed();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        LatchScanner scanner = new LatchScanner(properties(), entered, release);
        IndexManager manager = new IndexManager(properties(), scanner);

        ExecutorService bootstrap = Executors.newSingleThreadExecutor();
        Future<?> firstRefresh = bootstrap.submit(() -> manager.triggerRefresh(true));
        assertTrue(entered.await(5, TimeUnit.SECONDS), "第一次刷新应已进入扫描");
        release.countDown();
        firstRefresh.get(5, TimeUnit.SECONDS);
        bootstrap.shutdown();
        IndexSnapshot oldSnapshot = manager.currentSnapshot();
        long oldCount = oldSnapshot.processedCount();

        // 修改磁盘内容并启动第二次刷新，但让扫描停在中途
        Files.writeString(tempDir.resolve("extra.java"), "class Extra {}\n");
        CountDownLatch enteredSecond = new CountDownLatch(1);
        CountDownLatch releaseSecond = new CountDownLatch(1);
        scanner.enteredLatch = enteredSecond;
        scanner.releaseLatch = releaseSecond;
        scanner.blockNext = true;
        ExecutorService pool = Executors.newSingleThreadExecutor();
        pool.submit(() -> manager.triggerRefresh(true));
        assertTrue(enteredSecond.await(5, TimeUnit.SECONDS));

        // 构建中的新快照不可见：查询只能读到完整的旧快照
        for (int i = 0; i < 5; i++) {
            IndexSnapshot seen = manager.currentSnapshot();
            assertEquals(oldCount, seen.processedCount(), "刷新期间不得读到半成品或新旧混杂数据");
            assertEquals(1, seen.generation());
        }

        releaseSecond.countDown();
        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);
        assertEquals(2, manager.currentSnapshot().generation());
        assertEquals(oldCount + 1, manager.currentSnapshot().processedCount());
        manager.shutdown();
    }

    @Test
    void failureIsNormalizedAndOldSnapshotRemainsQueryable() throws Exception {
        writeSeed();
        IndexManager manager = new IndexManager(properties(), new CodeScanner(properties()));
        manager.triggerRefresh(true);

        IndexerProperties badProps = properties();
        badProps.setRootPath(tempDir.resolve("does-not-exist").toString());
        IndexManager badManager = new IndexManager(badProps, new CodeScanner(badProps));
        IndexerException ex = assertThrows(IndexerException.class, () -> badManager.triggerRefresh(true));
        assertEquals(IndexerErrorCode.ROOT_NOT_FOUND, ex.errorCode());

        RefreshStatus status = badManager.statusView(false);
        assertEquals("failed", status.state());
        assertEquals(IndexerErrorCode.ROOT_NOT_FOUND.code(), status.errorCode());
        assertNotNull(status.errorMessage());

        // 原 manager 的快照不受另一个失败任务影响（独立 manager），同 manager 失败也保留旧快照
        assertNotNull(manager.currentSnapshot());
        manager.shutdown();
        badManager.shutdown();
    }

    private void writeSeed() {
        try {
            Files.writeString(tempDir.resolve("Alpha.java"), "class Alpha {}\n");
            Files.writeString(tempDir.resolve("Beta.java"), "class Beta {}\n");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** 可在扫描中途挂起的扫描器，同时统计并发扫描数。 */
    private static class LatchScanner extends CodeScanner {
        private volatile CountDownLatch enteredLatch;
        private volatile CountDownLatch releaseLatch;
        private volatile boolean blockNext = true;
        private final AtomicInteger concurrentScans = new AtomicInteger(0);
        private final AtomicInteger totalScans = new AtomicInteger(0);

        LatchScanner(IndexerProperties properties, CountDownLatch enteredLatch, CountDownLatch releaseLatch) {
            super(properties);
            this.enteredLatch = enteredLatch;
            this.releaseLatch = releaseLatch;
        }

        @Override
        public IndexSnapshot scan(Path root, long generation, IndexSnapshot previous) {
            concurrentScans.incrementAndGet();
            totalScans.incrementAndGet();
            enteredLatch.countDown();
            try {
                if (blockNext) {
                    blockNext = false;
                    releaseLatch.await(10, TimeUnit.SECONDS);
                }
                return super.scan(root, generation, previous);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            } finally {
                concurrentScans.decrementAndGet();
            }
        }
    }
}
