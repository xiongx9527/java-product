package com.xiongx9527.askdata.system.sso;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * OIDC 授权码流程（A7）：授权码换 token → 校验 id_token（签名/iss/aud/exp/nonce）→ 身份。
 *
 * <p>id_token 的校验见 {@link OidcIdTokenVerifier}；未配置 JWKS 或 IdP 证书时拒绝登录，
 * 避免只凭 token 响应体就建立会话。</p>
 */
public class OidcProvider implements ExternalIdpProvider {

    @Override
    public IdpType type() {
        return IdpType.OIDC;
    }

    @Override
    public String authorizationUrl(ExternalIdpConfig cfg, String state) {
        return cfg.getAuthorizeUrl()
                + "?client_id=" + enc(cfg.getClientId())
                + "&response_type=code"
                + "&scope=" + enc("openid profile email")
                + "&redirect_uri=" + enc(cfg.getRedirectUri())
                + "&state=" + enc(state)
                // nonce 复用服务端签发的 state（同样随机、一次性），用于绑定 id_token 与本次请求
                + (state == null || state.isEmpty() ? "" : "&nonce=" + enc(state));
    }

    @Override
    public ExternalIdentity resolveIdentity(ExternalIdpConfig cfg, IdpCallback cb, IdpHttpClient http) {
        IdpHttpClient.Response token = http.postForm(cfg.getTokenUrl(), Map.of(
                "grant_type", "authorization_code",
                "code", cb.getCode() == null ? "" : cb.getCode(),
                "redirect_uri", cfg.getRedirectUri() == null ? "" : cfg.getRedirectUri(),
                "client_id", cfg.getClientId() == null ? "" : cfg.getClientId(),
                "client_secret", cfg.getClientSecret() == null ? "" : cfg.getClientSecret()), Map.of());
        if (!token.ok()) {
            throw new IllegalStateException("oidc token exchange failed: " + token.status);
        }
        Map<String, Object> t = SsoJson.parseObject(token.body);
        Map<String, Object> claims = OidcIdTokenVerifier.verify(
                SsoJson.str(t, "id_token"), cfg, cb.getState(), http);

        String sub = SsoJson.str(claims, "sub");
        String email = SsoJson.str(claims, "email");
        String name = SsoJson.str(claims, "name");
        if (name == null) {
            name = SsoJson.str(claims, "preferred_username");
        }
        if (sub == null || email == null || name == null) {
            // 授权服务器未把资料放进 id_token 时，用 access_token 补 userinfo
            IdpHttpClient.Response info = http.get(cfg.getUserInfoUrl(),
                    Map.of("Authorization", "Bearer " + strOrEmpty(t, "access_token")));
            if (info.ok()) {
                Map<String, Object> u = SsoJson.parseObject(info.body);
                if (sub == null) {
                    sub = SsoJson.str(u, "sub");
                }
                if (email == null) {
                    email = SsoJson.str(u, "email");
                }
                if (name == null) {
                    name = SsoJson.str(u, "name");
                }
            }
        }
        if (sub == null || sub.isEmpty()) {
            throw new IllegalStateException("oidc identity has no subject");
        }
        return new ExternalIdentity(sub, name, email, sub, claims);
    }

    private static String strOrEmpty(Map<String, Object> m, String k) {
        String value = SsoJson.str(m, k);
        return value == null ? "" : value;
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s == null ? "" : s;
        }
    }
}

