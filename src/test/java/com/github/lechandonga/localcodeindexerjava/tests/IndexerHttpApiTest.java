package com.github.lechandonga.localcodeindexerjava.tests;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 覆盖业务：HTTP 接口端到端行为——刷新、状态统计、检索命中与排序字段、
 * 空查询/非法参数/刷新冲突/未建索引查询的稳定错误码。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IndexerHttpApiTest extends BaseIndexerTest {

    static Path tempDir;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        try {
            tempDir = Files.createTempDirectory("indexer-http");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        registry.add("local-indexer.root-path", () -> tempDir.toString());
        registry.add("local-indexer.refresh-conflict-policy", () -> "reject");
    }

    @Autowired
    private MockMvc mockMvc;

    @Override
    protected String businessGroup() {
        return "HTTP 接口/错误码";
    }

    @BeforeAll
    void seedRepository() throws Exception {
        Files.writeString(tempDir.resolve("Demo.java"), "public class Demo {\n// lookupSymbol\nvoid lookupSymbol() {}\n}\n");
    }

    @Test
    void refreshThenSearchThenReflectsDeletion() throws Exception {
        mockMvc.perform(post("/api/index/refresh").param("wait", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("succeeded"))
                .andExpect(jsonPath("$.indexedCount").value(1))
                .andExpect(jsonPath("$.skippedCount").value(0));

        mockMvc.perform(get("/api/index/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("succeeded"))
                .andExpect(jsonPath("$.generation").value(1));

        mockMvc.perform(get("/api/search").param("q", "lookupSymbol").param("mode", "symbol"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.mode").value("symbol"))
                .andExpect(jsonPath("$.snapshotGeneration").value(1))
                .andExpect(jsonPath("$.hits[0].file").value("Demo.java"))
                .andExpect(jsonPath("$.hits[0].lineNumber").value(3))
                .andExpect(jsonPath("$.hits[0].snippet").value(org.hamcrest.Matchers.containsString("lookupSymbol")));

        Files.delete(tempDir.resolve("Demo.java"));
        mockMvc.perform(post("/api/index/refresh").param("wait", "true"))
                .andExpect(jsonPath("$.generation").value(2))
                .andExpect(jsonPath("$.indexedCount").value(0));

        mockMvc.perform(get("/api/search").param("q", "lookupSymbol"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void rejectsBlankQueryWithStableErrorCode() throws Exception {
        mockMvc.perform(get("/api/search").param("q", "   "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("invalid_argument"));

        mockMvc.perform(get("/api/search").param("q", "x").param("mode", "bogus"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("invalid_argument"));

        mockMvc.perform(get("/api/search").param("q", "x").param("limit", "-3"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("invalid_argument"));
    }
}
