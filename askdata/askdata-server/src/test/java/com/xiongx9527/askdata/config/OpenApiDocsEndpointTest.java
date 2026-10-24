package com.xiongx9527.askdata.config;

import com.xiongx9527.askdata.common.web.AuthWhitelist;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * API 文档端点（Knife4j {@code /doc.html} + OpenAPI {@code /v3/api-docs}）的可用性验证。
 *
 * <p>验收点：文档 UI 与规范 JSON <strong>免登录可访问</strong>（SQLBot 的 {@code /docs} 同样公开），
 * 且规范里带上了自定义元数据与 {@code X-SQLBOT-TOKEN} 安全方案；受保护接口路径不受白名单扩大影响。</p>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:askdata-openapi-test;MODE=MySQL;DB_CLOSE_ON_EXIT=FALSE",
        "spring.sql.init.mode=always"
})
@AutoConfigureMockMvc
class OpenApiDocsEndpointTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AuthWhitelist whitelist;

    @Test
    @DisplayName("/v3/api-docs 免鉴权返回 OpenAPI 3 规范，含标题与 token 安全方案")
    void apiDocsSpecIsPublic() throws Exception {
        MvcResult result = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn();
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(body.contains("\"openapi\""), body.substring(0, Math.min(200, body.length())));
        assertTrue(body.contains("askdata API"), "title missing: " + body.substring(0, 200));
        assertTrue(body.contains("X-SQLBOT-TOKEN"), "security scheme missing");
        assertTrue(body.contains("/api/v1/login/access-token"), "controllers not scanned");
    }

    @Test
    @DisplayName("/doc.html 免鉴权可打开 Knife4j 页面")
    void knife4jUiIsPublic() throws Exception {
        MvcResult result = mockMvc.perform(get("/doc.html"))
                .andExpect(status().isOk())
                .andReturn();
        String contentType = result.getResponse().getContentType();
        assertTrue(contentType != null && contentType.contains("text/html"), "unexpected content type: " + contentType);
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(body.contains("webjars/js/app"), "not the Knife4j page: " + body);
    }

    @Test
    @DisplayName("Knife4j 的 webjars 静态资源免鉴权可取（*.js 命中扩展名规则）")
    void knife4jStaticAssetsArePublic() throws Exception {
        mockMvc.perform(get("/webjars/js/app.c31badf5.js"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/webjars/css/app.1824bac3.css"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("/v3/api-docs/swagger-config 免鉴权可用（Knife4j 启动时先取分组配置）")
    void swaggerConfigIsPublic() throws Exception {
        mockMvc.perform(get("/v3/api-docs/swagger-config"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("SQLBot 的 /openapi.json 转发到 springdoc 规范端点")
    void sqlbotOpenapiJsonPathForwardsToSpec() throws Exception {
        mockMvc.perform(get("/openapi.json"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/v3/api-docs"));
    }

    @Test
    @DisplayName("SQLBot 的 /docs 落到 Knife4j 页面")
    void sqlbotDocsPathRedirectsToKnife4j() throws Exception {
        mockMvc.perform(get("/docs"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/doc.html"));
    }

    @Test
    @DisplayName("白名单扩大只覆盖文档站点，受保护接口仍需鉴权")
    void docsPathsWhitelistedButApiStillProtected() {
        assertTrue(whitelist.isWhitelistedPath("/doc.html"));
        assertTrue(whitelist.isWhitelistedPath("/docs"));
        assertTrue(whitelist.isWhitelistedPath("/openapi.json"));
        assertTrue(whitelist.isWhitelistedPath("/v3/api-docs"));
        assertTrue(whitelist.isWhitelistedPath("/v3/api-docs/swagger-config"));
        assertTrue(whitelist.isWhitelistedPath("/webjars/js/app.js"));
        assertFalse(whitelist.isWhitelistedPath("/chat/question"));
        assertFalse(whitelist.isWhitelistedPath("/datasource/list"));
    }
}
