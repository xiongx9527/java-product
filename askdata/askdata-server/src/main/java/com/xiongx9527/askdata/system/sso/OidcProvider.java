package com.xiongx9527.askdata.system.sso;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** OIDC 授权码流程（A7）。 */
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
                + "&state=" + enc(state);
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
        String sub = SsoJson.str(t, "sub");
        if (sub == null) {
            sub = SsoJson.str(t, "user_id");
        }
        IdpHttpClient.Response info = http.get(cfg.getUserInfoUrl(),
                Map.of("Authorization", "Bearer " + (sub != null ? "" : "") + strOrEmpty(t, "access_token")));
        Map<String, Object> u = SsoJson.parseObject(info.body);
        String email = SsoJson.str(u, "email");
        String name = SsoJson.str(u, "name");
        if (name == null) {
            name = SsoJson.str(u, "preferred_username");
        }
        if (sub == null) {
            sub = SsoJson.str(u, "sub");
        }
        if (sub == null) {
            sub = SsoJson.str(u, "user_id");
        }
        return new ExternalIdentity(sub, name, email, sub, u);
    }

    private static String strOrEmpty(Map<String, Object> m, String k) {
        return SsoJson.str(m, k) == null ? "" : SsoJson.str(m, k);
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s == null ? "" : s;
        }
    }
}
