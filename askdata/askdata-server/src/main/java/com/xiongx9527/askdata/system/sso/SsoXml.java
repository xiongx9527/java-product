package com.xiongx9527.askdata.system.sso;

import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** 极简 XML 解析（A7；CAS/SAML2 断言，忽略命名空间）。 */
public final class SsoXml {

    private SsoXml() {
    }

    private static Document doc(String xml) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            return f.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("parse idp xml failed: " + e.getMessage(), e);
        }
    }

    public static String textByLocalName(String xml, String local) {
        try {
            Document d = doc(xml);
            XPath xp = XPathFactory.newInstance().newXPath();
            Node n = (Node) xp.evaluate("//*[local-name()='" + local + "']", d, XPathConstants.NODE);
            return n == null ? null : n.getTextContent();
        } catch (Exception e) {
            throw new IllegalStateException("xpath failed: " + e.getMessage(), e);
        }
    }

    /** CAS/SAML 的 <Attribute name="k">v</Attribute> 或 <Attribute Name="k">v</Attribute>。 */
    public static Map<String, String> namedAttributes(String xml) {
        Map<String, String> map = new LinkedHashMap<>();
        try {
            Document d = doc(xml);
            XPath xp = XPathFactory.newInstance().newXPath();
            NodeList nodes = (NodeList) xp.evaluate(
                    "//*[@name or @Name]", d, XPathConstants.NODESET);
            for (int i = 0; i < nodes.getLength(); i++) {
                Node n = nodes.item(i);
                Node nameAttr = n.getAttributes().getNamedItem("name");
                if (nameAttr == null) {
                    nameAttr = n.getAttributes().getNamedItem("Name");
                }
                if (nameAttr != null) {
                    map.put(nameAttr.getTextContent(), n.getTextContent());
                }
            }
            return map;
        } catch (Exception e) {
            throw new IllegalStateException("attribute parse failed: " + e.getMessage(), e);
        }
    }
}
