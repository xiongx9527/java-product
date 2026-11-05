package com.xiongx9527.askdata.system.sso;

import com.xiongx9527.askdata.common.util.Maps;
import com.xiongx9527.askdata.system.service.UserService;
import com.xiongx9527.fw.plus.auth.social.SocialLoginProviderRegistry;
import com.xiongx9527.fw.plus.auth.social.enterprise.DingTalkSocialLoginProvider;
import com.xiongx9527.fw.plus.auth.social.enterprise.FeishuSocialLoginProvider;
import com.xiongx9527.fw.plus.auth.social.enterprise.LarkSocialLoginProvider;
import com.xiongx9527.fw.plus.auth.social.enterprise.LdapSocialLoginProvider;
import com.xiongx9527.fw.plus.auth.social.enterprise.WechatWorkSocialLoginProvider;
import com.xiongx9527.fw.plus.auth.social.general.CasSocialLoginProvider;
import com.xiongx9527.fw.plus.auth.social.general.OidcSocialLoginProvider;
import com.xiongx9527.fw.plus.auth.social.general.Saml2SocialLoginProvider;
import com.xiongx9527.fw.plus.auth.social.personal.WechatScanSocialLoginProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
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
 * 验收 A7：外部 IdP（OIDC/CAS/SAML2/企业微信/钉钉/飞书/Lark）经框架 fw-plus-auth-social 完成授权登录解析并自动开通本地账号。
 * 框架 provider 走真实 HTTP（SocialHttpUtils），故 resolveIdentity 用例用内嵌 HttpServer 提供 token/jwks/断言等固定响应；
 * OIDC/SAML2 同时验证签名校验（RS256/JWKS、XML DSig）。
 */
class ExternalIdpServiceTest {

    private static SocialLoginProviderRegistry registry() {
        SocialLoginProviderRegistry r = new SocialLoginProviderRegistry();
        r.register(new OidcSocialLoginProvider());
        r.register(new CasSocialLoginProvider());
        r.register(new Saml2SocialLoginProvider());
        r.register(new WechatWorkSocialLoginProvider());
        r.register(new DingTalkSocialLoginProvider());
        r.register(new FeishuSocialLoginProvider());
        r.register(new LarkSocialLoginProvider());
        r.register(new LdapSocialLoginProvider());
        r.register(new WechatScanSocialLoginProvider());
        return r;
    }

    private final ExternalIdpService service = new ExternalIdpService(registry());

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

    private static HttpServer startServer(Map<String, String> routes) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        for (Map.Entry<String, String> e : routes.entrySet()) {
            final String body = e.getValue();
            server.createContext(e.getKey(), exchange -> {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.getResponseBody().close();
            });
        }
        server.start();
        return server;
    }

    private static String json(Object obj) {
        try {
            return new ObjectMapper().writeValueAsString(obj);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void authorizationUrlContainsClientAndState() {
        String url = service.authorizationUrl(IdpType.OIDC, cfg(), "st123");
        assertNotNull(url);
        assertTrue(url.contains("client_id=cid"), url);
        assertTrue(url.contains("state=st123"), url);
        assertTrue(url.contains("nonce=st123"), url);
    }

    @Test
    void samlAuthorizationUrlCarriesAuthnRequest() {
        String url = service.authorizationUrl(IdpType.SAML2, cfg(), "st123");
        assertTrue(url.contains("SAMLRequest="), url);
        assertTrue(url.contains("RelayState=st123"), url);
    }

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

        Map<String, String> routes = new LinkedHashMap<String, String>();
        routes.put("/token", json(Maps.of("access_token", "at", "id_token", idToken)));
        routes.put("/jwks", IdpTestCrypto.jwks(pair, "k1"));
        HttpServer server = startServer(routes);
        try {
            String base = "http://localhost:" + server.getAddress().getPort();
            ExternalIdpConfig oidc = cfg();
            oidc.setTokenUrl(base + "/token");
            oidc.setExtra(Maps.of("jwks-url", base + "/jwks", "issuer", "https://idp"));

            IdpCallback callback = new IdpCallback();
            callback.setCode("c");
            callback.setState("st-1");
            ExternalIdentity idp = service.resolveIdentity(IdpType.OIDC, oidc, callback, IdpHttpClient.defaultClient());
            assertEquals("oidc:sub-123", idp.getAccount());
            assertEquals("Alice", idp.getName());
            assertEquals("a@b.com", idp.getEmail());
            assertEquals("sub-123", idp.getExternalId());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void oidcRejectsTokenWithWrongSignature() throws Exception {
        KeyPair signer = IdpTestCrypto.rsaKeyPair();
        KeyPair other = IdpTestCrypto.rsaKeyPair();
        Map<String, Object> claims = new LinkedHashMap<String, Object>();
        claims.put("aud", "cid");
        claims.put("sub", "sub-123");
        claims.put("exp", System.currentTimeMillis() / 1000L + 300L);
        // id_token 用 other 私钥签名，而 jwks 只提供 signer 的公钥 -> 验签必失败
        String idToken = IdpTestCrypto.idToken(other.getPrivate(), "k1", claims);

        Map<String, String> routes = new LinkedHashMap<String, String>();
        routes.put("/token", json(Maps.of("access_token", "at", "id_token", idToken)));
        routes.put("/jwks", IdpTestCrypto.jwks(signer, "k1"));
        HttpServer server = startServer(routes);
        try {
            String base = "http://localhost:" + server.getAddress().getPort();
            ExternalIdpConfig oidc = cfg();
            oidc.setTokenUrl(base + "/token");
            oidc.setExtra(Maps.of("jwks-url", base + "/jwks"));

            IdpCallback callback = new IdpCallback();
            callback.setCode("c");
            IllegalStateException error = assertThrows(IllegalStateException.class,
                    () -> service.resolveIdentity(IdpType.OIDC, oidc, callback, IdpHttpClient.defaultClient()));
            assertTrue(error.getMessage().contains("signature"), error.getMessage());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void oidcRejectsTokenWithoutVerificationMaterial() throws Exception {
        KeyPair pair = IdpTestCrypto.rsaKeyPair();
        Map<String, Object> claims = new LinkedHashMap<String, Object>();
        claims.put("aud", "cid");
        claims.put("sub", "sub-123");
        claims.put("exp", System.currentTimeMillis() / 1000L + 300L);
        String idToken = IdpTestCrypto.idToken(pair.getPrivate(), "k1", claims);

        Map<String, String> routes = new LinkedHashMap<String, String>();
        routes.put("/token", json(Maps.of("access_token", "at", "id_token", idToken)));
        HttpServer server = startServer(routes);
        try {
            String base = "http://localhost:" + server.getAddress().getPort();
            ExternalIdpConfig oidc = cfg();
            oidc.setTokenUrl(base + "/token");

            IdpCallback callback = new IdpCallback();
            callback.setCode("c");
            IllegalStateException error = assertThrows(IllegalStateException.class,
                    () -> service.resolveIdentity(IdpType.OIDC, oidc, callback, IdpHttpClient.defaultClient()));
            assertTrue(error.getMessage().contains("jwks-url"), error.getMessage());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void saml2VerifiesSignatureThenResolvesIdentity() throws Exception {
        KeyPair pair = IdpTestCrypto.rsaKeyPair();
        String xml = IdpTestCrypto.signedSamlResponse(pair.getPrivate(), "uidval", "e@f.com");

        ExternalIdpConfig saml = cfg();
        saml.setExtra(Maps.of("saml-cert", IdpTestCrypto.publicKeyBase64(pair)));
        IdpCallback cb = new IdpCallback();
        cb.setSamlResponse(IdpTestCrypto.base64(xml));

        ExternalIdentity idp = service.resolveIdentity(IdpType.SAML2, saml, cb, IdpHttpClient.defaultClient());
        assertEquals("saml:uidval", idp.getAccount());
        assertEquals("e@f.com", idp.getEmail());
    }

    @Test
    void saml2RejectsUnsignedAssertion() throws Exception {
        KeyPair pair = IdpTestCrypto.rsaKeyPair();
        ExternalIdpConfig saml = cfg();
        saml.setExtra(Maps.of("saml-cert", IdpTestCrypto.publicKeyBase64(pair)));
        IdpCallback cb = new IdpCallback();
        cb.setSamlResponse(IdpTestCrypto.base64(IdpTestCrypto.unsignedSamlResponse("uidval", "e@f.com")));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.resolveIdentity(IdpType.SAML2, saml, cb, IdpHttpClient.defaultClient()));
        assertTrue(error.getMessage().contains("not signed"), error.getMessage());
    }

    @Test
    void saml2RejectsAssertionWithoutConfiguredCertificate() {
        IdpCallback cb = new IdpCallback();
        cb.setSamlResponse(IdpTestCrypto.base64(IdpTestCrypto.unsignedSamlResponse("uidval", "e@f.com")));
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.resolveIdentity(IdpType.SAML2, cfg(), cb, IdpHttpClient.defaultClient()));
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
                () -> service.resolveIdentity(IdpType.SAML2, saml, cb, IdpHttpClient.defaultClient()));
        assertTrue(error.getMessage().contains("signature"), error.getMessage());
    }

    @Test
    void wechatWorkResolvesIdentity() throws Exception {
        Map<String, String> routes = new LinkedHashMap<String, String>();
        routes.put("/cgi-bin/gettoken", json(Maps.of("errcode", 0, "errmsg", "ok", "access_token", "at")));
        routes.put("/cgi-bin/user/getuserinfo", json(Maps.of("userid", "U1")));
        routes.put("/cgi-bin/user/get", json(Maps.of("name", "Bob", "email", "b@c.com")));
        HttpServer server = startServer(routes);
        try {
            String base = "http://localhost:" + server.getAddress().getPort();
            ExternalIdpConfig wc = cfg();
            wc.setClientId("corp");
            wc.setClientSecret("sec");
            wc.setExtra(Maps.of("api_base", base));
            ExternalIdentity idp = service.resolveIdentity(IdpType.WECHAT_WORK, wc,
                    IdpCallback.fromQuery(Maps.of("code", "c")), IdpHttpClient.defaultClient());
            assertEquals("wechat:U1", idp.getAccount());
            assertEquals("Bob", idp.getName());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void casResolvesIdentityFromXml() throws Exception {
        String xml = "<cas:serviceResponse xmlns:cas='http://www.yale.edu/tp/cas'>"
                + "<cas:authenticationSuccess><cas:user>casuser</cas:user>"
                + "<cas:attributes><cas:attribute name='email'>cas@e.com</cas:attribute>"
                + "</cas:attributes></cas:authenticationSuccess></cas:serviceResponse>";
        Map<String, String> routes = new LinkedHashMap<String, String>();
        routes.put("/serviceValidate", xml);
        HttpServer server = startServer(routes);
        try {
            String base = "http://localhost:" + server.getAddress().getPort();
            ExternalIdpConfig cas = cfg();
            cas.setTokenUrl(base + "/serviceValidate");
            ExternalIdentity idp = service.resolveIdentity(IdpType.CAS, cas,
                    IdpCallback.fromQuery(Maps.of("ticket", "t")), IdpHttpClient.defaultClient());
            assertEquals("cas:casuser", idp.getAccount());
            assertEquals("cas@e.com", idp.getEmail());
        } finally {
            server.stop(0);
        }
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
                () -> service.resolveIdentity(null, cfg(), new IdpCallback(), IdpHttpClient.defaultClient()));
    }
}
