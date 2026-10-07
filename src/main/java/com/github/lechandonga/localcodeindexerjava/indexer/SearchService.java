package com.github.lechandonga.localcodeindexerjava.indexer;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 基于 {@link IndexSnapshot} 的只读检索。
 *
 * <p>排序规则（固定、可解释、与遍历顺序无关）：
 * <ol>
 *   <li>分值 rankScore 高者在前；</li>
 *   <li>分值相同按文件相对路径字典序；</li>
 *   <li>文件相同按行号升序。</li>
 * </ol>
 * 分值构成：symbol 模式整行等于查询 100、位于行首标识符 80、其余标识符 60；
 * keyword 模式行首子串 50、词边界子串 30、普通子串 10。
 */
@Service
public class SearchService {

    private static final int MAX_QUERY_LENGTH = 200;

    private final IndexerProperties properties;

    public SearchService(IndexerProperties properties) {
        this.properties = properties;
    }

    public SearchResult search(IndexSnapshot snapshot, String rawQuery, String rawMode, Integer rawLimit) {
        String query = validateQuery(rawQuery);
        Mode mode = validateMode(rawMode);
        int limit = validateLimit(rawLimit);

        Pattern pattern = mode == Mode.SYMBOL
                ? Pattern.compile("\\b" + Pattern.quote(query) + "\\b")
                : Pattern.compile(Pattern.quote(query), Pattern.CASE_INSENSITIVE);
        Pattern boundaryInsensitive = Pattern.compile("\\b" + Pattern.quote(query) + "\\b",
                Pattern.CASE_INSENSITIVE);

        List<SearchHit> hits = new ArrayList<>();
        for (Map.Entry<String, IndexedFile> entry : snapshot.files().entrySet()) {
            String path = entry.getKey();
            List<String> lines = entry.getValue().lines();
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                Matcher matcher = pattern.matcher(line);
                if (!matcher.find()) {
                    continue;
                }
                int score = score(line, matcher.start(), query, mode, matcher, boundaryInsensitive);
                String snippet = snippet(line, matcher.start(), matcher.end());
                hits.add(new SearchHit(path, i + 1, snippet,
                        mode == Mode.SYMBOL ? "symbol" : "keyword", score));
            }
        }

        hits.sort(Comparator.comparingInt(SearchHit::rankScore).reversed()
                .thenComparing(SearchHit::file)
                .thenComparingInt(SearchHit::lineNumber));

        int total = hits.size();
        List<SearchHit> limited = hits.size() > limit ? List.copyOf(hits.subList(0, limit)) : hits;
        return new SearchResult(query, mode == Mode.SYMBOL ? "symbol" : "keyword",
                snapshot.generation(), total, limit, limited);
    }

    private static int score(String line, int matchStart, String query, Mode mode,
                             Matcher symbolMatcher, Pattern boundaryInsensitive) {
        String trimmed = line.trim();
        if (mode == Mode.SYMBOL) {
            if (trimmed.equals(query)) {
                return 100;
            }
            int trimmedStart = line.indexOf(trimmed);
            if (matchStart == trimmedStart) {
                return 80;
            }
            // 典型定义形态：修饰符/类型之后紧跟“符号(”或“符号;”，例如 "void foo() {}"、"int foo;"
            int after = symbolMatcher.end();
            if (after < line.length()
                    && (line.charAt(after) == '(' || line.charAt(after) == ';'
                    || line.charAt(after) == ' ' || line.charAt(after) == '\t')) {
                int prefixLen = matchStart - trimmedStart;
                if (prefixLen > 0 && prefixLen <= trimmed.length()) {
                    String before = trimmed.substring(0, prefixLen);
                    if (before.matches("(?i)[\\w.$\\[\\]<>\\s]+\\s")) {
                        return 75;
                    }
                }
            }
            return 60;
        }
        if (trimmed.toLowerCase(Locale.ROOT).startsWith(query.toLowerCase(Locale.ROOT))) {
            return 50;
        }
        Matcher boundary = boundaryInsensitive.matcher(line);
        if (boundary.find()) {
            return 30;
        }
        return 10;
    }

    private String snippet(String line, int matchStart, int matchEnd) {
        int context = Math.max(0, properties.getSnippetContextChars());
        int from = Math.max(0, matchStart - context);
        int to = Math.min(line.length(), matchEnd + context);
        String prefix = from > 0 ? "..." : "";
        String suffix = to < line.length() ? "..." : "";
        return prefix + line.substring(from, to) + suffix;
    }

    private String validateQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            throw new IndexerException(IndexerErrorCode.INVALID_ARGUMENT, "查询关键字 q 不能为空");
        }
        String query = rawQuery.trim();
        if (query.length() > MAX_QUERY_LENGTH) {
            throw new IndexerException(IndexerErrorCode.INVALID_ARGUMENT,
                    "查询关键字长度不能超过 " + MAX_QUERY_LENGTH + " 个字符");
        }
        return query;
    }

    private Mode validateMode(String rawMode) {
        if (rawMode == null || rawMode.isBlank() || rawMode.equalsIgnoreCase("keyword")) {
            return Mode.KEYWORD;
        }
        if (rawMode.equalsIgnoreCase("symbol")) {
            return Mode.SYMBOL;
        }
        throw new IndexerException(IndexerErrorCode.INVALID_ARGUMENT,
                "mode 只支持 keyword 或 symbol，实际为: " + rawMode);
    }

    private int validateLimit(Integer rawLimit) {
        int max = properties.getMaxResults();
        if (rawLimit == null) {
            return max;
        }
        if (rawLimit <= 0) {
            throw new IndexerException(IndexerErrorCode.INVALID_ARGUMENT, "limit 必须为正整数");
        }
        return Math.min(rawLimit, max);
    }

    private enum Mode {
        KEYWORD, SYMBOL
    }
}
