package dev.discraft.forge.server;

import com.sun.net.httpserver.HttpServer;
import com.google.gson.*;
import dev.discraft.core.*;
import dev.discraft.forge.bridge.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.*;

/** Loopback-only official REST contract fixture. Never contacts Discord or supplies a real token. */
public final class BridgeProtocolTest {
    private static int checks;
    private static void check(boolean value,String label){if(!value)throw new AssertionError(label);checks++;System.out.println("PASS "+label);}
    private static void rejected(Throwing work,String label)throws Exception{try{work.run();throw new AssertionError(label);}catch(AssertionError failed){throw failed;}catch(Exception expected){check(true,label);}}
    interface Throwing{void run()throws Exception;}
    private static void mentionEdits(){
        var first=new ActorRef(ActorRef.Kind.DISCORD,"2234567890123456","Same");var second=new ActorRef(ActorRef.Kind.DISCORD,"3234567890123456","Same");String label=first.label(),one=label+" ";var draft=new MentionDraft();draft.synchronize(one);draft.select(0,first);
        String both=one+one;draft.edit(both,0,0);draft.select(0,second);var tokens=draft.snapshot(both);
        check(tokens.size()==2&&tokens.get(0).target().equals(second)&&tokens.get(1).target().equals(first)&&tokens.get(1).start()==one.length(),"Same-display author prepended before an existing token retains both exact stable IDs");
        draft.clear();draft.synchronize(one);draft.select(0,first);draft.edit(both,one.length(),one.length());draft.select(one.length(),second);tokens=draft.snapshot(both);
        check(tokens.size()==2&&tokens.get(0).target().equals(first)&&tokens.get(1).target().equals(second),"Same-display author appended after a token does not change its recipient");
        draft.clear();draft.synchronize(one);draft.select(0,first);draft.edit(both,0,0);tokens=draft.snapshot(both);
        check(tokens.size()==1&&tokens.get(0).start()==one.length()&&tokens.get(0).target().equals(first),"Pasted identical prefix is unbound and shifts the original selected token");
        draft.edit(one,0,one.length());tokens=draft.snapshot(one);check(tokens.size()==1&&tokens.get(0).start()==0&&tokens.get(0).target().equals(first),"Deleting pasted identical prefix restores the original token with its exact ID");
        draft.invalidate(0,label.length());draft.edit(one,0,label.length());check(draft.snapshot(one).isEmpty(),"Replacing selected text with identical paste cannot preserve an old recipient");
        draft.select(0,first);draft.invalidate(0,label.length());draft.edit(one,0,label.length());draft.select(0,second);tokens=draft.snapshot(one);check(tokens.size()==1&&tokens.get(0).target().equals(second),"Explicit same-display replacement binds only the newly selected stable ID");
        draft.edit("x"+one,0,0);tokens=draft.snapshot("x"+one);check(tokens.size()==1&&tokens.get(0).start()==1&&tokens.get(0).target().equals(second),"Known-range typing outside a token preserves and shifts its selected identity");
        draft.invalidate(2,3);draft.edit("x"+one.substring(0,1)+one.substring(2),2,3);check(draft.snapshot("x"+one.substring(0,1)+one.substring(2)).isEmpty(),"Deletion inside a mention invalidates that selection");
        draft.clear();draft.synchronize(one);draft.select(0,first);draft.synchronize(both);check(draft.snapshot(both).isEmpty(),"Unknown ambiguous text changes conservatively discard identities rather than guessing a diff");
    }
    private static void normalizedMentions(){
        var first=new ActorRef(ActorRef.Kind.DISCORD,"2234567890123456","Same");var second=new ActorRef(ActorRef.Kind.DISCORD,"3234567890123456","Same");String label=first.label();
        var mapped=MentionDraft.normalized(label+" ",List.of(new Mention(0,label.length(),first)),label);check(mapped.size()==1&&mapped.get(0).target().equals(first)&&mapped.get(0).start()==0,"Vanilla trailing-space normalization preserves Enter-only selected mention");
        String raw="  prefix   "+label+" ";int at=raw.indexOf(label);mapped=MentionDraft.normalized(raw,List.of(new Mention(at,at+label.length(),first)),"prefix "+label);check(mapped.size()==1&&mapped.get(0).start()==7&&mapped.get(0).target().equals(first),"Leading and doubled spaces before a mention map its exact selected ID");
        raw=" "+label+"   "+label+" ";mapped=MentionDraft.normalized(raw,List.of(new Mention(1,1+label.length(),first),new Mention(raw.lastIndexOf(label),raw.lastIndexOf(label)+label.length(),second)),label+" "+label);check(mapped.size()==2&&mapped.get(0).target().equals(first)&&mapped.get(1).target().equals(second)&&mapped.get(1).start()==label.length()+1,"Whitespace mapping retains distinct IDs for same-display mentions");
        check(MentionDraft.normalized(label+" ",List.of(),label).isEmpty(),"Normalized typed lookalike text never gains a mention binding");check(MentionDraft.normalized(label+" ",List.of(new Mention(0,label.length(),first)),"changed "+label).isEmpty(),"Unknown message transforms withdraw mention bindings");
        raw="x".repeat(256)+label;mapped=MentionDraft.normalized(raw,List.of(new Mention(256,raw.length(),first)),"x".repeat(256));check(mapped.isEmpty(),"Vanilla truncation withdraws a mention outside the retained text");
    }
    private static RelayRecord inbound(DiscordBotAdapter adapter,String text)throws Exception{
        var message=new JsonObject();message.addProperty("id","8234567890123456");message.addProperty("content",text);var author=new JsonObject();author.addProperty("username","Human");message.add("author",author);
        var convert=DiscordBotAdapter.class.getDeclaredMethod("convert",JsonObject.class);convert.setAccessible(true);return (RelayRecord)convert.invoke(adapter,message);
    }
    public static void main(String[] args)throws Exception{
        ServerConfigTest.run();ResourcePackTest.run();NoticeDeliveryTest.run();GuildEmojiTest.run();mentionEdits();normalizedMentions();
        var id=UUID.randomUUID();var gif=Attachment.fromResource("dance.gif");
        var original=RelayRecord.draft(id,"Unicode 世界 👋",gif);var decoded=RelayRecord.decode(original.encode());
        check(decoded.id().equals(id)&&decoded.text().equals(original.text())&&decoded.attachments().get(0).media().frames().size()>1,"Wire roundtrip preserves Unicode and real GIF frames");
        rejected(()->RelayRecord.decode(Arrays.copyOf(original.encode(),20)),"Truncated packet rejected");
        rejected(()->RelayRecord.decode(new byte[RelayRecord.MAX_BYTES+1]),"Oversize packet rejected before decode");
        byte[] trailing=Arrays.copyOf(original.encode(),original.encode().length+1);rejected(()->RelayRecord.decode(trailing),"Trailing bytes rejected");
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);String base="http://127.0.0.1:"+server.getAddress().getPort()+"/api/v10/";
        var receiptValid=new AtomicBoolean(true);var posts=new ArrayList<byte[]>();var auth=new AtomicBoolean(true);var bot=new AtomicBoolean(true);var code=new AtomicInteger(200);var requests=new AtomicInteger();var lastQuery=new AtomicReference<String>("");
        var mediaMode=new AtomicReference<String>("ok");var mediaRequests=new AtomicInteger();
        String channel="1234567890123456";String first="2234567890123456",last="3234567890123456";
        server.createContext("/",exchange->{
            try{
                requests.incrementAndGet();lastQuery.set(String.valueOf(exchange.getRequestURI().getQuery()));String path=exchange.getRequestURI().getPath();byte[] response;
                int status=code.getAndSet(200);
                if(path.contains("/emoji/")){mediaRequests.incrementAndGet();if(mediaMode.get().equals("slow"))try{Thread.sleep(8000);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}if(mediaMode.get().equals("fail"))status=404;if(exchange.getRequestHeaders().getFirst("Authorization")!=null)auth.set(false);response=mediaMode.get().equals("invalid")?new byte[]{1,2,3}:Attachment.fromResource(path.endsWith("2222222222222222")?"dance.gif":"emoji.png").bytes();}
                else if(path.equals("/file.png")){if(exchange.getRequestHeaders().getFirst("Authorization")!=null)auth.set(false);response=Attachment.fromResource("landscape.png").bytes();}
                else if(path.equals("/huge")){response=new byte[RelayRecord.MAX_BYTES+1];}
                else if(path.equals("/redirect")){status=302;exchange.getResponseHeaders().set("Location","http://127.0.0.1:"+server.getAddress().getPort()+"/file.png");response=new byte[]{0};}
                else {
                    auth.compareAndSet(true,("Bot discraft-local-test-token").equals(exchange.getRequestHeaders().getFirst("Authorization")));
                    if(exchange.getRequestMethod().equals("POST")){posts.add(exchange.getRequestBody().readAllBytes());response=("{\"id\":\""+(receiptValid.get()?last:"invalid")+"\"}").getBytes(StandardCharsets.UTF_8);}
                    else if(path.endsWith("/users/@me")){response=("{\"id\":\"9999999999999999\",\"bot\":"+bot.get()+"}").getBytes(StandardCharsets.UTF_8);}
                    else if(path.contains("/members/")){response="{\"roles\":[]}".getBytes(StandardCharsets.UTF_8);}
                    else if(path.endsWith("/emojis")){
                        var list=new JsonArray();for(int i=0;i<40;i++){var e=new JsonObject();e.addProperty("id",String.valueOf(1111111111111111L+i));e.addProperty("name",i==0?"smile":"item"+i);e.addProperty("animated",false);e.addProperty("available",true);e.add("roles",new JsonArray());list.add(e);}var animated=new JsonObject();animated.addProperty("id","2222222222222222");animated.addProperty("name","dance");animated.addProperty("animated",true);animated.addProperty("available",true);animated.add("roles",new JsonArray());list.add(animated);response=list.toString().getBytes(StandardCharsets.UTF_8);
                    }
                    else if(path.endsWith("/messages")){
                        if(exchange.getRequestURI().getQuery().contains("limit=1"))response=("[{\"id\":\""+first+"\"}]").getBytes(StandardCharsets.UTF_8);
                        else {
                            var message=new JsonObject();message.addProperty("id",last);message.addProperty("content","Discord 世界 <:smile:1111111111111111> <a:dance:2222222222222222>");
                            var author=new JsonObject();author.addProperty("id","4234567890123456");author.addProperty("username","Human");author.addProperty("bot",false);message.add("author",author);
                            var files=new JsonArray();var file=new JsonObject();file.addProperty("size",1024);file.addProperty("filename","image.png");file.addProperty("url","http://127.0.0.1:"+server.getAddress().getPort()+"/file.png");files.add(file);message.add("attachments",files);
                            var own=message.deepCopy();own.addProperty("id","3234567890123457");own.getAsJsonObject("author").addProperty("bot",true);
                            var webhook=message.deepCopy();webhook.addProperty("id","3234567890123458");webhook.addProperty("webhook_id","9");
                            var all=new JsonArray();all.add(webhook);all.add(own);all.add(message);response=all.toString().getBytes(StandardCharsets.UTF_8);
                        }
                    } else response=("{\"id\":\""+channel+"\",\"type\":0,\"guild_id\":\"8888888888888888\"}").getBytes(StandardCharsets.UTF_8);
                }
                if(status==429)response="{\"retry_after\":2,\"global\":true}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status,response.length);exchange.getResponseBody().write(response);
            }catch(Exception failure){throw new RuntimeException(failure);}finally{exchange.close();}
        });server.start();
        try {
        try(var adapter=new DiscordBotAdapter(URI.create(base),channel,"discraft-local-test-token",true)){
            check(adapter.poll().isEmpty(),"Initial REST cursor skips existing history");
            var incoming=adapter.poll();check(incoming.size()==1,"REST ignores bot and webhook loops");var received=incoming.get(0);
            check(received.catalog().size()==2&&received.emoji().values().stream().anyMatch(a->a.media().animated()),"Official custom static and animated emoji fetched and decoded");
            check(received.attachments().get(0).media().format().equals("png"),"Bounded REST attachment decode");
            check(adapter.poll().isEmpty(),"REST duplicate IDs are suppressed across polling");
            code.set(500);rejected(adapter::poll,"Transient REST outage reported without resetting cursor");int beforeRetry=requests.get();
            check(adapter.poll().isEmpty()&&requests.get()==beforeRetry,"Polling backoff prevents immediate reconnect requests");
            Thread.sleep(2100);check(adapter.poll().isEmpty()&&lastQuery.get().contains("after=3234567890123458"),"Polling reconnect retains cursor and does not replay old messages");
            var outbound=adapter.prepare(RelayRecord.draft(UUID.randomUUID(),"World <:smile:1111111111111111>",gif));check(adapter.sendWithReceipt(outbound).equals(last),"Successful send returns exact message ID for scoped readback");
            String multipart=new String(posts.get(0),StandardCharsets.ISO_8859_1);
            check(multipart.contains("files[0]")&&multipart.contains("allowed_mentions")&&multipart.contains("enforce_nonce")&&multipart.contains("<:smile:1111111111111111>"),"Real multipart send preserves official emoji markup and suppresses mentions");
            var captionless=adapter.prepare(RelayRecord.draft(UUID.randomUUID(),"",gif));
            check(captionless.text().isEmpty()&&captionless.attachments().size()==1,"REST preparation preserves captionless attachments");
            adapter.send(captionless);String imageOnly=new String(posts.get(posts.size()-1),StandardCharsets.ISO_8859_1);
            check(imageOnly.contains("files[0]")&&!imageOnly.contains("content/media unavailable"),"Captionless multipart send does not invent an unavailable-content caption");
            var target=new ActorRef(ActorRef.Kind.DISCORD,"4234567890123456","同名成員");var other=new ActorRef(ActorRef.Kind.DISCORD,"5234567890123456","同名成員");
            var sender=new ActorRef(ActorRef.Kind.MINECRAFT,UUID.randomUUID().toString(),"Alice");String prefix="<:smile:1111111111111111> ",text=prefix+target.label()+" @everyone <@6234567890123456>";
            var selected=new RelayRecord(UUID.randomUUID(),"Alice",text,List.of(),Map.of(),Map.of(),sender,List.of(new Mention(prefix.length(),prefix.length()+target.label().length(),target)));
            var selectedWire=RelayRecord.decode(selected.encode());check(selectedWire.actor().equals(sender)&&selectedWire.mentions().get(0).target().id().equals(target.id())&&!target.equals(other),"Wire actor/mention identity is stable despite duplicate display names");
            var resolved=adapter.prepare(selected);check(resolved.text().substring(resolved.mentions().get(0).start(),resolved.mentions().get(0).end()).equals(target.label()),"Emoji normalization preserves explicit mention span/identity");adapter.send(resolved);
            var mentionPayload=JsonParser.parseString(new String(posts.get(posts.size()-1),StandardCharsets.UTF_8)).getAsJsonObject();var allowed=mentionPayload.getAsJsonObject("allowed_mentions");
            check(allowed.getAsJsonArray("parse").isEmpty()&&allowed.getAsJsonArray("users").size()==1&&allowed.getAsJsonArray("users").get(0).getAsString().equals(target.id())&&!allowed.has("roles"),"Only explicitly selected stable user ID is allowed; everyone/roles/arbitrary IDs remain suppressed");
            adapter.send(RelayRecord.draft(UUID.randomUUID(),target.label()+" @everyone <@6234567890123456>",null));var plainPayload=JsonParser.parseString(new String(posts.get(posts.size()-1),StandardCharsets.UTF_8)).getAsJsonObject();
            check(plainPayload.getAsJsonObject("allowed_mentions").getAsJsonArray("users").isEmpty(),"Typed mention-like display text does not create real Discord pings");
            var spoiler=Attachment.of("SPOILER_image.png",Attachment.fromResource("landscape.png").bytes());adapter.send(RelayRecord.draft(UUID.randomUUID(),"spoiler",spoiler));String spoilerMultipart=new String(posts.get(posts.size()-1),StandardCharsets.ISO_8859_1);
            check(spoilerMultipart.contains("SPOILER_image.png")&&spoilerMultipart.contains("\"is_spoiler\":true"),"Multipart uses spoiler filename and official is_spoiler attachment metadata");
            adapter.send(new RelayRecord(UUID.randomUUID(),"Aitchn","你好",List.of(),Map.of(),Map.of()));
            var namedPayload=JsonParser.parseString(new String(posts.get(posts.size()-1),StandardCharsets.UTF_8)).getAsJsonObject();
            check(namedPayload.get("content").getAsString().equals("<Aitchn> 你好"),"Discord text uses angle-bracket player name and preserves Unicode");
            var convert=DiscordBotAdapter.class.getDeclaredMethod("convert",JsonObject.class);convert.setAccessible(true);
            for(String flag:List.of("is_spoiler","flags")){
                var message=new JsonObject();message.addProperty("id","8234567890123456");message.addProperty("content","spoiler");var source=new JsonObject();source.addProperty("id",target.id());source.addProperty("username","Human");message.add("author",source);
                var files=new JsonArray();var file=new JsonObject();file.addProperty("filename","unprefixed.png");file.addProperty("size",1024);file.addProperty("url","http://127.0.0.1:"+server.getAddress().getPort()+"/file.png");if(flag.equals("flags"))file.addProperty(flag,8);else file.addProperty(flag,true);files.add(file);message.add("attachments",files);
                var hidden=(RelayRecord)convert.invoke(adapter,message);check(hidden.attachments().get(0).name().startsWith("SPOILER_")&&hidden.actor().id().equals(target.id()),"REST "+flag+" normalizes to concealed native media with stable author");
            }
            var fetch=DiscordBotAdapter.class.getDeclaredMethod("fetchMedia",URI.class);fetch.setAccessible(true);
            int redirectBefore=requests.get();rejected(()->fetch.invoke(adapter,URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/redirect")),"Media redirect rejected");check(requests.get()==redirectBefore+1,"Media redirect is not followed");
            rejected(()->fetch.invoke(adapter,URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/huge")),"Oversize HTTP response stream cancelled");
            try(var production=new DiscordBotAdapter(URI.create("https://discord.com/api/v10/"),channel,"discraft-local-test-token",false)){
                int beforeFetch=requests.get();rejected(()->fetch.invoke(production,URI.create("http://127.0.0.1/file")),"Production media rejects loopback before HTTP");
                rejected(()->fetch.invoke(production,URI.create("https://cdn.discordapp.com@127.0.0.1/file")),"Production media rejects disguised host");check(requests.get()==beforeFetch,"Rejected SSRF targets make no requests");
            }
            for(String mode:List.of("ok","fail","invalid")){
                mediaMode.set(mode);int prior=mediaRequests.get();
                var repeated=inbound(adapter,"<:x:1111111111111111>".repeat(80));
                check(mediaRequests.get()==prior+1,"Repeated "+mode+" emoji resolves once per conversion");
                check(repeated.catalog().size()==(mode.equals("ok")?1:0),"Positive and negative "+mode+" emoji cache preserves result");
            }
            mediaMode.set("ok");int mixedBefore=mediaRequests.get();
            var mixed=inbound(adapter,"<:x:1111111111111111> <a:x:1111111111111111>");
            check(mediaRequests.get()==mixedBefore+2&&mixed.catalog().size()==2,"Static and animated IDs have separate cache identities");
            mediaMode.set("fail");var distinct=new StringBuilder();for(int i=0;i<40;i++)distinct.append("<:x:").append(1111111111111111L+i).append("> ");
            int failedBefore=mediaRequests.get();inbound(adapter,distinct.toString());
            check(mediaRequests.get()==failedBefore+8,"Distinct failed emoji bounded to eight total attempts");
            mediaMode.set("ok");int validBefore=mediaRequests.get();
            var validDistinct=inbound(adapter,distinct.toString());
            check(mediaRequests.get()==validBefore+8&&validDistinct.catalog().size()==8,"Distinct successful emoji share eight-attempt work budget");
            mediaMode.set("slow");long started=System.nanoTime();int slowBefore=mediaRequests.get();
            var slow=inbound(adapter,"<:x:1111111111111111>".repeat(80)+"<:y:2222222222222222>");
            check(System.nanoTime()-started<java.util.concurrent.TimeUnit.SECONDS.toNanos(7)&&mediaRequests.get()==slowBefore+1&&slow.catalog().isEmpty(),"Slow repeated and unique emoji share six-second total deadline");
            Thread.sleep(2200);mediaMode.set("ok");
            check(auth.get(),"Bot authorization is confined to REST and omitted on media requests");
            code.set(429);rejected(()->adapter.send(original),"429 rejected without automatic resend");int before=requests.get();rejected(()->adapter.send(original),"Shared rate-limit gate blocks early retries");check(requests.get()==before,"No HTTP call during retry_after");
        }
        try(var adapter=new DiscordBotAdapter(URI.create(base),channel,"discraft-local-test-token",true)){
            bot.set(false);rejected(()->adapter.send(original),"Personal account rejected before sending");bot.set(true);
        }
        try(var adapter=new DiscordBotAdapter(URI.create(base),channel,"discraft-local-test-token",true)){
            adapter.poll();code.set(403);rejected(()->adapter.send(original),"Missing channel permissions fail closed");int before=requests.get();rejected(()->adapter.send(original),"Terminal permission failure requires operator correction");check(requests.get()==before,"No retry after 403");
        }
        try(var adapter=new DiscordBotAdapter(URI.create(base),channel,"discraft-local-test-token",true)){
            receiptValid.set(false);int before=posts.size();
            rejected(()->adapter.send(original),"Invalid receipt reports uncertain delivery");
            check(posts.size()==before+1,"Invalid receipt never retries an already posted message");
        }
        } finally {server.stop(0);}
        System.out.println("PASS "+checks+" bridge protocol checks (loopback HTTP; not live Discord)");
    }
}
