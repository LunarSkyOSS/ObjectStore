package cloud.lunarsky.store;
import java.nio.file.*;
import java.io.*;
import java.util.Arrays;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.List;

public final class StoreTest {
    interface Operation {void run() throws Exception;}
    static void fails(int status,Operation operation)throws Exception{
        try{operation.run();throw new AssertionError("Expected "+status);}catch(StoreException error){if(error.status!=status)throw error;}
    }
    static ObjectStorage.Metadata put(DiskStore store,String key,byte[] body,boolean only)throws Exception{
        return store.put("test",key,new ByteArrayInputStream(body),body.length,SigV4.hex(SigV4.hash(body)),null,only,"application/octet-stream");
    }
    public static void main(String[] args)throws Exception{
        var headers=new com.sun.net.httpserver.Headers();
        headers.set("host","examplebucket.s3.amazonaws.com");headers.set("range","bytes=0-9");
        headers.set("x-amz-date","20130524T000000Z");
        headers.set("x-amz-content-sha256","e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        headers.set("authorization","AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request,SignedHeaders=host;range;x-amz-content-sha256;x-amz-date,Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41");
        var clock=java.time.Clock.fixed(java.time.Instant.parse("2013-05-24T00:00:00Z"),java.time.ZoneOffset.UTC);
        var auth=new SigV4("AKIAIOSFODNN7EXAMPLE","wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY","us-east-1",clock);
        var uri=java.net.URI.create("/test.txt");auth.verify("GET",uri,headers);
        fails(403,()->auth.verify("GET",java.net.URI.create("/other.txt"),headers));
        fails(403,()->auth.verify("DELETE",uri,headers));
        fails(403,()->new SigV4("AKIAIOSFODNN7EXAMPLE","wrong","us-east-1",clock).verify("GET",uri,headers));
        fails(403,()->new SigV4("AKIAIOSFODNN7EXAMPLE","wrong","us-east-1",java.time.Clock.systemUTC()).verify("GET",uri,headers));
        headers.add("host","duplicate");fails(403,()->auth.verify("GET",uri,headers));
        System.out.println("SigV4 official vector and tampering tests passed");
        Path root=Files.createTempDirectory("store-test-");
        try{
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
            try(var restarted=new DiskStore(root,8,10)){
                try(var obj=restarted.open("test","../nested/☾")){if(obj.stream().read()!=9)throw new AssertionError("Persistence");}
                if(restarted.list("test","","",100,null).objects().size()!=2)throw new AssertionError("Index persistence");
                restarted.delete("test","../nested/☾");restarted.delete("test","../nested/☾");
                fails(404,()->restarted.open("test","../nested/☾"));
                var uploads=new MultipartStore(restarted);
                String upload=uploads.create("test","from-parts","text/plain");
                byte[] part={1,2,3};
                uploads.putPart(upload,"test","from-parts",1,new ByteArrayInputStream(part),part.length,
                    SigV4.hex(SigV4.hash(part)),null);
                Files.writeString(root.resolve("pending-upload-id"),upload);
            }
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
            System.out.println("Java storage tests passed: roundtrip, quota, indexing, persistence, multipart recovery, legacy reads, locking, corruption, delete");
        }finally{try(var paths=Files.walk(root)){for(var p:paths.sorted(java.util.Comparator.reverseOrder()).toList())Files.delete(p);}}
    }
}
