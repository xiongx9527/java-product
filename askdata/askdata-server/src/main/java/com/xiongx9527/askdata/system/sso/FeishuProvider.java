package com.xiongx9527.askdata.system.sso;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** 飞书（A7）：授权码 → tenant_access_token → userinfo(open_id)。 */
public class FeishuProvider implements ExternalIdpProvider {

    @Override
    public IdpType type() {
        return IdpType.FEISHU;
    }

    @Override
    public String authorizationUrl(ExternalIdpConfig cfg, String state) {
        return cfg.getAuthorizeUrl()
                + "?app_id=" + enc(cfg.getClientId())
                + "&redirect_uri=" + enc(cfg.getRedirectUri())
                + "&response_type=code"
                + (state == null ? "" : "&state=" + enc(state));
    }

    @Override
    public ExternalIdentity resolveIdentity(ExternalIdpConfig cfg, IdpCallback cb, IdpHttpClient http) {
        IdpHttpClient.Response token = http.postForm(cfg.getTokenUrl(), Map.of(
                "app_id", cfg.getClientId() == null ? "" : cfg.getClientId(),
                "app_secret", cfg.getClientSecret() == null ? "" : cfg.getClientSecret()), Map.of());
        if (!token.ok()) {
            throw new IllegalStateException("feishu token failed: " + token.status);
        }
        Map<String, Object> t = SsoJson.parseObject(token.body);
        String accessToken = SsoJson.str(t, "tenant_access_token");
        if (accessToken == null) {
            accessToken = SsoJson.str(t, "access_token");
        }
        IdpHttpClient.Response info = http.get(
                cfg.getUserInfoUrl() + "?access_token=" + enc(accessToken) + "&code=" + enc(cb.getCode()),
                Map.of());
        Map<String, Object> u = SsoJson.parseObject(info.body);
        String openId = SsoJson.str(u, "open_id");
        String name = SsoJson.str(u, "name");
        String email = SsoJson.str(u, "email");
        return new ExternalIdentity("feishu:" + openId, name, email, openId, u);
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s == null ? "" : s;
        }
    }
}
