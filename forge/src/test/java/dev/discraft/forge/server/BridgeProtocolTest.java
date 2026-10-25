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
        var posts=new ArrayList<byte[]>();var auth=new AtomicBoolean(true);var bot=new AtomicBoolean(true);var code=new AtomicInteger(200);var requests=new AtomicInteger();
        String channel="1234567890123456";String first="2234567890123456",last="3234567890123456";
        server.createContext("/",exchange->{
            try{
                requests.incrementAndGet();String path=exchange.getRequestURI().getPath();byte[] response;
                int status=code.getAndSet(200);
                if(path.contains("/emoji/")){if(exchange.getRequestHeaders().getFirst("Authorization")!=null)auth.set(false);response=Attachment.fromResource(path.endsWith("2222222222222222")?"dance.gif":"emoji.png").bytes();}
                else if(path.equals("/file.png")){if(exchange.getRequestHeaders().getFirst("Authorization")!=null)auth.set(false);response=Attachment.fromResource("landscape.png").bytes();}
                else if(path.equals("/huge")){response=new byte[RelayRecord.MAX_BYTES+1];}
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
            var outbound=adapter.prepare(RelayRecord.draft(UUID.randomUUID(),"World <:smile:1111111111111111>",gif));adapter.send(outbound);
            String multipart=new String(posts.get(0),StandardCharsets.ISO_8859_1);
            check(multipart.contains("files[0]")&&multipart.contains("allowed_mentions")&&multipart.contains("enforce_nonce")&&multipart.contains("<:smile:1111111111111111>"),"Real multipart send preserves official emoji markup and suppresses mentions");
            var fetch=DiscordBotAdapter.class.getDeclaredMethod("fetchMedia",URI.class);fetch.setAccessible(true);
            rejected(()->fetch.invoke(adapter,URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/huge")),"Oversize HTTP response stream cancelled");
            try(var production=new DiscordBotAdapter(URI.create("https://discord.com/api/v10/"),channel,"discraft-local-test-token",false)){
                int beforeFetch=requests.get();rejected(()->fetch.invoke(production,URI.create("http://127.0.0.1/file")),"Production media rejects loopback before HTTP");
                rejected(()->fetch.invoke(production,URI.create("https://cdn.discordapp.com@127.0.0.1/file")),"Production media rejects disguised host");check(requests.get()==beforeFetch,"Rejected SSRF targets make no requests");
            }
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
