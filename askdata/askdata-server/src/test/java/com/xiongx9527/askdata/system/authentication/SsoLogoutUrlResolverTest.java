package com.xiongx9527.askdata.system.authentication;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import javax.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 登出联动地址：IdP 登出后要能把浏览器送回本系统，而不是停在 IdP 自己的登出页。
 *
 * <p>回归点：部分 IdP 的 {@code /sign/force/logout} 会整页跳转，忽略
 * {@code post_logout_redirect_uri}，只认 {@code redirect_uri}（拼到登出页地址上，
 * 前端登出组件再 {@code location.href} 跳回）；不带上回跳地址，用户就停在
 * {@code /#/passport/logout}。</p>
 */
class SsoLogoutUrlResolverTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private SsoLogoutUrlResolver resolver(Map<String, Object> config) throws Exception {
        AuthenticationRepository repository = mock(AuthenticationRepository.class);
        Map<String, Object> stored = new LinkedHashMap<>();
        stored.put("type", SsoAuthenticator.TYPE_OIDC);
        stored.put("config", objectMapper.writeValueAsString(config));
        when(repository.findByType(SsoAuthenticator.TYPE_OIDC)).thenReturn(Optional.of(stored));
        return new SsoLogoutUrlResolver(repository, mock(Saml2SsoClient.class), objectMapper);
    }

    private HttpServletRequest requestFrom(String origin) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("Origin")).thenReturn(origin);
        when(request.getContextPath()).thenReturn("");
        return request;
    }

    @Test
    void appendsReturnToUrlForIdPForceLogout() throws Exception {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("end_session_endpoint", "http://idp.example.com/sign/force/logout");
        SsoLogoutUrlResolver resolver = resolver(config);

        String url = resolver.resolve(SsoAuthenticator.TYPE_OIDC, requestFrom("https://25946kt718.goho.co"));

        assertTrue(url.startsWith("http://idp.example.com/sign/force/logout?"), url);
        assertTrue(url.contains("redirect_uri=https%3A%2F%2F25946kt718.goho.co%2F"), url);
        assertTrue(url.contains("post_logout_redirect_uri=https%3A%2F%2F25946kt718.goho.co%2F"), url);
    }

    @Test
    void keepsExistingQueryStringWhenAppending() throws Exception {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("logoutUrl", "https://idp.example.com/logout?client_id=abc");
        SsoLogoutUrlResolver resolver = resolver(config);

        String url = resolver.resolve(SsoAuthenticator.TYPE_OIDC, requestFrom("http://localhost:8000"));

        assertTrue(url.startsWith("https://idp.example.com/logout?client_id=abc&"), url);
        assertTrue(url.contains("redirect_uri=http%3A%2F%2Flocalhost%3A8000%2F"), url);
    }

    @Test
    void fallsBackToConfiguredRedirectWhenNoOriginHeader() throws Exception {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("end_session_endpoint", "http://idp.example.com/sign/force/logout");
        config.put("redirect_uri", "https://25946kt718.goho.co/");
        SsoLogoutUrlResolver resolver = resolver(config);

        String url = resolver.resolve(SsoAuthenticator.TYPE_OIDC, mock(HttpServletRequest.class));

        assertTrue(url.contains("redirect_uri=https%3A%2F%2F25946kt718.goho.co%2F"), url);
    }

    @Test
    void localLoginHasNoIdpLogout() {
        SsoLogoutUrlResolver resolver = new SsoLogoutUrlResolver(mock(AuthenticationRepository.class),
                mock(Saml2SsoClient.class), objectMapper);
        assertNull(resolver.resolve(0));
        assertEquals(null, resolver.resolve(0, mock(HttpServletRequest.class)));
    }
}
