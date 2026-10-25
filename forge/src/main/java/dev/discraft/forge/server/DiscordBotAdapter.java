package dev.discraft.forge.server;

import com.google.gson.*;
import dev.discraft.core.*;
import dev.discraft.forge.bridge.RelayRecord;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import java.util.regex.Pattern;

/** Official Discord Bot REST v10 adapter. Polling is deliberate; no user-account or Gateway automation. */
public final class DiscordBotAdapter implements DiscordAdapter {
    private final String token,channel;
    private final URI api;
    private final boolean localTest;
    private final ExecutorService io=Executors.newFixedThreadPool(2,r->{var t=new Thread(r,"DisCraft HTTP");t.setDaemon(true);return t;});
    private final HttpClient http;
    // One bounded media worker also bounds DNS and decoding time seen by the server bridge.
    private final ThreadPoolExecutor mediaWorker=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(1),r->{var t=new Thread(r,"DisCraft media");t.setDaemon(true);return t;});
    private static final int MEDIA_ATTEMPTS=8;
    private static final long MEDIA_NANOS=TimeUnit.SECONDS.toNanos(6);
    private record EmojiKey(String id,boolean animated) {}
    private final class ConversionBudget {
        final long deadline=System.nanoTime()+MEDIA_NANOS;int attempts;long pixels;
        Attachment resolve(String name,URI uri)throws Exception{
            if(attempts>=MEDIA_ATTEMPTS||System.nanoTime()>=deadline)throw new IOException("Conversion media budget exhausted");
            attempts++;
            Future<Attachment> pending=mediaWorker.submit(()->Attachment.of(name,fetchMedia(uri)));
            try{
                long remaining=deadline-System.nanoTime();if(remaining<=0)throw new TimeoutException();
                var image=pending.get(remaining,TimeUnit.NANOSECONDS);
                long count=(long)image.media().width()*image.media().height()*image.media().frames().size();
                if(count>Limits.MEDIA_TOTAL_PIXELS-pixels)throw new IOException("Conversion pixel budget exhausted");
                pixels+=count;return image;
            }finally{if(!pending.isDone())pending.cancel(true);mediaWorker.purge();}
        }
    }
    private String cursor;
    private boolean initialized,terminal;
    private volatile boolean closed;
    private long blockedUntil,nextPoll;
    private int failures;
    private static final Pattern EMOJI=Pattern.compile("<(a?):([A-Za-z0-9_]{1,32}):([0-9]{15,22})>");
    public static DiscordBotAdapter fromEnvironment(){
        return new DiscordBotAdapter(URI.create("https://discord.com/api/v10/"),System.getenv("DISCRAFT_DISCORD_CHANNEL"),System.getenv("DISCRAFT_DISCORD_BOT_TOKEN"),false);
    }
    // Only package-local tests may substitute an endpoint, and only a non-secret fixed test token is accepted.
    DiscordBotAdapter(URI api,String channel,String token,boolean localTest){
        if(channel==null||!channel.matches("[0-9]{15,22}")||token==null||token.length()<20||token.length()>200||token.chars().anyMatch(c->Character.isWhitespace(c)||Character.isISOControl(c)))throw new IllegalArgumentException("Invalid server Discord configuration");
        if(localTest&&(!api.getHost().equals("127.0.0.1")||!token.equals("discraft-local-test-token")))throw new IllegalArgumentException("Invalid test endpoint");
        if(!localTest&&!api.toString().equals("https://discord.com/api/v10/"))throw new IllegalArgumentException("Invalid Discord endpoint");
        this.api=api;this.channel=channel;this.token=token;this.localTest=localTest;
        http=HttpClient.newBuilder().executor(io).followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(5)).build();
    }
    private void ready()throws Exception{
        if(closed||terminal)throw new IOException("Discord adapter unavailable");
        if(System.nanoTime()<blockedUntil)throw new IOException("Discord rate limited");
        if(initialized)return;
        var me=json(request("GET","users/@me",null,null));
        if(!me.has("bot")||!me.get("bot").getAsBoolean()){terminal=true;throw new IOException("Bot account required");}
        var room=json(request("GET","channels/"+channel,null,null));
        if(!room.get("id").getAsString().equals(channel)||room.get("type").getAsInt()!=0){terminal=true;throw new IOException("Only designated guild text channels are supported");}
        initialized=true;
    }
    @Override public RelayRecord prepare(RelayRecord record)throws Exception{
        ready();var message=new JsonObject();message.addProperty("id",record.id().toString());message.addProperty("content",record.text());
        var author=new JsonObject();author.addProperty("username","Minecraft");message.add("author",author);
        var parsed=convert(message);
        var prepared=new RelayRecord(record.id(),record.author(),record.text().isBlank()&&!record.attachments().isEmpty()?"":parsed.text(),record.attachments(),parsed.catalog(),parsed.emoji());prepared.encode();return prepared;
    }
    @Override public void send(RelayRecord message)throws Exception{
        ready();var payload=new JsonObject();String text=message.text();for(var entry:message.catalog().entrySet()){var c=entry.getValue();text=text.replace(":"+entry.getKey()+":","<"+(c.animated()?"a":"")+":"+c.name()+":"+c.id()+">");}
        payload.addProperty("content","[Minecraft "+message.author()+"] "+text);
        payload.addProperty("tts",false);payload.addProperty("nonce",message.id().toString().replace("-","").substring(0,24));payload.addProperty("enforce_nonce",true);
        var mentions=new JsonObject();mentions.add("parse",new JsonArray());payload.add("allowed_mentions",mentions);
        if(message.attachments().isEmpty()){request("POST","channels/"+channel+"/messages",payload.toString().getBytes(StandardCharsets.UTF_8),"application/json");return;}
        var file=message.attachments().get(0);if(file.bytes().length>RelayRecord.FILE_BYTES)throw new IOException("Bridge file budget exceeded");
        String name=file.name().replaceAll("[^A-Za-z0-9_.-]","_");if(name.length()>80)name=name.substring(0,80);
        var files=new JsonArray();var description=new JsonObject();description.addProperty("id",0);description.addProperty("filename",name);files.add(description);payload.add("attachments",files);
        String boundary="DisCraft"+UUID.randomUUID().toString().replace("-","");var bytes=new ByteArrayOutputStream();
        bytes.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"payload_json\"\r\nContent-Type: application/json\r\n\r\n"+payload+"\r\n--"+boundary+"\r\nContent-Disposition: form-data; name=\"files[0]\"; filename=\""+name+"\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        bytes.write(file.bytes());bytes.write(("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8));
        request("POST","channels/"+channel+"/messages",bytes.toByteArray(),"multipart/form-data; boundary="+boundary);
    }
    @Override public List<RelayRecord> poll()throws Exception{
        if(closed||terminal||System.nanoTime()<nextPoll||System.nanoTime()<blockedUntil)return List.of();
        try{
            ready();
            var response=JsonParser.parseString(new String(request("GET","channels/"+channel+"/messages?limit="+(cursor==null?1:4)+(cursor==null?"":"&after="+cursor),null,null),StandardCharsets.UTF_8)).getAsJsonArray();
            var records=new ArrayList<RelayRecord>();
            if(response.size()>4)throw new IOException("Discord page budget exceeded");
            if(cursor==null){cursor=response.isEmpty()?"0":response.get(0).getAsJsonObject().get("id").getAsString();if(!cursor.matches("[0-9]{1,22}"))throw new IOException("Invalid cursor");return records;}
            var sorted=new ArrayList<JsonObject>();response.forEach(e->sorted.add(e.getAsJsonObject()));sorted.sort(Comparator.comparing(o->new java.math.BigInteger(o.get("id").getAsString())));
            for(var m:sorted){
                String id=m.get("id").getAsString();if(!id.matches("[0-9]{1,22}")||new java.math.BigInteger(id).compareTo(new java.math.BigInteger(cursor))<=0)continue;
                try{
                    var author=m.getAsJsonObject("author");
                    if(m.has("webhook_id")||author.has("bot")&&author.get("bot").getAsBoolean())continue;
                    records.add(convert(m));
                }catch(Exception malformed){/* Skip one malformed/unsupported event; never poison the polling cursor. */}
                finally{cursor=id;}
            }
            failures=0;nextPoll=0;return records;
        }catch(Exception unavailable){failures=Math.min(6,failures+1);nextPoll=System.nanoTime()+Math.min(60,1L<<failures)*1_000_000_000L;throw new IOException("Discord polling temporarily unavailable");}
    }
    private RelayRecord convert(JsonObject m)throws Exception{
        String text=m.has("content")?m.get("content").getAsString():"";Limits.text(text,2000,"Discord content");
        var budget=new ConversionBudget();var resolved=new HashMap<EmojiKey,Optional<Attachment>>();
        var catalog=new LinkedHashMap<String,Content.CustomEmoji>();var emoji=new HashMap<String,Attachment>();var matcher=EMOJI.matcher(text);var converted=new StringBuffer();
        while(matcher.find()){
            String id=matcher.group(3);boolean animated=!matcher.group(1).isEmpty();
            var identity=new EmojiKey(id,animated);String alias=(animated?"a":"e")+id,key="emoji-"+id+(animated?".gif":".png");
            var image=resolved.get(identity);
            if(image==null){
                try{image=Optional.of(budget.resolve(key,URI.create(localTest?api.resolve("emoji/"+id).toString():"https://cdn.discordapp.com/emojis/"+id+(animated?".gif":".png")+"?size=64&quality=lossless")));}
                catch(Exception unsupported){image=Optional.empty();}
                resolved.put(identity,image); // Negative results must be cached as well as successful decodes.
            }
            if(image.isPresent()){
                catalog.putIfAbsent(alias,new Content.CustomEmoji(id,matcher.group(2),animated,key));emoji.putIfAbsent(key,image.get());
                matcher.appendReplacement(converted,":"+alias+":");
            }else matcher.appendReplacement(converted,java.util.regex.Matcher.quoteReplacement(":"+matcher.group(2)+":"));
        }
        matcher.appendTail(converted);var files=new ArrayList<Attachment>();
        if(m.has("attachments"))for(var f:m.getAsJsonArray("attachments")){
            if(!files.isEmpty()||budget.attempts>=MEDIA_ATTEMPTS||System.nanoTime()>=budget.deadline)break;
            var a=f.getAsJsonObject();
            try{if(a.get("size").getAsLong()>RelayRecord.FILE_BYTES)continue;files.add(budget.resolve(a.get("filename").getAsString(),URI.create(a.get("url").getAsString())));}
            catch(Exception unsupported){/* Shared attempt/deadline budget includes unsuccessful attachment downloads. */}
        }
        if(converted.toString().isBlank()&&files.isEmpty())converted.append("[Discord content/media unavailable]");
        String author=m.getAsJsonObject("author").get("username").getAsString();
        var record=new RelayRecord(UUID.nameUUIDFromBytes(("discord:"+m.get("id").getAsString()).getBytes(StandardCharsets.UTF_8)),"Discord: "+author,converted.toString(),files,catalog,emoji);
        record.encode();return record;
    }
    private byte[] fetchMedia(URI uri)throws Exception{
        if(localTest){if(!uri.getHost().equals("127.0.0.1")||uri.getPort()!=api.getPort())throw new IOException("Invalid local media URL");}
        else{
            if(!"https".equals(uri.getScheme())||uri.getRawUserInfo()!=null||uri.getFragment()!=null||(uri.getPort()!=-1&&uri.getPort()!=443)
                ||!Set.of("cdn.discordapp.com","media.discordapp.net").contains(uri.getHost())||!(uri.getPath().startsWith("/attachments/")||uri.getPath().matches("/emojis/[0-9]{15,22}\\.(png|gif)")))throw new IOException("Unapproved media location");
            for(var ip:InetAddress.getAllByName(uri.getHost()))if(ip.isAnyLocalAddress()||ip.isLoopbackAddress()||ip.isLinkLocalAddress()||ip.isSiteLocalAddress()||ip.isMulticastAddress())throw new IOException("Unsafe CDN resolution");
        }
        var r=execute(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET().build(),RelayRecord.FILE_BYTES);
        if(r.statusCode()!=200)throw new IOException("Media fetch rejected");return r.body();
    }
    private byte[] request(String method,String path,byte[] body,String type)throws Exception{
        if(closed||terminal||System.nanoTime()<blockedUntil)throw new IOException("Discord adapter unavailable or rate limited");
        var builder=HttpRequest.newBuilder(api.resolve(path)).timeout(Duration.ofSeconds(10)).header("Authorization","Bot "+token).header("User-Agent","DisCraft/0.2 (server bridge)");
        if(type!=null)builder.header("Content-Type",type);
        builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(body));
        var r=execute(builder.build(),RelayRecord.MAX_BYTES);
        if(r.statusCode()==429){
            double seconds=60;try{seconds=json(r.body()).get("retry_after").getAsDouble();}catch(Exception ignored){}
            if(!Double.isFinite(seconds)||seconds<0||seconds>3600)seconds=3600;
            blockedUntil=System.nanoTime()+(long)Math.ceil(Math.max(1,seconds)*1e9);throw new IOException("Discord rate limited");
        }
        if(r.statusCode()==401||r.statusCode()==403){terminal=true;throw new IOException("Discord credentials/permissions rejected; restart after operator correction");}
        if(r.statusCode()<200||r.statusCode()>=300)throw new IOException("Discord request failed or uncertain; not automatically retried");
        return r.body();
    }
    private HttpResponse<byte[]> execute(HttpRequest request,int max)throws Exception{
        var future=http.sendAsync(request,info->new LimitedBody(max));
        try{return future.get(12,TimeUnit.SECONDS);}catch(Exception failure){future.cancel(true);throw new IOException("Bounded HTTP request failed");}
    }
    private static JsonObject json(byte[] b){return JsonParser.parseString(new String(b,StandardCharsets.UTF_8)).getAsJsonObject();}
    @Override public void close(){closed=true;mediaWorker.shutdownNow();io.shutdownNow();}
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final int max;private final ByteArrayOutputStream out=new ByteArrayOutputStream();private final CompletableFuture<byte[]> result=new CompletableFuture<>();private Flow.Subscription subscription;
        LimitedBody(int max){this.max=max;}
        public CompletionStage<byte[]> getBody(){return result;}
        public void onSubscribe(Flow.Subscription s){subscription=s;s.request(1);}
        public void onNext(List<ByteBuffer> buffers){try{for(var b:buffers){if(b.remaining()>max-out.size())throw new IOException("HTTP body limit");byte[] bytes=new byte[b.remaining()];b.get(bytes);out.write(bytes);}subscription.request(1);}catch(Exception e){subscription.cancel();result.completeExceptionally(new IOException("HTTP body limit"));}}
        public void onError(Throwable error){result.completeExceptionally(new IOException("HTTP stream failed"));}
        public void onComplete(){result.complete(out.toByteArray());}
    }
}
