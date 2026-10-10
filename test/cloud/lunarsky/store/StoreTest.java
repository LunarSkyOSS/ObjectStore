package cloud.lunarsky.store;
import java.nio.file.*;
import java.io.*;
import java.util.Arrays;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;

public final class StoreTest {
    interface Operation {void run() throws Exception;}
    static void fails(int status,Operation operation)throws Exception{
        try {
            operation.run();
            throw new AssertionError("Expected " + status);
        } catch (StoreException error) {
            if (error.status != status) throw error;
        }
    }
    static ObjectStorage.Metadata put(DiskStore store,String key,byte[] body,boolean only)throws Exception{
        return store.put("test",key,new ByteArrayInputStream(body),body.length,SigV4.hex(SigV4.hash(body)),null,only,"application/octet-stream");
    }
    private static void testSignature() throws Exception {
        var headers=new com.sun.net.httpserver.Headers();
        headers.set("host","examplebucket.s3.amazonaws.com");
        headers.set("range","bytes=0-9");
        headers.set("x-amz-date","20130524T000000Z");
        headers.set("x-amz-content-sha256","e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        headers.set("authorization","AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request,SignedHeaders=host;range;x-amz-content-sha256;x-amz-date,Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41");
        var clock=java.time.Clock.fixed(java.time.Instant.parse("2013-05-24T00:00:00Z"),java.time.ZoneOffset.UTC);
        var auth=new SigV4("AKIAIOSFODNN7EXAMPLE","wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY","us-east-1",clock);
        var uri=java.net.URI.create("/test.txt");
        auth.verify("GET",uri,headers);
        fails(403,()->auth.verify("GET",java.net.URI.create("/other.txt"),headers));
        fails(403,()->auth.verify("DELETE",uri,headers));
        fails(403,()->new SigV4("AKIAIOSFODNN7EXAMPLE","wrong","us-east-1",clock).verify("GET",uri,headers));
        fails(403,()->new SigV4("AKIAIOSFODNN7EXAMPLE","wrong","us-east-1",java.time.Clock.systemUTC()).verify("GET",uri,headers));
        headers.add("host","duplicate");
        fails(403,()->auth.verify("GET",uri,headers));
        var presignedHeaders = new com.sun.net.httpserver.Headers();
        presignedHeaders.set("host", "examplebucket.s3.amazonaws.com");
        var presigned = java.net.URI.create("/test.txt?X-Amz-Algorithm=AWS4-HMAC-SHA256" +
            "&X-Amz-Credential=AKIAIOSFODNN7EXAMPLE%2F20130524%2Fus-east-1%2Fs3%2Faws4_request" +
            "&X-Amz-Date=20130524T000000Z&X-Amz-Expires=86400&X-Amz-SignedHeaders=host" +
            "&X-Amz-Signature=aeeed9bbccd4d02ee5c0109b86d86835f995330da4c265957d157751f604d404");
        if (!"UNSIGNED-PAYLOAD".equals(auth.verifyRequest("GET", presigned, presignedHeaders).payload()))
            throw new AssertionError("Official presigned URL was not accepted");
        fails(403, () -> auth.verifyRequest("PUT", presigned, presignedHeaders));
        fails(403, () -> new SigV4("AKIAIOSFODNN7EXAMPLE",
            "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY", "us-east-1",
            java.time.Clock.fixed(java.time.Instant.parse("2013-05-25T00:00:01Z"),
                java.time.ZoneOffset.UTC)).verifyRequest("GET", presigned, presignedHeaders));
        System.out.println("SigV4 official vector and tampering tests passed");
    }

    private static void testAwsChunked() throws Exception {
        String secret = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY";
        String date = "20130524T000000Z";
        String scope = "20130524/us-east-1/s3/aws4_request";
        byte[] key = SigV4.signingKey(secret, "20130524", "us-east-1");
        byte[] body = new byte[66560];
        Arrays.fill(body, (byte) 'a');
        var encoded = new ByteArrayOutputStream();
        encoded.write("10000;chunk-signature=ad80c730a21e5b8d04586a2213dd63b9a0e99e0e2307b0ade35a65485a288648\r\n".getBytes());
        encoded.write(body, 0, 65536);
        encoded.write("\r\n400;chunk-signature=0055627c9e194cb4542bae2aa5492e3c1575bbb81b612b7d234b86a503ef5497\r\n".getBytes());
        encoded.write(body, 65536, 1024);
        encoded.write("\r\n0;chunk-signature=b6c6ea8a5354eaf15b3cb7646744f4275b71ea724fed81ceb9323e279d449df9\r\n\r\n".getBytes());
        var authorization = new SigV4.Verified("STREAMING-AWS4-HMAC-SHA256-PAYLOAD", "", key,
            date, scope, "4f232c4386841ef735655705268965c44a0e4690baa4adea153f7db9fa80a0a9");
        try (var stream = new AwsChunkedInputStream(new ByteArrayInputStream(encoded.toByteArray()),
                authorization, body.length, null)) {
            if (!Arrays.equals(body, stream.readAllBytes())) throw new AssertionError("Signed chunk vector mismatch");
        }
        byte[] tampered = encoded.toByteArray();
        tampered[100] = 'b';
        fails(400, () -> {
            try (var stream = new AwsChunkedInputStream(new ByteArrayInputStream(tampered),
                    authorization, body.length, null)) { stream.readAllBytes(); }
        });
        var withTrailer = new ByteArrayOutputStream();
        withTrailer.write("10000;chunk-signature=b474d8862b1487a5145d686f57f013e54db672cee1c953b3010fb58501ef5aa2\r\n".getBytes());
        withTrailer.write(body, 0, 65536);
        withTrailer.write("\r\n400;chunk-signature=1c1344b170168f8e65b41376b44b20fe354e373826ccbbe2c1d40a8cae51e5c7\r\n".getBytes());
        withTrailer.write(body, 65536, 1024);
        withTrailer.write("\r\n0;chunk-signature=2ca2aba2005185cf7159c6277faf83795951dd77a3a99e6e65d5c9f85863f992\r\n".getBytes());
        withTrailer.write("x-amz-checksum-crc32c:sOO8/Q==\r\n".getBytes());
        withTrailer.write("x-amz-trailer-signature=d81f82fc3505edab99d459891051a732e8730629a2e4a59689829ca17fe2e435\r\n\r\n".getBytes());
        var trailerAuthorization = new SigV4.Verified("STREAMING-AWS4-HMAC-SHA256-PAYLOAD-TRAILER",
            "", key, date, scope, "106e2a8a18243abcf37539882f36619c00e2dfc72633413f02d3b74544bfeb8e");
        try (var stream = new AwsChunkedInputStream(new ByteArrayInputStream(withTrailer.toByteArray()),
                trailerAuthorization, body.length, "x-amz-checksum-crc32c")) {
            if (!Arrays.equals(body, stream.readAllBytes()) || !"sOO8/Q==".equals(stream.trailerValue()))
                throw new AssertionError("Signed checksum trailer vector mismatch");
        }
    }

    private static void testCrc64Nvme() {
        byte[] zeros = new byte[32];
        Crc64Nvme checksum = new Crc64Nvme();
        checksum.update(zeros, 0, zeros.length);
        if (checksum.getValue() != 0xcf3473434d4ecf3bL)
            throw new AssertionError("CRC64NVME test vector mismatch");
    }

    private static void testXxHashes() {
        byte[] body = "abc".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        Map<String, String> vectors = Map.of(
            "XXHASH64", "44bc2cf5ad770999",
            "XXHASH3", "78af5f94892f3950",
            "XXHASH128", "06b05ab6733a618578af5f94892f3950");
        for (var entry : vectors.entrySet()) {
            XxHashes hash = new XxHashes(entry.getKey());
            hash.update(body, 0, 1);
            hash.update(body, 1, 2);
            if (!SigV4.hex(hash.digest()).equals(entry.getValue()))
                throw new AssertionError(entry.getKey() + " test vector mismatch");
        }
    }

    private static void testInitialStore(Path root) throws Exception {
        try(var store=new DiskStore(root,8,10)){
            byte[] body={1,2,3,4,5,6};
            put(store,"../nested/☾",body,true);
            try(var obj=store.open("test","../nested/☾")){if(!Arrays.equals(body,obj.stream().readAllBytes()))throw new AssertionError("Roundtrip");}
            fails(412,()->put(store,"../nested/☾",new byte[]{9},true));
            fails(507,()->put(store,"second",body,true));
            fails(413,()->put(store,"large",new byte[9],true));
            fails(400,()->store.put("test","bad",new ByteArrayInputStream(body),6,"0".repeat(64),null,true,"application/octet-stream"));
            fails(400,()->store.put("test","short",new ByteArrayInputStream(body),7,SigV4.hex(SigV4.hash(body)),null,true,"application/octet-stream"));
            fails(404,()->store.open("test","bad"));
            put(store,"../nested/☾",new byte[]{9},false);
            put(store,"empty",new byte[0],true);
            if(store.list("test","","",100,null).objects().size()!=2)throw new AssertionError("List index");
            if(store.objectCount()!=2||store.legacyObjects()!=0)throw new AssertionError("Object counts");
            try {
                new DiskStore(root,8,10);
                throw new AssertionError("A second process opened the same data directory");
            } catch(IOException expected) {
                if(!expected.getMessage().contains("already in use"))throw expected;
            }
        }
    }

    private static void testRestart(Path root) throws Exception {
        try(var restarted=new DiskStore(root,8,10)){
            try(var obj=restarted.open("test","../nested/☾")){if(obj.stream().read()!=9)throw new AssertionError("Persistence");}
            if(restarted.list("test","","",100,null).objects().size()!=2)throw new AssertionError("Index persistence");
            restarted.delete("test","../nested/☾");
            restarted.delete("test","../nested/☾");
            fails(404,()->restarted.open("test","../nested/☾"));
            var uploads=new MultipartStore(restarted);
            String upload=uploads.create("test","from-parts","text/plain");
            byte[] part={1,2,3};
            uploads.putPart(upload,"test","from-parts",1,new ByteArrayInputStream(part),part.length,
                SigV4.hex(SigV4.hash(part)),null);
            Files.writeString(root.resolve("pending-upload-id"),upload);
        }
    }

    private static void testMultipartRecovery(Path root) throws Exception {
        try(var resumed=new DiskStore(root,8,10)){
            Path unfinished=root.resolve("multipart/.creating-00000000-0000-0000-0000-000000000000");
            Files.createDirectory(unfinished);
            Files.write(unfinished.resolve("manifest"),new byte[]{1,2,3});
            var uploads=new MultipartStore(resumed);
            if(Files.exists(unfinished))throw new AssertionError("Unfinished multipart creation survived restart");
            String upload=Files.readString(root.resolve("pending-upload-id"));
            byte[] part={1,2,3};
            String etag=SigV4.hex(MessageDigest.getInstance("MD5").digest(part));
            uploads.complete(upload,"test","from-parts",List.of(new MultipartStorage.Part(1,etag)));
            try(var obj=resumed.open("test","from-parts")){
                if(!Arrays.equals(part,obj.stream().readAllBytes())||!obj.metadata().contentType().equals("text/plain"))
                    throw new AssertionError("Multipart restart");
            }
            resumed.delete("test","from-parts");
        }
    }

    private static void testLegacyRecord(Path root) throws Exception {
        byte[] old={4,5,6};
        String oldId=SigV4.hex(SigV4.hash("test/legacy".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        Path oldPath=root.resolve("objects").resolve(oldId.substring(0,2)).resolve(oldId);
        Files.createDirectories(oldPath.getParent());
        ByteBuffer oldRecord=ByteBuffer.allocate(72+old.length).putLong(0x4c534f424a303031L)
            .putLong(old.length).putLong(123456789L)
            .put(MessageDigest.getInstance("MD5").digest(old)).put(SigV4.hash(old)).put(old);
        Files.write(oldPath,oldRecord.array());
        try(var migrated=new DiskStore(root,8,10)){
            if(migrated.objectCount()!=2||migrated.legacyObjects()!=1)throw new AssertionError("Legacy counts");
            try(var obj=migrated.open("test","legacy")){
                if(!Arrays.equals(old,obj.stream().readAllBytes()))throw new AssertionError("Legacy read");
            }
            if(migrated.list("test","","",100,null).objects().stream().anyMatch(entry->entry.key().equals("legacy")))
                throw new AssertionError("Legacy object appeared without a stored key");
            put(migrated,"legacy",old,false);
            if(migrated.objectCount()!=2||migrated.legacyObjects()!=0)throw new AssertionError("Legacy count after overwrite");
            if(migrated.list("test","","",100,null).objects().stream().noneMatch(entry->entry.key().equals("legacy")))
                throw new AssertionError("Legacy overwrite was not indexed");
        }
    }

    private static void testCorruption(Path root) throws Exception {
        Files.delete(root.resolve("pending-upload-id"));
        String id=SigV4.hex(SigV4.hash("test/empty".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        Files.write(root.resolve("objects").resolve(id.substring(0,2)).resolve(id),new byte[]{1},StandardOpenOption.APPEND);
        try {
            new DiskStore(root,8,10);
            throw new AssertionError("A damaged object record was accepted");
        } catch(IOException expected) {
            if(!expected.getMessage().contains("object record"))throw expected;
        }
        try(var pending=Files.list(root.resolve("pending"))){if(pending.count()!=0)throw new AssertionError("Pending cleanup");}
    }

    private static void testAttributesRestart(Path root) throws Exception {
        Path data = root.resolve("attributes");
        byte[] body = {1, 2, 3};
        var crc = new Crc64Nvme();
        crc.update(body, 0, body.length);
        byte[] digest = ByteBuffer.allocate(8).putLong(crc.getValue()).array();
        Map<String, String> checksums = Map.of("x-amz-checksum-crc64nvme",
            java.util.Base64.getEncoder().encodeToString(digest));
        try (var store = new DiskStore(data, 8, 10)) {
            store.put("test", "metadata", new ByteArrayInputStream(body), body.length,
                SigV4.hex(SigV4.hash(body)), null, false, "text/plain",
                Map.of("project", "LunarSky"), Map.of("stage", "one"), () -> checksums);
            store.setTags("test", "metadata", Map.of("stage", "two"));
        }
        try (var store = new DiskStore(data, 8, 10);
             var object = store.open("test", "metadata")) {
            if (!Arrays.equals(body, object.stream().readAllBytes()) ||
                !object.metadata().userMetadata().equals(Map.of("project", "LunarSky")) ||
                !object.metadata().tags().equals(Map.of("stage", "two")) ||
                !object.metadata().checksums().equals(checksums))
                throw new AssertionError("Object attributes were lost after restart");
        }
    }

    private static void testV3Record(Path root) throws Exception {
        Path data = root.resolve("v3-record");
        String bucket = "test", key = "v3-object";
        byte[] body = {8, 9, 10};
        byte[] bucketBytes = bucket.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] keyBytes = key.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] typeBytes = "text/plain".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] metadata = ObjectAttributes.encode(Map.of("legacy", "yes"), 4096);
        byte[] tags = ObjectAttributes.encode(Map.of("source", "v3"), 8192);
        String pathHash = SigV4.hex(SigV4.hash((bucket + "/" + key).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        Path path = data.resolve("objects").resolve(pathHash.substring(0, 2)).resolve(pathHash);
        Files.createDirectories(path.getParent());
        ByteBuffer bytes = ByteBuffer.allocate(82 + bucketBytes.length + keyBytes.length +
            typeBytes.length + metadata.length + tags.length + body.length);
        bytes.putLong(0x4c534f424a303033L).putLong(body.length).putLong(123456789L)
            .put(MessageDigest.getInstance("MD5").digest(body)).put(SigV4.hash(body))
            .putShort((short) bucketBytes.length).putShort((short) keyBytes.length)
            .putShort((short) typeBytes.length).putShort((short) metadata.length)
            .putShort((short) tags.length).put(bucketBytes).put(keyBytes).put(typeBytes)
            .put(metadata).put(tags).put(body);
        Files.write(path, bytes.array());
        try (var store = new DiskStore(data, 64, 128)) {
            try (var object = store.open(bucket, key)) {
                if (!Arrays.equals(body, object.stream().readAllBytes()) ||
                    !object.metadata().userMetadata().equals(Map.of("legacy", "yes")) ||
                    !object.metadata().tags().equals(Map.of("source", "v3")))
                    throw new AssertionError("V3 record was not readable");
            }
            store.setTags(bucket, key, Map.of("source", "v4"));
        }
        try (var store = new DiskStore(data, 64, 128);
             var object = store.open(bucket, key)) {
            if (!Arrays.equals(body, object.stream().readAllBytes()) ||
                !object.metadata().tags().equals(Map.of("source", "v4")))
                throw new AssertionError("V3 record upgrade failed");
        }
    }

    private static void testBucketRestart(Path root) throws Exception {
        Path data = root.resolve("buckets");
        byte[] body = {4, 5, 6};
        try (var store = new DiskStore(data, 8, 10)) {
            store.ensureBucket("default-bucket");
            store.createBucket("second-bucket");
            store.put("second-bucket", "object", new ByteArrayInputStream(body), body.length,
                SigV4.hex(SigV4.hash(body)), null, false, "application/octet-stream");
        }
        try (var store = new DiskStore(data, 8, 10);
             var object = store.open("second-bucket", "object")) {
            if (store.buckets().size() != 2 || !Arrays.equals(body, object.stream().readAllBytes()))
                throw new AssertionError("Bucket catalog was lost after restart");
            fails(409, () -> store.deleteBucket("second-bucket"));
            store.delete("second-bucket", "object");
            store.deleteBucket("second-bucket");
            fails(404, () -> store.bucket("second-bucket"));
        }
    }

    private static void testAclPersistence(Path root) throws Exception {
        Path data = root.resolve("acl");
        Map<String, String> grant = Map.of("SECONDARYKEY1234", Integer.toString(Acl.READ));
        byte[] body = {9, 8, 7};
        String older;
        try (var store = new DiskStore(data, 32, 128)) {
            store.ensureBucket("acl-bucket");
            store.setBucketAcl("acl-bucket", grant);
            store.setVersioning("acl-bucket", ObjectStorage.VersioningState.ENABLED);
            older = store.put("acl-bucket", "image", new ByteArrayInputStream(body), body.length,
                SigV4.hex(SigV4.hash(body)), null, false, "image/png",
                Map.of(), Map.of(), Map::of, grant).versionId();
            store.put("acl-bucket", "image", new ByteArrayInputStream(body), body.length,
                SigV4.hex(SigV4.hash(body)), null, false, "image/png");
        }
        try (var store = new DiskStore(data, 32, 128);
             var previous = store.open("acl-bucket", "image", older);
             var current = store.open("acl-bucket", "image")) {
            if (!store.bucket("acl-bucket").acl().equals(grant) ||
                !previous.metadata().acl().equals(grant) || !current.metadata().acl().isEmpty())
                throw new AssertionError("ACL grants changed after restart or version replacement");
            store.setObjectAcl("acl-bucket", "image", older, Map.of(Acl.ALL_USERS, "1"));
        }
        try (var store = new DiskStore(data, 32, 128);
             var previous = store.open("acl-bucket", "image", older)) {
            if (!previous.metadata().acl().equals(Map.of(Acl.ALL_USERS, "1")))
                throw new AssertionError("Version-specific ACL edit was not durable");
        }
    }

    private static void testAdditionalKeys(Path root) throws Exception {
        Path file = root.resolve("access-keys");
        Files.writeString(file, "SECONDARYKEY1234:secondary-secret-key-that-is-at-least-32-characters\n");
        Map<String, String> keys = Main.identities(
            Map.of("S3_CREDENTIALS_FILE", file.toString()),
            "TESTACCESSKEY123", "test-secret-key-that-is-at-least-32-characters");
        if (keys.size() != 2 || !keys.containsKey("SECONDARYKEY1234"))
            throw new AssertionError("Additional access key was not loaded");
        Files.writeString(file, "TESTACCESSKEY123:duplicate-root-secret-that-is-at-least-32-characters\n");
        try {
            Main.identities(Map.of("S3_CREDENTIALS_FILE", file.toString()),
                "TESTACCESSKEY123", "test-secret-key-that-is-at-least-32-characters");
            throw new AssertionError("Duplicate root key was accepted");
        } catch (IllegalArgumentException expected) { }
    }

    private static void testVersioning(Path root) throws Exception {
        Path data = root.resolve("versioning");
        String first;
        String second;
        String marker;
        try (var store = new DiskStore(data, 8, 100)) {
            store.createBucket("versioned-bucket");
            byte[] old = {1};
            store.put("versioned-bucket", "note", new ByteArrayInputStream(old), old.length,
                SigV4.hex(SigV4.hash(old)), null, false, "text/plain");
            store.setVersioning("versioned-bucket", ObjectStorage.VersioningState.ENABLED);
            byte[] newer = {2};
            first = store.put("versioned-bucket", "note", new ByteArrayInputStream(newer), newer.length,
                SigV4.hex(SigV4.hash(newer)), null, false, "text/plain").versionId();
            byte[] latest = {3};
            second = store.put("versioned-bucket", "note", new ByteArrayInputStream(latest), latest.length,
                SigV4.hex(SigV4.hash(latest)), null, false, "text/plain").versionId();
            if (first == null || second == null || first.equals(second)) throw new AssertionError("Unique versions");
            if (store.usedBytes() != 3) throw new AssertionError("Retained versions did not count toward capacity");
            try (var object = store.open("versioned-bucket", "note", first)) {
                if (object.stream().read() != 2) throw new AssertionError("Old version was overwritten");
            }
            marker = store.delete("versioned-bucket", "note", null).versionId();
            fails(404, () -> store.open("versioned-bucket", "note"));
            if (!store.list("versioned-bucket", "", "", 10, null).objects().isEmpty())
                throw new AssertionError("Delete marker appeared in current listing");
            var page = store.listVersions("versioned-bucket", "", null, null, 2);
            if (!page.truncated() || !page.entries().getFirst().deleteMarker() ||
                !page.entries().get(1).versionId().equals(second)) throw new AssertionError("Version listing");
            var rest = store.listVersions("versioned-bucket", "", page.nextKey(), page.nextVersionId(), 10);
            if (rest.entries().size() != 2 || !rest.entries().getLast().versionId().equals("null"))
                throw new AssertionError("Version pagination");
            store.delete("versioned-bucket", "note", marker);
            try (var object = store.open("versioned-bucket", "note")) {
                if (object.stream().read() != 3) throw new AssertionError("Delete marker removal");
            }
            store.setVersioning("versioned-bucket", ObjectStorage.VersioningState.SUSPENDED);
            byte[] suspended = {4};
            var nullVersion = store.put("versioned-bucket", "note", new ByteArrayInputStream(suspended),
                suspended.length, SigV4.hex(SigV4.hash(suspended)), null, false, "text/plain");
            if (!"null".equals(nullVersion.versionId())) throw new AssertionError("Suspended null version");
            if (store.usedBytes() != 3) throw new AssertionError("Suspended write did not replace null version");
            try (var object = store.open("versioned-bucket", "note", second)) {
                if (object.stream().read() != 3) throw new AssertionError("Suspension removed a version");
            }
            store.setTags("versioned-bucket", "note", Map.of("stage", "suspended"));
        }
        try (var store = new DiskStore(data, 8, 100)) {
            if (store.bucket("versioned-bucket").versioning() != ObjectStorage.VersioningState.SUSPENDED)
                throw new AssertionError("Versioning state was lost");
            try (var object = store.open("versioned-bucket", "note")) {
                if (object.stream().read() != 4 || !object.metadata().tags().equals(Map.of("stage", "suspended")))
                    throw new AssertionError("Versioned object was lost");
            }
            store.delete("versioned-bucket", "note");
            fails(404, () -> store.open("versioned-bucket", "note"));
            if (store.usedBytes() != 2) throw new AssertionError("Suspended delete did not release null version");
            try (var object = store.open("versioned-bucket", "note", second)) {
                if (object.stream().read() != 3) throw new AssertionError("Suspended delete removed old version");
            }
            fails(409, () -> store.deleteBucket("versioned-bucket"));
            for (var entry : store.listVersions("versioned-bucket", "", null, null, 10).entries())
                store.delete("versioned-bucket", "note", entry.versionId());
            if (store.usedBytes() != 0) throw new AssertionError("Version deletes did not release capacity");
            store.deleteBucket("versioned-bucket");
        }
    }

    public static void main(String[] args) throws Exception {
        testSignature();
        testAwsChunked();
        testCrc64Nvme();
        testXxHashes();
        Path root = Files.createTempDirectory("store-test-");
        try {
            testInitialStore(root);
            testRestart(root);
            testMultipartRecovery(root);
            testLegacyRecord(root);
            testAttributesRestart(root);
            testV3Record(root);
            testBucketRestart(root);
            testAclPersistence(root);
            testAdditionalKeys(root);
            testVersioning(root);
            testCorruption(root);
            System.out.println("Java storage tests passed: roundtrip, quota, indexing, persistence, multipart recovery, legacy reads, locking, corruption, delete");
        } finally {
            try (var paths = Files.walk(root)) {
                for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}
