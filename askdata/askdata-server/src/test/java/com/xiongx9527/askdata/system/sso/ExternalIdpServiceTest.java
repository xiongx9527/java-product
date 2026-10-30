package com.xiongx9527.askdata.system.sso;

import com.xiongx9527.askdata.common.util.Maps;

import com.xiongx9527.askdata.system.service.UserService;

import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验收 A7：外部 IdP（OIDC/CAS/SAML2/企业微信/钉钉/飞书/Lark）可完成授权登录解析并自动开通本地账号。
 * 采用 mock IdP（StubHttp）+ 真实 RS256/XMLDSig 签名验证协议流程，无需真实 IdP。
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
    void oidcVerifiesSignedIdTokenAndResolvesIdentity() throws Exception {
        KeyPair pair = IdpTestCrypto.rsaKeyPair();
        Map<String, Object> claims = new LinkedHashMap<String, Object>();
        claims.put("iss", "https://idp");
        claims.put("aud", "cid");
        claims.put("sub", "sub-123");
        claims.put("email", "a@b.com");
        claims.put("name", "Alice");
        claims.put("exp", System.currentTimeMillis() / 1000L + 300L);
        claims.put("nonce", "st-1");
        String idToken = IdpTestCrypto.idToken(pair.getPrivate(), "k1", claims);

        ExternalIdpConfig oidc = cfg();
        oidc.setExtra(Maps.of("jwks-url", "https://idp/jwks", "issuer", "https://idp"));
        StubHttp http = new StubHttp(Maps.of(
                "jwks", IdpTestCrypto.jwks(pair, "k1"),
                "token", "{\"access_token\":\"at\",\"id_token\":\"" + idToken + "\"}",
                "userinfo", "{\"email\":\"a@b.com\",\"name\":\"Alice\"}"));

        IdpCallback callback = new IdpCallback();
        callback.setCode("c");
        callback.setState("st-1");
        ExternalIdentity idp = service.resolveIdentity(IdpType.OIDC, oidc, callback, http);
        assertEquals("sub-123", idp.getAccount());
        assertEquals("Alice", idp.getName());
        assertEquals("a@b.com", idp.getEmail());
        assertEquals("sub-123", idp.getExternalId());
    }

    @Test
    void oidcRejectsTokenWithWrongSignature() throws Exception {
        KeyPair signer = IdpTestCrypto.rsaKeyPair();
        KeyPair other = IdpTestCrypto.rsaKeyPair();
        Map<String, Object> claims = new LinkedHashMap<String, Object>();
        claims.put("aud", "cid");
        claims.put("sub", "sub-123");
        claims.put("exp", System.currentTimeMillis() / 1000L + 300L);
        String idToken = IdpTestCrypto.idToken(signer.getPrivate(), "k1", claims);

        ExternalIdpConfig oidc = cfg();
        oidc.setExtra(Maps.of("jwks-url", "https://idp/jwks"));
        StubHttp http = new StubHttp(Maps.of(
                "jwks", IdpTestCrypto.jwks(other, "k1"),
                "token", "{\"access_token\":\"at\",\"id_token\":\"" + idToken + "\"}"));
        IdpCallback callback = new IdpCallback();
        callback.setCode("c");
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.resolveIdentity(IdpType.OIDC, oidc, callback, http));
        assertTrue(error.getMessage().contains("signature"), error.getMessage());
    }

    @Test
    void oidcRejectsTokenWithoutVerificationMaterial() throws Exception {
        KeyPair pair = IdpTestCrypto.rsaKeyPair();
        Map<String, Object> claims = new LinkedHashMap<String, Object>();
        claims.put("aud", "cid");
        claims.put("sub", "sub-123");
        claims.put("exp", System.currentTimeMillis() / 1000L + 300L);
        String idToken = IdpTestCrypto.idToken(pair.getPrivate(), "k1", claims);
        StubHttp http = new StubHttp(Maps.of(
                "token", "{\"access_token\":\"at\",\"id_token\":\"" + idToken + "\"}"));
        IdpCallback callback = new IdpCallback();
        callback.setCode("c");
        // 未配置 jwks-url / id-token-cert：拒绝而不是静默接受
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.resolveIdentity(IdpType.OIDC, cfg(), callback, http));
        assertTrue(error.getMessage().contains("jwks-url"), error.getMessage());
    }

    @Test
    void wechatWorkResolvesIdentity() {
        ExternalIdpConfig wc = cfg();
        wc.setTokenUrl("https://idp/gettoken");
        StubHttp http = new StubHttp(Maps.of(
                "gettoken", "{\"access_token\":\"at\"}",
                "userinfo", "{\"userid\":\"U1\",\"name\":\"Bob\",\"email\":\"b@c.com\"}"));
        ExternalIdentity idp = service.resolveIdentity(IdpType.WECHAT_WORK, wc,
                IdpCallback.fromQuery(Maps.of("code", "c")), http);
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
        StubHttp http = new StubHttp(Maps.of("serviceValidate", xml));
        ExternalIdentity idp = service.resolveIdentity(IdpType.CAS, cas,
                IdpCallback.fromQuery(Maps.of("ticket", "t")), http);
        assertEquals("cas:casuser", idp.getAccount());
        assertEquals("cas@e.com", idp.getEmail());
    }

    @Test
    void saml2VerifiesSignatureThenResolvesIdentity() throws Exception {
        KeyPair pair = IdpTestCrypto.rsaKeyPair();
        String xml = IdpTestCrypto.signedSamlResponse(pair.getPrivate(), "uidval", "e@f.com");

        ExternalIdpConfig saml = cfg();
        saml.setExtra(Maps.of("saml-cert", IdpTestCrypto.publicKeyBase64(pair)));
        IdpCallback cb = new IdpCallback();
        cb.setSamlResponse(IdpTestCrypto.base64(xml));

        ExternalIdentity idp = service.resolveIdentity(IdpType.SAML2, saml, cb, new StubHttp(Maps.of()));
        assertEquals("saml:uidval", idp.getAccount());
        assertEquals("e@f.com", idp.getEmail());
    }

    @Test
    void saml2RejectsUnsignedAssertion() {
        ExternalIdpConfig saml = cfg();
        saml.setExtra(Maps.of("saml-cert", "AAAA"));
        IdpCallback cb = new IdpCallback();
        cb.setSamlResponse(IdpTestCrypto.base64(IdpTestCrypto.unsignedSamlResponse("uidval", "e@f.com")));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.resolveIdentity(IdpType.SAML2, saml, cb, new StubHttp(Maps.of())));
        assertTrue(error.getMessage().contains("not signed"), error.getMessage());
    }

    @Test
    void saml2RejectsAssertionWithoutConfiguredCertificate() {
        IdpCallback cb = new IdpCallback();
        cb.setSamlResponse(IdpTestCrypto.base64(IdpTestCrypto.unsignedSamlResponse("uidval", "e@f.com")));
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.resolveIdentity(IdpType.SAML2, cfg(), cb, new StubHttp(Maps.of())));
        assertTrue(error.getMessage().contains("certificate is not configured"), error.getMessage());
    }

    @Test
    void saml2RejectsTamperedAssertion() throws Exception {
        KeyPair pair = IdpTestCrypto.rsaKeyPair();
        String xml = IdpTestCrypto.signedSamlResponse(pair.getPrivate(), "uidval", "e@f.com");
        String tampered = xml.replace("uidval", "attacker");

        ExternalIdpConfig saml = cfg();
        saml.setExtra(Maps.of("saml-cert", IdpTestCrypto.publicKeyBase64(pair)));
        IdpCallback cb = new IdpCallback();
        cb.setSamlResponse(IdpTestCrypto.base64(tampered));
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.resolveIdentity(IdpType.SAML2, saml, cb, new StubHttp(Maps.of())));
        assertTrue(error.getMessage().contains("signature"), error.getMessage());
    }

    @Test
    void linkerAutoProvisionsLocalAccount() {
        UserService userService = mock(UserService.class);
        when(userService.provisionExternalUser(anyString(), any(), any())).thenReturn(42L);
        LocalAccountLinker linker = new LocalAccountLinker(userService, 1L);
        long uid = linker.link(new ExternalIdentity("oidc:sub-123", "Alice", "a@b.com",
                "sub-123", Maps.of()));
        assertEquals(42L, uid);
        verify(userService).provisionExternalUser("oidc:sub-123", "Alice", "a@b.com");
    }

    @Test
    void unsupportedTypeRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> service.resolveIdentity(null, cfg(), new IdpCallback(), new StubHttp(Maps.of())));
    }

    @Test
    void authorizationUrlContainsClientAndState() {
        String url = service.authorizationUrl(IdpType.OIDC, cfg(), "st123");
        assertNotNull(url);
        assertTrue(url.contains("client_id=cid"));
        assertTrue(url.contains("state=st123"));
        // nonce 与 state 绑定，供 id_token 校验使用
        assertTrue(url.contains("nonce=st123"), url);
    }

    @Test
    void samlAuthorizationUrlCarriesAuthnRequest() {
        String url = service.authorizationUrl(IdpType.SAML2, cfg(), "st123");
        assertTrue(url.contains("SAMLRequest="), url);
        assertTrue(url.contains("RelayState=st123"), url);
    }
}

