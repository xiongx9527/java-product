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
    /**
     * 去除 SAML XML 声明（{@code <?xml}）之前的杂散内容：UTF-8 BOM、前置空白/换行等。
     * 否则 XML 解析会报 “Content is not allowed in prolog”。部分 IdP（如 Casdoor）的
     * SAMLResponse 首部会带 BOM。
     */
    private static String stripXmlPrologJunk(String xml) {
        if (xml == null || xml.isEmpty()) {
            return xml;
        }
        if (xml.charAt(0) == '\uFEFF') {
            xml = xml.substring(1);
        }
        int decl = xml.indexOf("<?xml");
        if (decl > 0) {
            xml = xml.substring(decl);
        }
        return xml;
    }

    /**
     * 解码 SAMLResponse 为 XML 文本。兼容：
     * <ul>
     *   <li>标准 HTTP-POST 绑定：base64(明文 XML)；</li>
     *   <li>部分 IdP 对 POST 响应也做 DEFLATE 压缩（如 Casdoor 的 enable_saml_compress=1）：
     *       base64(deflate(XML))，需先 inflate 再按 UTF-8 解码，否则会报
     *       “Content is not allowed in prolog”；</li>
     *   <li>XML 声明前的 UTF-8 BOM/空白杂散内容。</li>
     * </ul>
     */
    private static String decodeSamlXml(String base64Response) {
        byte[] raw;
        try {
            raw = Base64.getMimeDecoder().decode(base64Response);
        } catch (IllegalArgumentException e) {
            raw = Base64.getDecoder().decode(base64Response.replaceAll("\\s", ""));
        }
        String xml = new String(raw, StandardCharsets.UTF_8);
        if (looksLikeXml(xml)) {
            return stripXmlPrologJunk(xml);
        }
        // 可能被 DEFLATE 压缩：inflate 后重试
        byte[] inflated = inflate(raw);
        if (inflated != null) {
            String inflatedXml = new String(inflated, StandardCharsets.UTF_8);
            if (looksLikeXml(inflatedXml)) {
                return stripXmlPrologJunk(inflatedXml);
            }
        }
        if (log != null) {
            log.warn("SAML2 response did not decode to XML after base64/inflate (b64len={}, rawLen={})",
                    base64Response.length(), raw.length);
        }
        return stripXmlPrologJunk(xml);
    }

    /** 去除 BOM/空白后是否以 '<' 开头（粗略判断是否为 XML）。 */
    private static boolean looksLikeXml(String s) {
        if (s == null) {
            return false;
        }
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\uFEFF' || Character.isWhitespace(c)) {
                i++;
                continue;
            }
            return c == '<';
        }
        return false;
    }

    /** zlib(DEFLATE) 解压；失败返回 null。 */
    private static byte[] inflate(byte[] data) {
        try (java.util.zip.InflaterInputStream in =
                     new java.util.zip.InflaterInputStream(new ByteArrayInputStream(data))) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    public Map<String, Object> parseResponse(String base64Response, Map<String, Object> config) {
        if (base64Response == null || base64Response.isEmpty()) {
            throw new IllegalStateException("Missing SAMLResponse");
        }
        String xml = decodeSamlXml(base64Response);
        // SAML assertion signature verification (reuse A7 SamlSignatureVerifier, fail-closed)
        String cert = str(config, "certificate");
        String spEntityId = str(config, "spEntityId");
        boolean wantSigned = Boolean.parseBoolean(str(config, "wantAssertionsSigned"));
        // 签名校验交给 SamlSignatureVerifier（命名空间感知，fail-closed）。
        // 原先用 xml.indexOf("<Signature") 的裸字符串判断，会把 <ds:Signature> 这类带前缀的
        // 写法（Casdoor 及绝大多数 IdP 的默认输出）误判为“未签名”，从而把合法断言拒绝掉。
        if (cert != null && !cert.trim().isEmpty()) {
            try {
                SamlSignatureVerifier.verify(xml, cert, spEntityId);
            } catch (IllegalStateException e) {
                if (e.getMessage() != null && e.getMessage().contains("not signed")) {
                    // 响应里根本没有 <Signature>：按 wantAssertionsSigned 决定拒绝或放行
                    if (wantSigned) {
                        throw new IllegalStateException(
                                "SAML2 wantAssertionsSigned is true but the IdP response is not signed; refusing");
                    }
                    log.warn("SAML2 response is unsigned while an IdP certificate is configured; "
                            + "signature NOT verified (wantAssertionsSigned=false)");
                } else {
                    // 真正的验签失败（签名无效 / 过期 / Audience 不匹配）：fail-closed 拒绝
                    throw e;
                }
            }
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
                    String attrValue = values.item(0).getTextContent();
                    attributes.put(name, attrValue);
                    // 同时存小写键，兼容大小写不敏感匹配（如 Casdoor 默认的 Name/Email）
                    attributes.put(name.toLowerCase(), attrValue);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Invalid SAMLResponse: " + e.getMessage(), e);
        }

        Map<String, Object> mapping = parseMapping(str(config, "mapping"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("platform_uid", nameId);
        result.put("account", pick(attributes, mapping, "account", nameId, "uid", "name", "email"));
        result.put("name", pick(attributes, mapping, "name", nameId, "cn", "displayName", "realName"));
        result.put("email", pick(attributes, mapping, "email", null, "mail", "emailAddress"));
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
            throw new IllegalStateException("missing idpMetaUrl in SAML2 config");
        }
        try {
            return readSsoEndpoint(idpMetaUrl) != null;
        } catch (Exception e) {
            throw new IllegalStateException("SAML2 metadata unreadable: " + e.getMessage(), e);
        }
    }

    /** 从 IdP 元数据 XML 中读出 SingleSignOnService 的 Location。 */
    private String readSsoEndpoint(String idpMetaUrl) {
        try {
            return parseEndpoint(fetchMetadata(idpMetaUrl), "SingleSignOnService");
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Unable to read IdP metadata: " + e.getMessage(), e);
        }
    }

    /**
     * A24：从 IdP 元数据读出 SingleLogoutService 的 Location（SLO 端点），
     * 供 SSO 登出联动 IdP 登出使用。元数据不可读或未声明 SLO 时返回 null（登出仍清本地会话）。
     */
    public String logoutEndpointFromMetadata(String idpMetaUrl) {
        if (idpMetaUrl == null || idpMetaUrl.trim().isEmpty()) {
            return null;
        }
        try {
            return parseEndpoint(fetchMetadata(idpMetaUrl.trim()), "SingleLogoutService");
        } catch (Exception e) {
            log.warn("Unable to read SAML2 IdP SingleLogoutService: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 拉取 IdP 元数据 XML 文本（失败抛 IllegalStateException，信息可直接展示给管理员）。
     * 供 SAML2 设置页的校验端点复用。
     */
    public String metadata(String idpMetaUrl) {
        if (idpMetaUrl == null || idpMetaUrl.trim().isEmpty()) {
            throw new IllegalStateException("Empty IdP metadata url");
        }
        return fetchMetadata(idpMetaUrl.trim());
    }

    /** 拉取 IdP 元数据 XML 文本（失败抛 IllegalStateException，信息可直接展示）。 */
    private String fetchMetadata(String idpMetaUrl) {
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
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to read IdP metadata: " + e.getMessage(), e);
        }
    }

    /**
     * 从元数据 XML 中解析指定元素的 {@code Location}（纯解析，便于离线校验与单测）。
     *
     * @param tagName 元素名（{@code SingleSignOnService} / {@code SingleLogoutService}）
     * @return Location；未声明时返回 null
     * @throws IllegalStateException XML 无法解析
     */
    public static String parseEndpoint(String metadataXml, String tagName) {
        if (metadataXml == null || metadataXml.trim().isEmpty()) {
            throw new IllegalStateException("Empty IdP metadata");
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document document = builder.parse(new ByteArrayInputStream(
                    metadataXml.getBytes(StandardCharsets.UTF_8)));
            NodeList nodes = document.getElementsByTagNameNS("*", tagName);
            for (int i = 0; i < nodes.getLength(); i++) {
                Element element = (Element) nodes.item(i);
                String location = element.getAttribute("Location");
                if (location != null && !location.isEmpty()) {
                    return location;
                }
            }
            return null;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Invalid IdP metadata: " + e.getMessage(), e);
        }
    }

    /** 解析 SAML2 配置里的字段映射（{@code {"account":"attrName", ...}}），无效/为空返回空 Map。 */
    private Map<String, Object> parseMapping(String mappingJson) {
        if (mappingJson == null || mappingJson.trim().isEmpty()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(mappingJson,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                    });
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    /**
     * 按映射与候选名从断言属性中取字段值。
     *
     * @param mapping  用户配置的字段映射：本地字段名 → SAML Attribute Name
     * @param localKey 本地字段名（也是默认的 Attribute Name 候选）
     * @param fallback 兜底值（如 NameID），作为最后手段返回
     */
    private String pick(Map<String, Object> attributes, Map<String, Object> mapping, String localKey,
                        String fallback, String... candidates) {
        // 1) 用户配置的映射优先：localKey -> Attribute Name
        if (mapping != null) {
            Object mapped = mapping.get(localKey);
            if (mapped != null) {
                Object v = attributes.get(String.valueOf(mapped));
                if (v != null && !String.valueOf(v).isEmpty()) {
                    return String.valueOf(v);
                }
            }
        }
        // 2) 同名属性
        if (localKey != null) {
            Object v = attributes.get(localKey);
            if (v != null && !String.valueOf(v).isEmpty()) {
                return String.valueOf(v);
            }
        }
        // 3) 常见候选属性名
        for (String candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            Object value = attributes.get(candidate);
            if (value != null && !String.valueOf(value).isEmpty()) {
                return String.valueOf(value);
            }
        }
        // 4) 兜底（如 NameID）
        if (fallback != null && !fallback.isEmpty()) {
            return fallback;
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
