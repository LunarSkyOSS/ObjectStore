package cloud.lunarsky.store;

import com.sun.net.httpserver.HttpExchange;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class S3Xml {
    private S3Xml() { }

    static String tagXml(Map<String, String> tags) {
        StringBuilder body = new StringBuilder("<Tagging><TagSet>");
        new java.util.TreeMap<>(tags).forEach((key, value) -> body.append("<Tag><Key>")
            .append(xml(key)).append("</Key><Value>").append(xml(value)).append("</Value></Tag>"));
        return body.append("</TagSet></Tagging>").toString();
    }

    static Map<String, String> parseTags(byte[] body) {
        try {
            var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setExpandEntityReferences(false);
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() {
                @Override public void fatalError(org.xml.sax.SAXParseException error) throws org.xml.sax.SAXException {
                    throw error;
                }
            });
            var document = builder.parse(new ByteArrayInputStream(body));
            var root = document.getDocumentElement();
            if (!root.getTagName().equals("Tagging")) throw new IllegalArgumentException();
            var sets = root.getElementsByTagName("TagSet");
            if (sets.getLength() != 1) throw new IllegalArgumentException();
            for (int i = 0; i < root.getChildNodes().getLength(); i++) {
                var child = root.getChildNodes().item(i);
                if (child instanceof org.w3c.dom.Element && child != sets.item(0))
                    throw new IllegalArgumentException();
            }
            Map<String, String> tags = new java.util.TreeMap<>();
            var entries = sets.item(0).getChildNodes();
            for (int i = 0; i < entries.getLength(); i++) {
                if (!(entries.item(i) instanceof org.w3c.dom.Element tag)) continue;
                if (!tag.getTagName().equals("Tag")) throw new IllegalArgumentException();
                var keys = tag.getElementsByTagName("Key");
                var values = tag.getElementsByTagName("Value");
                if (keys.getLength() != 1 || values.getLength() != 1 ||
                    tag.getElementsByTagName("*").getLength() != 2) throw new IllegalArgumentException();
                if (tags.put(keys.item(0).getTextContent(), values.item(0).getTextContent()) != null)
                    throw new IllegalArgumentException();
            }
            ObjectAttributes.validateTags(tags);
            return Map.copyOf(tags);
        } catch (Exception error) {
            throw new StoreException(400, "MalformedXML", "Invalid object tagging body");
        }
    }
    static List<MultipartStorage.Part> completedParts(byte[] body) {
        try {
            var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setExpandEntityReferences(false);
            var document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(body));
            if (!document.getDocumentElement().getNodeName().equals("CompleteMultipartUpload"))
                throw new IllegalArgumentException();
            var nodes = document.getDocumentElement().getChildNodes();
            List<MultipartStorage.Part> parts = new ArrayList<>();
            for (int i = 0; i < nodes.getLength(); i++) {
                if (!(nodes.item(i) instanceof org.w3c.dom.Element element)) continue;
                if (!element.getTagName().equals("Part")) throw new IllegalArgumentException();
                String number = null, etag = null, checksumCrc32 = null;
                var fields = element.getChildNodes();
                for (int j = 0; j < fields.getLength(); j++) {
                    if (!(fields.item(j) instanceof org.w3c.dom.Element field)) continue;
                    if (field.getTagName().equals("PartNumber") && number == null)
                        number = field.getTextContent().trim();
                    else if (field.getTagName().equals("ETag") && etag == null)
                        etag = field.getTextContent().trim();
                    else if (field.getTagName().equals("ChecksumCRC32") && checksumCrc32 == null)
                        checksumCrc32 = field.getTextContent().trim();
                    else throw new IllegalArgumentException();
                }
                if (number == null || etag == null || !etag.matches("\"?[0-9a-f]{32}\"?"))
                    throw new IllegalArgumentException();
                if (checksumCrc32 != null && java.util.Base64.getDecoder().decode(checksumCrc32).length != 4)
                    throw new IllegalArgumentException();
                parts.add(new MultipartStorage.Part(Integer.parseInt(number), etag, checksumCrc32));
                if (parts.size() > 10000) throw new IllegalArgumentException();
            }
            return parts;
        } catch (Exception error) {
            throw new StoreException(400, "MalformedXML", "Invalid multipart completion body");
        }
    }
    static void sendXml(HttpExchange exchange, int status, String xml) throws IOException {
        byte[] body = ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>" + xml).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/xml");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }
    static ObjectStorage.VersioningState parseVersioning(byte[] body) {
        try {
            var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setExpandEntityReferences(false);
            var document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(body));
            var root = document.getDocumentElement();
            if (!root.getNodeName().equals("VersioningConfiguration")) throw new IllegalArgumentException();
            String status = null;
            for (int i = 0; i < root.getChildNodes().getLength(); i++) {
                var child = root.getChildNodes().item(i);
                if (!(child instanceof org.w3c.dom.Element element)) continue;
                if (!element.getNodeName().equals("Status") || status != null)
                    throw new IllegalArgumentException();
                status = element.getTextContent().trim();
            }
            if ("Enabled".equals(status)) return ObjectStorage.VersioningState.ENABLED;
            if ("Suspended".equals(status)) return ObjectStorage.VersioningState.SUSPENDED;
        } catch (Exception ignored) { }
        throw new StoreException(400, "MalformedXML", "Invalid versioning configuration");
    }
    static void appendListHeader(StringBuilder xml, Map<String, String> query, S3Request.ListRequest request,
                                  ObjectStorage.ListPage page, String bucketName) {
        String prefix = request.prefix(), delimiter = request.delimiter(), encoding = request.encoding();
        xml.append("<Name>").append(xml(bucketName)).append("</Name><Prefix>").append(xml(listKey(prefix, encoding))).append("</Prefix>");
        if (!delimiter.isEmpty()) xml.append("<Delimiter>").append(xml(listKey(delimiter, encoding))).append("</Delimiter>");
        if (encoding != null) xml.append("<EncodingType>url</EncodingType>");
        if (query.containsKey("continuation-token")) xml.append("<ContinuationToken>")
            .append(xml(query.get("continuation-token"))).append("</ContinuationToken>");
        if (query.containsKey("start-after")) xml.append("<StartAfter>")
            .append(xml(listKey(query.get("start-after"), encoding))).append("</StartAfter>");
        xml.append("<KeyCount>").append(page.keyCount()).append("</KeyCount><MaxKeys>").append(request.maxKeys())
            .append("</MaxKeys><IsTruncated>").append(page.truncated()).append("</IsTruncated>");
    }

    static void appendListEntries(StringBuilder xml, ObjectStorage.ListPage page, String encoding) {
        int objectAt = 0, prefixAt = 0;
        while (objectAt < page.objects().size() || prefixAt < page.prefixes().size()) {
            if (objectAt < page.objects().size() &&
                (prefixAt == page.prefixes().size() ||
                 page.objects().get(objectAt).key().compareTo(page.prefixes().get(prefixAt)) < 0)) {
                var entry = page.objects().get(objectAt++);
                var meta = entry.metadata();
                xml.append("<Contents><Key>").append(xml(listKey(entry.key(), encoding))).append("</Key><LastModified>")
                    .append(Instant.ofEpochMilli(meta.modified())).append("</LastModified><ETag>&quot;")
                    .append(meta.etag()).append("&quot;</ETag><Size>").append(meta.length())
                    .append("</Size><StorageClass>STANDARD</StorageClass></Contents>");
            } else {
                xml.append("<CommonPrefixes><Prefix>")
                    .append(xml(listKey(page.prefixes().get(prefixAt++), encoding)))
                    .append("</Prefix></CommonPrefixes>");
            }
        }
    }

    static String listKey(String key, String encoding) {
        return encoding == null ? key : SigV4.encode(key, false);
    }
    static String xml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
    static void sendError(HttpExchange exchange, int status, String code, String message, String id) throws IOException {
        if (exchange.getResponseCode() != -1) return;
        byte[] body = ("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Error><Code>" + xml(code) +
            "</Code><Message>" + xml(message) + "</Message><RequestId>" + id + "</RequestId></Error>")
            .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/xml");
        exchange.sendResponseHeaders(status, exchange.getRequestMethod().equals("HEAD") ? -1 : body.length);
        if (!exchange.getRequestMethod().equals("HEAD")) exchange.getResponseBody().write(body);
    }
}
