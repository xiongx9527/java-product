package com.xiongx9527.askdata.system.sso;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** CAS 协议（A7）：/login 授权跳转，/serviceValidate 校验 ticket 并解析 XML。 */
public class CasProvider implements ExternalIdpProvider {

    @Override
    public IdpType type() {
        return IdpType.CAS;
    }

    @Override
    public String authorizationUrl(ExternalIdpConfig cfg, String state) {
        return cfg.getAuthorizeUrl()
                + "?service=" + enc(cfg.getRedirectUri())
                + (state == null ? "" : "&state=" + enc(state));
    }

    @Override
    public ExternalIdentity resolveIdentity(ExternalIdpConfig cfg, IdpCallback cb, IdpHttpClient http) {
        String url = cfg.getTokenUrl()
                + "?service=" + enc(cfg.getRedirectUri())
                + "&ticket=" + enc(cb.getTicket());
        IdpHttpClient.Response resp = http.get(url, Map.of());
        if (!resp.ok()) {
            throw new IllegalStateException("cas ticket validation failed: " + resp.status);
        }
        String user = SsoXml.textByLocalName(resp.body, "user");
        Map<String, String> attrs = SsoXml.namedAttributes(resp.body);
        String email = attrs.get("email");
        String name = attrs.get("name");
        String externalId = user;
        if (externalId == null) {
            externalId = attrs.get("uid");
        }
        return new ExternalIdentity("cas:" + (user == null ? externalId : user), name, email, externalId,
                (Map<String, Object>) (Map<String, ?>) attrs);
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s == null ? "" : s;
        }
    }
}
