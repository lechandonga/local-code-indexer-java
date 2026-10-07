package com.github.lechandonga.localcodeindexerjava.index;

import java.util.List;

/**
 * 刷新任务状态的对外视图，状态码稳定：RUNNING / SUCCESS / FAILED / NEVER_RUN。
 */
public record RefreshStatus(
        String state,
        Long revision,
        String startedAt,
        String finishedAt,
        Long durationMillis,
        Long indexedCount,
        Long skippedCount,
        Long unchangedCount,
        Long reindexedCount,
        Long deletedCount,
        String errorCode,
        String message,
        List<SkippedFile> skippedFiles,
        boolean reused) {

    public static RefreshStatus neverRun() {
        return new RefreshStatus("NEVER_RUN", null, null, null, null, null, null, null, null, null,
                null, "no refresh has completed yet", null, false);
    }
}
