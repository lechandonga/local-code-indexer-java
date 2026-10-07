package com.github.lechandonga.localcodeindexerjava.search;

import java.util.List;

/**
 * 一次查询的完整结果，包含所基于的快照版本，便于调用方判断结果一致性。
 */
public record SearchResult(
        String query,
        String mode,
        boolean caseSensitive,
        int limit,
        int totalHits,
        boolean truncated,
        long revision,
        List<SearchHit> hits) {
}
