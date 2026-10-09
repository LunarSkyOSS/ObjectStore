package cloud.lunarsky.store;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

public final class Main {
    private final DiskStore store;
    private final SigV4 authentication;
    private final String bucket;
    private final Semaphore slots=new Semaphore(16);
    Main(DiskStore store,SigV4 authentication,String bucket){this.store=store;this.authentication=authentication;this.bucket=bucket;}

    void handle(HttpExchange exchange) throws IOException {
        boolean admitted=slots.tryAcquire();
        String requestId=UUID.randomUUID().toString();
        exchange.getResponseHeaders().set("x-amz-request-id",requestId);
        exchange.getResponseHeaders().set("X-Content-Type-Options","nosniff");
        try {
            if(!admitted)throw new StoreException(503,"SlowDown","Too many concurrent requests");
            if(exchange.getRequestURI().getRawPath().equals("/health")&&exchange.getRequestMethod().equals("GET")){
                byte[] body="{\"status\":\"ok\",\"service\":\"lunarsky-objectstore\"}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);return;
            }
            String hash=authentication.verify(exchange.getRequestMethod(),exchange.getRequestURI(),exchange.getRequestHeaders());
            String path=SigV4.decode(exchange.getRequestURI().getRawPath());
            String prefix="/"+bucket+"/";
            if(!path.startsWith(prefix))throw new StoreException(404,"NoSuchBucket","Bucket not found");
            String key=path.substring(prefix.length());
            if(key.isEmpty()||key.getBytes(StandardCharsets.UTF_8).length>1024||key.indexOf('\0')>=0)throw new StoreException(400,"InvalidArgument","Invalid object key");
            String query=exchange.getRequestURI().getRawQuery();
            if(query!=null&&!query.isEmpty()&&!query.matches("x-id=(PutObject|GetObject|HeadObject|DeleteObject)"))unsupported("Query operation");
            var headers=exchange.getRequestHeaders();
            if(headers.containsKey("range")||headers.containsKey("if-match")||headers.containsKey("if-modified-since")||headers.containsKey("if-unmodified-since"))unsupported("Range or conditional read");
            for(String name:headers.keySet()){
                String lower=name.toLowerCase(java.util.Locale.ROOT);
                if(lower.startsWith("x-amz-")&&!java.util.Set.of("x-amz-date","x-amz-content-sha256","x-amz-checksum-sha256","x-amz-sdk-checksum-algorithm","x-amz-user-agent").contains(lower))unsupported("Amazon header");
                if(lower.startsWith("x-amz-meta-")||lower.startsWith("x-amz-server-side-")||lower.startsWith("x-amz-copy-")||lower.startsWith("x-amz-acl")||lower.startsWith("x-amz-grant")||lower.startsWith("x-amz-tagging")||lower.equals("content-md5"))unsupported("Object metadata, encryption, ACL, copy, tagging or MD5 header");
                if(lower.startsWith("x-amz-checksum-")&&!lower.equals("x-amz-checksum-sha256"))unsupported("Checksum algorithm");
            }
            String method=exchange.getRequestMethod();
            String algorithm=SigV4.single(headers,"x-amz-sdk-checksum-algorithm");
            if(algorithm!=null&&!algorithm.equals("SHA256"))unsupported("Checksum algorithm");
            if(!method.equals("PUT")&&(headers.containsKey("transfer-encoding")||(headers.containsKey("content-length")&&!"0".equals(SigV4.single(headers,"content-length")))))throw new StoreException(400,"InvalidRequest","Read/delete requests must have empty bodies");
            if(!method.equals("PUT")&&!hash.equals(SigV4.hex(SigV4.hash(new byte[0]))))throw new StoreException(400,"InvalidRequest","Read/delete requests must have empty bodies");
            switch(method){
                case "PUT" -> {
                    String length=SigV4.single(headers,"content-length"),condition=SigV4.single(headers,"if-none-match");
                    if(condition!=null&&!condition.equals("*"))unsupported("Write condition");
                    long bytes;try{bytes=length==null?-1:Long.parseLong(length);}catch(NumberFormatException e){throw new StoreException(400,"InvalidArgument","Invalid Content-Length");}
                    if(headers.containsKey("content-encoding"))unsupported("Encoded payload");
                    DiskStore.Metadata data=store.put(bucket,key,exchange.getRequestBody(),bytes,hash,SigV4.single(headers,"x-amz-checksum-sha256"),condition!=null);
                    exchange.getResponseHeaders().set("ETag","\""+data.etag()+"\"");
                    exchange.getResponseHeaders().set("x-amz-checksum-sha256",java.util.Base64.getEncoder().encodeToString(data.sha256()));
                    exchange.sendResponseHeaders(200,-1);
                }
                case "GET", "HEAD" -> {
                    if(headers.containsKey("if-none-match"))unsupported("Conditional read");
                    try(var object=store.open(bucket,key)){
                        var meta=object.metadata();
                        exchange.getResponseHeaders().set("Content-Type","application/octet-stream");
                        exchange.getResponseHeaders().set("Content-Length",Long.toString(meta.length()));
                        exchange.getResponseHeaders().set("ETag","\""+meta.etag()+"\"");
                        exchange.getResponseHeaders().set("Last-Modified",DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC).format(Instant.ofEpochMilli(meta.modified())));
                        if(method.equals("HEAD")||meta.length()==0)exchange.sendResponseHeaders(200,-1);
                        else{exchange.sendResponseHeaders(200,meta.length());object.stream().transferTo(exchange.getResponseBody());}
                    }
                }
                case "DELETE" -> {if(headers.containsKey("if-none-match"))unsupported("Conditional delete");store.delete(bucket,key);exchange.sendResponseHeaders(204,-1);}
                default -> unsupported("HTTP method");
            }
        } catch(StoreException error){sendError(exchange,error.status,error.code,error.getMessage(),requestId);}
        catch(Exception error){System.err.println("ObjectStore request failed: "+requestId+" "+error.getClass().getSimpleName());sendError(exchange,500,"InternalError","Storage operation failed",requestId);}
        finally {if(admitted)slots.release();exchange.close();}
    }
    private static void unsupported(String feature){throw new StoreException(501,"NotImplemented",feature+" is not supported in this prototype");}
    private static String xml(String text){return text.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");}
    private static void sendError(HttpExchange exchange,int status,String code,String message,String id)throws IOException{
        if(exchange.getResponseCode()!=-1)return;
        byte[] body=("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Error><Code>"+xml(code)+"</Code><Message>"+xml(message)+"</Message><RequestId>"+id+"</RequestId></Error>").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type","application/xml");exchange.sendResponseHeaders(status,exchange.getRequestMethod().equals("HEAD")?-1:body.length);
        if(!exchange.getRequestMethod().equals("HEAD"))exchange.getResponseBody().write(body);
    }
    public static void main(String[] args)throws Exception{
        Map<String,String> env=System.getenv();
        String access=required(env,"S3_ACCESS_KEY"),secret=required(env,"S3_SECRET_KEY"),bucket=env.getOrDefault("S3_BUCKET","lunaris-files"),region=env.getOrDefault("S3_REGION","us-east-1");
        if(!access.matches("[A-Za-z0-9]{16,128}")||secret.length()<32||!bucket.matches("[a-z0-9][a-z0-9-]{1,61}[a-z0-9]"))throw new IllegalArgumentException("Invalid storage credentials/bucket configuration");
        long maxObject=Long.parseLong(env.getOrDefault("MAX_OBJECT_BYTES","10485760")),maxTotal=Long.parseLong(env.getOrDefault("MAX_TOTAL_BYTES","2147483648"));
        if(maxObject<1||maxObject>1073741824L||maxTotal<maxObject)throw new IllegalArgumentException("Invalid size limits");
        var store=new DiskStore(Path.of(env.getOrDefault("DATA_DIR","/data")),maxObject,maxTotal);
        var app=new Main(store,new SigV4(access,secret,region,Clock.systemUTC()),bucket);
        var server=HttpServer.create(new InetSocketAddress(Integer.parseInt(env.getOrDefault("PORT","9000"))),64);
        var executor=Executors.newVirtualThreadPerTaskExecutor();server.setExecutor(executor);server.createContext("/",app::handle);
        Runtime.getRuntime().addShutdownHook(new Thread(()->{
            server.stop(5);
            executor.close();
            try { store.close(); }
            catch (IOException error) { System.err.println("Could not release ObjectStore data lock: "+error.getMessage()); }
        }));
        server.start();System.out.println("LunarSky ObjectStore listening; S3 object-operation prototype, bucket="+bucket);
    }
    private static String required(Map<String,String> env,String key){String value=env.get(key);if(value==null||value.isBlank())throw new IllegalArgumentException("Missing "+key);return value;}
}
