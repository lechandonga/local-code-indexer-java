package com.github.lechandonga.localcodeindexerjava.search;

import com.github.lechandonga.localcodeindexerjava.config.IndexerProperties;
import com.github.lechandonga.localcodeindexerjava.index.IndexService;
import com.github.lechandonga.localcodeindexerjava.index.IndexSnapshot;
import com.github.lechandonga.localcodeindexerjava.index.IndexedFile;
import com.github.lechandonga.localcodeindexerjava.web.BadRequestException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 基于不可变 {@link IndexSnapshot} 的代码检索。
 *
 * <p>排序规则（固定，保证同查询多次执行结果一致）：
 * 文件相对路径字典序 → 行号升序 → 命中起始列升序 → 命中词字典序。
 *
 * <p>两种模式：
 * <ul>
 *   <li>KEYWORD：大小写不敏感的子串匹配（默认，适合关键字搜索）；</li>
 *   <li>SYMBOL：大小写敏感，按“符号 token”匹配，token 边界为非
 *       [A-Za-z0-9_] 字符或行首行尾（适合方法名、变量名、类名）。</li>
 * </ul>
 */
@Service
public class SearchService {

    private static final int MAX_QUERY_LENGTH = 200;
    private static final int MAX_FRAGMENT_LENGTH = 500;

    private final IndexService indexService;
    private final IndexerProperties properties;

    public SearchService(IndexService indexService, IndexerProperties properties) {
        this.indexService = indexService;
        this.properties = properties;
    }

    public SearchResult search(String rawQuery, String rawMode, Boolean caseSensitive, Integer limit) {
        if (rawQuery == null || rawQuery.isBlank()) {
            throw new BadRequestException("EMPTY_QUERY", "query must not be empty or blank");
        }
        String query = rawQuery.trim();
        if (query.length() > MAX_QUERY_LENGTH) {
            throw new BadRequestException("QUERY_TOO_LONG",
                    "query length must not exceed " + MAX_QUERY_LENGTH + " characters");
        }
        boolean symbolMode;
        if (rawMode == null || rawMode.isBlank() || rawMode.equalsIgnoreCase("KEYWORD")) {
            symbolMode = false;
        } else if (rawMode.equalsIgnoreCase("SYMBOL")) {
            symbolMode = true;
        } else {
            throw new BadRequestException("INVALID_MODE", "mode must be either KEYWORD or SYMBOL");
        }
        boolean sensitive = caseSensitive != null ? caseSensitive : symbolMode;
        int effectiveLimit = resolveLimit(limit);

        IndexSnapshot snapshot = indexService.currentSnapshot();
        List<SearchHit> hits = new ArrayList<>();
        for (Map.Entry<String, IndexedFile> entry : snapshot.filesByRelativePath().entrySet()) {
            String file = entry.getKey();
            IndexedFile indexedFile = entry.getValue();
            List<String> lines = indexedFile.lines();
            for (int lineIndex = 0; lineIndex < lines.size(); lineIndex++) {
                collectLineHits(snapshot.revision(), file, lineIndex + 1, lines.get(lineIndex),
                        query, symbolMode, sensitive, hits);
            }
        }
        hits.sort(Comparator
                .comparing(SearchHit::file)
                .thenComparingInt(SearchHit::line)
                .thenComparingInt(SearchHit::matchStartColumn)
                .thenComparing(SearchHit::matchedTerm));
        int total = hits.size();
        boolean truncated = total > effectiveLimit;
        List<SearchHit> page = truncated ? new ArrayList<>(hits.subList(0, effectiveLimit)) : hits;
        return new SearchResult(query, symbolMode ? "SYMBOL" : "KEYWORD", sensitive,
                effectiveLimit, total, truncated, snapshot.revision(), page);
    }

    private int resolveLimit(Integer limit) {
        if (limit == null) {
            return properties.getDefaultLimit();
        }
        if (limit <= 0) {
            throw new BadRequestException("INVALID_LIMIT", "limit must be a positive integer");
        }
        if (limit > properties.getMaxLimit()) {
            throw new BadRequestException("INVALID_LIMIT",
                    "limit must not exceed " + properties.getMaxLimit());
        }
        return limit;
    }

    private void collectLineHits(long revision, String file, int lineNumber, String line,
                                 String query, boolean symbolMode, boolean caseSensitive,
                                 List<SearchHit> out) {
        String haystack = caseSensitive ? line : line.toLowerCase();
        String needle = caseSensitive ? query : query.toLowerCase();
        int from = 0;
        while (from <= haystack.length() - needle.length()) {
            int found = haystack.indexOf(needle, from);
            if (found < 0) {
                break;
            }
            if (!symbolMode || isTokenAt(line, found, needle.length())) {
                String matchedTerm = line.substring(found, found + needle.length());
                int[] window = fragmentWindow(line.length(), found, needle.length());
                String fragment = abbreviate(line.substring(window[0], window[1]));
                out.add(new SearchHit(file, lineNumber, symbolMode ? "SYMBOL" : "KEYWORD",
                        matchedTerm, found + 1, fragment, window[0] + 1, revision));
            }
            from = found + Math.max(1, needle.length());
        }
    }

    /**
     * SYMBOL 模式：命中位置前后必须是符号边界，即非字母/数字/下划线或行首尾。
     */
    static boolean isTokenAt(String line, int start, int length) {
        int end = start + length;
        boolean leftBoundary = start == 0 || !isIdentifierChar(line.charAt(start - 1));
        boolean rightBoundary = end == line.length() || !isIdentifierChar(line.charAt(end));
        return leftBoundary && rightBoundary;
    }

    private static boolean isIdentifierChar(char c) {
        return c == '_' || Character.isLetterOrDigit(c);
    }

    private int[] fragmentWindow(int lineLength, int matchStart, int matchLength) {
        int window = properties.getFragmentWindowChars();
        int start = Math.max(0, matchStart - window);
        int end = Math.min(lineLength, matchStart + matchLength + window);
        return new int[]{start, end};
    }

    private static String abbreviate(String fragment) {
        if (fragment.length() <= MAX_FRAGMENT_LENGTH) {
            return fragment;
        }
        return fragment.substring(0, MAX_FRAGMENT_LENGTH - 3) + "...";
    }
}
