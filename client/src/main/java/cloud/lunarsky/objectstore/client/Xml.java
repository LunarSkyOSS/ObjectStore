package cloud.lunarsky.objectstore.client;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

final class Xml {
    private Xml() { }

    static Document parse(byte[] bytes) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            return factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
        } catch (ParserConfigurationException | SAXException | IllegalArgumentException e) {
            throw new ProtocolException("Malformed or unsafe XML response", e);
        }
    }

    static Element child(Element element, String name) {
        for (Node node = element.getFirstChild(); node != null; node = node.getNextSibling())
            if (node instanceof Element found && name.equals(found.getLocalName())) return found;
        return null;
    }

    static String text(Element element, String name) {
        Element found = child(element, name);
        return found == null ? null : found.getTextContent();
    }

    static NodeList descendants(Element element, String name) {
        return element.getElementsByTagNameNS("*", name);
    }

    static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&apos;");
    }
}
