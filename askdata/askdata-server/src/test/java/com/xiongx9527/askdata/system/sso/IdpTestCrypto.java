package com.xiongx9527.askdata.system.sso;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.crypto.dsig.CanonicalizationMethod;
import javax.xml.crypto.dsig.DigestMethod;
import javax.xml.crypto.dsig.Reference;
import javax.xml.crypto.dsig.SignatureMethod;
import javax.xml.crypto.dsig.SignedInfo;
import javax.xml.crypto.dsig.Transform;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMSignContext;
import javax.xml.crypto.dsig.spec.C14NMethodParameterSpec;
import javax.xml.crypto.dsig.spec.TransformParameterSpec;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** A7 测试用的 IdP 侧密码学材料：RSA 密钥、签名 id_token、签名 SAML 断言。 */
final class IdpTestCrypto {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private IdpTestCrypto() {
    }

    static KeyPair rsaKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    static String b64url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** 生成 RS256 签名的 id_token。 */
    static String idToken(PrivateKey key, String kid, Map<String, Object> claims) throws Exception {
        Map<String, Object> header = new LinkedHashMap<String, Object>();
        header.put("alg", "RS256");
        header.put("typ", "JWT");
        header.put("kid", kid);
        String signingInput = b64url(MAPPER.writeValueAsBytes(header)) + "."
                + b64url(MAPPER.writeValueAsBytes(claims));
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(key);
        signer.update(signingInput.getBytes(StandardCharsets.UTF_8));
        return signingInput + "." + b64url(signer.sign());
    }

    /** 与密钥对匹配的 JWKS 文档。 */
    static String jwks(KeyPair pair, String kid) throws Exception {
        java.security.interfaces.RSAPublicKey pub = (java.security.interfaces.RSAPublicKey) pair.getPublic();
        String n = b64url(unsigned(pub.getModulus()));
        String e = b64url(unsigned(pub.getPublicExponent()));
        return "{\"keys\":[{\"kty\":\"RSA\",\"kid\":\"" + kid + "\",\"n\":\"" + n + "\",\"e\":\"" + e + "\"}]}";
    }

    /** IdP 公钥的 base64（X.509 SubjectPublicKeyInfo），等价于 IdP 证书里取出的公钥。 */
    static String publicKeyBase64(KeyPair pair) {
        return Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
    }

    private static byte[] unsigned(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            byte[] trimmed = new byte[bytes.length - 1];
            System.arraycopy(bytes, 1, trimmed, 0, trimmed.length);
            return trimmed;
        }
        return bytes;
    }

    /** 生成已签名的 SAMLResponse（对 Assertion 做 enveloped 签名）。 */
    static String signedSamlResponse(PrivateKey key, String uid, String email) throws Exception {
        String xml = assertionXml(uid, email);
        Document doc = parse(xml);
        Element assertion = (Element) doc.getElementsByTagNameNS("*", "Assertion").item(0);
        assertion.setIdAttribute("ID", true);

        XMLSignatureFactory factory = XMLSignatureFactory.getInstance("DOM");
        Reference reference = factory.newReference("#_assert1",
                factory.newDigestMethod(DigestMethod.SHA256, null),
                Collections.singletonList(factory.newTransform(Transform.ENVELOPED,
                        (TransformParameterSpec) null)), null, null);
        SignedInfo signedInfo = factory.newSignedInfo(
                factory.newCanonicalizationMethod(CanonicalizationMethod.INCLUSIVE,
                        (C14NMethodParameterSpec) null),
                factory.newSignatureMethod(SignatureMethod.RSA_SHA256, null),
                Collections.singletonList(reference));
        DOMSignContext context = new DOMSignContext(key, assertion);
        context.setDefaultNamespacePrefix("ds");
        factory.newXMLSignature(signedInfo, null).sign(context);
        return serialize(doc);
    }

    /** 未签名的 SAMLResponse（用于验证「未签名必须被拒绝」）。 */
    static String unsignedSamlResponse(String uid, String email) {
        return assertionXml(uid, email);
    }

    private static String assertionXml(String uid, String email) {
        return "<samlp:Response xmlns:samlp=\"urn:oasis:names:tc:SAML:2.0:protocol\""
                + " xmlns:saml=\"urn:oasis:names:tc:SAML:2.0:assertion\" ID=\"_resp1\" Version=\"2.0\""
                + " IssueInstant=\"" + Instant.now() + "\">"
                + "<saml:Assertion ID=\"_assert1\" Version=\"2.0\" IssueInstant=\"" + Instant.now() + "\">"
                + "<saml:Issuer>https://idp</saml:Issuer>"
                + "<saml:Subject><saml:NameID>" + uid + "</saml:NameID></saml:Subject>"
                + "<saml:AttributeStatement>"
                + "<saml:Attribute Name=\"uid\"><saml:AttributeValue>" + uid + "</saml:AttributeValue></saml:Attribute>"
                + "<saml:Attribute Name=\"email\"><saml:AttributeValue>" + email + "</saml:AttributeValue></saml:Attribute>"
                + "</saml:AttributeStatement></saml:Assertion></samlp:Response>";
    }

    static String base64(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    private static Document parse(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    private static String serialize(Document doc) throws Exception {
        Transformer transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        StringWriter writer = new StringWriter();
        transformer.transform(new DOMSource(doc), new StreamResult(writer));
        return writer.toString();
    }
}

