package dev.discraft.forge.server;

import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Loopback official-REST peer for member-display conversion; no live credentials or messages. */
final class DiscordAuthorFixtures implements AutoCloseable {
    static final String CHANNEL="1234567890123456",GUILD="8888888888888888",BOT="9999999999999999";
    static final List<String> USERS=List.of("4234567890123456","5234567890123456","6234567890123456","7234567890123456");
    static final List<String> EXPECTED=List.of("HoverTester","小雨 Global","plain_user","Mira [x] \"Hi\"");
    final AtomicInteger memberRequests=new AtomicInteger(),posts=new AtomicInteger();
    private final HttpServer server;private final Map<String,JsonObject> members=new HashMap<>();private int batch;private volatile int delay;private volatile boolean mismatch;
    DiscordAuthorFixtures()throws Exception{
        for(int i=0;i<4;i++){var member=new JsonObject();var user=author(i);member.add("user",user);member.add("roles",new JsonArray());if(i==0||i==3)member.addProperty("nick",EXPECTED.get(i));else member.add("nick",JsonNull.INSTANCE);members.put(USERS.get(i),member);}
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/",exchange->{try{
            String path=exchange.getRequestURI().getPath(),response;
            if(!"Bot discraft-local-test-token".equals(exchange.getRequestHeaders().getFirst("Authorization")))throw new IllegalStateException("Fixture authorization mismatch");
            if(exchange.getRequestMethod().equals("POST")){exchange.getRequestBody().readNBytes(512*1024+1);posts.incrementAndGet();response="{\"id\":\"1234567890123999\"}";}
            else if(path.endsWith("users/@me"))response="{\"id\":\""+BOT+"\",\"bot\":true}";
            else if(path.endsWith("channels/"+CHANNEL))response="{\"id\":\""+CHANNEL+"\",\"type\":0,\"guild_id\":\""+GUILD+"\"}";
            else if(path.endsWith("/members/"+BOT))response="{\"roles\":[]}";
            else if(path.contains("/members/")){memberRequests.incrementAndGet();if(delay>0)try{Thread.sleep(delay);}catch(InterruptedException stopped){Thread.currentThread().interrupt();}String id=path.substring(path.lastIndexOf('/')+1);var member=members.get(id).deepCopy();if(mismatch)member.getAsJsonObject("user").addProperty("id","8234567890123456");response=member.toString();}
            else if(path.endsWith("/emojis"))response="[]";
            else if(path.endsWith("/messages")){if(exchange.getRequestURI().getQuery().contains("limit=1"))response="[{\"id\":\"1234567890123000\"}]";else{var messages=new JsonArray();for(int i=0;i<4;i++){var message=new JsonObject();message.addProperty("id",String.valueOf(1234567890123001L+batch*10+i));message.add("author",author(i));message.addProperty("content",i==0?"文字":i==3?"literal {\"clickEvent\":\"run_command\"} / no action":"display fallback "+i);messages.add(message);}response=messages.toString();}}
            else throw new IllegalStateException("Unexpected fixture route");
            byte[] bytes=response.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
        }finally{exchange.close();}});server.start();
    }
    private static JsonObject author(int index){var user=new JsonObject();user.addProperty("id",USERS.get(index));user.addProperty("username",index==2?"plain_user":"account_"+index);if(index==0)user.addProperty("global_name","Global Alias");else if(index==1)user.addProperty("global_name",EXPECTED.get(1));else user.add("global_name",JsonNull.INSTANCE);return user;}
    DiscordBotAdapter adapter(){return new DiscordBotAdapter(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/api/v10/"),CHANNEL,"discraft-local-test-token",true);}
    void advance(){batch++;}
    void nickname(String id,String nick){members.get(id).addProperty("nick",nick);}
    void slow(){delay=3000;}
    void mismatched(){mismatch=true;}
    @Override public void close(){server.stop(0);}
}
