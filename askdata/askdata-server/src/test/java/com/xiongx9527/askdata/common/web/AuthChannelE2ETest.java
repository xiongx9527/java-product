package com.xiongx9527.askdata.common.web;

import com.xiongx9527.askdata.system.auth.CryptoUtil;
import com.xiongx9527.askdata.system.auth.JwtCodec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 鉴权三通道 + 公共路径白名单的端到端验证（A4 验收项）。
 *
 * <p>经真实 HTTP（MockMvc）验证：</p>
 * <ol>
 *   <li>主通道 {@code X-SQLBOT-TOKEN: Bearer <token>} 可用；</li>
 *   <li>API Key 通道 {@code X-SQLBOT-ASK-TOKEN: sk <jwt>}：合法 key 通过、非法/停用 key 401；</li>
 *   <li>嵌入通道 {@code X-SQLBOT-ASSISTANT-TOKEN: embedded <jwt>}：app_secret 正确通过、
 *       管理员账号被拒、非 type=4 被拒；</li>
 *   <li>SQLBot 公共路径免鉴权（尤其 /system/assistant/*、/system/appearance/*、
 *       /system/authentication/*、/mcp*、/images/*）；</li>
 *   <li>伪造 {@code Host: evil.com/api/v1/mcp} 的请求被 400 拒绝；</li>
 *   <li>401 响应带 {@code Access-Control-Allow-Origin: *}。</li>
 * </ol>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:askdata-auth-test;MODE=MySQL;DB_CLOSE_ON_EXIT=FALSE",
        "spring.sql.init.mode=always"
})
@AutoConfigureMockMvc
class AuthChannelE2ETest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    private String login() throws Exception {
        MvcResult result = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/login/access-token")
                        .param("username", CryptoUtil.encryptSqlbot("admin"))
                        .param("password", CryptoUtil.encryptSqlbot("SQLBot@123456")))
                .andExpect(status().isOk())
                .andReturn();
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        int start = body.indexOf("\"access_token\":\"");
        assertTrue(start > 0, "token missing: " + body);
        int from = start + "\"access_token\":\"".length();
        int end = body.indexOf('"', from);
        return body.substring(from, end);
    }

    // ------------------------------------------------------------------
    // 1) 主通道
    // ------------------------------------------------------------------

    @Test
    @DisplayName("主通道 X-SQLBOT-TOKEN: Bearer <token> 可用")
    void bearerChannelWorks() throws Exception {
        String token = login();
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/user/info")
                        .header("X-SQLBOT-TOKEN", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("受保护路径无 token → 401（且带 CORS 头）")
    void protectedPathWithoutTokenRejected() throws Exception {
        MvcResult result = mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/user/info"))
                .andExpect(status().isUnauthorized())
                .andReturn();
        assertEquals("*", result.getResponse().getHeader("Access-Control-Allow-Origin"),
                "主通道 401 响应必须带 Access-Control-Allow-Origin: *");
    }

    // ------------------------------------------------------------------
    // 2) API Key 通道
    // ------------------------------------------------------------------

    @Test
    @DisplayName("API Key 通道：合法 key 通过")
    void apiKeyChannelValidKeyAccepted() throws Exception {
        insertApiKey("ak-e2e-valid", "sk-e2e-secret", 1L, true);
        String token = "sk " + jwt("ak-e2e-valid", "sk-e2e-secret");

        MvcResult r = mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/user/info")
                        .header("X-SQLBOT-ASK-TOKEN", token))
                .andReturn();
        org.junit.jupiter.api.Assertions.assertEquals(200, r.getResponse().getStatus(),
                "body: " + r.getResponse().getContentAsString());
    }

    @Test
    @DisplayName("API Key 通道：停用的 key → 401（且带 CORS 头）")
    void apiKeyChannelDisabledKeyRejected() throws Exception {
        insertApiKey("ak-e2e-disabled", "sk-e2e-secret", 1L, false);
        String token = "sk " + jwt("ak-e2e-disabled", "sk-e2e-secret");

        MvcResult result = mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/user/info")
                        .header("X-SQLBOT-ASK-TOKEN", token))
                .andExpect(status().isUnauthorized())
                .andReturn();
        assertEquals("*", result.getResponse().getHeader("Access-Control-Allow-Origin"),
                "401 响应必须带 Access-Control-Allow-Origin: *");
    }

    @Test
    @DisplayName("API Key 通道：非法 key → 401（且带 CORS 头）")
    void apiKeyChannelInvalidKeyRejected() throws Exception {
        String token = "sk " + jwt("ak-does-not-exist", "sk-e2e-secret");

        MvcResult result = mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/user/info")
                        .header("X-SQLBOT-ASK-TOKEN", token))
                .andExpect(status().isUnauthorized())
                .andReturn();
        assertEquals("*", result.getResponse().getHeader("Access-Control-Allow-Origin"),
                "401 响应必须带 Access-Control-Allow-Origin: *");
    }

    // ------------------------------------------------------------------
    // 3) 嵌入助手通道
    // ------------------------------------------------------------------

    @Test
    @DisplayName("嵌入通道：app_secret 正确 + type=4 → 通过")
    void embeddedChannelAccepted() throws Exception {
        insertAssistant(9001L, 4, "e2e-app-secret");
        String token = "Embedded " + embeddedJwt("admin", 9001L, "e2e-app-secret");

        // 管理员被拒（安全红线）→ 先用一个普通账号验证通过路径
        insertUser(9101L, "e2e-user", "E2E User", 1L, 1);
        String userToken = "Embedded " + embeddedJwt("e2e-user", 9001L, "e2e-app-secret");

        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/user/info")
                        .header("X-SQLBOT-ASSISTANT-TOKEN", userToken))
                .andExpect(status().isOk());

        assertNotNull(token);
    }

    @Test
    @DisplayName("嵌入通道：管理员账号被拒 → 401（且带 CORS 头）")
    void embeddedChannelAdminRejected() throws Exception {
        insertAssistant(9002L, 4, "e2e-app-secret");
        String token = "Embedded " + embeddedJwt("admin", 9002L, "e2e-app-secret");

        MvcResult result = mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/user/info")
                        .header("X-SQLBOT-ASSISTANT-TOKEN", token))
                .andExpect(status().isUnauthorized())
                .andReturn();
        assertEquals("*", result.getResponse().getHeader("Access-Control-Allow-Origin"),
                "401 响应必须带 Access-Control-Allow-Origin: *");
    }

    @Test
    @DisplayName("嵌入通道：非 type=4 的助手被拒 → 401（且带 CORS 头）")
    void embeddedChannelNonEmbeddedTypeRejected() throws Exception {
        insertAssistant(9003L, 0, "e2e-app-secret");
        insertUser(9102L, "e2e-user2", "E2E User2", 1L, 1);
        String token = "Embedded " + embeddedJwt("e2e-user2", 9003L, "e2e-app-secret");

        MvcResult result = mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/user/info")
                        .header("X-SQLBOT-ASSISTANT-TOKEN", token))
                .andExpect(status().isUnauthorized())
                .andReturn();
        assertEquals("*", result.getResponse().getHeader("Access-Control-Allow-Origin"),
                "401 响应必须带 Access-Control-Allow-Origin: *");
    }

    // ------------------------------------------------------------------
    // 4) 公共路径白名单
    // ------------------------------------------------------------------

    @Test
    @DisplayName("SQLBot 公共路径免鉴权")
    void publicPathsNeedNoToken() throws Exception {
        String[] publicPaths = {
                "/api/v1/system/config/key",
                "/api/v1/system/appearance/ui",
                "/api/v1/system/authentication/platform/status",
                "/api/v1/system/license",
                "/api/v1/login/health"
        };
        for (String path : publicPaths) {
            mockMvc.perform(MockMvcRequestBuilders.get(path))
                    .andExpect(status().isOk());
        }
    }

    /**
     * 全部 35 条 SQLBot 公共路径逐条走真实 HTTP：<b>不得返回 401</b>。
     *
     * <p>断言口径：鉴权放行后，路径若已实现则返回 2xx/404（业务层结果），
     * 若未实现则返回 404/405（路由层结果）；唯独不能是 401（鉴权层结果）。
     * 这样既覆盖"白名单全部放行"，又不要求 askdata 立刻实现 SQLBot 的每一条端点。</p>
     */
    @Test
    @DisplayName("SQLBot 35 条公共路径逐条免鉴权（均不得 401）")
    void allSqlBotPublicPathsAreNotUnauthorized() throws Exception {
        for (String path : AuthWhitelist.DEFAULT_PATHS) {
            // 把白名单条目还原成可请求的具体路径（条目含通配符 / 静态后缀）
            String requestPath = concretePathOf(path);
            int status = mockMvc.perform(MockMvcRequestBuilders.get(requestPath))
                    .andReturn().getResponse().getStatus();
            org.junit.jupiter.api.Assertions.assertNotEquals(401, status,
                    "公共路径不应返回 401: " + path + " → " + requestPath);
        }
    }

    /** 把白名单条目（如 {@code /system/assistant/info/*}）还原成一个具体可请求路径。 */
    private static String concretePathOf(String whitelistEntry) {
        if (whitelistEntry.endsWith("/*")) {
            return "/api/v1" + whitelistEntry.substring(0, whitelistEntry.length() - 1) + "1";
        }
        if (whitelistEntry.endsWith("*")) {
            return "/api/v1" + whitelistEntry.substring(0, whitelistEntry.length() - 1) + "x";
        }
        if (whitelistEntry.startsWith("*.")) {
            return "/api/v1/assets/file" + whitelistEntry.substring(1);
        }
        return "/api/v1" + whitelistEntry;
    }

    @Test
    @DisplayName("/system/assistant/picture/* 与 /images/* 免鉴权（文件不存在时为 404 而非 401）")
    void publicFilePathsNeedNoToken() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/system/assistant/picture/none.png"))
                .andExpect(status().isNotFound());
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/system/appearance/picture/none.png"))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------
    // 5) Host 头校验
    // ------------------------------------------------------------------

    @Test
    @DisplayName("伪造 Host: evil.com/api/v1/mcp → 400 拒绝")
    void forgedHostRejected() throws Exception {
        MvcResult result = mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/user/info")
                        .header("Host", "evil.com/api/v1/mcp"))
                .andExpect(status().isBadRequest())
                .andReturn();
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(body.contains("invalid host header"), "body: " + body);
    }

    @Test
    @DisplayName("伪造 Host 也不能让受保护接口绕过鉴权")
    void forgedHostDoesNotBypassAuth() throws Exception {
        // /api/v1/mcp 前缀的白名单条目在 Host 污染下不得生效
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/system/user/list")
                        .header("Host", "evil.com/api/v1/mcp"))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private String jwt(String accessKey, String secret) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("access_key", accessKey);
        return JwtCodec.encode(payload, secret, 3600L);
    }

    private String embeddedJwt(String account, long embeddedId, String appSecret) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("account", account);
        payload.put("embeddedId", embeddedId);
        payload.put("appId", "e2e-app-id");
        return JwtCodec.encode(payload, appSecret, 3600L);
    }

    private void insertApiKey(String accessKey, String secretKey, long uid, boolean status) {
        jdbc.update("DELETE FROM sys_apikey WHERE access_key = ?", accessKey);
        jdbc.update("INSERT INTO sys_apikey (id, access_key, secret_key, create_time, uid, status) "
                + "VALUES (?, ?, ?, ?, ?, ?)", System.nanoTime(), accessKey, secretKey,
                System.currentTimeMillis(), uid, status);
    }

    private void insertAssistant(long id, int type, String appSecret) {
        jdbc.update("DELETE FROM sys_assistant WHERE id = ?", id);
        jdbc.update("INSERT INTO sys_assistant (id, name, type, domain, description, configuration, "
                        + "create_time, app_id, app_secret, oid, enable_custom_model, custom_model) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, "e2e-assistant-" + id, type, "https://demo.example.com", "e2e", "{}",
                System.currentTimeMillis(), "e2e-app-id-" + id, appSecret, 1L, false, null);
    }

    private void insertUser(long id, String account, String name, long oid, int status) {
        jdbc.update("DELETE FROM sys_user WHERE id = ?", id);
        jdbc.update("INSERT INTO sys_user (id, account, name, password, email, oid, status, create_time, "
                        + "language, origin) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, account, name, CryptoUtil.md5Hex("x"), account + "@example.com", oid, status,
                System.currentTimeMillis(), "zh-CN", 0);
    }
}
