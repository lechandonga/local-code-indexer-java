package com.github.lechandonga.localcodeindexerjava.tests;

import com.github.lechandonga.localcodeindexerjava.config.IndexerProperties;
import com.github.lechandonga.localcodeindexerjava.index.CodeFileScanner;
import com.github.lechandonga.localcodeindexerjava.index.IndexRefreshException;
import com.github.lechandonga.localcodeindexerjava.index.IndexService;
import com.github.lechandonga.localcodeindexerjava.index.IndexSnapshot;
import com.github.lechandonga.localcodeindexerjava.index.RefreshAlreadyRunningException;
import com.github.lechandonga.localcodeindexerjava.index.RefreshStatus;
import com.github.lechandonga.localcodeindexerjava.index.SkipReason;
import com.github.lechandonga.localcodeindexerjava.search.SearchResult;
import com.github.lechandonga.localcodeindexerjava.search.SearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@BusinessDomain("索引刷新：初始建索引、增量复用、删除回收、跳过分类、单飞与快照一致性")
@ExtendWith(TestLoggingExtension.class)
class RefreshServiceTest {

    @TempDir
    Path root;

    private IndexerProperties properties;

    @BeforeEach
    void setUp() {
        properties = new IndexerProperties();
        properties.setRoot(root.toString());
        properties.setAutoRefreshOnStartup(false);
    }

    private IndexService newService() {
        return new IndexService(properties, new CodeFileScanner(properties));
    }

    private Path writeFile(String relative, String content) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }

    private static RefreshStatus await(IndexService service, boolean reuse) {
        return service.triggerRefresh(reuse).join();
    }

    @Test
    @DisplayName("首次刷新建立完整快照，统计数与版本号正确")
    void first_refresh_indexes_all_files_with_stats() throws Exception {
        writeFile("src/A.java", "class A {}");
        writeFile("src/sub/B.java", "class B {}");

        IndexService service = newService();
        RefreshStatus status = await(service, true);

        assertEquals("SUCCESS", status.state());
        assertEquals(1L, status.revision());
        assertEquals(2L, status.indexedCount());
        assertEquals(0L, status.skippedCount());
        assertEquals(2L, status.reindexedCount());
        assertTrue(status.durationMillis() >= 0);
        IndexSnapshot snapshot = service.currentSnapshot();
        assertEquals(1L, snapshot.revision());
        assertTrue(snapshot.filesByRelativePath().containsKey("src/A.java"));
    }

    @Test
    @DisplayName("未变化文件不重复解析，修改文件重新索引，删除文件从快照移除")
    void incremental_refresh_reuses_unchanged_reindexes_modified_drops_deleted() throws Exception {
        Path keep = writeFile("keep/K.java", "class Keep {}");
        Path change = writeFile("change/C.java", "class C {}");
        Path doomed = writeFile("old/D.java", "class D {}");

        IndexService service = newService();
        await(service, true);
        Object keepIdentityAfterFirst = service.currentSnapshot().filesByRelativePath().get("keep/K.java");

        Files.writeString(change, "class CModified {}\n");
        Files.setLastModifiedTime(change,
                java.nio.file.attribute.FileTime.fromMillis(Files.getLastModifiedTime(change).toMillis() + 5000));
        Files.delete(doomed);

        RefreshStatus second = await(service, true);
        assertEquals(2L, second.revision());
        assertEquals(1L, second.unchangedCount());
        assertEquals(1L, second.reindexedCount());
        assertEquals(1L, second.deletedCount());
        assertEquals(2L, second.indexedCount());

        IndexSnapshot snapshot = service.currentSnapshot();
        assertTrue(snapshot.filesByRelativePath().containsKey("change/C.java"));
        assertFalse(snapshot.filesByRelativePath().containsKey("old/D.java"));
        assertSame(keepIdentityAfterFirst, snapshot.filesByRelativePath().get("keep/K.java"),
                "未变化文件必须复用旧条目，不重复解析");
    }

    @Test
    @DisplayName("删除的文件刷新后不能再被检索到，新增文件可以被检索到")
    void deleted_files_are_not_searchable_after_refresh() throws Exception {
        writeFile("pkg/Dead.java", "class DeadKeyword {}\n");
        writeFile("pkg/Alive.java", "class AliveKeyword {}\n");

        IndexService service = newService();
        SearchService search = new SearchService(service, properties);
        await(service, true);
        assertEquals(1, search.search("DeadKeyword", null, null, null).hits().size());

        Files.deleteIfExists(root.resolve("pkg/Dead.java"));
        await(service, true);

        SearchResult afterDelete = search.search("DeadKeyword", null, null, null);
        assertEquals(0, afterDelete.hits().size());
        assertEquals(1, search.search("AliveKeyword", null, null, null).hits().size());
    }

    @Test
    @DisplayName("二进制/超大/不可读/越界链接分别给出可区分的跳过原因，且不影响其他文件")
    void skipped_files_have_distinct_reasons_and_do_not_fail_scan() throws Exception {
        writeFile("good/Main.java", "class Main {}\n");
        Path binary = writeFile("data/blob.bin", "placeholder");
        Files.write(binary, new byte[]{'a', 'b', 0, 0, 'c'});

        Path huge = writeFile("data/huge.txt", "x");
        long sizeLimit = properties.getMaxFileBytes();
        try (var out = Files.newOutputStream(huge)) {
            byte[] chunk = new byte[4096];
            long written = 0;
            while (written <= sizeLimit) {
                out.write(chunk);
                written += chunk.length;
            }
        }

        Path unreadable = writeFile("data/secret.txt", "cannot read me\n");
        unreadable.toFile().setReadable(false);
        unreadable.toFile().setWritable(true);

        Path outside = Files.createTempDirectory("outside-root");
        Files.writeString(outside.resolve("Evil.java"), "class Evil {}\n");
        Files.createDirectories(root.resolve("links"));
        Files.createSymbolicLink(root.resolve("links/Evil.java"), outside.resolve("Evil.java"));

        IndexService service = newService();
        RefreshStatus status = await(service, true);
        try {
            assertEquals("SUCCESS", status.state());
            assertEquals(1L, status.indexedCount());
            assertEquals(4L, status.skippedCount());

            List<SkipReason> reasons = service.currentSkippedFiles().stream()
                    .map(skip -> skip.reason()).toList();
            assertTrue(reasons.contains(SkipReason.BINARY_OR_UNDECODABLE_FILE));
            assertTrue(reasons.contains(SkipReason.FILE_TOO_LARGE));
            assertTrue(reasons.contains(SkipReason.UNREADABLE_FILE));
            assertTrue(reasons.contains(SkipReason.LINK_OUTSIDE_ROOT));
            Set<String> paths = service.currentSkippedFiles().stream()
                    .map(skip -> skip.path()).collect(java.util.stream.Collectors.toSet());
            assertTrue(paths.contains("data/blob.bin"));
            assertTrue(paths.contains("data/huge.txt"));
            assertTrue(paths.contains("data/secret.txt"));
            assertTrue(paths.contains("links/Evil.java"));
        } finally {
            unreadable.toFile().setReadable(true);
            Files.deleteIfExists(root.resolve("links/Evil.java"));
        }
    }

    @Test
    @DisplayName("根目录不可用时刷新归一化为 ROOT_UNAVAILABLE，旧快照保留，状态为 FAILED")
    void unavailable_root_is_normalized_and_old_snapshot_remains() throws Exception {
        writeFile("A.java", "class A {}");
        IndexService service = newService();
        await(service, true);

        properties.setRoot(root.resolve("does-not-exist").toString());
        IndexRefreshException exception = assertThrows(IndexRefreshException.class,
                () -> service.triggerRefreshWithOutcome(true));
        assertEquals("ROOT_UNAVAILABLE", exception.getErrorCode());

        RefreshStatus status = service.currentStatus();
        assertEquals("FAILED", status.state());
        assertEquals("ROOT_UNAVAILABLE", status.errorCode());
        assertEquals(1L, service.currentSnapshot().indexedCount(), "失败不得破坏已发布快照");
    }

    @Test
    @DisplayName("并发刷新复用同一任务，拒绝策略返回 409 语义异常，底层扫描只执行一次")
    void concurrent_refresh_is_single_flight() throws Exception {
        writeFile("A.java", "class A {}");
        CountDownLatch insideScan = new CountDownLatch(1);
        CountDownLatch releaseScan = new CountDownLatch(1);
        AtomicInteger scanCount = new AtomicInteger();
        CodeFileScanner controlled = new CodeFileScanner(properties) {
            @Override
            public ScanResult scan(Path ignored) throws IOException {
                scanCount.incrementAndGet();
                insideScan.countDown();
                assertDoesNotThrow(() -> releaseScan.await());
                return super.scan(root);
            }
        };
        IndexService service = new IndexService(properties, controlled);

        CompletableFuture<RefreshStatus> first = service.triggerRefresh(true);
        assertTrue(insideScan.await(5, java.util.concurrent.TimeUnit.SECONDS));

        CompletableFuture<RefreshStatus> second = service.triggerRefresh(true);
        assertSame(first, second, "reuse=true 时必须复用进行中的任务");
        assertThrows(RefreshAlreadyRunningException.class, () -> service.triggerRefresh(false),
                "reuse=false 且任务进行中时必须明确拒绝");
        assertTrue(service.isRefreshRunning());

        releaseScan.countDown();
        assertEquals(first.join(), second.join());
        assertEquals(1, scanCount.get(), "同一时间只能有一个刷新任务写索引");
        assertEquals("SUCCESS", service.currentStatus().state());
    }

    @Test
    @DisplayName("刷新进行中的查询只看到旧的完整快照，刷新结束后整体切换到新快照")
    void queries_during_refresh_see_a_single_consistent_snapshot() throws Exception {
        writeFile("v1/Old.java", "class OldVersion {}\n");
        CountDownLatch insideScan = new CountDownLatch(1);
        CountDownLatch releaseScan = new CountDownLatch(1);
        AtomicBoolean blockNextScan = new AtomicBoolean(false);
        CodeFileScanner controlled = new CodeFileScanner(properties) {
            @Override
            public ScanResult scan(Path ignored) throws IOException {
                if (blockNextScan.get()) {
                    insideScan.countDown();
                    assertDoesNotThrow(() -> releaseScan.await());
                }
                return super.scan(root);
            }
        };
        IndexService controlledService = new IndexService(properties, controlled);
        SearchService controlledSearch = new SearchService(controlledService, properties);
        controlledService.triggerRefresh(true).join();
        blockNextScan.set(true);
        CompletableFuture<RefreshStatus> secondRefresh = controlledService.triggerRefresh(true);
        assertEquals(1L, controlledService.currentSnapshot().revision());

        Files.createDirectories(root.resolve("v2"));
        Files.writeString(root.resolve("v2/New.java"), "class NewVersion {}\n");
        Files.delete(root.resolve("v1/Old.java"));
        assertTrue(insideScan.await(5, java.util.concurrent.TimeUnit.SECONDS));

        for (int i = 0; i < 5; i++) {
            SearchResult during = controlledSearch.search("Version", null, null, null);
            assertEquals(1L, during.revision(), "刷新期间必须始终读到旧的完整快照");
            assertEquals(List.of("v1/Old.java"),
                    during.hits().stream().map(hit -> hit.file()).distinct().toList());
        }

        releaseScan.countDown();
        secondRefresh.join();
        SearchResult after = controlledSearch.search("Version", null, null, null);
        assertNotEquals(1L, after.revision());
        assertEquals(List.of("v2/New.java"),
                after.hits().stream().map(hit -> hit.file()).distinct().toList());
    }

    private static void assertSame(Object expected, Object actual, String message) {
        org.junit.jupiter.api.Assertions.assertSame(expected, actual, message);
    }
}
