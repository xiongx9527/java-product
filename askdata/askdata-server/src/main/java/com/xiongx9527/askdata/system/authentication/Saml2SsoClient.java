package com.xiongx9527.askdata.system.authentication;

import com.fasterxml.jackson.databind.ObjectMapper;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import com.xiongx9527.askdata.system.sso.SamlSignatureVerifier;

/**
 * SAML2 认证源客户端。
 *
 * <p>config 字段（对齐前端 {@code views/system/authentication/SAML2Editor.vue}）：
 * {@code idpMetaUrl} / {@code privateKey} / {@code certificate} /
 * {@code wantAuthnRequestsSigned} / {@code wantAssertionsSigned} / {@code mapping}。</p>
 *
 * <p>实现范围：</p>
 * <ul>
 *   <li><b>授权跳转</b>：从 IdP 元数据（{@code idpMetaUrl}）解析 SSO 端点，生成
 *       {@code SAMLRequest}（HTTP-Redirect 绑定，Deflate + base64）跳转 URL；</li>
 *   <li><b>回调解析</b>：解析 base64 的 {@code SAMLResponse} XML，读取
 *       {@code NameID} 与 {@code AttributeStatement} 中的属性；</li>
 *   <li><b>断言签名校验</b>：配置了 IdP 证书时用 {@link SamlSignatureVerifier} 做 XML-DSig
 *       验签（复用 A7 的 fail-closed 语义：验签失败直接拒绝，不放行未验证断言）；
 *       配置了 {@code wantAssertionsSigned=true} 但未配证书时同样拒绝；两者都未配置时
 *       记警告日志并按历史行为放行（部署时应在网关上强制 TLS + IdP 侧约束）。</li>
 * </ul>
 */
public class Saml2SsoClient implements SsoClient {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(Saml2SsoClient.class);

    private final ObjectMapper objectMapper;

    public Saml2SsoClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String authorizeUrl(Map<String, Object> config, SsoCallback callback) {
        String idpMetaUrl = str(config, "idpMetaUrl");
        if (idpMetaUrl == null || idpMetaUrl.isEmpty()) {
            throw new IllegalStateException("Missing idpMetaUrl");
        }
        String ssoUrl = readSsoEndpoint(idpMetaUrl);
        String spEntityId = str(config, "spEntityId");
        String relayState = callback.getState() == null ? "" : callback.getState();

        // HTTP-Redirect 绑定：SAMLRequest = base64(deflate(xml)) 后 URL 编码
        String request = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<samlp:AuthnRequest xmlns:samlp=\"urn:oasis:names:tc:SAML:2.0:protocol\" "
                + "ID=\"askdata-" + System.currentTimeMillis() + "\" Version=\"2.0\" "
                + "IssueInstant=\"" + java.time.Instant.now() + "\" "
                + "Destination=\"" + ssoUrl + "\" "
                + "AssertionConsumerServiceURL=\"" + (str(config, "spAcs") == null ? "" : str(config, "spAcs")) + "\" "
                + "ProtocolBinding=\"urn:oasis:names:tc:SAML:2.0:bindings:HTTP-POST\">"
                + "<saml:Issuer xmlns:saml=\"urn:oasis:names:tc:SAML:2.0:assertion\">"
                + (spEntityId == null ? "" : spEntityId) + "</saml:Issuer></samlp:AuthnRequest>";
        String encoded = base64Deflate(request);
        return ssoUrl + (ssoUrl.contains("?") ? "&" : "?") + "SAMLRequest=" + urlEncode(encoded)
                + "&RelayState=" + urlEncode(relayState);
    }

    @Override
    public Map<String, Object> exchange(Map<String, Object> config, SsoCallback callback) {
        throw new UnsupportedOperationException(
                "SAML2 response is posted to the SP ACS endpoint; see AuthenticationService#samlCallback");
    }

    /**
     * 解析 base64 的 SAMLResponse XML，取出用户标识与属性。
     *
     * @param base64Response base64 编码的 SAMLResponse XML
     */
    public Map<String, Object> parseResponse(String base64Response, Map<String, Object> config) {
        if (base64Response == null || base64Response.isEmpty()) {
            throw new IllegalStateException("Missing SAMLResponse");
        }
        String xml = new String(Base64.getMimeDecoder().decode(base64Response), StandardCharsets.UTF_8);
        // SAML assertion signature verification (reuse A7 SamlSignatureVerifier, fail-closed)
        String cert = str(config, "certificate");
        boolean wantSigned = Boolean.parseBoolean(str(config, "wantAssertionsSigned"));
        if (cert != null && !cert.trim().isEmpty()) {
            SamlSignatureVerifier.verify(xml, cert, str(config, "spEntityId"));
        } else if (wantSigned) {
            throw new IllegalStateException(
                    "SAML2 wantAssertionsSigned is true but no IdP certificate configured; refusing unverified assertion");
        } else {
            log.warn("SAML2 assertion signature is NOT verified (no certificate configured)");
        }
        Map<String, Object> attributes = new LinkedHashMap<>();
        String nameId = null;

        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            // 关闭外部实体，避免 XXE
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document document = builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

            NodeList nameIdNodes = document.getElementsByTagNameNS("*", "NameID");
            if (nameIdNodes.getLength() > 0) {
                nameId = nameIdNodes.item(0).getTextContent();
            }
            NodeList attributeNodes = document.getElementsByTagNameNS("*", "Attribute");
            for (int i = 0; i < attributeNodes.getLength(); i++) {
                Element attribute = (Element) attributeNodes.item(i);
                String name = attribute.getAttribute("Name");
                NodeList values = attribute.getElementsByTagNameNS("*", "AttributeValue");
                if (name != null && !name.isEmpty() && values.getLength() > 0) {
                    attributes.put(name, values.item(0).getTextContent());
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Invalid SAMLResponse: " + e.getMessage(), e);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("platform_uid", nameId);
        result.put("account", pick(attributes, config(nameId), "account", "uid", "name", "email", nameId));
        result.put("name", pick(attributes, config(nameId), "name", "cn", "displayName", "realName", nameId));
        result.put("email", pick(attributes, config(nameId), "email", "mail", "emailAddress", null));
        return result;
    }

    /**
     * 连通性校验：拉取 IdP 元数据并解析 SSO 端点。
     *
     * <p>注意：IdP 元数据本身不保证签名有效，真正的断言签名校验需要 opensaml（当前不可用）。</p>
     */
    @Override
    public boolean validate(Map<String, Object> config) {
        String idpMetaUrl = str(config, "idpMetaUrl");
        if (idpMetaUrl == null || idpMetaUrl.isEmpty()) {
            return false;
        }
        try {
            return readSsoEndpoint(idpMetaUrl) != null;
        } catch (Exception e) {
            return false;
        }
    }

    /** 从 IdP 元数据 XML 中读出 SingleSignOnService 的 Location。 */
    private String readSsoEndpoint(String idpMetaUrl) {
        try {
            java.net.HttpURLConnection connection =
                    (java.net.HttpURLConnection) new java.net.URL(idpMetaUrl).openConnection();
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            StringBuilder sb = new StringBuilder();
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
            }
            connection.disconnect();
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document document = builder.parse(new ByteArrayInputStream(sb.toString().getBytes(StandardCharsets.UTF_8)));
            NodeList nodes = document.getElementsByTagNameNS("*", "SingleSignOnService");
            for (int i = 0; i < nodes.getLength(); i++) {
                Element element = (Element) nodes.item(i);
                String location = element.getAttribute("Location");
                if (location != null && !location.isEmpty()) {
                    return location;
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Unable to read IdP metadata: " + e.getMessage(), e);
        }
        return null;
    }

    private Map<String, Object> config(String nameId) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("nameId", nameId);
        return config;
    }

    private String pick(Map<String, Object> attributes, Map<String, Object> config, String localKey,
                        String... candidates) {
        for (String candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            Object value = attributes.get(candidate);
            if (value != null && !String.valueOf(value).isEmpty()) {
                return String.valueOf(value);
            }
        }
        return null;
    }

    /** base64(deflate(xml))（SAML HTTP-Redirect 绑定）。 */
    static String base64Deflate(String xml) {
        try {
            java.util.zip.Deflater deflater = new java.util.zip.Deflater(
                    java.util.zip.Deflater.DEFLATED, true);
            deflater.setInput(xml.getBytes(StandardCharsets.UTF_8));
            deflater.finish();
            byte[] buffer = new byte[1024];
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            while (!deflater.finished()) {
                int count = deflater.deflate(buffer);
                out.write(buffer, 0, count);
            }
            deflater.end();
            return Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (Exception e) {
            throw new IllegalStateException("Unable to deflate SAMLRequest", e);
        }
    }

    static String str(Map<String, Object> config, String key) {
        Object value = config == null ? null : config.get(key);
        return value == null ? null : String.valueOf(value);
    }

    static String urlEncode(String value) {
        try {
            return java.net.URLEncoder.encode(value == null ? "" : value, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }
}
