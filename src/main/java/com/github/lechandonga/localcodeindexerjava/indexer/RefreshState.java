package com.github.lechandonga.localcodeindexerjava.indexer;

/**
 * 刷新任务的生命周期状态。
 */
public enum RefreshState {
    /** 尚未执行过任何刷新。 */
    IDLE,
    /** 有且仅有一个刷新任务正在执行。 */
    RUNNING,
    /** 最近一次刷新成功，索引快照已原子切换。 */
    SUCCEEDED,
    /** 最近一次刷新失败，旧快照（若有）仍可查询。 */
    FAILED
}
