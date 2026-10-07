package com.github.lechandonga.localcodeindexerjava.tests;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@BusinessDomain("错误归一化：未配置根目录、参数非法时对外只有稳定错误码")
@ExtendWith(TestLoggingExtension.class)
@SpringBootTest(properties = "code-indexer.root=")
@AutoConfigureMockMvc
class IndexApiErrorEnvelopeTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("未配置 root 时刷新返回 500/ROOT_NOT_CONFIGURED，不泄漏异常细节")
    void refresh_without_root_returns_stable_error() throws Exception {
        mockMvc.perform(post("/api/index/refresh").param("wait", "true"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.errorCode").value("ROOT_NOT_CONFIGURED"))
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    @DisplayName("limit 非法返回 400/INVALID_LIMIT")
    void invalid_limit_returns_stable_error() throws Exception {
        mockMvc.perform(get("/api/search").param("q", "abc").param("limit", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_LIMIT"));
    }
}
