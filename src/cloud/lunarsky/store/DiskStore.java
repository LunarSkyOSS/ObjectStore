package cloud.lunarsky.store;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;

final class DiskStore implements AutoCloseable {
    private static final long MAGIC = 0x4c534f424a303031L;
    private static final int HEADER = 72;
    private final Path objects, temporary;
    private final FileChannel lockChannel;
    private final FileLock processLock;
    private final long maxObject, maxTotal;
    private final Object[] locks = new Object[128];
    private long used;
    record Metadata(long length, long modified, String etag, byte[] sha256) {}
    record OpenObject(Metadata metadata, InputStream stream) implements AutoCloseable {
        public void close() throws IOException { stream.close(); }
    }

    DiskStore(Path root, long maxObject, long maxTotal) throws IOException {
        objects = root.resolve("objects"); temporary = root.resolve("pending");
        this.maxObject = maxObject; this.maxTotal = maxTotal;
        Arrays.setAll(locks, i -> new Object());
        Files.createDirectories(root);
        FileChannel channel = FileChannel.open(root.resolve(".process.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock acquired = null;
        boolean ready = false;
        try {
            try { acquired = channel.tryLock(); }
            catch (OverlappingFileLockException e) { throw new IOException("Data directory is already in use", e); }
            if (acquired == null) throw new IOException("Data directory is already in use");
            Files.createDirectories(objects); Files.createDirectories(temporary);
            try (var paths=Files.list(temporary)) {
                for(Path p:paths.toList()) if(p.getFileName().toString().endsWith(".part"))Files.delete(p);
            }
            try (var paths=Files.walk(objects)) {
                for(Path p:paths.filter(Files::isRegularFile).toList()) {
                    try(var in=new DataInputStream(Files.newInputStream(p))) {
                        long length = metadata(in).length();
                        if(Files.size(p)-HEADER != length) throw new IOException("Truncated or oversized object record: "+p);
                        used = Math.addExact(used, length);
                    }
                }
            }
            ready = true;
        } finally {
            if (!ready) {
                if (acquired != null) acquired.release();
                channel.close();
            }
        }
        lockChannel = channel;
        processLock = acquired;
    }

    @Override public void close() throws IOException {
        processLock.release();
        lockChannel.close();
    }

    private Path object(String bucket, String key) throws IOException {
        String id=SigV4.hex(SigV4.hash((bucket+"/"+key).getBytes(StandardCharsets.UTF_8)));
        Path shard=objects.resolve(id.substring(0,2));Files.createDirectories(shard);
        return shard.resolve(id);
    }
    private Object lock(Path p){return locks[(p.hashCode()&0x7fffffff)%locks.length];}

    Metadata put(String bucket,String key,InputStream input,long length,String expectedHash,String checksum,boolean createOnly) throws IOException {
        if(length<0)throw new StoreException(411,"MissingContentLength","Content-Length is required");
        if(length>maxObject)throw new StoreException(413,"EntityTooLarge","Object exceeds the configured size limit");
        Path destination=object(bucket,key),pending=Files.createTempFile(temporary,"upload-",".part");
        try {
            MessageDigest sha=digest("SHA-256"),md5=digest("MD5");
            long count=0;
            try(OutputStream out=Files.newOutputStream(pending)){
                out.write(new byte[HEADER]);byte[] buffer=new byte[65536];int n;
                while((n=input.read(buffer))!=-1){count+=n;if(count>length||count>maxObject)throw new StoreException(413,"EntityTooLarge","Payload exceeds declared size");sha.update(buffer,0,n);md5.update(buffer,0,n);out.write(buffer,0,n);}
            }
            if(count!=length)throw new StoreException(400,"IncompleteBody","Payload length does not match Content-Length");
            byte[] hash=sha.digest(),etag=md5.digest();
            if(!MessageDigest.isEqual(hash,HexFormat.of().parseHex(expectedHash)))throw new StoreException(400,"XAmzContentSHA256Mismatch","Payload hash mismatch");
            if(checksum!=null&&!java.util.Base64.getEncoder().encodeToString(hash).equals(checksum))throw new StoreException(400,"BadDigest","SHA-256 checksum mismatch");
            long modified=Instant.now().toEpochMilli();
            try(FileChannel file=FileChannel.open(pending,StandardOpenOption.WRITE)){
                ByteBuffer header=ByteBuffer.allocate(HEADER).putLong(MAGIC).putLong(count).putLong(modified).put(etag).put(hash);header.flip();
                while(header.hasRemaining())file.write(header);file.force(true);
            }
            synchronized(lock(destination)){
                long previous=0;
                if(Files.exists(destination)){
                    if(createOnly)throw new StoreException(412,"PreconditionFailed","Object already exists");
                    try(var in=new DataInputStream(Files.newInputStream(destination))){previous=metadata(in).length();}
                }
                synchronized(this){
                    if(used-previous+count>maxTotal)throw new StoreException(507,"InsufficientStorage","Store capacity limit reached");
                    Files.move(pending,destination,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
                    used=used-previous+count;
                }
            }
            return new Metadata(count,modified,SigV4.hex(etag),hash);
        } finally {Files.deleteIfExists(pending);}
    }

    OpenObject open(String bucket,String key) throws IOException {
        Path destination=object(bucket,key);
        synchronized(lock(destination)){
            final DataInputStream input;
            try{input=new DataInputStream(Files.newInputStream(destination));}
            catch(NoSuchFileException e){throw new StoreException(404,"NoSuchKey","Object not found");}
            try{return new OpenObject(metadata(input),input);}catch(IOException e){input.close();throw e;}
        }
    }
    void delete(String bucket,String key) throws IOException {
        Path destination=object(bucket,key);
        synchronized(lock(destination)){
            if(!Files.exists(destination))return;
            long length;try(var input=new DataInputStream(Files.newInputStream(destination))){length=metadata(input).length();}
            synchronized(this){Files.delete(destination);used-=length;}
        }
    }
    private static Metadata metadata(DataInputStream in) throws IOException {
        if(in.readLong()!=MAGIC)throw new IOException("Invalid object record");
        long length=in.readLong(),modified=in.readLong();byte[] md5=new byte[16],sha=new byte[32];in.readFully(md5);in.readFully(sha);
        if(length<0)throw new IOException("Invalid object length");
        return new Metadata(length,modified,SigV4.hex(md5),sha);
    }
    private static MessageDigest digest(String algorithm){try{return MessageDigest.getInstance(algorithm);}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
}
