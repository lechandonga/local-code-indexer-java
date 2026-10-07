package com.github.lechandonga.localcodeindexerjava.index;

import com.github.lechandonga.localcodeindexerjava.config.IndexerProperties;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 负责把一个根目录扫描为 {@link IndexedFile} 列表与 {@link SkippedFile} 列表。
 * 单个文件的任何问题都被归一成可区分的跳过原因，绝不让整次扫描失败；
 * 只有根目录本身不可用时才抛出 {@link IOException}。
 */
public class CodeFileScanner {

    private final IndexerProperties properties;
    private final Set<String> excludedDirNames;

    public CodeFileScanner(IndexerProperties properties) {
        this.properties = properties;
        this.excludedDirNames = new TreeSet<>(Arrays.asList(properties.getExcludeDirNames()));
    }

    /**
     * 扫描结果。
     */
    public record ScanResult(List<IndexedFile> indexed, List<SkippedFile> skipped) {
    }

    /**
     * 扫描 {@code root} 下所有可接受的文件。
     *
     * @throws IOException 根目录不存在、不是目录或无法访问
     */
    public ScanResult scan(Path root) throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        if (!Files.exists(normalizedRoot)) {
            throw new IOException("index root does not exist: " + normalizedRoot);
        }
        if (!Files.isDirectory(normalizedRoot)) {
            throw new IOException("index root is not a directory: " + normalizedRoot);
        }
        List<IndexedFile> indexed = new ArrayList<>();
        List<SkippedFile> skipped = new ArrayList<>();
        walk(normalizedRoot, normalizedRoot, indexed, skipped);
        indexed.sort(Comparator.comparing(IndexedFile::relativePath));
        skipped.sort(Comparator.comparing(SkippedFile::path).thenComparing(s -> s.reason().name()));
        return new ScanResult(indexed, skipped);
    }

    private void walk(Path root, Path dir, List<IndexedFile> indexed, List<SkippedFile> skipped) {
        List<Path> children;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            children = new ArrayList<>();
            for (Path child : stream) {
                children.add(child);
            }
        } catch (IOException | SecurityException e) {
            skipped.add(new SkippedFile(rel(root, dir), SkipReason.UNREADABLE_FILE,
                    "cannot list directory: " + safeMessage(e)));
            return;
        }
        children.sort(Comparator.comparing(path -> path.getFileName().toString()));
        for (Path child : children) {
            BasicFileAttributes linkAttrs;
            try {
                linkAttrs = Files.readAttributes(child, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (IOException | SecurityException e) {
                skipped.add(new SkippedFile(rel(root, child), SkipReason.UNREADABLE_FILE,
                        "cannot stat path: " + safeMessage(e)));
                continue;
            }
            if (Files.isSymbolicLink(child)) {
                if (!handleSymlink(root, child, indexed, skipped)) {
                    continue;
                }
            } else if (linkAttrs.isDirectory()) {
                if (!excludedDirNames.contains(child.getFileName().toString())) {
                    walk(root, child, indexed, skipped);
                }
                continue;
            } else if (!linkAttrs.isRegularFile()) {
                skipped.add(new SkippedFile(rel(root, child), SkipReason.NOT_REGULAR_FILE,
                        "path is neither a regular file nor a directory"));
                continue;
            }
            readRegularFile(root, child, indexed, skipped);
        }
    }

    /**
     * @return true 表示链接指向根内普通文件，应继续读取
     */
    private boolean handleSymlink(Path root, Path link, List<IndexedFile> indexed, List<SkippedFile> skipped) {
        Path resolved;
        try {
            resolved = link.toRealPath();
        } catch (IOException | SecurityException e) {
            skipped.add(new SkippedFile(rel(root, link), SkipReason.LINK_OUTSIDE_ROOT,
                    "broken or unresolvable symbolic link: " + safeMessage(e)));
            return false;
        }
        Path canonicalRoot;
        try {
            canonicalRoot = root.toRealPath();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (!resolved.startsWith(canonicalRoot)) {
            skipped.add(new SkippedFile(rel(root, link), SkipReason.LINK_OUTSIDE_ROOT,
                    "link resolves outside of index root"));
            return false;
        }
        BasicFileAttributes targetAttrs;
        try {
            targetAttrs = Files.readAttributes(resolved, BasicFileAttributes.class);
        } catch (IOException | SecurityException e) {
            skipped.add(new SkippedFile(rel(root, link), SkipReason.LINK_OUTSIDE_ROOT,
                    "link target cannot be read: " + safeMessage(e)));
            return false;
        }
        if (targetAttrs.isDirectory()) {
            skipped.add(new SkippedFile(rel(root, link), SkipReason.NOT_REGULAR_FILE,
                    "symbolic link points to a directory"));
            return false;
        }
        if (!targetAttrs.isRegularFile()) {
            skipped.add(new SkippedFile(rel(root, link), SkipReason.NOT_REGULAR_FILE,
                    "symbolic link points to a non-regular file"));
            return false;
        }
        return true;
    }

    private void readRegularFile(Path root, Path file, List<IndexedFile> indexed, List<SkippedFile> skipped) {
        String relativePath = rel(root, file);
        long size;
        long lastModified;
        try {
            BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
            size = attrs.size();
            lastModified = attrs.lastModifiedTime().toMillis();
        } catch (IOException | SecurityException e) {
            skipped.add(new SkippedFile(relativePath, SkipReason.UNREADABLE_FILE,
                    "cannot stat file: " + safeMessage(e)));
            return;
        }
        if (size > properties.getMaxFileBytes()) {
            skipped.add(new SkippedFile(relativePath, SkipReason.FILE_TOO_LARGE,
                    "file size " + size + " exceeds limit " + properties.getMaxFileBytes()));
            return;
        }
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException | SecurityException | OutOfMemoryError e) {
            skipped.add(new SkippedFile(relativePath, SkipReason.UNREADABLE_FILE,
                    "cannot read file: " + safeMessage(e)));
            return;
        }
        int probeLength = Math.min(bytes.length, properties.getBinaryProbeBytes());
        if (looksBinary(bytes, probeLength)) {
            skipped.add(new SkippedFile(relativePath, SkipReason.BINARY_OR_UNDECODABLE_FILE,
                    "binary content detected in first " + probeLength + " bytes"));
            return;
        }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            skipped.add(new SkippedFile(relativePath, SkipReason.BINARY_OR_UNDECODABLE_FILE,
                    "file is not valid UTF-8 text"));
            return;
        }
        List<String> lines = splitLines(text);
        indexed.add(new IndexedFile(relativePath, size, lastModified, lines));
    }

    /**
     * 二进制判定规则（固定、可解释）：探测字节中出现 NUL 字节，
     * 或非文本控制字符占比超过 30%。常见空白控制字符（制表、换行等）不计入。
     */
    static boolean looksBinary(byte[] bytes, int length) {
        int suspicious = 0;
        for (int i = 0; i < length; i++) {
            int b = bytes[i] & 0xFF;
            if (b == 0) {
                return true;
            }
            if (b < 0x20 && b != '\t' && b != '\n' && b != '\r' && b != '\f' && b != 0x1B) {
                suspicious++;
            }
        }
        return length > 0 && suspicious * 100L / length > 30L;
    }

    private static List<String> splitLines(String text) {
        List<String> lines = new ArrayList<>();
        int start = 0;
        int length = text.length();
        for (int i = 0; i < length; i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r') {
                lines.add(text.substring(start, i));
                if (c == '\r' && i + 1 < length && text.charAt(i + 1) == '\n') {
                    i++;
                }
                start = i + 1;
            }
        }
        if (start < length) {
            lines.add(text.substring(start));
        }
        return lines;
    }

    private static String rel(Path root, Path path) {
        String relative = root.relativize(path.toAbsolutePath().normalize()).toString();
        return relative.replace(java.io.File.separatorChar, '/');
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        String type = throwable.getClass().getSimpleName();
        if (message == null || message.isBlank()) {
            return type;
        }
        return type + ": " + abbreviate(message);
    }

    private static String abbreviate(String value) {
        String cleaned = value.replace('\n', ' ').replace('\r', ' ');
        return cleaned.length() <= 200 ? cleaned : cleaned.substring(0, 200) + "...";
    }

}
