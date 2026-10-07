package com.github.lechandonga.localcodeindexerjava.tests;

import com.github.lechandonga.localcodeindexerjava.indexer.CodeScanner;
import com.github.lechandonga.localcodeindexerjava.indexer.IndexSnapshot;
import com.github.lechandonga.localcodeindexerjava.indexer.IndexerProperties;
import com.github.lechandonga.localcodeindexerjava.indexer.SkipReason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖业务：增量扫描（新增/修改/删除/未变化复用）与各类跳过边界
 * （二进制、超大、扩展名过滤、越界符号链接、不可读文件）。
 */
class CodeScannerTest extends BaseIndexerTest {

    @TempDir
    Path tempDir;

    @Override
    protected String businessGroup() {
        return "扫描建索引/增量与跳过边界";
    }

    private CodeScanner scanner(long maxFileSize, List<String> extensions) {
        IndexerProperties properties = new IndexerProperties();
        properties.setMaxFileSizeBytes(maxFileSize);
        properties.setIncludeExtensions(extensions);
        return new CodeScanner(properties);
    }

    private long countSkipped(IndexSnapshot snapshot, SkipReason reason) {
        return snapshot.skipped().stream().filter(s -> s.reason().equals(reason.code())).count();
    }

    @Test
    void scansNewFilesAndSkipsBinaryAndLargeAndExtension() throws Exception {
        Path src = tempDir.resolve("src");
        Files.createDirectories(src);
        Files.writeString(src.resolve("App.java"), "class App { int x = 1; }\n");
        Files.write(src.resolve("logo.png"), new byte[]{1, 2, 0, 3, 4});
        byte[] big = new byte[2048];
        Files.write(src.resolve("big.java"), big);
        Files.writeString(src.resolve("notes.txt"), "plain notes\n");

        CodeScanner codeScanner = scanner(1024, List.of("java"));
        IndexSnapshot snapshot = codeScanner.scan(tempDir, 1, null);

        assertEquals(1, snapshot.processedCount(), "白名单 [java] 下只有 App.java 被索引");
        assertTrue(snapshot.files().containsKey("src/App.java"));
        assertEquals(0, countSkipped(snapshot, SkipReason.BINARY));
        assertEquals(1, countSkipped(snapshot, SkipReason.TOO_LARGE), "big.java 超过 1024 字节");
        assertEquals(2, countSkipped(snapshot, SkipReason.IGNORED_EXTENSION), "png 与 txt 都不在白名单");
        assertEquals(3, snapshot.skippedCount());
    }

    @Test
    void withoutExtensionFilterBinaryContentIsDetectedDirectly() throws Exception {
        Files.write(tempDir.resolve("weird.dat"), new byte[]{'h', 'i', 0, 0});
        Files.writeString(tempDir.resolve("readme"), "hello\n");
        IndexSnapshot snapshot = scanner(10_000, List.of()).scan(tempDir, 1, null);
        assertEquals(1, snapshot.processedCount());
        assertTrue(snapshot.files().containsKey("readme"));
        assertEquals(1, countSkipped(snapshot, SkipReason.BINARY));
    }

    @Test
    void incrementalRefreshHandlesAddModifyDeleteAndReuse() throws Exception {
        Path a = tempDir.resolve("a.java");
        Path b = tempDir.resolve("b.java");
        Files.writeString(a, "class A {}\n");
        Files.writeString(b, "class B {}\n");
        CodeScanner codeScanner = scanner(10_000, List.of());

        IndexSnapshot first = codeScanner.scan(tempDir, 1, null);
        assertEquals(2, first.processedCount());
        assertEquals(2, first.reprocessedCount());

        // 不修改任何文件：两个都应被复用，不重新解析
        IndexSnapshot second = codeScanner.scan(tempDir, 2, first);
        assertEquals(2, second.processedCount());
        assertEquals(2, second.reusedCount());
        assertEquals(0, second.reprocessedCount());

        // 修改 a、删除 b、新增 c
        Files.writeString(a, "class A { int changed = 2; }\n");
        Files.delete(b);
        Files.writeString(tempDir.resolve("c.java"), "class C {}\n");
        IndexSnapshot third = codeScanner.scan(tempDir, 3, second);

        assertEquals(2, third.processedCount());
        assertTrue(third.files().containsKey("a.java"));
        assertTrue(third.files().containsKey("c.java"));
        assertFalse(third.files().containsKey("b.java"), "删除的文件不能再进入索引");
        assertEquals(2, third.reprocessedCount(), "a 修改、c 新增都需要重新解析");
        assertEquals(0, third.reusedCount(), "b 已删除，没有未变化文件可复用");
        assertNotNull(third.file("a.java"));
    }

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "POSIX 权限与符号链接语义仅在类 Unix 验证")
    void skipsSymlinkPointingOutsideRoot() throws Exception {
        Path outside = Files.createTempDirectory("indexer-outside");
        try {
            Path secret = outside.resolve("secret.java");
            Files.writeString(secret, "class Secret {}\n");
            Path link = tempDir.resolve("link.java");
            Files.createSymbolicLink(link, secret);

            IndexSnapshot snapshot = scanner(10_000, List.of()).scan(tempDir, 1, null);
            assertEquals(0, snapshot.processedCount());
            assertEquals(1, snapshot.skippedCount());
            assertEquals(SkipReason.OUTSIDE_ROOT.code(), snapshot.skipped().get(0).reason());
        } finally {
            Files.walk(outside).sorted(ComparatorReverse.REVERSE).forEach(DeleteSilently::delete);
        }
    }

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "POSIX 权限语义仅在类 Unix 验证")
    void skipsUnreadableFileWithoutFailingWholeScan() throws Exception {
        Assumptions.assumeTrue(isNotRunningAsRoot(), "root 用户绕过文件权限，跳过该用例");
        Path readable = tempDir.resolve("ok.java");
        Path locked = tempDir.resolve("locked.java");
        Files.writeString(readable, "class Ok {}\n");
        Files.writeString(locked, "class Locked {}\n");
        Files.setPosixFilePermissions(locked, EnumSet.noneOf(PosixFilePermission.class));
        try {
            IndexSnapshot snapshot = scanner(10_000, List.of()).scan(tempDir, 1, null);
            assertEquals(1, snapshot.processedCount(), "可读文件不受影响");
            assertEquals(1, countSkipped(snapshot, SkipReason.UNREADABLE));
        } finally {
            Files.setPosixFilePermissions(locked, EnumSet.allOf(PosixFilePermission.class));
        }
    }

    @Test
    void binaryDetectionHeuristic() {
        assertTrue(CodeScanner.looksBinary(new byte[]{'a', 0, 'b'}));
        assertFalse(CodeScanner.looksBinary("hello world\n".getBytes()));
    }

    private static boolean isNotRunningAsRoot() {
        try {
            Process process = new ProcessBuilder("id", "-u").redirectErrorStream(true).start();
            String out = new String(process.getInputStream().readAllBytes()).trim();
            process.waitFor();
            return !"0".equals(out);
        } catch (Exception e) {
            return true;
        }
    }

    /** 极简辅助，避免引入额外 import 噪音。 */
    private static final class ComparatorReverse implements java.util.Comparator<Path> {
        static final ComparatorReverse REVERSE = new ComparatorReverse();
        @Override
        public int compare(Path o1, Path o2) {
            return o2.compareTo(o1);
        }
    }

    private static final class DeleteSilently {
        static void delete(Path path) {
            try {
                Files.deleteIfExists(path);
            } catch (Exception ignored) {
                // 临时目录清理尽力而为
            }
        }
    }
}
