package com.xiongx9527.askdata.common.debounce;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "askdata.debounce.enabled=true",
        "askdata.cache.type=memory",
        "askdata.debounce.window-ms=5000"
})
@AutoConfigureMockMvc
class DebounceIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void duplicateWithinWindowReturns429() throws Exception {
        MvcResult r1 = mvc.perform(post("/test/debounce/apply").param("id", "101")).andReturn();
        assertEquals(200, r1.getResponse().getStatus());
        MvcResult r2 = mvc.perform(post("/test/debounce/apply").param("id", "101")).andReturn();
        assertEquals(429, r2.getResponse().getStatus(), "窗口内重复请求应被拦截");
        String body = r2.getResponse().getContentAsString();
        assertTrue(body.contains("\"code\":429") || body.contains("\"code\": 429"),
                "应返回统一 Result 信封 code=429, 实际: " + body);
        assertNotNull(r2.getResponse().getHeader("Retry-After"));
    }

    @Test
    void differentParamNotBlocked() throws Exception {
        mvc.perform(post("/test/debounce/apply").param("id", "201")).andExpect(status().isOk());
        mvc.perform(post("/test/debounce/apply").param("id", "202")).andExpect(status().isOk());
    }

    @Test
    void unannotatedEndpointNotAffected() throws Exception {
        mvc.perform(post("/test/debounce/open").param("id", "301")).andExpect(status().isOk());
        mvc.perform(post("/test/debounce/open").param("id", "301")).andExpect(status().isOk());
    }

    @Test
    void customMessageReturned() throws Exception {
        mvc.perform(post("/test/debounce/apply-custom").param("id", "401")).andExpect(status().isOk());
        MvcResult r = mvc.perform(post("/test/debounce/apply-custom").param("id", "401")).andReturn();
        assertEquals(429, r.getResponse().getStatus());
        // Spring 5.3 的 getContentAsString() 默认按 ISO-8859-1 解码（Spring 6 起默认 UTF-8），
        // 中文提示必须显式指定 UTF-8，否则断言会因乱码失败。
        String body = r.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(body.contains("请勿重复提交"), "应返回自定义拦截提示, 实际: " + body);
    }

    @Test
    void perMethodWindowOverridesGlobal() throws Exception {
        // 全局窗口 5000ms，/apply-window 注解窗口 500ms；若覆盖生效，500~5000ms 之间应已放行
        mvc.perform(post("/test/debounce/apply-window").param("id", "501")).andExpect(status().isOk());
        MvcResult r2 = mvc.perform(post("/test/debounce/apply-window").param("id", "501")).andReturn();
        assertEquals(429, r2.getResponse().getStatus(), "窗口内重复请求应被拦截");
        Thread.sleep(700L); // 超过注解窗口 500ms，但仍在全局窗口 5000ms 内
        MvcResult r3 = mvc.perform(post("/test/debounce/apply-window").param("id", "501")).andReturn();
        assertEquals(200, r3.getResponse().getStatus(),
                "注解窗口过期后应放行（证明未沿用全局 5000ms 窗口）, 实际: " + r3.getResponse().getStatus());
    }
}
