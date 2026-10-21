package com.xiongx9527.askdata.common.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Origin 推导与域名匹配回归（对齐 SQLBot {@code common/utils/utils.py:247-286} 的
 * {@code get_origin_from_referer} / {@code origin_match_domain} / {@code get_domain_list}）。
 */
class OriginSupportTest {

    private static MockHttpServletRequest requestWith(String headerName, String value) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/system/assistant/info/1");
        if (value != null) {
            request.addHeader(headerName, value);
        }
        return request;
    }

    @Test
    @DisplayName("Referer 推导 origin：默认端口不保留")
    void refererDerivesOrigin() {
        assertEquals("https://a.com",
                OriginSupport.getOriginFromReferer(requestWith("Referer", "https://a.com/x")));
        assertEquals("https://a.com",
                OriginSupport.getOriginFromReferer(requestWith("Referer", "https://a.com:443/x")));
        assertEquals("http://a.com",
                OriginSupport.getOriginFromReferer(requestWith("Referer", "http://a.com:80/x")));
        assertEquals("http://a.com:8080",
                OriginSupport.getOriginFromReferer(requestWith("Referer", "http://a.com:8080/x")));
    }

    @Test
    @DisplayName("Referer 缺失或解析不出 scheme/host → null（对齐 SQLBot 返回 None）")
    void unparseableRefererYieldsNull() {
        assertNull(OriginSupport.getOriginFromReferer(requestWith("Referer", null)));
        assertNull(OriginSupport.getOriginFromReferer(requestWith("Referer", "")));
        assertNull(OriginSupport.getOriginFromReferer(requestWith("Referer", "not a url")));
        assertNull(OriginSupport.getOriginFromReferer(requestWith("Referer", "/relative/path")));
    }

    @Test
    @DisplayName("请求 origin 优先级：Origin 头 > X-SQLBOT-HOST-ORIGIN > Referer")
    void requestOriginPriority() {
        MockHttpServletRequest request = requestWith("Referer", "https://from-referer.com/x");
        assertEquals("https://from-referer.com", OriginSupport.requestOrigin(request));

        request.addHeader("X-SQLBOT-HOST-ORIGIN", "https://from-host-origin.com");
        assertEquals("https://from-host-origin.com", OriginSupport.requestOrigin(request));

        request.addHeader("Origin", "https://from-origin.com");
        assertEquals("https://from-origin.com", OriginSupport.requestOrigin(request));
    }

    @Test
    @DisplayName("origin 与 domain 精确匹配（支持 , 与 ; 分隔，忽略尾部 /）")
    void originMatchDomain() {
        assertTrue(OriginSupport.originMatchDomain("https://a.com", "https://a.com"));
        assertTrue(OriginSupport.originMatchDomain("https://a.com/", "https://a.com"));
        assertTrue(OriginSupport.originMatchDomain("https://a.com", "https://b.com,https://a.com"));
        assertTrue(OriginSupport.originMatchDomain("https://a.com", "https://b.com;https://a.com"));

        assertFalse(OriginSupport.originMatchDomain("https://evil.com", "https://a.com"));
        assertFalse(OriginSupport.originMatchDomain("https://a.com.evil.com", "https://a.com"));
        assertFalse(OriginSupport.originMatchDomain("https://a.com", ""));
        assertFalse(OriginSupport.originMatchDomain(null, "https://a.com"));
    }

    @Test
    @DisplayName("domain 列表拆分（对齐 SQLBot get_domain_list）")
    void domainListSplitsOnCommaAndSemicolon() {
        assertEquals(java.util.Arrays.asList("https://a.com", "https://b.com"),
                OriginSupport.domainList("https://a.com,https://b.com"));
        assertEquals(java.util.Arrays.asList("https://a.com", "https://b.com"),
                OriginSupport.domainList("https://a.com ; https://b.com/"));
        assertTrue(OriginSupport.domainList(null).isEmpty());
        assertTrue(OriginSupport.domainList("").isEmpty());
    }
}
