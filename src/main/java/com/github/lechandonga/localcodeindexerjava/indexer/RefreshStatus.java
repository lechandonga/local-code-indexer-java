package com.github.lechandonga.localcodeindexerjava.indexer;

import java.util.List;
import java.util.Locale;

/**
 * 刷新任务状态的不可变视图，同时用于刷新接口的响应体。
 */
public record RefreshStatus(
        String state,
        long generation,
        String rootPath,
        Long startedAtMillis,
        Long finishedAtMillis,
        Long durationMillis,
        int indexedCount,
        int skippedCount,
        long reprocessedCount,
        long reusedCount,
        List<SkippedFile> skippedFiles,
        String errorCode,
        String errorMessage,
        /** 本次请求是否复用了已在进行中的刷新任务。 */
        boolean joinedExisting) {

    static RefreshStatus running(long generation, String rootPath, long startedAtMillis) {
        return new RefreshStatus(RefreshState.RUNNING.name().toLowerCase(Locale.ROOT), generation, rootPath,
                startedAtMillis, null, null, 0, 0, 0, 0, List.of(), null, null, false);
    }

    static RefreshStatus idle(String rootPath) {
        return new RefreshStatus(RefreshState.IDLE.name().toLowerCase(Locale.ROOT), 0, rootPath,
                null, null, null, 0, 0, 0, 0, List.of(), null, null, false);
    }
}
