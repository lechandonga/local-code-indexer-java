package com.github.lechandonga.localcodeindexerjava.index;

/**
 * 触发刷新的结果：复用进行中的任务，还是真正启动了新任务。
 */
public record RefreshOutcome(RefreshStatus status, boolean reused) {
}
