package com.xiongx9527.askdata.system.sso;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.crypto.dsig.CanonicalizationMethod;
import javax.xml.crypto.dsig.DigestMethod;
import javax.xml.crypto.dsig.Reference;
import javax.xml.crypto.dsig.SignatureMethod;
import javax.xml.crypto.dsig.SignedInfo;
import javax.xml.crypto.dsig.Transform;
import javax.xml.crypto.dsig.XMLSignature;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMSignContext;
import javax.xml.crypto.dsig.spec.C14NMethodParameterSpec;
import javax.xml.crypto.dsig.spec.TransformParameterSpec;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * SAML 断言验签的回归点：<b>SHA-1 签名必须能通过</b>。
 *
 * <p>JDK 17 起 XML-DSig 默认开启 secure validation，会直接把 rsa-sha1 签名判为无效；
 * 而 Casdoor 等主流 SAML IdP 仍用 rsa-sha1 签断言。若不显式关闭该策略，同一份断言在
 * JDK 8 上能登录、在 JDK 17 上必报 {@code saml assertion signature is invalid}。
 * 另含篡改用例，确认关闭 secure validation 后仍 fail-closed。</p>
 */
class SamlSignatureVerifierTest {

    private static final String RSA_SHA1 = "http://www.w3.org/2000/09/xmldsig#rsa-sha1";

    private static final String RSA_SHA256 = "http://www.w3.org/2001/04/xmldsig-more#rsa-sha256";

    private static final String XML_TEMPLATE = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<samlp:Response xmlns:samlp=\"urn:oasis:names:tc:SAML:2.0:protocol\" "
            + "xmlns:saml=\"urn:oasis:names:tc:SAML:2.0:assertion\" ID=\"_r1\" Version=\"2.0\">"
            + "<saml:Assertion ID=\"_a1\" Version=\"2.0\">"
            + "<saml:Subject><saml:NameID>admin</saml:NameID></saml:Subject>"
            + "</saml:Assertion></samlp:Response>";

    @Test
    void acceptsSha1SignedAssertion() throws Exception {
        Signed signed = sign(RSA_SHA1, DigestMethod.SHA1);
        assertDoesNotThrow(() -> SamlSignatureVerifier.verify(signed.xml, signed.publicKey, null));
    }

    @Test
    void acceptsSha256SignedAssertion() throws Exception {
        Signed signed = sign(RSA_SHA256, DigestMethod.SHA256);
        assertDoesNotThrow(() -> SamlSignatureVerifier.verify(signed.xml, signed.publicKey, null));
    }

    @Test
    void rejectsTamperedAssertion() throws Exception {
        Signed signed = sign(RSA_SHA1, DigestMethod.SHA1);
        String tampered = signed.xml.replace(">admin<", ">attacker<");
        assertThrows(IllegalStateException.class,
                () -> SamlSignatureVerifier.verify(tampered, signed.publicKey, null));
    }

    @Test
    void rejectsUnsignedAssertion() {
        assertThrows(IllegalStateException.class,
                () -> SamlSignatureVerifier.verify(XML_TEMPLATE, "not-a-cert", null));
    }

    private static Signed sign(String signatureAlgorithm, String digestAlgorithm) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        Document document = factory.newDocumentBuilder()
                .parse(new ByteArrayInputStream(XML_TEMPLATE.getBytes(StandardCharsets.UTF_8)));
        registerIds(document);

        XMLSignatureFactory signatures = XMLSignatureFactory.getInstance("DOM");
        Reference reference = signatures.newReference("#_a1",
                signatures.newDigestMethod(digestAlgorithm, null),
                Collections.singletonList(signatures.newTransform(Transform.ENVELOPED,
                        (TransformParameterSpec) null)),
                null, null);
        SignedInfo signedInfo = signatures.newSignedInfo(
                signatures.newCanonicalizationMethod(CanonicalizationMethod.EXCLUSIVE,
                        (C14NMethodParameterSpec) null),
                signatures.newSignatureMethod(signatureAlgorithm, null),
                Collections.singletonList(reference));
        signatures.newXMLSignature(signedInfo, null)
                .sign(new DOMSignContext(keyPair.getPrivate(), document.getDocumentElement()));

        StringWriter writer = new StringWriter();
        TransformerFactory.newInstance().newTransformer()
                .transform(new DOMSource(document), new StreamResult(writer));
        return new Signed(writer.toString(),
                Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()));
    }

    /** 让签名引用 {@code #_a1} 能按 ID 解析（否则 Reference 解引用失败）。 */
    private static void registerIds(Document document) {
        NodeList all = document.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            Element element = (Element) all.item(i);
            if (element.hasAttribute("ID")) {
                element.setIdAttribute("ID", true);
            }
        }
    }

    private static final class Signed {

        private final String xml;

        private final String publicKey;

        private Signed(String xml, String publicKey) {
            this.xml = xml;
            this.publicKey = publicKey;
        }
    }
}
