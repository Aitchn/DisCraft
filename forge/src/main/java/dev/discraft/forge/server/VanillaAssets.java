package dev.discraft.forge.server;

import com.google.gson.*;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Pinned game data only. No executable loading, credentials, arbitrary URLs or resource redistribution. */
final class VanillaAssets implements AutoCloseable {
    static final URI MANIFEST=URI.create("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json");
    static final String CLIENT="0c3ec587af28e5a785c0b4a7b8a30f9a8f78f838";
    interface Fetcher {byte[] get(URI uri,int limit)throws Exception;}
    private final ExecutorService executor=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"DisCraft vanilla resources");t.setDaemon(true);return t;});
    private volatile boolean closed;
    void start(Path root,String locale,Consumer<ResourcePackIndex> ready){
        executor.execute(()->{status("LOADING");try{var lower=load(root,locale,network());if(!closed){ready.accept(lower);status("VERIFIED_CACHE_READY");}}catch(Exception unavailable){if(!closed)status("UNAVAILABLE_USING_LOCAL_FALLBACK");}});
    }
    private static void status(String state){com.mojang.logging.LogUtils.getLogger().info("DisCraft vanilla resources: {} (local cache; official Minecraft 1.20.1 base only)",state);}
    @Override public void close(){closed=true;executor.shutdownNow();}
    static ResourcePackIndex load(Path root,String locale,Fetcher fetch)throws Exception{
        return load(root,locale,fetch,CLIENT);
    }
    static ResourcePackIndex load(Path root,String locale,Fetcher fetch,String pinnedClient)throws Exception{
        if(!locale.matches("[a-z0-9_]{1,32}"))throw new IOException("Invalid vanilla locale");secureDirectory(root);
        try(var channel=FileChannel.open(root.resolve("cache.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);var lock=channel.tryLock()){
            if(lock==null)throw new IOException("Vanilla cache busy");checkCacheSize(root,0,0);
            // A receipt retains the exact verified descriptor hash for offline restarts. All objects are rehashed.
            Path receipt=root.resolve("receipt.json");String descriptor;
            if(Files.exists(receipt,LinkOption.NOFOLLOW_LINKS))descriptor=hashField(ResourcePackIndex.json(read(receipt,1024)),"descriptor");
            else{var manifest=ResourcePackIndex.json(fetch.get(MANIFEST,1024*1024));descriptor=null;int entries=0;
                for(var entry:manifest.getAsJsonArray("versions")){if(++entries>5000)throw new IOException("Manifest entry limit");var v=entry.getAsJsonObject();if("1.20.1".equals(v.get("id").getAsString())){if(descriptor!=null)throw new IOException("Ambiguous game version");descriptor=hashField(v,"sha1");URI url=official(v.get("url").getAsString(),"piston-meta.mojang.com");object(root,descriptor,"json",-1,1024*1024,url,fetch);}}
                if(descriptor==null)throw new IOException("Game version unavailable");
            }
            var version=ResourcePackIndex.json(readObject(root,descriptor,"json",-1,1024*1024));if(!"1.20.1".equals(version.get("id").getAsString()))throw new IOException("Game version mismatch");
            var client=version.getAsJsonObject("downloads").getAsJsonObject("client");String clientHash=hashField(client,"sha1");if(!pinnedClient.equals(clientHash))throw new IOException("Pinned client mismatch");
            Path jar=object(root,clientHash,"jar",size(client,32*1024*1024),32*1024*1024,official(client.get("url").getAsString(),"piston-data.mojang.com"),fetch);
            byte[] language=null;String languageHash="en_us";
            if(!locale.equals("en_us")){
                var index=version.getAsJsonObject("assetIndex");String indexHash=hashField(index,"sha1");Path file=object(root,indexHash,"json",size(index,1024*1024),1024*1024,official(index.get("url").getAsString(),"piston-meta.mojang.com"),fetch);
                var objects=ResourcePackIndex.json(read(file,1024*1024)).getAsJsonObject("objects");var selected=objects.get("minecraft/lang/"+locale+".json");
                if(selected!=null){var entry=selected.getAsJsonObject();languageHash=hashField(entry,"hash");Path lang=object(root,languageHash,"json",size(entry,1024*1024),1024*1024,official("https://resources.download.minecraft.net/"+languageHash.substring(0,2)+"/"+languageHash,"resources.download.minecraft.net"),fetch);language=read(lang,1024*1024);}
            }
            var result=ResourcePackIndex.vanilla(jar,locale,language,clientHash+":"+languageHash);
            if(!Files.exists(receipt,LinkOption.NOFOLLOW_LINKS)){var value=new JsonObject();value.addProperty("descriptor",descriptor);publish(root,receipt,value.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));}
            return result;
        }
    }
    static URI official(String value,String host)throws IOException{
        try{URI u=URI.create(value);if(!Set.of("piston-meta.mojang.com","piston-data.mojang.com","resources.download.minecraft.net").contains(host)||!"https".equals(u.getScheme())||!host.equals(u.getHost())||u.getPort()!=-1||u.getUserInfo()!=null||u.getRawQuery()!=null||u.getRawFragment()!=null||!u.getPath().startsWith("/")||u.getPath().contains(".."))throw new IOException();return u;}catch(Exception bad){throw new IOException("Non-official vanilla resource URL rejected");}
    }
    private static String hashField(JsonObject o,String name)throws IOException{String value=o.get(name).getAsString();if(!value.matches("[0-9a-f]{40}"))throw new IOException("Invalid resource digest");return value;}
    private static int size(JsonObject o,int max)throws IOException{String value=o.get("size").getAsString();if(!value.matches("[1-9][0-9]{0,8}"))throw new IOException("Resource size rejected");int n=Integer.parseInt(value);if(n>max)throw new IOException("Resource size rejected");return n;}
    private static String sha(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes));}
    private static Path object(Path root,String hash,String suffix,int size,int max,URI url,Fetcher fetch)throws Exception{
        Path file=root.resolve(hash+"."+suffix);if(Files.exists(file,LinkOption.NOFOLLOW_LINKS)){readObject(root,hash,suffix,size,max);return file;}
        checkCacheSize(root,0,0);byte[] bytes=fetch.get(url,max);if(bytes.length>max||size>=0&&bytes.length!=size||!sha(bytes).equals(hash))throw new IOException("Vanilla resource integrity failure");publish(root,file,bytes);readObject(root,hash,suffix,size,max);return file;
    }
    private static byte[] readObject(Path root,String hash,String suffix,int size,int max)throws Exception{byte[] bytes=read(root.resolve(hash+"."+suffix),max);if(size>=0&&bytes.length!=size||!sha(bytes).equals(hash))throw new IOException("Cached vanilla integrity failure");return bytes;}
    private static byte[] read(Path path,int max)throws IOException{if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.size(path)>max)throw new IOException("Vanilla cache file rejected");try(var in=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)){byte[] b=in.readNBytes(max+1);if(b.length>max)throw new IOException("Vanilla cache size limit");return b;}}
    static void secureDirectory(Path path)throws IOException{Path absolute=path.toAbsolutePath().normalize();for(Path p=absolute;p!=null;p=p.getParent())if(Files.isSymbolicLink(p))throw new IOException("Vanilla cache symlink rejected");Files.createDirectories(absolute);if(!Files.isDirectory(absolute,LinkOption.NOFOLLOW_LINKS))throw new IOException("Vanilla cache directory rejected");}
    private static void checkCacheSize(Path root,long extraBytes,int extraFiles)throws IOException{int count=extraFiles;long bytes=extraBytes;try(var list=Files.newDirectoryStream(root)){for(Path file:list){if(++count>128||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(file)||(bytes+=Files.size(file))>96L*1024*1024)throw new IOException("Vanilla cache capacity exceeded");}}}
    private static void publish(Path root,Path target,byte[] bytes)throws IOException{
        checkCacheSize(root,2L*bytes.length,2);Path temp=Files.createTempFile(root,".download-",".tmp");try{Files.write(temp,bytes,StandardOpenOption.TRUNCATE_EXISTING,LinkOption.NOFOLLOW_LINKS);try{Files.createLink(target,temp);}catch(FileAlreadyExistsException race){throw new IOException("Vanilla cache publication conflict");}}finally{Files.deleteIfExists(temp);}
    }
    static Fetcher network(){
        // Each download and the entire initialization have finite deadlines.
        var http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(90);
        return (uri,max)->{long remaining=deadline-System.nanoTime();if(remaining<=0)throw new IOException("Vanilla initialization timeout");
            var request=HttpRequest.newBuilder(uri).timeout(Duration.ofNanos(Math.min(remaining,TimeUnit.SECONDS.toNanos(45)))).GET().build();
            var pending=http.sendAsync(request,info->new LimitedBody(max));try{var response=pending.get(remaining,TimeUnit.NANOSECONDS);if(response.statusCode()!=200)throw new IOException("Official resource HTTP unavailable");return response.body();}catch(Exception e){pending.cancel(true);throw new IOException("Official resource request failed");}
        };
    }
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final int max;private final ByteArrayOutputStream out=new ByteArrayOutputStream();private final CompletableFuture<byte[]> result=new CompletableFuture<>();private Flow.Subscription subscription;
        LimitedBody(int max){this.max=max;}
        public CompletionStage<byte[]> getBody(){return result;}
        public void onSubscribe(Flow.Subscription s){subscription=s;s.request(1);}
        public void onNext(List<ByteBuffer> buffers){try{for(var b:buffers){if(b.remaining()>max-out.size())throw new IOException();byte[] bytes=new byte[b.remaining()];b.get(bytes);out.write(bytes);}subscription.request(1);}catch(Exception e){subscription.cancel();result.completeExceptionally(new IOException("Official resource response limit"));}}
        public void onError(Throwable error){result.completeExceptionally(new IOException("Official resource transport failure"));}
        public void onComplete(){result.complete(out.toByteArray());}
    }
}
