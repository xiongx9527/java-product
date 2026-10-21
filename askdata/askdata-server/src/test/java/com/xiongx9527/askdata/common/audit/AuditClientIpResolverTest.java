package com.xiongx9527.askdata.common.audit;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 客户端 IP 解析单测（对齐 SQLBot {@code SystemLogger.get_client_info} 的六级链 + RFC 7239）。
 */
class AuditClientIpResolverTest {

    private static MockHttpServletRequest request(String... headerPairs) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        for (int i = 0; i + 1 < headerPairs.length; i += 2) {
            request.addHeader(headerPairs[i], headerPairs[i + 1]);
        }
        request.setRemoteAddr("10.0.0.1");
        return request;
    }

    @Test
    void xForwardedForTakesFirstEntry() {
        assertEquals("203.0.113.43", AuditClientIpResolver.resolve(
                request("X-Forwarded-For", "203.0.113.43, 70.41.3.18, 150.172.238.178")));
    }

    @Test
    void xRealIpIsUsedWhenForwardedForAbsent() {
        assertEquals("203.0.113.43", AuditClientIpResolver.resolve(
                request("X-Real-IP", "203.0.113.43")));
    }

    @Test
    void cfConnectingIpIsThirdInChain() {
        assertEquals("203.0.113.43", AuditClientIpResolver.resolve(
                request("CF-Connecting-IP", "203.0.113.43")));
    }

    @Test
    void xClientIpIsFourthInChain() {
        assertEquals("203.0.113.43", AuditClientIpResolver.resolve(
                request("X-Client-IP", "203.0.113.43")));
    }

    @Test
    void xForwardedIsFifthInChain() {
        assertEquals("203.0.113.43", AuditClientIpResolver.resolve(
                request("X-Forwarded", "203.0.113.43")));
    }

    @Test
    void rfc7239ForwardedIsSixthInChain() {
        assertEquals("203.0.113.43", AuditClientIpResolver.resolve(
                request("Forwarded", "for=203.0.113.43;proto=https;by=203.0.113.1")));
        assertEquals("203.0.113.43", AuditClientIpResolver.resolve(
                request("Forwarded", "for=\"203.0.113.43\"")));
    }

    @Test
    void earlierHeadersWinOverForwarded() {
        assertEquals("1.1.1.1", AuditClientIpResolver.resolve(
                request("X-Forwarded-For", "1.1.1.1", "Forwarded", "for=2.2.2.2")));
    }

    @Test
    void socketPeerIsLastResort() {
        assertEquals("10.0.0.1", AuditClientIpResolver.resolve(request()));
    }

    @Test
    void nullRequestYieldsNull() {
        assertNull(AuditClientIpResolver.resolve(null));
    }

    @Test
    void forwardedParsingHandlesMissingForParameter() {
        assertNull(AuditClientIpResolver.parseForwarded("proto=https;by=203.0.113.1"));
    }
}
