package com.github.lechandonga.localcodeindexerjava.indexer;

import java.util.List;

/**
 * 一次查询的完整结果，记录所基于的快照代次，便于调用方确认数据版本。
 */
public record SearchResult(String query, String mode, long snapshotGeneration, int total,
                          int limit, List<SearchHit> hits) {
}
