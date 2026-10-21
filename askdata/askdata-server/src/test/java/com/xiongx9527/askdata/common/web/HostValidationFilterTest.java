package com.xiongx9527.askdata.common.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Host 头校验回归（对齐 SQLBot {@code backend/tests/test_embedded_auth_bypass_fix.py} 的
 * {@code TestHostValidation}）。
 *
 * <p>该测试钉住 CNVD 嵌入鉴权绕过的第一道防线：Host 头携带路径片段会污染由 Host 拼出的
 * URL 路径，从而把受保护接口伪装成白名单前缀。askdata 侧在入口 Filter 直接拒绝。</p>
 */
class HostValidationFilterTest {

    @ParameterizedTest(name = "合法 Host 被接受: {0}")
    @ValueSource(strings = {
            "localhost",
            "localhost:8000",
            "127.0.0.1",
            "127.0.0.1:8000",
            "example.com",
            "api.example.com:443",
            "[::1]",
            "[::1]:8000",
            "10.0.0.1",
            "a.b.c.d.e.f.g"
    })
    @DisplayName("合法 Host（域名/IPv4/IPv6/带端口）被接受")
    void validHostAccepted(String host) {
        assertTrue(HostValidationFilter.HOST_PATTERN.matcher(host).matches(),
                "should accept host: " + host);
    }

    @ParameterizedTest(name = "非法 Host 被拒绝: {0}")
    @ValueSource(strings = {
            "",
            "evil.com/api/v1/mcp",
            "/api/v1/mcp",
            "x/api/v1/mcp",
            "a@b",
            "evil.com/path?x=1",
            "evil.com#frag",
            "evil com",
            "evil.com\nX-Real-IP: 1.2.3.4"
    })
    @DisplayName("非法 Host（路径注入/userinfo/查询/片段/空白/换行）被拒绝")
    void invalidHostRejected(String host) {
        assertFalse(HostValidationFilter.HOST_PATTERN.matcher(host).matches(),
                "should reject host: " + host);
    }

    @Test
    @DisplayName("伪造 Host: evil.com/api/v1/mcp 的请求被 400 拒绝，响应体为 invalid host header")
    void forgedHostRejectedWith400() throws Exception {
        HostValidationFilter filter = new HostValidationFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/system/user/list");
        request.addHeader("Host", "evil.com/api/v1/mcp");
        org.springframework.mock.web.MockHttpServletResponse response =
                new org.springframework.mock.web.MockHttpServletResponse();
        boolean[] chained = {false};

        filter.doFilter(request, response, (req, res) -> chained[0] = true);

        assertFalse(chained[0], "业务逻辑不应被执行");
        assertTrue(response.getStatus() == 400, "status should be 400, was " + response.getStatus());
        assertTrue(response.getContentAsString().contains("invalid host header"),
                "body: " + response.getContentAsString());
        assertTrue(response.getContentAsString().contains("\"code\":400"),
                "body: " + response.getContentAsString());
    }

    @Test
    @DisplayName("合法 Host 的请求正常通过 Filter")
    void validHostPassesThrough() throws Exception {
        HostValidationFilter filter = new HostValidationFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/system/user/list");
        request.addHeader("Host", "localhost:8000");
        org.springframework.mock.web.MockHttpServletResponse response =
                new org.springframework.mock.web.MockHttpServletResponse();
        boolean[] chained = {false};

        filter.doFilter(request, response, (req, res) -> chained[0] = true);

        assertTrue(chained[0], "合法 Host 应放行到业务逻辑");
    }
}
