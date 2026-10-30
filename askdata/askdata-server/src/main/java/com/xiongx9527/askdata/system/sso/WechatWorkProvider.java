package com.xiongx9527.askdata.system.sso;

import com.xiongx9527.askdata.common.util.Maps;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** 企业微信（A7）：扫码授权码 → access_token → userid/姓名/邮箱。 */
public class WechatWorkProvider implements ExternalIdpProvider {

    @Override
    public IdpType type() {
        return IdpType.WECHAT_WORK;
    }

    @Override
    public String authorizationUrl(ExternalIdpConfig cfg, String state) {
        return cfg.getAuthorizeUrl()
                + "?appid=" + enc(cfg.getClientId())
                + "&redirect_uri=" + enc(cfg.getRedirectUri())
                + "&response_type=code&scope=snsapi_base"
                + (state == null ? "" : "&state=" + enc(state))
                + "#wechat_redirect";
    }

    @Override
    public ExternalIdentity resolveIdentity(ExternalIdpConfig cfg, IdpCallback cb, IdpHttpClient http) {
        String tokenUrl = cfg.getTokenUrl()
                + "?corpid=" + enc(cfg.getClientId()) + "&corpsecret=" + enc(cfg.getClientSecret());
        IdpHttpClient.Response token = http.get(tokenUrl, Maps.of());
        if (!token.ok()) {
            throw new IllegalStateException("wechat token failed: " + token.status);
        }
        Map<String, Object> t = SsoJson.parseObject(token.body);
        String accessToken = SsoJson.str(t, "access_token");
        String userInfoUrl = cfg.getUserInfoUrl()
                + "?access_token=" + enc(accessToken) + "&code=" + enc(cb.getCode());
        IdpHttpClient.Response info = http.get(userInfoUrl, Maps.of());
        Map<String, Object> u = SsoJson.parseObject(info.body);
        String userId = SsoJson.str(u, "userid");
        String name = SsoJson.str(u, "name");
        String email = SsoJson.str(u, "email");
        return new ExternalIdentity("wechat:" + userId, name, email, userId, u);
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Exception e) {
            return s == null ? "" : s;
        }
    }
}
