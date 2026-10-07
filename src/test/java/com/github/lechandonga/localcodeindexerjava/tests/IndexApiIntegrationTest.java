package com.github.lechandonga.localcodeindexerjava.tests;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@BusinessDomain("HTTP 接口契约：刷新/状态/跳过明细/检索/删除回收/错误信封")
@ExtendWith(TestLoggingExtension.class)
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IndexApiIntegrationTest {

    @TempDir
    static Path sharedRoot;

    @DynamicPropertySource
    static void registerRoot(DynamicPropertyRegistry registry) {
        registry.add("code-indexer.root", () -> sharedRoot.toString());
        registry.add("code-indexer.auto-refresh-on-startup", () -> "false");
    }

    @Autowired
    private MockMvc mockMvc;

    private static void write(String relative, String content) throws IOException {
        Path file = sharedRoot.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    @BeforeAll
    static void seed() throws IOException {
        write("src/Hello.java", "class Hello {\n    String helloWorld = \"hi\";\n}\n");
        Path blob = sharedRoot.resolve("src/data/blob.bin");
        Files.createDirectories(blob.getParent());
        Files.write(blob, new byte[]{'x', 0, 'y', 0});
    }

    @Test
    @Order(1)
    @DisplayName("首次刷新成功：统计含 1 个索引文件与 1 个二进制跳过，跳过原因可区分")
    void first_refresh_reports_indexed_and_skipped() throws Exception {
        mockMvc.perform(post("/api/index/refresh").param("wait", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status.state").value("SUCCESS"))
                .andExpect(jsonPath("$.status.revision").value(1))
                .andExpect(jsonPath("$.status.indexedCount").value(1))
                .andExpect(jsonPath("$.status.skippedCount").value(1));

        mockMvc.perform(get("/api/index/skipped"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.skippedCount").value(1))
                .andExpect(jsonPath("$.skippedFiles[0].path").value("src/data/blob.bin"))
                .andExpect(jsonPath("$.skippedFiles[0].reason")
                        .value("BINARY_OR_UNDECODABLE_FILE"));
    }

    @Test
    @Order(2)
    @DisplayName("检索接口返回文件、行号、命中片段，符号模式遵守 token 边界")
    void search_returns_file_line_and_fragment() throws Exception {
        mockMvc.perform(get("/api/search").param("q", "helloWorld").param("mode", "SYMBOL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("SYMBOL"))
                .andExpect(jsonPath("$.totalHits").value(1))
                .andExpect(jsonPath("$.hits[0].file").value("src/Hello.java"))
                .andExpect(jsonPath("$.hits[0].line").value(2))
                .andExpect(jsonPath("$.hits[0].fragment").value(org.hamcrest.Matchers.containsString("helloWorld")))
                .andExpect(jsonPath("$.revision").value(1));

        mockMvc.perform(get("/api/search").param("q", "hello").param("mode", "SYMBOL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalHits").value(0));
    }

    @Test
    @Order(3)
    @DisplayName("删除文件后再刷新，删除文件不再可检索且 deletedCount 正确")
    void deleted_file_disappears_after_second_refresh() throws Exception {
        Files.delete(sharedRoot.resolve("src/Hello.java"));

        mockMvc.perform(post("/api/index/refresh").param("wait", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status.revision").value(2))
                .andExpect(jsonPath("$.status.deletedCount").value(1))
                .andExpect(jsonPath("$.status.indexedCount").value(0));

        mockMvc.perform(get("/api/search").param("q", "helloWorld"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalHits").value(0));
    }

    @Test
    @Order(4)
    @DisplayName("空查询与非法 mode 返回 400 和稳定错误码，错误信封不含原始异常")
    void invalid_search_arguments_return_stable_error_envelope() throws Exception {
        mockMvc.perform(get("/api/search"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("EMPTY_QUERY"))
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.timestamp").exists());

        mockMvc.perform(get("/api/search").param("q", "abc").param("mode", "BOGUS"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_MODE"));
    }

}
