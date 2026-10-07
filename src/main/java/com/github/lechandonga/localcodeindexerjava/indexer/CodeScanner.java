package com.github.lechandonga.localcodeindexerjava.indexer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 目录扫描器：遍历根目录下的代码文件，产出新的不可变索引快照。
 *
 * <p>增量规则：相对路径 + 大小 + 修改时间 + 内容哈希。与上一代快照相比
 * 未变化的文件直接复用解析结果，不重复读取与解析；新增/修改的文件重新读取；
 * 上一代存在但本次遍历不到的路径自然不出现在新快照中（即删除生效）。
 *
 * <p>任何单个文件的问题（越界链接、二进制、超大、不可读等）只记录为跳过项，
 * 不会让整次刷新失败；只有根目录本身不可用时刷新才失败。
 */
@Component
public class CodeScanner {

    private static final Logger log = LoggerFactory.getLogger(CodeScanner.class);

    /** 判定二进制时读取/探测的最大前缀字节数。 */
    private static final int BINARY_PROBE_BYTES = 8192;

    private final IndexerProperties properties;

    public CodeScanner(IndexerProperties properties) {
        this.properties = properties;
    }

    /**
     * 执行一次扫描。
     *
     * @param root       已校验存在的根目录
     * @param generation 新一代快照序号
     * @param previous   上一代快照，首次刷新时为 {@code null}
     */
    public IndexSnapshot scan(Path root, long generation, IndexSnapshot previous) {
        long started = System.nanoTime();
        Map<String, IndexedFile> indexed = new TreeMap<>();
        List<SkippedFile> skipped = new ArrayList<>();
        long[] counters = new long[2]; // 0=重新解析, 1=复用

        Set<String> extensions = normalizeExtensions(properties.getIncludeExtensions());
        Visitor visitor = new Visitor(root, previous, indexed, skipped, counters, extensions);
        try {
            Files.walkFileTree(root, Set.of(FileVisitOption.FOLLOW_LINKS), Integer.MAX_VALUE, visitor);
        } catch (IOException e) {
            // 根目录级别的遍历失败（例如根目录在扫描期间被删除）归一化为刷新失败
            throw new IndexerException(IndexerErrorCode.SCAN_FAILED,
                    "扫描根目录失败: " + root, e);
        }

        long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();
        log.info("索引扫描完成 generation={} root={} 已索引={} 跳过={} 重新解析={} 复用={} 耗时={}ms",
                generation, root, indexed.size(), skipped.size(), counters[0], counters[1], elapsedMillis);
        return new IndexSnapshot(generation, root.toString(), indexed, List.copyOf(skipped),
                indexed.size(), skipped.size(), counters[0], counters[1], System.currentTimeMillis());
    }

    private final class Visitor extends SimpleFileVisitor<Path> {

        private final Path root;
        private final IndexSnapshot previous;
        private final Map<String, IndexedFile> indexed;
        private final List<SkippedFile> skipped;
        private final long[] counters;
        private final Set<String> extensions;

        private Visitor(Path root, IndexSnapshot previous, Map<String, IndexedFile> indexed,
                        List<SkippedFile> skipped, long[] counters, Set<String> extensions) {
            this.root = root;
            this.previous = previous;
            this.indexed = indexed;
            this.skipped = skipped;
            this.counters = counters;
            this.extensions = extensions;
        }

        @Override
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
            // walkFileTree 配置了 FOLLOW_LINKS，被跟随的链接目录其 toRealPath 必须仍在根目录内
            try {
                Path realRoot = root.toRealPath();
                Path realDir = dir.toRealPath();
                if (!realDir.startsWith(realRoot) && !realDir.equals(realRoot)) {
                    skip(relativeOrName(dir), SkipReason.OUTSIDE_ROOT,
                            "链接目录指向根目录之外: " + realDir);
                    return FileVisitResult.SKIP_SUBTREE;
                }
            } catch (IOException e) {
                skip(relativeOrName(dir), SkipReason.SYMLINK_LOOP, "目录链接无法解析: " + e.getMessage());
                return FileVisitResult.SKIP_SUBTREE;
            }
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            String relative = relativeOrName(file);
            try {
                if (Files.isSymbolicLink(file)) {
                    Path target = file.toRealPath();
                    Path realRoot = root.toRealPath();
                    if (!target.startsWith(realRoot)) {
                        skip(relative, SkipReason.OUTSIDE_ROOT, "符号链接指向根目录之外: " + target);
                        return FileVisitResult.CONTINUE;
                    }
                }
                if (!attrs.isRegularFile()) {
                    skip(relative, SkipReason.NOT_REGULAR_FILE, "不是普通文件");
                    return FileVisitResult.CONTINUE;
                }
                if (!extensions.isEmpty() && !extensions.contains(extensionOf(file))) {
                    skip(relative, SkipReason.IGNORED_EXTENSION, "扩展名不在配置的白名单内");
                    return FileVisitResult.CONTINUE;
                }
                if (attrs.size() > properties.getMaxFileSizeBytes()) {
                    skip(relative, SkipReason.TOO_LARGE,
                            "文件大小 " + attrs.size() + " 字节，超过上限 " + properties.getMaxFileSizeBytes());
                    return FileVisitResult.CONTINUE;
                }

                IndexedFile reused = tryReuse(file, relative, attrs.size(), attrs.lastModifiedTime().toMillis());
                if (reused != null) {
                    indexed.put(relative, reused);
                    counters[1]++;
                    return FileVisitResult.CONTINUE;
                }

                IndexedFile parsed = readAndParse(file, relative, attrs.size(), attrs.lastModifiedTime().toMillis());
                indexed.put(relative, parsed);
                counters[0]++;
            } catch (IndexerException e) {
                // readAndParse 归一化的单文件问题
                skip(relative, e.reason(), e.getMessage());
            } catch (IOException | RuntimeException e) {
                log.debug("读取文件失败 {}: {}", relative, e.toString());
                skip(relative, SkipReason.UNREADABLE, e.getMessage());
            }
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException exc) {
            // 无法访问的条目（权限、悬空链接、循环链接等）记录为跳过，遍历继续
            SkipReason reason = exc instanceof java.nio.file.FileSystemLoopException
                    ? SkipReason.SYMLINK_LOOP : SkipReason.UNREADABLE;
            skip(relativeOrName(file), reason, exc.getMessage());
            return FileVisitResult.CONTINUE;
        }

        private IndexedFile tryReuse(Path file, String relative, long size, long lastModified) throws IOException {
            if (previous == null) {
                return null;
            }
            IndexedFile old = previous.file(relative);
            if (old == null || old.sizeBytes() != size || old.lastModified() != lastModified) {
                return null;
            }
            // 大小与修改时间都未变，再用哈希确认内容确实没变，避免 mtime 被还原导致漏刷新
            String hash = sha256(Files.readAllBytes(file));
            if (!hash.equals(old.contentHash())) {
                return null;
            }
            return old;
        }

        private IndexedFile readAndParse(Path file, String relative, long size, long lastModified) throws IOException {
            byte[] bytes;
            try {
                bytes = Files.readAllBytes(file);
            } catch (IOException e) {
                throw new IndexerException(IndexerErrorCode.SKIP_FILE, SkipReason.UNREADABLE,
                        "读取失败: " + e.getMessage(), e);
            }
            if (looksBinary(bytes)) {
                throw new IndexerException(IndexerErrorCode.SKIP_FILE, SkipReason.BINARY,
                        "内容含 NUL 字节等二进制特征");
            }
            String text;
            try {
                text = new String(bytes, StandardCharsets.UTF_8);
                // 主动校验一次，捕获非法 UTF-8 序列
                java.nio.charset.CodingErrorAction report = java.nio.charset.CodingErrorAction.REPORT;
                java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes);
                StandardCharsets.UTF_8.newDecoder().onMalformedInput(report).onUnmappableCharacter(report).decode(buffer);
            } catch (CharacterCodingException e) {
                throw new IndexerException(IndexerErrorCode.SKIP_FILE, SkipReason.BINARY,
                        "内容不是合法 UTF-8 文本，按二进制跳过");
            }
            List<String> lines = text.isEmpty() ? List.of() : List.of(text.split("\n", -1));
            return new IndexedFile(relative, sha256(bytes), size, lastModified, lines);
        }

        private void skip(String relative, SkipReason reason, String detail) {
            skipped.add(SkippedFile.of(relative, reason, detail));
            log.info("跳过文件 path={} reason={} detail={}", relative, reason.code(), detail);
        }

        private String relativeOrName(Path path) {
            String rel;
            try {
                rel = root.toRealPath().relativize(path.toAbsolutePath().normalize()).toString();
            } catch (IOException e) {
                rel = path.getFileName() == null ? path.toString() : path.getFileName().toString();
            }
            return rel.isEmpty() ? path.toString() : rel;
        }
    }

    /** 简单启发式二进制检测：前 8KB 内出现 NUL 字节即判定二进制。 */
    public static boolean looksBinary(byte[] bytes) {
        int limit = Math.min(bytes.length, BINARY_PROBE_BYTES);
        for (int i = 0; i < limit; i++) {
            if (bytes[i] == 0) {
                return true;
            }
        }
        return false;
    }

    private static String extensionOf(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static Set<String> normalizeExtensions(List<String> configured) {
        if (configured == null) {
            return Set.of();
        }
        java.util.Set<String> result = new java.util.HashSet<>();
        for (String ext : configured) {
            if (ext != null && !ext.isBlank()) {
                result.add(ext.startsWith(".") ? ext.substring(1).toLowerCase(Locale.ROOT)
                        : ext.toLowerCase(Locale.ROOT));
            }
        }
        return Set.copyOf(result);
    }

    static String sha256(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content));
        } catch (Exception e) {
            throw new IndexerException(IndexerErrorCode.SCAN_FAILED, "计算内容指纹失败", e);
        }
    }

}
