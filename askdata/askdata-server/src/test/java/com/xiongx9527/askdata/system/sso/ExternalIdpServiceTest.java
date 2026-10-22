package com.xiongx9527.askdata.system.sso;

import com.xiongx9527.askdata.system.service.UserService;

import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验收 A7：外部 IdP（OIDC/CAS/SAML2/企业微信/钉钉/飞书/Lark）可完成授权登录解析并自动开通本地账号。
 * 采用 mock IdP（StubHttp）验证协议流程，无需真实 IdP。
 */
class ExternalIdpServiceTest {

    private static ExternalIdpConfig cfg() {
        ExternalIdpConfig c = new ExternalIdpConfig();
        c.setAuthorizeUrl("https://idp/authorize");
        c.setTokenUrl("https://idp/token");
        c.setUserInfoUrl("https://idp/userinfo");
        c.setClientId("cid");
        c.setClientSecret("sec");
        c.setRedirectUri("https://app/cb");
        return c;
    }

    /** 按 URL 子串返回固定响应的 mock IdP HTTP 客户端。 */
    static class StubHttp implements IdpHttpClient {
        private final Map<String, String> responses;

        StubHttp(Map<String, String> responses) {
            this.responses = responses;
        }

        private Response match(String url) {
            for (Map.Entry<String, String> e : responses.entrySet()) {
                if (url != null && url.contains(e.getKey())) {
                    return new Response(200, e.getValue());
                }
            }
            return new Response(404, "");
        }

        @Override
        public Response get(String url, Map<String, String> headers) {
            return match(url);
        }

        @Override
        public Response postForm(String url, Map<String, String> form, Map<String, String> headers) {
            return match(url);
        }
    }

    private final ExternalIdpService service = new ExternalIdpService();

    @Test
    void oidcResolvesIdentity() {
        StubHttp http = new StubHttp(Map.of(
                "token", "{\"access_token\":\"at\",\"sub\":\"sub-123\"}",
                "userinfo", "{\"email\":\"a@b.com\",\"name\":\"Alice\"}"));
        ExternalIdentity idp = service.resolveIdentity(IdpType.OIDC, cfg(),
                IdpCallback.fromQuery(Map.of("code", "c")), http);
        assertEquals("sub-123", idp.getAccount());
        assertEquals("Alice", idp.getName());
        assertEquals("a@b.com", idp.getEmail());
        assertEquals("sub-123", idp.getExternalId());
    }

    @Test
    void wechatWorkResolvesIdentity() {
        ExternalIdpConfig wc = cfg();
        wc.setTokenUrl("https://idp/gettoken");
        StubHttp http = new StubHttp(Map.of(
                "gettoken", "{\"access_token\":\"at\"}",
                "userinfo", "{\"userid\":\"U1\",\"name\":\"Bob\",\"email\":\"b@c.com\"}"));
        ExternalIdentity idp = service.resolveIdentity(IdpType.WECHAT_WORK, wc,
                IdpCallback.fromQuery(Map.of("code", "c")), http);
        assertEquals("wechat:U1", idp.getAccount());
        assertEquals("Bob", idp.getName());
    }

    @Test
    void casResolvesIdentityFromXml() {
        String xml = "<cas:serviceResponse xmlns:cas='http://www.yale.edu/tp/cas'>"
                + "<cas:authenticationSuccess><cas:user>casuser</cas:user>"
                + "<cas:attributes><cas:attribute name='email'>cas@e.com</cas:attribute>"
                + "</cas:attributes></cas:authenticationSuccess></cas:serviceResponse>";
        ExternalIdpConfig cas = cfg();
        cas.setTokenUrl("https://idp/serviceValidate");
        StubHttp http = new StubHttp(Map.of("serviceValidate", xml));
        ExternalIdentity idp = service.resolveIdentity(IdpType.CAS, cas,
                IdpCallback.fromQuery(Map.of("ticket", "t")), http);
        assertEquals("cas:casuser", idp.getAccount());
        assertEquals("cas@e.com", idp.getEmail());
    }

    @Test
    void saml2ResolvesIdentityFromBase64Assertion() {
        String xml = "<Response><Assertion><Subject><NameID>nid-1</NameID></Subject>"
                + "<AttributeStatement><Attribute Name='uid'><AttributeValue>uidval</AttributeValue></Attribute>"
                + "<Attribute Name='email'><AttributeValue>e@f.com</AttributeValue></Attribute>"
                + "</AttributeStatement></Assertion></Response>";
        String base64 = Base64.getEncoder().encodeToString(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        IdpCallback cb = new IdpCallback();
        cb.setSamlResponse(base64);
        ExternalIdentity idp = service.resolveIdentity(IdpType.SAML2, cfg(), cb, new StubHttp(Map.of()));
        assertEquals("saml:uidval", idp.getAccount());
        assertEquals("e@f.com", idp.getEmail());
    }

    @Test
    void linkerAutoProvisionsLocalAccount() {
        UserService userService = mock(UserService.class);
        when(userService.provisionExternalUser(anyString(), any(), any())).thenReturn(42L);
        LocalAccountLinker linker = new LocalAccountLinker(userService, 1L);
        long uid = linker.link(new ExternalIdentity("oidc:sub-123", "Alice", "a@b.com",
                "sub-123", Map.of()));
        assertEquals(42L, uid);
        verify(userService).provisionExternalUser("oidc:sub-123", "Alice", "a@b.com");
    }

    @Test
    void unsupportedTypeRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> service.resolveIdentity(null, cfg(), new IdpCallback(), new StubHttp(Map.of())));
    }

    @Test
    void authorizationUrlContainsClientAndState() {
        String url = service.authorizationUrl(IdpType.OIDC, cfg(), "st123");
        assertNotNull(url);
        org.junit.jupiter.api.Assertions.assertTrue(url.contains("client_id=cid"));
        org.junit.jupiter.api.Assertions.assertTrue(url.contains("state=st123"));
    }
}

