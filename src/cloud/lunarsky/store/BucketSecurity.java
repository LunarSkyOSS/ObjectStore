package cloud.lunarsky.store;

import com.sun.net.httpserver.Headers;
import java.io.ByteArrayInputStream;
import java.util.Map;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;

final class BucketSecurity {
    private BucketSecurity() { }

    static String ownershipName(ObjectStorage.ObjectOwnership ownership) {
        return switch (ownership) {
            case BUCKET_OWNER_ENFORCED -> "BucketOwnerEnforced";
            case BUCKET_OWNER_PREFERRED -> "BucketOwnerPreferred";
            case OBJECT_WRITER -> "ObjectWriter";
        };
    }

    static ObjectStorage.ObjectOwnership ownership(String value) {
        return switch (value) {
            case "BucketOwnerEnforced" -> ObjectStorage.ObjectOwnership.BUCKET_OWNER_ENFORCED;
            case "BucketOwnerPreferred" -> ObjectStorage.ObjectOwnership.BUCKET_OWNER_PREFERRED;
            case "ObjectWriter" -> ObjectStorage.ObjectOwnership.OBJECT_WRITER;
            default -> throw new StoreException(400, "InvalidArgument", "Invalid object ownership setting");
        };
    }

    static String ownershipXml(ObjectStorage.ObjectOwnership ownership) {
        return "<OwnershipControls xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">" +
            "<Rule><ObjectOwnership>" + ownershipName(ownership) +
            "</ObjectOwnership></Rule></OwnershipControls>";
    }

    static ObjectStorage.ObjectOwnership parseOwnership(byte[] body) {
        Element root = root(body, "OwnershipControls");
        Element rule = onlyChild(root, "Rule");
        return ownership(onlyChild(rule, "ObjectOwnership").getTextContent().trim());
    }

    static String publicAccessXml(ObjectStorage.PublicAccessBlock block) {
        return "<PublicAccessBlockConfiguration xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">" +
            "<BlockPublicAcls>" + block.blockPublicAcls() + "</BlockPublicAcls>" +
            "<IgnorePublicAcls>" + block.ignorePublicAcls() + "</IgnorePublicAcls>" +
            "<BlockPublicPolicy>" + block.blockPublicPolicy() + "</BlockPublicPolicy>" +
            "<RestrictPublicBuckets>" + block.restrictPublicBuckets() +
            "</RestrictPublicBuckets></PublicAccessBlockConfiguration>";
    }

    static ObjectStorage.PublicAccessBlock parsePublicAccess(byte[] body) {
        Element root = root(body, "PublicAccessBlockConfiguration");
        if (elements(root) != 4) malformed();
        return new ObjectStorage.PublicAccessBlock(
            bool(onlyNamedChild(root, "BlockPublicAcls")),
            bool(onlyNamedChild(root, "IgnorePublicAcls")),
            bool(onlyNamedChild(root, "BlockPublicPolicy")),
            bool(onlyNamedChild(root, "RestrictPublicBuckets")));
    }

    static void checkAcl(ObjectStorage.Bucket bucket, Headers headers, Map<String, String> acl,
                         boolean aclOperation) {
        if (bucket.ownership() == ObjectStorage.ObjectOwnership.BUCKET_OWNER_ENFORCED) {
            String canned = SigV4.single(headers, "x-amz-acl");
            if (aclOperation || !acl.isEmpty() ||
                canned != null && !canned.equals("bucket-owner-full-control"))
                throw new StoreException(400, "AccessControlListNotSupported", "ACLs are disabled for this bucket");
        }
        if (bucket.publicAccessBlock().blockPublicAcls() && isPublic(acl))
            throw new StoreException(403, "AccessDenied", "Public ACLs are blocked for this bucket");
    }

    static boolean allowsAcl(ObjectStorage.Bucket bucket, Map<String, String> acl,
                             String principal, String owner, int permission) {
        if (bucket.ownership() == ObjectStorage.ObjectOwnership.BUCKET_OWNER_ENFORCED)
            return owner.equals(principal);
        if (bucket.publicAccessBlock().ignorePublicAcls() && isPublicPrincipal(principal))
            return false;
        if (bucket.publicAccessBlock().ignorePublicAcls())
            acl = acl.entrySet().stream()
                .filter(entry -> !isPublicPrincipal(entry.getKey()))
                .collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
        return Acl.allows(acl, principal, owner, permission);
    }

    static boolean isPublic(Map<String, String> acl) {
        return acl.containsKey(Acl.ALL_USERS) || acl.containsKey(Acl.AUTHENTICATED_USERS);
    }

    private static boolean isPublicPrincipal(String principal) {
        return Acl.ALL_USERS.equals(principal) || Acl.AUTHENTICATED_USERS.equals(principal);
    }

    private static boolean bool(Element element) {
        if (elements(element) != 0) malformed();
        return switch (element.getTextContent().trim().toLowerCase(java.util.Locale.ROOT)) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new StoreException(400, "MalformedXML", "Invalid public access block setting");
        };
    }

    private static Element root(byte[] body, String name) {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setExpandEntityReferences(false);
            Element root = factory.newDocumentBuilder().parse(new ByteArrayInputStream(body)).getDocumentElement();
            if (!name.equals(root.getLocalName())) malformed();
            return root;
        } catch (StoreException error) { throw error; }
        catch (Exception error) { throw new StoreException(400, "MalformedXML", "Invalid bucket configuration"); }
    }

    private static Element onlyChild(Element parent, String name) {
        if (elements(parent) != 1) malformed();
        return onlyNamedChild(parent, name);
    }

    private static Element onlyNamedChild(Element parent, String name) {
        var children = parent.getChildNodes();
        Element found = null;
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element child && name.equals(child.getLocalName())) {
                if (found != null) malformed();
                found = child;
            }
        }
        if (found == null) malformed();
        return found;
    }

    private static int elements(Element parent) {
        int count = 0;
        var children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++)
            if (children.item(i) instanceof Element) count++;
        return count;
    }

    private static void malformed() {
        throw new StoreException(400, "MalformedXML", "Invalid bucket configuration");
    }
}
