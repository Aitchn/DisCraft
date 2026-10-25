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
    public static void main(String[] args)throws Exception{
        var id=UUID.randomUUID();var gif=Attachment.fromResource("dance.gif");
        var original=RelayRecord.draft(id,"Unicode 世界 👋",gif);var decoded=RelayRecord.decode(original.encode());
        check(decoded.id().equals(id)&&decoded.text().equals(original.text())&&decoded.attachments().get(0).media().frames().size()>1,"Wire roundtrip preserves Unicode and real GIF frames");
        rejected(()->RelayRecord.decode(Arrays.copyOf(original.encode(),20)),"Truncated packet rejected");
        rejected(()->RelayRecord.decode(new byte[RelayRecord.MAX_BYTES+1]),"Oversize packet rejected before decode");
        byte[] trailing=Arrays.copyOf(original.encode(),original.encode().length+1);rejected(()->RelayRecord.decode(trailing),"Trailing bytes rejected");
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);String base="http://127.0.0.1:"+server.getAddress().getPort()+"/api/v10/";
        var posts=new ArrayList<byte[]>();var auth=new AtomicBoolean(true);var bot=new AtomicBoolean(true);var code=new AtomicInteger(200);var requests=new AtomicInteger();var lastQuery=new AtomicReference<String>("");
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
                    if(exchange.getRequestMethod().equals("POST")){posts.add(exchange.getRequestBody().readAllBytes());response=("{\"id\":\""+last+"\"}").getBytes(StandardCharsets.UTF_8);}
                    else if(path.endsWith("/users/@me")){response=("{\"id\":\"9999999999999999\",\"bot\":"+bot.get()+"}").getBytes(StandardCharsets.UTF_8);}
                    else if(path.endsWith("/messages")){
                        if(exchange.getRequestURI().getQuery().contains("limit=1"))response=("[{\"id\":\""+first+"\"}]").getBytes(StandardCharsets.UTF_8);
                        else {
                            var message=new JsonObject();message.addProperty("id",last);message.addProperty("content","Discord 世界 <:smile:1111111111111111> <a:dance:2222222222222222>");
                            var author=new JsonObject();author.addProperty("username","Human");author.addProperty("bot",false);message.add("author",author);
                            var files=new JsonArray();var file=new JsonObject();file.addProperty("size",1024);file.addProperty("filename","image.png");file.addProperty("url","http://127.0.0.1:"+server.getAddress().getPort()+"/file.png");files.add(file);message.add("attachments",files);
                            var own=message.deepCopy();own.addProperty("id","3234567890123457");own.getAsJsonObject("author").addProperty("bot",true);
                            var webhook=message.deepCopy();webhook.addProperty("id","3234567890123458");webhook.addProperty("webhook_id","9");
                            var all=new JsonArray();all.add(webhook);all.add(own);all.add(message);response=all.toString().getBytes(StandardCharsets.UTF_8);
                        }
                    } else response=("{\"id\":\""+channel+"\",\"type\":0}").getBytes(StandardCharsets.UTF_8);
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
            var outbound=adapter.prepare(RelayRecord.draft(UUID.randomUUID(),"World <:smile:1111111111111111>",gif));adapter.send(outbound);
            String multipart=new String(posts.get(0),StandardCharsets.ISO_8859_1);
            check(multipart.contains("files[0]")&&multipart.contains("allowed_mentions")&&multipart.contains("enforce_nonce")&&multipart.contains("<:smile:1111111111111111>"),"Real multipart send preserves official emoji markup and suppresses mentions");
            var captionless=adapter.prepare(RelayRecord.draft(UUID.randomUUID(),"",gif));
            check(captionless.text().isEmpty()&&captionless.attachments().size()==1,"REST preparation preserves captionless attachments");
            adapter.send(captionless);String imageOnly=new String(posts.get(posts.size()-1),StandardCharsets.ISO_8859_1);
            check(imageOnly.contains("files[0]")&&!imageOnly.contains("content/media unavailable"),"Captionless multipart send does not invent an unavailable-content caption");
            var fetch=DiscordBotAdapter.class.getDeclaredMethod("fetchMedia",URI.class);fetch.setAccessible(true);
            int redirectBefore=requests.get();rejected(()->fetch.invoke(adapter,URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/redirect")),"Media redirect rejected");check(requests.get()==redirectBefore+1,"Media redirect is not followed");
            rejected(()->fetch.invoke(adapter,URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/huge")),"Oversize HTTP response stream cancelled");
            try(var production=new DiscordBotAdapter(URI.create("https://discord.com/api/v10/"),channel,"discraft-local-test-token",false)){
                int beforeFetch=requests.get();rejected(()->fetch.invoke(production,URI.create("http://127.0.0.1/file")),"Production media rejects loopback before HTTP");
                rejected(()->fetch.invoke(production,URI.create("https://cdn.discordapp.com@127.0.0.1/file")),"Production media rejects disguised host");check(requests.get()==beforeFetch,"Rejected SSRF targets make no requests");
            }
            for(String mode:List.of("ok","fail","invalid")){
                mediaMode.set(mode);int prior=mediaRequests.get();
                var repeated=adapter.prepare(RelayRecord.draft(UUID.randomUUID(),"<:x:1111111111111111>".repeat(80),null));
                check(mediaRequests.get()==prior+1,"Repeated "+mode+" emoji resolves once per conversion");
                check(repeated.catalog().size()==(mode.equals("ok")?1:0),"Positive and negative "+mode+" emoji cache preserves result");
            }
            mediaMode.set("ok");int mixedBefore=mediaRequests.get();
            var mixed=adapter.prepare(RelayRecord.draft(UUID.randomUUID(),"<:x:1111111111111111> <a:x:1111111111111111>",null));
            check(mediaRequests.get()==mixedBefore+2&&mixed.catalog().size()==2,"Static and animated IDs have separate cache identities");
            mediaMode.set("fail");var distinct=new StringBuilder();for(int i=0;i<40;i++)distinct.append("<:x:").append(1111111111111111L+i).append("> ");
            int failedBefore=mediaRequests.get();adapter.prepare(RelayRecord.draft(UUID.randomUUID(),distinct.toString(),null));
            check(mediaRequests.get()==failedBefore+8,"Distinct failed emoji bounded to eight total attempts");
            mediaMode.set("ok");int validBefore=mediaRequests.get();
            var validDistinct=adapter.prepare(RelayRecord.draft(UUID.randomUUID(),distinct.toString(),null));
            check(mediaRequests.get()==validBefore+8&&validDistinct.catalog().size()==8,"Distinct successful emoji share eight-attempt work budget");
            mediaMode.set("slow");long started=System.nanoTime();int slowBefore=mediaRequests.get();
            var slow=adapter.prepare(RelayRecord.draft(UUID.randomUUID(),"<:x:1111111111111111>".repeat(80)+"<:y:2222222222222222>",null));
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
        } finally {server.stop(0);}
        System.out.println("PASS "+checks+" bridge protocol checks (loopback HTTP; not live Discord)");
    }
}
