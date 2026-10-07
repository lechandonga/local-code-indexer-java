package com.github.lechandonga.localcodeindexerjava.tests;

import com.github.lechandonga.localcodeindexerjava.config.IndexerProperties;
import com.github.lechandonga.localcodeindexerjava.index.IndexService;
import com.github.lechandonga.localcodeindexerjava.search.SearchHit;
import com.github.lechandonga.localcodeindexerjava.search.SearchResult;
import com.github.lechandonga.localcodeindexerjava.search.SearchService;
import com.github.lechandonga.localcodeindexerjava.web.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@BusinessDomain("代码检索：关键字/符号匹配、片段、确定性排序、非法参数拒绝")
@ExtendWith(TestLoggingExtension.class)
class SearchServiceTest {

    @TempDir
    Path root;

    private IndexService indexService;
    private SearchService searchService;
    private IndexerProperties properties;

    @BeforeEach
    void setUp() throws IOException {
        properties = new IndexerProperties();
        properties.setRoot(root.toString());
        properties.setAutoRefreshOnStartup(false);
        indexService = new IndexService(properties,
                new com.github.lechandonga.localcodeindexerjava.index.CodeFileScanner(properties));
        searchService = new SearchService(indexService, properties);

        Files.createDirectories(root.resolve("a"));
        Files.createDirectories(root.resolve("b"));
        Files.writeString(root.resolve("a/Zeta.java"),
                "public class Zeta {\n    void findMe() { log(\"findme FINDME\"); }\n}\n");
        Files.writeString(root.resolve("b/Alpha.java"),
                "class Alpha {\n    int findMeCount = 1;\n    void doNotFindMe() {}\n}\n");
        indexService.triggerRefresh(true).join();
    }

    @Test
    @DisplayName("关键字默认大小写不敏感，返回文件、行号、片段与版本号")
    void keyword_search_is_case_insensitive_and_reports_location() {
        SearchResult result = searchService.search("findme", null, null, null);

        assertEquals("KEYWORD", result.mode());
        assertFalse(result.caseSensitive());
        assertEquals(5, result.hits().size());
        SearchHit first = result.hits().get(0);
        assertEquals("a/Zeta.java", first.file());
        assertEquals(2, first.line());
        assertTrue(first.fragment().contains("findme"));
        assertTrue(first.matchStartColumn() >= 1);
        assertEquals(result.revision(), first.revision());
    }

    @Test
    @DisplayName("排序固定为文件路径、行号、起始列，多次查询结果完全一致")
    void ordering_is_deterministic_across_runs() {
        SearchResult first = searchService.search("findme", "KEYWORD", false, null);
        SearchResult second = searchService.search("findme", "KEYWORD", false, null);

        List<String> firstKeys = keys(first);
        List<String> secondKeys = keys(second);
        assertEquals(firstKeys, secondKeys);
        assertEquals(List.of(
                "a/Zeta.java:2:10",
                "a/Zeta.java:2:26",
                "a/Zeta.java:2:33",
                "b/Alpha.java:2:9",
                "b/Alpha.java:3:15"), firstKeys,
                "必须按 文件路径 -> 行号 -> 起始列 固定排序");
    }

    @Test
    @DisplayName("SYMBOL 模式大小写敏感且按 token 边界匹配，不命中子串与其他大小写")
    void symbol_search_respects_case_and_token_boundaries() {
        SearchResult symbol = searchService.search("findMe", "SYMBOL", null, null);
        assertTrue(symbol.caseSensitive());
        assertEquals(List.of("a/Zeta.java:2:10"), keys(symbol),
                "findMeCount 中是前缀子串，doNotFindMe 中左侧是标识符字符，均不得作为符号命中");

        assertEquals(List.of("a/Zeta.java:2:26"),
                keys(searchService.search("findme", "SYMBOL", null, null)),
                "SYMBOL 大小写敏感：只有字符串字面量里的小写 findme 命中，方法名 findMe 不命中");
    }

    @Test
    @DisplayName("空查询、非法模式、非法分页参数必须被明确拒绝并带稳定错误码")
    void invalid_arguments_are_rejected_with_stable_codes() {
        BadRequestException empty = assertThrows(BadRequestException.class,
                () -> searchService.search("   ", null, null, null));
        assertEquals("EMPTY_QUERY", empty.getErrorCode());

        BadRequestException mode = assertThrows(BadRequestException.class,
                () -> searchService.search("findMe", "REGEX", null, null));
        assertEquals("INVALID_MODE", mode.getErrorCode());

        BadRequestException zero = assertThrows(BadRequestException.class,
                () -> searchService.search("findMe", null, null, 0));
        assertEquals("INVALID_LIMIT", zero.getErrorCode());

        BadRequestException tooLarge = assertThrows(BadRequestException.class,
                () -> searchService.search("findMe", null, null, properties.getMaxLimit() + 1));
        assertEquals("INVALID_LIMIT", tooLarge.getErrorCode());
    }

    @Test
    @DisplayName("limit 截断时返回的命中数受限，并标记 truncated 与 totalHits")
    void limit_truncates_and_reports_total() {
        SearchResult result = searchService.search("findme", "KEYWORD", false, 2);
        assertEquals(2, result.hits().size());
        assertTrue(result.truncated());
        assertEquals(5, result.totalHits());
    }

    private static List<String> keys(SearchResult result) {
        return result.hits().stream()
                .map(hit -> hit.file() + ":" + hit.line() + ":" + hit.matchStartColumn())
                .toList();
    }
}
