package cloud.lunarsky.store;

import com.sun.net.httpserver.Headers;
import java.io.ByteArrayInputStream;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

final class Acl {
    static final String ALL_USERS = "http://acs.amazonaws.com/groups/global/AllUsers";
    static final String AUTHENTICATED_USERS =
        "http://acs.amazonaws.com/groups/global/AuthenticatedUsers";
    static final int READ = 1;
    static final int WRITE = 2;
    static final int READ_ACP = 4;
    static final int WRITE_ACP = 8;
    static final int FULL_CONTROL = READ | WRITE | READ_ACP | WRITE_ACP;
    private static final Map<String, Integer> PERMISSIONS = Map.of(
        "READ", READ, "WRITE", WRITE, "READ_ACP", READ_ACP,
        "WRITE_ACP", WRITE_ACP, "FULL_CONTROL", FULL_CONTROL);

    private Acl() {}

    static boolean allows(Map<String, String> grants, String principal, String owner, int permission) {
        if (owner.equals(principal)) return true;
        if (principal != null && (bits(grants.get(principal)) & permission) == permission) return true;
        if (principal != null && (bits(grants.get(AUTHENTICATED_USERS)) & permission) == permission)
            return true;
        return (bits(grants.get(ALL_USERS)) & permission) == permission;
    }

    static void require(Map<String, String> grants, String principal, String owner, int permission) {
        if (!allows(grants, principal, owner, permission))
            throw new StoreException(403, "AccessDenied", "Access denied");
    }

    static Map<String, String> fromHeaders(Headers headers, Set<String> identities) {
        String canned = SigV4.single(headers, "x-amz-acl");
        Set<String> grantNames = Set.of("x-amz-grant-read", "x-amz-grant-write",
            "x-amz-grant-read-acp", "x-amz-grant-write-acp", "x-amz-grant-full-control");
        for (String name : headers.keySet()) {
            String lower = name.toLowerCase(java.util.Locale.ROOT);
            if (lower.startsWith("x-amz-grant-") && !grantNames.contains(lower))
                throw new StoreException(501, "NotImplemented", "Unsupported ACL grant header");
            if (canned != null && grantNames.contains(lower))
                throw new StoreException(400, "InvalidRequest", "Use either a canned ACL or explicit grants");
        }
        TreeMap<String, Integer> grants = new TreeMap<>();
        if (canned != null) {
            if (!canned.equals("private") && !canned.equals("public-read") &&
                !canned.equals("authenticated-read") &&
                !canned.equals("bucket-owner-full-control"))
                throw new StoreException(400, "InvalidArgument", "Unsupported canned ACL");
            if (canned.equals("public-read")) grants.put(ALL_USERS, READ);
            if (canned.equals("authenticated-read")) grants.put(AUTHENTICATED_USERS, READ);
        }
        for (String name : new String[]{"read", "write", "read-acp", "write-acp", "full-control"}) {
            String value = SigV4.single(headers, "x-amz-grant-" + name);
            if (value == null) continue;
            int permission = PERMISSIONS.get(name.replace('-', '_').toUpperCase(java.util.Locale.ROOT));
            for (String grant : value.split(",")) {
                String entry = grant.trim();
                String principal;
                if (entry.startsWith("id=\"") && entry.endsWith("\""))
                    principal = entry.substring(4, entry.length() - 1);
                else if (entry.startsWith("uri=\"") && entry.endsWith("\""))
                    principal = entry.substring(5, entry.length() - 1);
                else throw new StoreException(400, "InvalidArgument", "Invalid ACL grant");
                if (!identities.contains(principal) && !principal.equals(ALL_USERS) &&
                    !principal.equals(AUTHENTICATED_USERS))
                    throw new StoreException(400, "InvalidArgument", "Unknown ACL grantee");
                if (principal.equals(ALL_USERS) && permission != READ)
                    throw new StoreException(400, "InvalidArgument", "Only public read is supported");
                grants.merge(principal, permission, (left, right) -> left | right);
            }
        }
        return encoded(grants);
    }

    static Map<String, String> validate(Map<String, String> values, Set<String> identities) {
        if (values.size() > 64) throw new StoreException(400, "InvalidArgument", "Too many ACL grantees");
        TreeMap<String, String> valid = new TreeMap<>();
        for (var entry : values.entrySet()) {
            String principal = entry.getKey();
            int bits = bits(entry.getValue());
            if ((!identities.contains(principal) && !principal.equals(ALL_USERS) &&
                 !principal.equals(AUTHENTICATED_USERS)) ||
                bits == 0 || (bits & ~FULL_CONTROL) != 0 ||
                principal.equals(ALL_USERS) && bits != READ)
                throw new StoreException(400, "InvalidArgument", "Invalid ACL grantee or permission");
            valid.put(principal, Integer.toString(bits));
        }
        return Map.copyOf(valid);
    }

    static Map<String, String> fromXml(byte[] body, String owner, Set<String> identities) {
        try {
            var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setExpandEntityReferences(false);
            var document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(body));
            var root = document.getDocumentElement();
            if (!root.getLocalName().equals("AccessControlPolicy")) throw new IllegalArgumentException();
            var ownerNodes = root.getElementsByTagNameNS("*", "Owner");
            var lists = root.getElementsByTagNameNS("*", "AccessControlList");
            if (ownerNodes.getLength() != 1 || lists.getLength() != 1 ||
                !owner.equals(text((org.w3c.dom.Element) ownerNodes.item(0), "ID")))
                throw new IllegalArgumentException();
            TreeMap<String, Integer> grants = new TreeMap<>();
            var nodes = ((org.w3c.dom.Element) lists.item(0)).getElementsByTagNameNS("*", "Grant");
            if (nodes.getLength() > 64) throw new IllegalArgumentException();
            for (int i = 0; i < nodes.getLength(); i++) {
                var grant = (org.w3c.dom.Element) nodes.item(i);
                var grantees = grant.getElementsByTagNameNS("*", "Grantee");
                if (grantees.getLength() != 1) throw new IllegalArgumentException();
                var grantee = (org.w3c.dom.Element) grantees.item(0);
                String type = grantee.getAttributeNS("http://www.w3.org/2001/XMLSchema-instance", "type");
                String principal = switch (type) {
                    case "CanonicalUser" -> text(grantee, "ID");
                    case "Group" -> text(grantee, "URI");
                    default -> throw new IllegalArgumentException();
                };
                Integer permission = PERMISSIONS.get(text(grant, "Permission"));
                if (permission == null) throw new IllegalArgumentException();
                grants.merge(principal, permission, (left, right) -> left | right);
            }
            grants.remove(owner);
            return validate(encoded(grants), identities);
        } catch (Exception error) {
            throw new StoreException(400, "MalformedACLError", "Invalid access control policy");
        }
    }

    private static String text(org.w3c.dom.Element element, String name) {
        var nodes = element.getElementsByTagNameNS("*", name);
        if (nodes.getLength() != 1) throw new IllegalArgumentException();
        return nodes.item(0).getTextContent().trim();
    }

    static String xml(Map<String, String> grants, String owner) {
        StringBuilder xml = new StringBuilder("<AccessControlPolicy xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"><Owner><ID>")
            .append(owner).append("</ID></Owner><AccessControlList>");
        grant(xml, owner, "FULL_CONTROL", false);
        for (var entry : new TreeMap<>(grants).entrySet()) {
            if (entry.getKey().equals(owner)) continue;
            int bits = bits(entry.getValue());
            if (bits == FULL_CONTROL) grant(xml, entry.getKey(), "FULL_CONTROL",
                entry.getKey().equals(ALL_USERS) || entry.getKey().equals(AUTHENTICATED_USERS));
            else for (var permission : PERMISSIONS.entrySet())
                if (!permission.getKey().equals("FULL_CONTROL") && (bits & permission.getValue()) != 0)
                    grant(xml, entry.getKey(), permission.getKey(),
                        entry.getKey().equals(ALL_USERS) || entry.getKey().equals(AUTHENTICATED_USERS));
        }
        return xml.append("</AccessControlList></AccessControlPolicy>").toString();
    }

    private static void grant(StringBuilder xml, String principal, String permission, boolean group) {
        xml.append("<Grant><Grantee xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xsi:type=\"")
            .append(group ? "Group\"><URI>" : "CanonicalUser\"><ID>")
            .append(principal).append(group ? "</URI>" : "</ID>")
            .append("</Grantee><Permission>").append(permission).append("</Permission></Grant>");
    }

    private static Map<String, String> encoded(Map<String, Integer> grants) {
        TreeMap<String, String> values = new TreeMap<>();
        grants.forEach((key, value) -> values.put(key, Integer.toString(value)));
        return Map.copyOf(values);
    }

    private static int bits(String value) {
        if (value == null) return 0;
        try { return Integer.parseInt(value); }
        catch (NumberFormatException error) { return 0; }
    }
}
