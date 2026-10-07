package com.github.lechandonga.localcodeindexerjava.index;

import java.util.List;
import java.util.Map;

/**
 * 一次刷新产生的完整、不可变索引快照。查询始终基于某一个快照，
 * 因此刷新期间查询要么看到上一次完整结果，要么看到新一次完整结果，不会混杂。
 */
public record IndexSnapshot(
        long revision,
        String root,
        Map<String, IndexedFile> filesByRelativePath,
        List<SkippedFile> skippedFiles,
        long indexedCount,
        long skippedCount,
        long scannedAtEpochMillis) {

    /** 尚未完成过任何刷新时使用的空快照。 */
    public static IndexSnapshot empty() {
        return new IndexSnapshot(0, "", Map.of(), List.of(), 0, 0, System.currentTimeMillis());
    }
}
