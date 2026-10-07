package com.github.lechandonga.localcodeindexerjava.tests;

import com.github.lechandonga.localcodeindexerjava.indexer.CodeScanner;
import com.github.lechandonga.localcodeindexerjava.indexer.IndexSnapshot;
import com.github.lechandonga.localcodeindexerjava.indexer.IndexerErrorCode;
import com.github.lechandonga.localcodeindexerjava.indexer.IndexerException;
import com.github.lechandonga.localcodeindexerjava.indexer.IndexerProperties;
import com.github.lechandonga.localcodeindexerjava.indexer.SearchHit;
import com.github.lechandonga.localcodeindexerjava.indexer.SearchResult;
import com.github.lechandonga.localcodeindexerjava.indexer.SearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖业务：关键字/符号检索、命中片段与固定排序的确定性、空查询与非法参数拒绝。
 */
class SearchServiceTest extends BaseIndexerTest {

    @TempDir
    Path tempDir;

    private SearchService searchService;
    private IndexSnapshot snapshot;

    @Override
    protected String businessGroup() {
        return "检索/排序与参数校验";
    }

    @BeforeEach
    void setUp() throws Exception {
        Files.writeString(tempDir.resolve("Service.java"), String.join("\n",
                "public class Service {",
                "    // findUser is used here",
                "    findUser() { return userService.findUser(); }",
                "}",
                ""));
        Files.writeString(tempDir.resolve("Api.java"), String.join("\n",
                "class Api {",
                "findUser = 1;",
                "}",
                ""));
        Files.createDirectories(tempDir.resolve("sub"));
        Files.writeString(tempDir.resolve("sub/Helper.java"), "class Helper { int findUser; }\n");

        IndexerProperties properties = new IndexerProperties();
        searchService = new SearchService(properties);
        snapshot = new CodeScanner(properties).scan(tempDir, 1, null);
    }

    @Test
    void keywordSearchIsCaseInsensitiveAndSortedDeterministically() {
        SearchResult first = searchService.search(snapshot, "FINDUSER", null, null);
        SearchResult second = searchService.search(snapshot, "finduser", null, null);
        assertEquals(first.total(), second.total());
        List<SearchHit> hits = first.hits();
        assertTrue(hits.size() >= 4);
        // 固定排序：分值降序，同分值按文件路径，再按行号
        for (int i = 1; i < hits.size(); i++) {
            SearchHit prev = hits.get(i - 1);
            SearchHit cur = hits.get(i);
            assertTrue(prev.rankScore() > cur.rankScore()
                    || prev.rankScore() == cur.rankScore()
                    && (prev.file().compareTo(cur.file()) < 0
                    || prev.file().equals(cur.file()) && prev.lineNumber() <= cur.lineNumber()));
        }
        // 同样参数再查一次，结果序列完全一致
        assertEquals(describe(second.hits()), describe(hits));
    }

    @Test
    void symbolSearchMatchesIdentifierBoundariesAndReportsLineAndSnippet() {
        SearchResult result = searchService.search(snapshot, "findUser", "symbol", null);
        assertTrue(result.total() >= 4);
        for (SearchHit hit : result.hits()) {
            assertTrue(hit.lineNumber() > 0);
            assertTrue(hit.snippet().contains("findUser"));
            assertEquals("symbol", hit.matchType());
        }
        // 典型定义/声明得 75，同分时按文件路径字典序，Api.java 在 Service.java 前
        assertEquals("Api.java", result.hits().get(0).file());
        assertEquals(80, result.hits().get(0).rankScore());
        SearchHit last = result.hits().get(result.hits().size() - 1);
        assertEquals(60, last.rankScore(), "普通行内引用（注释）分值最低");
    }

    @Test
    void rejectsBlankQueryAndIllegalModeAndLimit() {
        IndexerException blank = assertThrows(IndexerException.class,
                () -> searchService.search(snapshot, "  ", null, null));
        assertEquals(IndexerErrorCode.INVALID_ARGUMENT, blank.errorCode());

        IndexerException mode = assertThrows(IndexerException.class,
                () -> searchService.search(snapshot, "foo", "regex", null));
        assertEquals(IndexerErrorCode.INVALID_ARGUMENT, mode.errorCode());

        IndexerException limit = assertThrows(IndexerException.class,
                () -> searchService.search(snapshot, "foo", null, 0));
        assertEquals(IndexerErrorCode.INVALID_ARGUMENT, limit.errorCode());
    }

    private static List<String> describe(List<SearchHit> hits) {
        return hits.stream().map(h -> h.file() + ":" + h.lineNumber() + ":" + h.rankScore()).toList();
    }
}
