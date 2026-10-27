package dev.discraft.forge.server;

import com.google.gson.*;
import dev.discraft.core.*;
import dev.discraft.forge.bridge.*;
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
    private final BridgeDiagnostics diagnostics;
    private boolean readReady;
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
    private String guild="",botId="";
    private record MemberName(String nick,String global,String username,long expires){}
    private final Map<String,MemberName> memberNames=new LinkedHashMap<>(128,.75f,true);
    private int memberAttempts;private long memberNanosRemaining;
    private GuildEmojiCatalog guildCatalog=GuildEmojiCatalog.empty(0);
    private long nextCatalog;
    private final EmojiThumbnailCache thumbnails=new EmojiThumbnailCache();
    private static String thumbnailKey(GuildEmojiCatalog.Entry e){return e.id()+(e.animated()?".gif":".png");}
    private GuildEmojiCatalog unavailableCatalog(){
        if(guildCatalog.ready())guildCatalog=GuildEmojiCatalog.empty(guildCatalog.version()+1);
        thumbnails.clear();return guildCatalog;
    }
    @Override public GuildEmojiCatalog catalog(){
        if(closed||terminal)return unavailableCatalog();
        if(System.nanoTime()<nextCatalog)return guildCatalog;
        nextCatalog=System.nanoTime()+60_000_000_000L;
        try{
            ready();
            var member=object(request("GET","guilds/"+guild+"/members/"+botId,null,null),BridgeDiagnostics.Operation.CATALOG);var roles=new HashSet<String>();
            if(!member.has("roles")||member.getAsJsonArray("roles").size()>256)throw new IOException("Invalid bot roles");
            member.getAsJsonArray("roles").forEach(r->roles.add(r.getAsString()));
            var response=array(request("GET","guilds/"+guild+"/emojis",null,null),BridgeDiagnostics.Operation.CATALOG);
            if(response.size()>2000)throw new IOException("Guild catalog response budget");
            var entries=new ArrayList<GuildEmojiCatalog.Entry>();var ids=new HashSet<String>();
            for(var value:response){
                var e=value.getAsJsonObject();String id=e.get("id").getAsString(),name=e.get("name").getAsString();if(!ids.add(id))throw new IOException("Duplicate guild emoji identity");
                if(!e.has("roles")||e.getAsJsonArray("roles").size()>256)throw new IOException("Invalid emoji roles");
                boolean restricted=!e.getAsJsonArray("roles").isEmpty(),permitted=!restricted;
                for(var role:e.getAsJsonArray("roles"))if(roles.contains(role.getAsString()))permitted=true;
                entries.add(new GuildEmojiCatalog.Entry(id,name,e.has("animated")&&e.get("animated").getAsBoolean(),e.has("available")&&e.get("available").getAsBoolean(),permitted,restricted));
            }
            entries.sort(Comparator.comparing(GuildEmojiCatalog.Entry::name,String.CASE_INSENSITIVE_ORDER).thenComparing(GuildEmojiCatalog.Entry::id));
            boolean complete=entries.size()<=GuildEmojiCatalog.MAX_ENTRIES;
            if(!complete)entries=new ArrayList<>(entries.subList(0,GuildEmojiCatalog.MAX_ENTRIES));
            if(!guildCatalog.ready()||!guildCatalog.entries().equals(entries)||guildCatalog.complete()!=complete)
                guildCatalog=new GuildEmojiCatalog(guildCatalog.version()+1,"Discord guild emoji",false,true,complete,entries);
            var retained=new HashSet<String>();for(var e:entries)if(e.usable())retained.add(thumbnailKey(e));thumbnails.retain(retained);
            return guildCatalog;
        }catch(Exception unavailable){failed(BridgeDiagnostics.Operation.CATALOG);return unavailableCatalog();}
    }
    @Override public Map<String,Attachment> thumbnails(List<String> ids){
        if(ids.size()>GuildEmojiCatalog.THUMB_BATCH)return Map.of();var snapshot=catalog();if(!snapshot.ready())return Map.of();
        var result=new LinkedHashMap<String,Attachment>();var budget=new ConversionBudget();
        for(String id:new LinkedHashSet<>(ids)){
            var entry=snapshot.find(id);if(entry==null||!entry.usable())continue;
            var image=thumbnails.get(thumbnailKey(entry),()->budget.resolve(thumbnailKey(entry),URI.create(localTest?api.resolve("emoji/"+id).toString():"https://cdn.discordapp.com/emojis/"+id+(entry.animated()?".gif":".png")+"?size=32&quality=lossless")));
            image.ifPresent(a->result.put(id,a));
        }
        return Map.copyOf(result);
    }
    private String cursor;
    private boolean initialized,terminal;
    private volatile boolean closed;
    private long blockedUntil,nextPoll;
    private int failures;
    private static final Pattern EMOJI=Pattern.compile("<(a?):([A-Za-z0-9_]{1,32}):([0-9]{15,22})>");
    static DiscordBotAdapter fromConfig(ServerConfig config){
        if(!config.enabled())throw new IllegalArgumentException("Discord configuration disabled");
        return new DiscordBotAdapter(URI.create("https://discord.com/api/v10/"),config.channel(),config.token(),false);
    }
    // Only package-local tests may substitute an endpoint, and only a non-secret fixed test token is accepted.
    DiscordBotAdapter(URI api,String channel,String token,boolean localTest){this(api,channel,token,localTest,BridgeDiagnostics.production(localTest));}
    DiscordBotAdapter(URI api,String channel,String token,boolean localTest,BridgeDiagnostics diagnostics){
        ServerConfig.validateCredentials(channel,token);
        if(localTest&&(!api.getHost().equals("127.0.0.1")||!token.equals("discraft-local-test-token")))throw new IllegalArgumentException("Invalid test endpoint");
        if(!localTest&&!api.toString().equals("https://discord.com/api/v10/"))throw new IllegalArgumentException("Invalid Discord endpoint");
        this.api=api;this.channel=channel;this.token=token;this.localTest=localTest;this.diagnostics=diagnostics;
        http=HttpClient.newBuilder().executor(io).followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(5)).build();
    }
    private void ready()throws Exception{
        if(closed||terminal)throw new IOException("Discord adapter unavailable");
        if(System.nanoTime()<blockedUntil)throw new IOException("Discord rate limited");
        if(initialized)return;
        var me=object(request("GET","users/@me",null,null),BridgeDiagnostics.Operation.IDENTITY);
        if(!me.has("bot")||!me.get("bot").getAsBoolean()){terminal=true;diagnostics.report(BridgeDiagnostics.Code.NOT_BOT,BridgeDiagnostics.Operation.IDENTITY,0,BridgeDiagnostics.Terminal.YES);throw new IOException("Bot account required");}
        var room=object(request("GET","channels/"+channel,null,null),BridgeDiagnostics.Operation.CHANNEL);
        if(!room.get("id").getAsString().equals(channel)||room.get("type").getAsInt()!=0){terminal=true;diagnostics.report(BridgeDiagnostics.Code.UNSUPPORTED_TARGET,BridgeDiagnostics.Operation.CHANNEL,0,BridgeDiagnostics.Terminal.YES);throw new IOException("Only designated guild text channels are supported");}
        guild=room.has("guild_id")?room.get("guild_id").getAsString():"";botId=me.get("id").getAsString();
        if(!guild.matches("[0-9]{15,22}")||!botId.matches("[0-9]{15,22}")){terminal=true;diagnostics.report(BridgeDiagnostics.Code.INVALID_RESPONSE,BridgeDiagnostics.Operation.CHANNEL,0,BridgeDiagnostics.Terminal.YES);throw new IOException("Invalid guild bot identity");}
        initialized=true;diagnostics.report(BridgeDiagnostics.Code.TARGET_VERIFIED,BridgeDiagnostics.Operation.CHANNEL,0,BridgeDiagnostics.Terminal.NO);
    }
    @Override public RelayRecord prepare(RelayRecord record)throws Exception{
        ready();var current=catalog();
        if(EMOJI.matcher(record.text()).find()&&!current.ready())throw new IOException("Guild emoji catalog unavailable");
        var message=new JsonObject();message.addProperty("id",record.id().toString());message.addProperty("content",record.text());
        var author=new JsonObject();author.addProperty("username","Minecraft");message.add("author",author);
        var parsed=convert(message,record.mentions(),true);
        var prepared=new RelayRecord(record.id(),record.author(),record.text().isBlank()&&!record.attachments().isEmpty()?"":parsed.text(),record.attachments(),parsed.catalog(),parsed.emoji(),record.actor(),parsed.mentions());prepared.encode();return prepared;
    }
    @Override public void failed(BridgeDiagnostics.Operation operation){diagnostics.report(BridgeDiagnostics.Code.OPERATION_FAILED,operation,0,terminal?BridgeDiagnostics.Terminal.YES:BridgeDiagnostics.Terminal.NO);}
    private void readSucceeded(){if(!readReady){readReady=true;diagnostics.report(BridgeDiagnostics.Code.READ_READY,BridgeDiagnostics.Operation.POLL,0,BridgeDiagnostics.Terminal.NO);}}
    @Override public void send(RelayRecord message)throws Exception{sendWithReceipt(message);}
    // Receipt lets an explicit test harness read back only its own posts, without scanning history.
    String sendWithReceipt(RelayRecord message)throws Exception{
        ready();var payload=new JsonObject();var text=new StringBuilder();var users=new JsonArray();var mentioned=new LinkedHashSet<String>();int at=0;
        for(var mention:message.mentions()){
            text.append(expandEmoji(message.text().substring(at,mention.start()),message.catalog()));
            if(mention.target().kind()==ActorRef.Kind.DISCORD){text.append("<@").append(mention.target().id()).append('>');mentioned.add(mention.target().id());}
            else text.append(mention.target().label());at=mention.end();
        }
        text.append(expandEmoji(message.text().substring(at),message.catalog()));mentioned.forEach(users::add);
        payload.addProperty("content","<"+message.author()+"> "+text);
        payload.addProperty("tts",false);payload.addProperty("nonce",message.id().toString().replace("-","").substring(0,24));payload.addProperty("enforce_nonce",true);
        var mentions=new JsonObject();mentions.add("parse",new JsonArray());mentions.add("users",users);payload.add("allowed_mentions",mentions);
        if(message.attachments().isEmpty())return accepted(request("POST","channels/"+channel+"/messages",payload.toString().getBytes(StandardCharsets.UTF_8),"application/json"));
        var file=message.attachments().get(0);if(file.bytes().length>RelayRecord.FILE_BYTES)throw new IOException("Bridge file budget exceeded");
        String name=file.name().replaceAll("[^A-Za-z0-9_.-]","_");if(name.length()>80)name=name.substring(0,80);
        var files=new JsonArray();var description=new JsonObject();description.addProperty("id",0);description.addProperty("filename",name);description.addProperty("is_spoiler",file.name().startsWith("SPOILER_"));files.add(description);payload.add("attachments",files);
        String boundary="DisCraft"+UUID.randomUUID().toString().replace("-","");var bytes=new ByteArrayOutputStream();
        bytes.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"payload_json\"\r\nContent-Type: application/json\r\n\r\n"+payload+"\r\n--"+boundary+"\r\nContent-Disposition: form-data; name=\"files[0]\"; filename=\""+name+"\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        bytes.write(file.bytes());bytes.write(("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8));
        return accepted(request("POST","channels/"+channel+"/messages",bytes.toByteArray(),"multipart/form-data; boundary="+boundary));
    }
    private String accepted(byte[] body)throws IOException{
        try{var id=receipt(body);diagnostics.report(BridgeDiagnostics.Code.SEND_ACCEPTED,BridgeDiagnostics.Operation.SEND,0,BridgeDiagnostics.Terminal.NO);return id;}
        catch(IOException invalid){diagnostics.report(BridgeDiagnostics.Code.INVALID_RESPONSE,BridgeDiagnostics.Operation.SEND,0,BridgeDiagnostics.Terminal.NO);throw invalid;}
    }
    private JsonObject object(byte[] bytes,BridgeDiagnostics.Operation operation)throws IOException{try{return json(bytes);}catch(RuntimeException invalid){diagnostics.report(BridgeDiagnostics.Code.INVALID_RESPONSE,operation,0,BridgeDiagnostics.Terminal.NO);throw new IOException("Discord metadata validation failed");}}
    private JsonArray array(byte[] bytes,BridgeDiagnostics.Operation operation)throws IOException{try{return JsonParser.parseString(new String(bytes,StandardCharsets.UTF_8)).getAsJsonArray();}catch(RuntimeException invalid){diagnostics.report(BridgeDiagnostics.Code.INVALID_RESPONSE,operation,0,BridgeDiagnostics.Terminal.NO);throw new IOException("Discord page validation failed");}}
    private static String receipt(byte[] body)throws IOException{
        try{String id=json(body).get("id").getAsString();if(id.matches("[0-9]{15,22}"))return id;}catch(Exception ignored){}
        throw new IOException("Discord send receipt invalid; delivery uncertain, do not retry automatically");
    }
    private static String expandEmoji(String text,Map<String,Content.CustomEmoji> catalog){for(var entry:catalog.entrySet()){var c=entry.getValue();text=text.replace(":"+entry.getKey()+":","<"+(c.animated()?"a":"")+":"+c.name()+":"+c.id()+">");}return text;}
    @Override public List<RelayRecord> poll()throws Exception{
        if(closed||terminal||System.nanoTime()<nextPoll||System.nanoTime()<blockedUntil)return List.of();
        try{
            ready();
            var response=array(request("GET","channels/"+channel+"/messages?limit="+(cursor==null?1:4)+(cursor==null?"":"&after="+cursor),null,null),BridgeDiagnostics.Operation.POLL);
            var records=new ArrayList<RelayRecord>();
            if(response.size()>4)throw new IOException("Discord page budget exceeded");
            if(cursor==null){cursor=response.isEmpty()?"0":response.get(0).getAsJsonObject().get("id").getAsString();if(!cursor.matches("[0-9]{1,22}"))throw new IOException("Invalid cursor");readSucceeded();return records;}
            memberAttempts=0;memberNanosRemaining=TimeUnit.SECONDS.toNanos(2);
            var sorted=new ArrayList<JsonObject>();response.forEach(e->sorted.add(e.getAsJsonObject()));sorted.sort(Comparator.comparing(o->new java.math.BigInteger(o.get("id").getAsString())));
            for(var m:sorted){
                String id=m.get("id").getAsString();if(!id.matches("[0-9]{1,22}")||new java.math.BigInteger(id).compareTo(new java.math.BigInteger(cursor))<=0)continue;
                try{
                    var author=m.getAsJsonObject("author");
                    if(m.has("webhook_id")||author.has("bot")&&author.get("bot").getAsBoolean())continue;
                    if(!author.has("id")||!author.get("id").getAsString().matches("[0-9]{15,22}"))throw new IOException("Invalid Discord actor identity");
                    records.add(convert(m));
                }catch(Exception malformed){/* Skip one malformed/unsupported event; never poison the polling cursor. */}
                finally{cursor=id;}
            }
            failures=0;nextPoll=0;readSucceeded();return records;
        }catch(Exception unavailable){readReady=false;failed(BridgeDiagnostics.Operation.POLL);failures=Math.min(6,failures+1);nextPoll=System.nanoTime()+Math.min(60,1L<<failures)*1_000_000_000L;throw new IOException("Discord polling temporarily unavailable");}
    }
    private RelayRecord convert(JsonObject m)throws Exception{return convert(m,List.of());}
    private record Edit(int end,int delta) {}
    private RelayRecord convert(JsonObject m,List<Mention> inputMentions)throws Exception{return convert(m,inputMentions,false);}
    private RelayRecord convert(JsonObject m,List<Mention> inputMentions,boolean outbound)throws Exception{
        var authorJson=m.getAsJsonObject("author");String display=outbound?basicName(authorJson):memberDisplay(authorJson);
        String text=m.has("content")?m.get("content").getAsString():"";Limits.text(text,2000,"Discord content");
        var edits=new ArrayList<Edit>();
        var budget=new ConversionBudget();var resolved=new HashMap<EmojiKey,Optional<Attachment>>();
        var catalog=new LinkedHashMap<String,Content.CustomEmoji>();var emoji=new HashMap<String,Attachment>();var matcher=EMOJI.matcher(text);var converted=new StringBuffer();
        while(matcher.find()){
            boolean inside=false;for(var mention:inputMentions)if(matcher.start()<mention.end()&&matcher.end()>mention.start()){inside=true;break;}
            if(inside){matcher.appendReplacement(converted,java.util.regex.Matcher.quoteReplacement(matcher.group()));continue;}
            String id=matcher.group(3);boolean animated=!matcher.group(1).isEmpty();String emojiName=matcher.group(2);
            if(outbound){var definition=guildCatalog.find(id);if(definition==null||!definition.usable())throw new IOException("Guild emoji unavailable or not permitted");animated=definition.animated();emojiName=definition.name();}
            var identity=new EmojiKey(id,animated);String alias=(animated?"a":"e")+id,key="emoji-"+id+(animated?".gif":".png");
            var image=resolved.get(identity);
            if(image==null){
                try{image=Optional.of(budget.resolve(key,URI.create(localTest?api.resolve("emoji/"+id).toString():"https://cdn.discordapp.com/emojis/"+id+(animated?".gif":".png")+"?size=64&quality=lossless")));}
                catch(Exception unsupported){image=Optional.empty();}
                resolved.put(identity,image); // Negative results must be cached as well as successful decodes.
            }
            String replacement;
            if(image.isPresent()){
                catalog.putIfAbsent(alias,new Content.CustomEmoji(id,emojiName,animated,key));emoji.putIfAbsent(key,image.get());
                replacement=":"+alias+":";
            }else replacement=":"+matcher.group(2)+":";
            matcher.appendReplacement(converted,java.util.regex.Matcher.quoteReplacement(replacement));edits.add(new Edit(matcher.end(),replacement.length()-(matcher.end()-matcher.start())));
        }
        matcher.appendTail(converted);var files=new ArrayList<Attachment>();
        if(m.has("attachments"))for(var f:m.getAsJsonArray("attachments")){
            if(!files.isEmpty()||budget.attempts>=MEDIA_ATTEMPTS||System.nanoTime()>=budget.deadline)break;
            var a=f.getAsJsonObject();
            try{if(a.get("size").getAsLong()>RelayRecord.FILE_BYTES)continue;String name=a.get("filename").getAsString();boolean hidden=a.has("is_spoiler")&&a.get("is_spoiler").getAsBoolean()||a.has("flags")&&(a.get("flags").getAsInt()&8)!=0;if(hidden&&!name.startsWith("SPOILER_")){if(name.length()>112)name=name.substring(0,112);name="SPOILER_"+name;}files.add(budget.resolve(name,URI.create(a.get("url").getAsString())));}
            catch(Exception unsupported){/* Shared attempt/deadline budget includes unsuccessful attachment downloads. */}
        }
        if(converted.toString().isBlank()&&files.isEmpty())converted.append("[Discord content/media unavailable]");
        String author=display;
        var mentions=new ArrayList<Mention>();for(var mention:inputMentions){int delta=edits.stream().filter(e->e.end()<=mention.start()).mapToInt(Edit::delta).sum();mentions.add(new Mention(mention.start()+delta,mention.end()+delta,mention.target()));}
        ActorRef actor=authorJson.has("id")?new ActorRef(ActorRef.Kind.DISCORD,authorJson.get("id").getAsString(),author):null;
        var record=new RelayRecord(UUID.nameUUIDFromBytes(("discord:"+m.get("id").getAsString()).getBytes(StandardCharsets.UTF_8)),"Discord: "+author,converted.toString(),files,catalog,emoji,actor,mentions);
        record.encode();return record;
    }
    private static String nameField(JsonObject object,String field){
        if(object==null||!object.has(field)||!object.get(field).isJsonPrimitive()||!object.getAsJsonPrimitive(field).isString())return null;
        String name=object.get(field).getAsString();try{Limits.text(name,64,"Discord display");if(name.isBlank()||name.chars().anyMatch(Character::isISOControl))return null;return name;}catch(IllegalArgumentException invalid){return null;}
    }
    private static String basicName(JsonObject user){String global=nameField(user,"global_name"),username=nameField(user,"username");return global!=null?global:username!=null?username:"Discord user";}
    private String memberDisplay(JsonObject author){
        String id=author.has("id")?author.get("id").getAsString():"";if(guild.isEmpty()||!id.matches("[0-9]{15,22}"))return basicName(author);
        String key=guild+":"+id;long now=System.nanoTime();MemberName member=memberNames.get(key);
        if(member!=null&&now>=member.expires()){memberNames.remove(key);member=null;}
        if(member==null&&memberAttempts<4&&memberNanosRemaining>0){
            memberAttempts++;long started=System.nanoTime();
            try{var response=json(request("GET","guilds/"+guild+"/members/"+id,null,null,started+memberNanosRemaining,16*1024));
                JsonObject user=response.has("user")&&response.get("user").isJsonObject()?response.getAsJsonObject("user"):null;
                if(user!=null&&(!user.has("id")||!user.get("id").getAsString().equals(id)))throw new IOException("Guild member identity mismatch");
                member=new MemberName(nameField(response,"nick"),nameField(user,"global_name"),nameField(user,"username"),System.nanoTime()+TimeUnit.SECONDS.toNanos(60));
            }catch(Exception unavailable){member=new MemberName(null,null,null,System.nanoTime()+TimeUnit.SECONDS.toNanos(15));}
            finally{memberNanosRemaining=Math.max(0,memberNanosRemaining-(System.nanoTime()-started));}
            memberNames.put(key,member);while(memberNames.size()>128)memberNames.remove(memberNames.keySet().iterator().next());
        }
        if(member!=null){if(member.nick()!=null)return member.nick();if(member.global()!=null)return member.global();if(nameField(author,"global_name")!=null)return nameField(author,"global_name");if(member.username()!=null)return member.username();}
        return basicName(author);
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
        return request(method,path,body,type,Long.MAX_VALUE,RelayRecord.MAX_BYTES);
    }
    private byte[] request(String method,String path,byte[] body,String type,long deadline,int maxBytes)throws Exception{
        if(closed||terminal||System.nanoTime()<blockedUntil)throw new IOException("Discord adapter unavailable or rate limited");
        var operation=operation(method,path);
        long remaining=deadline==Long.MAX_VALUE?TimeUnit.SECONDS.toNanos(10):deadline-System.nanoTime();if(remaining<=0)throw new IOException("Discord metadata deadline exceeded");
        var builder=HttpRequest.newBuilder(api.resolve(path)).timeout(Duration.ofNanos(Math.min(remaining,TimeUnit.SECONDS.toNanos(10)))).header("Authorization","Bot "+token).header("User-Agent","DisCraft/0.2 (server bridge)");
        if(type!=null)builder.header("Content-Type",type);
        builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(body));
        var r=execute(builder.build(),maxBytes,deadline,operation);
        if(r.statusCode()==429){
            double seconds=60;try{seconds=json(r.body()).get("retry_after").getAsDouble();}catch(Exception ignored){}
            if(!Double.isFinite(seconds)||seconds<0||seconds>3600)seconds=3600;
            blockedUntil=System.nanoTime()+(long)Math.ceil(Math.max(1,seconds)*1e9);diagnostics.report(BridgeDiagnostics.Code.RATE_LIMITED,operation,429,BridgeDiagnostics.Terminal.NO);throw new IOException("Discord rate limited");
        }
        if(r.statusCode()==401||r.statusCode()==403){terminal=true;unavailableCatalog();diagnostics.report(r.statusCode()==401?BridgeDiagnostics.Code.AUTH_REJECTED:BridgeDiagnostics.Code.PERMISSION_REJECTED,operation,r.statusCode(),BridgeDiagnostics.Terminal.YES);throw new IOException("Discord credentials/permissions rejected; restart after operator correction");}
        if(r.statusCode()<200||r.statusCode()>=300){diagnostics.report(r.statusCode()==404?BridgeDiagnostics.Code.NOT_FOUND:BridgeDiagnostics.Code.HTTP_FAILED,operation,r.statusCode(),BridgeDiagnostics.Terminal.NO);throw new IOException("Discord request failed or uncertain; not automatically retried");}
        return r.body();
    }
    private HttpResponse<byte[]> execute(HttpRequest request,int max)throws Exception{
        return execute(request,max,Long.MAX_VALUE);
    }
    private HttpResponse<byte[]> execute(HttpRequest request,int max,long deadline)throws Exception{
        return execute(request,max,deadline,BridgeDiagnostics.Operation.MEDIA);
    }
    private HttpResponse<byte[]> execute(HttpRequest request,int max,long deadline,BridgeDiagnostics.Operation operation)throws Exception{
        CompletableFuture<HttpResponse<byte[]>> future=null;
        try{future=http.sendAsync(request,info->new LimitedBody(max));return future.get(deadline==Long.MAX_VALUE?TimeUnit.SECONDS.toNanos(12):Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);}
        catch(Exception failure){if(future!=null)future.cancel(true);var code=transportFailure(failure);if(code==BridgeDiagnostics.Code.INTERRUPTED)Thread.currentThread().interrupt();diagnostics.report(code,operation,0,terminal?BridgeDiagnostics.Terminal.YES:BridgeDiagnostics.Terminal.NO);throw new IOException("Bounded HTTP request failed");}
    }
    private static BridgeDiagnostics.Operation operation(String method,String path){
        if(path.equals("users/@me"))return BridgeDiagnostics.Operation.IDENTITY;
        if(path.contains("/members/"))return BridgeDiagnostics.Operation.MEMBER;
        if(path.endsWith("/emojis"))return BridgeDiagnostics.Operation.CATALOG;
        if(path.contains("/messages"))return method.equals("POST")?BridgeDiagnostics.Operation.SEND:BridgeDiagnostics.Operation.POLL;
        return BridgeDiagnostics.Operation.CHANNEL;
    }
    private static BridgeDiagnostics.Code transportFailure(Throwable failure){
        for(int depth=0;failure!=null&&depth<8;depth++,failure=failure.getCause()){
            if(failure instanceof SafeHttpFailure safe)return safe.code;
            if(failure instanceof TimeoutException||failure instanceof HttpTimeoutException||failure instanceof SocketTimeoutException)return BridgeDiagnostics.Code.TIMEOUT;
            if(failure instanceof InterruptedException||failure instanceof CancellationException)return BridgeDiagnostics.Code.INTERRUPTED;
        }return BridgeDiagnostics.Code.NETWORK_FAILED;
    }
    private static final class SafeHttpFailure extends IOException {
        final BridgeDiagnostics.Code code;SafeHttpFailure(BridgeDiagnostics.Code code){super("Bounded HTTP stream failed");this.code=code;}
    }
    private static JsonObject json(byte[] b){return JsonParser.parseString(new String(b,StandardCharsets.UTF_8)).getAsJsonObject();}
    @Override public void close(){closed=true;unavailableCatalog();memberNames.clear();mediaWorker.shutdownNow();io.shutdownNow();}
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final int max;private final ByteArrayOutputStream out=new ByteArrayOutputStream();private final CompletableFuture<byte[]> result=new CompletableFuture<>();private Flow.Subscription subscription;
        LimitedBody(int max){this.max=max;}
        public CompletionStage<byte[]> getBody(){return result;}
        public void onSubscribe(Flow.Subscription s){subscription=s;s.request(1);}
        public void onNext(List<ByteBuffer> buffers){try{for(var b:buffers){if(b.remaining()>max-out.size())throw new IOException("HTTP body limit");byte[] bytes=new byte[b.remaining()];b.get(bytes);out.write(bytes);}subscription.request(1);}catch(Exception e){subscription.cancel();result.completeExceptionally(new SafeHttpFailure(BridgeDiagnostics.Code.RESPONSE_LIMIT));}}
        public void onError(Throwable error){result.completeExceptionally(new SafeHttpFailure(transportFailure(error)));}
        public void onComplete(){result.complete(out.toByteArray());}
    }
}
