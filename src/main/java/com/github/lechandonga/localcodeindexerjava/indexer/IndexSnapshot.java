package com.github.lechandonga.localcodeindexerjava.indexer;

import java.util.List;
import java.util.Map;

/**
 * 一次刷新产出的不可变索引快照。
 * 查询永远基于某个完整快照，不会读到构建中的半成品，也不会新旧混杂。
 */
public record IndexSnapshot(
        long generation,
        String rootPath,
        Map<String, IndexedFile> files,
        List<SkippedFile> skipped,
        int processedCount,
        int skippedCount,
        long reprocessedCount,
        long reusedCount,
        long builtAtMillis) {

    public IndexedFile file(String relativePath) {
        return files.get(relativePath);
    }
}
