package com.xiongx9527.askdata.system.sso;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.crypto.dsig.XMLSignature;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMValidateContext;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.cert.CertificateFactory;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;

/**
 * SAML2 断言签名校验（A7）。
 *
 * <p>未校验签名的 SAMLResponse 等于「谁都能伪造一条登录断言」，因此这里默认<b>失败关闭</b>：
 * 未配置 IdP 证书、响应未签名、签名不匹配、断言过期或 audience 不匹配时一律拒绝。</p>
 */
public final class SamlSignatureVerifier {

    private SamlSignatureVerifier() {
    }

    /**
     * 校验 SAMLResponse 的 XML 签名与有效期。
     *
     * @param xml        解码后的 SAML 响应 XML
     * @param certText   IdP 签名证书（PEM 或 base64 DER）
     * @param audience   期望的 Audience（为空时跳过该检查）
     */
    public static void verify(String xml, String certText, String audience) {
        if (certText == null || certText.trim().isEmpty()) {
            throw new IllegalStateException(
                    "saml idp certificate is not configured; refusing to accept an unverified assertion");
        }
        Document doc = parse(xml);
        registerIds(doc);
        NodeList signatures = doc.getElementsByTagNameNS(XMLSignature.XMLNS, "Signature");
        if (signatures.getLength() == 0) {
            throw new IllegalStateException("saml response is not signed");
        }
        PublicKey publicKey = publicKey(certText);
        boolean valid = false;
        for (int i = 0; i < signatures.getLength() && !valid; i++) {
            DOMValidateContext context = new DOMValidateContext(publicKey, signatures.item(i));
            context.setProperty("javax.xml.crypto.dsig.cacheReference", Boolean.TRUE);
            try {
                XMLSignature signature = XMLSignatureFactory.getInstance("DOM").unmarshalXMLSignature(context);
                valid = signature.validate(context);
            } catch (Exception e) {
                // 该签名块无法解析：继续尝试下一块，全部失败即拒绝
                valid = false;
            }
        }
        if (!valid) {
            throw new IllegalStateException("saml assertion signature is invalid");
        }
        assertNotExpired(doc);
        assertAudience(doc, audience);
    }

    private static Document parse(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            return factory.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("parse saml response failed: " + e.getMessage(), e);
        }
    }

    /** 注册 ID 属性，使签名引用（#_xxx）能被 DOM 校验上下文解析。 */
    private static void registerIds(Document doc) {
        NodeList all = doc.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            Element element = (Element) all.item(i);
            for (String name : new String[]{"ID", "Id", "id"}) {
                if (element.hasAttribute(name)) {
                    element.setIdAttribute(name, true);
                    break;
                }
            }
        }
    }

    private static void assertNotExpired(Document doc) {
        String notOnOrAfter = attributeValue(doc, "NotOnOrAfter");
        if (notOnOrAfter == null) {
            return;
        }
        Instant deadline;
        try {
            deadline = Instant.parse(notOnOrAfter.trim());
        } catch (Exception e) {
            throw new IllegalStateException("saml assertion has an invalid NotOnOrAfter: " + notOnOrAfter);
        }
        if (Instant.now().isAfter(deadline)) {
            throw new IllegalStateException("saml assertion expired at " + notOnOrAfter);
        }
    }

    private static void assertAudience(Document doc, String expected) {
        if (expected == null || expected.trim().isEmpty()) {
            return;
        }
        NodeList audiences = doc.getElementsByTagNameNS("*", "Audience");
        if (audiences.getLength() == 0) {
            throw new IllegalStateException("saml assertion has no Audience restriction");
        }
        for (int i = 0; i < audiences.getLength(); i++) {
            if (expected.trim().equals(audiences.item(i).getTextContent().trim())) {
                return;
            }
        }
        throw new IllegalStateException("saml assertion audience does not match " + expected);
    }

    private static String attributeValue(Document doc, String attribute) {
        NodeList all = doc.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            Element element = (Element) all.item(i);
            if (element.hasAttribute(attribute)) {
                return element.getAttribute(attribute);
            }
        }
        return null;
    }

    /** 接受 PEM/base64 的 IdP 证书，或 base64 的 X.509 公钥（SubjectPublicKeyInfo）。 */
    private static PublicKey publicKey(String text) {
        String base64 = text
                .replace("-----BEGIN CERTIFICATE-----", "")
                .replace("-----END CERTIFICATE-----", "")
                .replaceAll("\\s", "");
        byte[] der;
        try {
            der = Base64.getDecoder().decode(base64);
        } catch (Exception e) {
            throw new IllegalStateException("saml idp certificate is not valid base64: " + e.getMessage(), e);
        }
        try {
            return CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(der)).getPublicKey();
        } catch (Exception notCertificate) {
            try {
                return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
            } catch (Exception e) {
                throw new IllegalStateException("saml idp certificate/public key is invalid: "
                        + e.getMessage(), e);
            }
        }
    }
}

