package com.xiongx9527.askdata.common.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 公共路径白名单回归（对齐 SQLBot {@code backend/tests/test_embedded_auth_bypass_fix.py} 的
 * {@code TestWhitelistMatching}）。
 *
 * <p>核心不变式：白名单按<strong>请求行真实路径</strong>判定。
 * SQLBot 侧对应 {@code request.scope.get("path")}：Starlette 的 {@code URL(scope)} 会把未校验的
 * Host 头原始值拼进 URL，攻击者可用 {@code Host: evil.com/api/v1/mcp} 让
 * {@code request.url.path} 变成 {@code /api/v1/mcp/api/v1/system/embedded}，从而绕过白名单。
 * askdata 侧用 {@code request.getRequestURI()}，天然不含 Host。</p>
 */
class AuthWhitelistTest {

    private final AuthWhitelist whitelist = new AuthWhitelist("/api/v1");

    @ParameterizedTest(name = "公共路径命中白名单: {0}")
    @ValueSource(strings = {
            "/api/v1/mcp/access_token",
            "/api/v1/mcp/mcp_start",
            "/api/v1/mcp/mcp_question",
            "/api/v1/mcp/mcp_assistant",
            "/mcp/access_token",
            "/api/v1/login/access-token",
            "/api/v1/system/config/key",
            "/api/v1/system/assistant/info/123"
    })
    @DisplayName("合法公共路径命中白名单")
    void legitPathsWhitelisted(String path) {
        assertTrue(whitelist.isWhitelistedPath(path), "should be whitelisted: " + path);
    }

    @ParameterizedTest(name = "受保护路径不命中白名单: {0}")
    @ValueSource(strings = {
            "/api/v1/system/embedded",
            "/api/v1/user/info",
            "/api/v1/user/defaultPwd",
            "/api/v1/system/user/list",
            "/api/v1/chat/list",
            "/api/v1/datasource/list"
    })
    @DisplayName("受保护路径不命中白名单")
    void protectedPathsNotWhitelisted(String path) {
        assertFalse(whitelist.isWhitelistedPath(path), "should NOT be whitelisted: " + path);
    }

    @Test
    @DisplayName("注入路径仍命中白名单正则 → 因此必须按 requestURI 原始路径判定（纵深防御）")
    void injectedPathStillMatchesRegex() {
        // '/api/v1/mcp/api/v1/system/embedded' 剥前缀后为 '/mcp/api/v1/system/embedded'
        // → 命中 ^/mcp.*$。这正是为什么实现必须使用真实请求路径而非 Host 拼接的 URL：
        // 同一请求的真实路径 '/api/v1/system/embedded' 并不命中。
        assertTrue(whitelist.isWhitelistedPath("/api/v1/mcp/api/v1/system/embedded"),
                "注入路径在正则层面仍命中（与 SQLBot 行为一致）");
        assertFalse(whitelist.isWhitelistedPath("/api/v1/system/embedded"),
                "同一请求的真实路径不应命中");
    }

    @Test
    @DisplayName("按 requestURI 判定：Host 头伪造不影响白名单结果")
    void whitelistUsesRawRequestUriNotHost() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/system/embedded");
        // 伪造的 Host：若实现用 Host 拼 URL，路径会变成 /api/v1/mcp/api/v1/system/embedded 而命中白名单。
        request.addHeader("Host", "evil.com/api/v1/mcp");
        assertFalse(whitelist.isWhitelisted(request),
                "必须按 requestURI 原始路径判定，不能受 Host 头影响");
        assertEquals("/api/v1/system/embedded", whitelist.rawPath(request));
    }

    @Test
    @DisplayName("真实路径命中白名单时，伪造 Host 也不影响放行")
    void whitelistedRawPathUnaffectedByHost() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/mcp/access_token");
        request.addHeader("Host", "evil.com/api/v1/mcp");
        assertTrue(whitelist.isWhitelisted(request));
    }

    @ParameterizedTest(name = "白名单条目命中: {0}")
    @ValueSource(strings = {
            "/",
            "/docs",
            "/login/access-token",
            "/login/change-pwd",
            "/system/license",
            "/system/config/key",
            "/images/logo.png",
            "/sse",
            "/system/appearance/ui",
            "/system/appearance/picture/abc.png",
            "/system/assistant/info/1",
            "/system/assistant/app/abc",
            "/system/assistant/picture/abc.png",
            "/system/assistant/validate/1",
            "/system/authentication/platform/status",
            "/system/authentication/login/1",
            "/system/authentication/sso/1",
            "/system/platform/sso/6",
            "/system/platform/client/6",
            "/system/parameter/login"
    })
    @DisplayName("SQLBot 35 条白名单的关键条目均命中")
    void sqlBotWhitelistEntriesMatch(String path) {
        assertTrue(whitelist.isWhitelistedPath(path), "should be whitelisted: " + path);
    }

    @ParameterizedTest(name = "静态资源后缀命中: {0}")
    @ValueSource(strings = {
            "/favicon.ico",
            "/index.html",
            "/assets/app.js",
            "/assets/app.css",
            "/assets/app.css.map",
            "/img/a.png",
            "/img/a.jpg",
            "/img/a.jpeg",
            "/img/a.gif",
            "/img/a.svg",
            "/fonts/a.woff",
            "/fonts/a.woff2",
            "/fonts/a.ttf",
            "/fonts/a.eot",
            "/fonts/a.otf"
    })
    @DisplayName("静态资源后缀命中白名单")
    void staticSuffixesMatch(String path) {
        assertTrue(whitelist.isWhitelistedPath(path), "should be whitelisted: " + path);
    }

    @Test
    @DisplayName("白名单条目数与 SQLBot wlist 一致（35 条）")
    void whitelistEntryCountMatchesSqlBot() {
        // SQLBot common/utils/whitelist.py 的 wlist 实测 35 条（brief 文本里的“33 条”是概数，
        // 逐条比对 SQLBot 源码后以源码为准）。
        assertEquals(35, AuthWhitelist.DEFAULT_PATHS.size(),
                "白名单条目数应与 SQLBot wlist 一致；当前: " + AuthWhitelist.DEFAULT_PATHS);
    }

    @Test
    @DisplayName("context path 前缀会被剥离")
    void contextPathStripped() {
        // SQLBot 的 API_V1_STR = CONTEXT_PATH + "/api/v1"，因此 context path 一定在 /api/v1 之后。
        AuthWhitelist withContext = AuthWhitelist.withContextPath("/sqlbot/api/v1", "/sqlbot");
        assertTrue(withContext.isWhitelistedPath("/sqlbot/api/v1/system/config/key"));
        assertFalse(withContext.isWhitelistedPath("/sqlbot/api/v1/user/info"));
    }

    /** F-13：多重尾斜杠归一化——通配条目的尾斜杠变体命中。 */
    @Test
    void multipleTrailingSlashesMatchWildcardEntries() {
        assertTrue(whitelist.isWhitelistedPath("/mcp/"));
        assertTrue(whitelist.isWhitelistedPath("/mcp//"));
        assertTrue(whitelist.isWhitelistedPath("/login/access-token/"));
        // 精确条目仍与 SQLBot 一致：不被尾斜杠放行（不改变既有语义）
        assertFalse(whitelist.isWhitelistedPath("/system/license/"));
    }
}
