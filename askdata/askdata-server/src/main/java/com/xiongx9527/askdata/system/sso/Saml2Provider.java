package com.xiongx9527.askdata.system.sso;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * SAML2 HTTP-POST 绑定（A7）：签发 AuthnRequest 跳转，回调时<b>先验签</b>再解析断言属性。
 *
 * <p>签名校验见 {@link SamlSignatureVerifier}：未配置 IdP 证书、响应未签名或签名不匹配时拒绝，
 * 否则任何人都能构造一条断言登录任意账号。</p>
 */
public class Saml2Provider implements ExternalIdpProvider {

    @Override
    public IdpType type() {
        return IdpType.SAML2;
    }

    @Override
    public String authorizationUrl(ExternalIdpConfig cfg, String state) {
        String request = authnRequest(cfg);
        return cfg.getAuthorizeUrl()
                + (cfg.getAuthorizeUrl().contains("?") ? "&" : "?")
                + "SAMLRequest=" + enc(deflate(request))
                + (state == null ? "" : "&RelayState=" + enc(state));
    }

    @Override
    public ExternalIdentity resolveIdentity(ExternalIdpConfig cfg, IdpCallback cb, IdpHttpClient http) {
        String xml = decode(cb.getSamlResponse());
        SamlSignatureVerifier.verify(xml, cfg.extra("saml-cert"), cfg.extra("saml-audience"));
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
        if (account == null || account.trim().isEmpty()) {
            throw new IllegalStateException("saml assertion has no subject identifier");
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

    /** 最小可用的 AuthnRequest（HTTP-Redirect 绑定，deflate + base64）。 */
    private static String authnRequest(ExternalIdpConfig cfg) {
        String issuer = cfg.extra("saml-issuer");
        if (issuer == null) {
            issuer = cfg.getClientId() == null ? "askdata" : cfg.getClientId();
        }
        StringBuilder sb = new StringBuilder();
        sb.append("<samlp:AuthnRequest xmlns:samlp=\"urn:oasis:names:tc:SAML:2.0:protocol\"")
                .append(" xmlns:saml=\"urn:oasis:names:tc:SAML:2.0:assertion\"")
                .append(" ID=\"_").append(UUID.randomUUID().toString().replace("-", "")).append("\"")
                .append(" Version=\"2.0\"")
                .append(" IssueInstant=\"").append(Instant.now().toString()).append("\"")
                .append(" Destination=\"").append(cfg.getAuthorizeUrl()).append("\"")
                .append(" ProtocolBinding=\"urn:oasis:names:tc:SAML:2.0:bindings:HTTP-POST\"");
        if (cfg.getRedirectUri() != null && !cfg.getRedirectUri().isEmpty()) {
            sb.append(" AssertionConsumerServiceURL=\"").append(cfg.getRedirectUri()).append("\"");
        }
        sb.append("><saml:Issuer>").append(issuer).append("</saml:Issuer></samlp:AuthnRequest>");
        return sb.toString();
    }

    private static String deflate(String xml) {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (DeflaterOutputStream out = new DeflaterOutputStream(buffer, new Deflater(Deflater.DEFAULT_COMPRESSION, true))) {
                out.write(xml.getBytes(StandardCharsets.UTF_8));
            }
            return Base64.getEncoder().encodeToString(buffer.toByteArray());
        } catch (Exception e) {
            throw new IllegalStateException("build saml request failed: " + e.getMessage(), e);
        }
    }

    private static String decode(String samlResponse) {
        if (samlResponse == null) {
            throw new IllegalStateException("saml response is null");
        }
        byte[] decoded = Base64.getDecoder().decode(samlResponse);
        // 尝试 zlib inflate（HTTP-Redirect Binding），失败则按原样（POST Binding）
        try (InflaterInputStream iis = new InflaterInputStream(
                new ByteArrayInputStream(decoded), new Inflater(true))) {
            return new String(readAll(iis), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return new String(decoded, StandardCharsets.UTF_8);
        }
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Exception e) {
            return s == null ? "" : s;
        }
    }

    /** 读尽输入流（Java 8 无 {@code readAllBytes()}）。 */
    private static byte[] readAll(InflaterInputStream in) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int n;
        while ((n = in.read(chunk)) >= 0) {
            buf.write(chunk, 0, n);
        }
        return buf.toByteArray();
    }
}

