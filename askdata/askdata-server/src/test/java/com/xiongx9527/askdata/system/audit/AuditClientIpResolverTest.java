package com.xiongx9527.askdata.system.audit;

import com.xiongx9527.askdata.common.audit.AuditClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 客户端 IP 解析链（对齐 SQLBot get_client_info 六级优先级）。
 */
class AuditClientIpResolverTest {

    private final AuditClientIpResolver resolver = new AuditClientIpResolver();

    private HttpServletRequest req(String header, String value) {
        HttpServletRequest m = mock(HttpServletRequest.class);
        when(m.getHeader(header)).thenReturn(value);
        return m;
    }

    @Test
    void xForwardedForTakesFirstHop() {
        HttpServletRequest m = mock(HttpServletRequest.class);
        when(m.getHeader("X-Forwarded-For")).thenReturn("203.0.113.7, 70.41.3.18, 150.172.238.178");
        assertEquals("203.0.113.7", resolver.resolve(m));
    }

    @Test
    void xRealIpWinsWhenNoXff() {
        assertEquals("10.0.0.5", resolver.resolve(req("X-Real-IP", "10.0.0.5")));
    }

    @Test
    void cfConnectingIp() {
        assertEquals("1.2.3.4", resolver.resolve(req("CF-Connecting-IP", "1.2.3.4")));
    }

    @Test
    void rfc7239ForwardedFor() {
        HttpServletRequest m = mock(HttpServletRequest.class);
        when(m.getHeader("Forwarded")).thenReturn("for=192.0.2.60;proto=http;by=203.0.113.43");
        assertEquals("192.0.2.60", resolver.resolve(m));
    }

    @Test
    void rfc7239ForwardedIpv6() {
        HttpServletRequest m = mock(HttpServletRequest.class);
        when(m.getHeader("Forwarded")).thenReturn("for=[2001:db8::1]:47011;proto=https");
        assertEquals("2001:db8::1", resolver.resolve(m));
    }

    @Test
    void fallsBackToRemoteAddr() {
        HttpServletRequest m = mock(HttpServletRequest.class);
        when(m.getRemoteAddr()).thenReturn("127.0.0.1");
        assertEquals("127.0.0.1", resolver.resolve(m));
    }

    @Test
    void nullRequestReturnsNull() {
        assertEquals(null, resolver.resolve(null));
    }
}
