package com.xiongx9527.askdata.system.sso;

import java.io.ByteArrayInputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/** SAML2 POST-Binding（A7）：解码 (inflate) SAMLResponse 并解析断言属性。 */
public class Saml2Provider implements ExternalIdpProvider {

    @Override
    public IdpType type() {
        return IdpType.SAML2;
    }

    @Override
    public String authorizationUrl(ExternalIdpConfig cfg, String state) {
        // 真实场景需构造 SAMLRequest（AuthnRequest）并 base64 后跳转；此处返回 IdP SSO 入口。
        return cfg.getAuthorizeUrl() + (state == null ? "" : "?RelayState=" + enc(state));
    }

    @Override
    public ExternalIdentity resolveIdentity(ExternalIdpConfig cfg, IdpCallback cb, IdpHttpClient http) {
        String xml = decode(cb.getSamlResponse());
        String nameId = SsoXml.textByLocalName(xml, "NameID");
        String subject = SsoXml.textByLocalName(xml, "Subject");
        Map<String, String> attrs = SsoXml.namedAttributes(xml);
        String account = attrs.get("uid");
        if (account == null) {
            account = attrs.get("urn:oid:0.9.2342.19200300.100.1.1");
        }
        if (account == null) {
            account = nameId;
        }
        String email = attrs.get("email");
        if (email == null) {
            email = attrs.get("urn:oid:0.9.2342.19200300.100.1.3");
        }
        String name = attrs.get("cn");
        if (name == null) {
            name = attrs.get("displayName");
        }
        String externalId = nameId != null ? nameId : subject;
        return new ExternalIdentity("saml:" + account, name, email, externalId,
                (Map<String, Object>) (Map<String, ?>) attrs);
    }

    private static String decode(String samlResponse) {
        if (samlResponse == null) {
            throw new IllegalStateException("saml response is null");
        }
        byte[] decoded = Base64.getDecoder().decode(samlResponse);
        // 尝试 zlib inflate（HTTP-Redirect Binding），失败则按原样（POST Binding）
        try (InflaterInputStream iis = new InflaterInputStream(
                new ByteArrayInputStream(decoded), new Inflater(true))) {
            return new String(iis.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return new String(decoded, StandardCharsets.UTF_8);
        }
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s == null ? "" : s;
        }
    }
}
