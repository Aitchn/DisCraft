package dev.discraft.forge.server;

import com.google.gson.*;
import java.net.URI;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Deterministic session transport/clock; never creates a network client or uses a real credential. */
final class DiscordGatewayTest {
    private static int checks;
    private static final String BOT="1234567890123456",TOKEN="fake-gateway-test-token";
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);checks++;System.out.println("PASS "+message);}
    private static final class Wire implements DiscordGateway.Socket {
        final List<JsonObject> sent=new ArrayList<>();boolean aborted,closed;
        public void text(String value){sent.add(JsonParser.parseString(value).getAsJsonObject());}public void close(){closed=true;}public void abort(){aborted=true;}
        int op(){return sent.get(sent.size()-1).get("op").getAsInt();}
    }
    private static final class Fixture implements AutoCloseable {
        final AtomicLong time=new AtomicLong(100);final List<DiscordGateway.Status> statuses=new ArrayList<>();final List<URI> urls=new ArrayList<>();
        DiscordGateway.Listener listener;Wire wire;java.util.function.Consumer<JsonObject> dispatched=d->{};java.util.function.Consumer<DiscordGateway.Status> reported=x->{};
        final DiscordGateway gateway=new DiscordGateway(TOKEN,BOT,(url,sink)->{urls.add(url);listener=sink;wire=new Wire();sink.open(wire);},time::get,()->.5,x->{statuses.add(x);reported.accept(x);},false,d->dispatched.accept(d));
        void start(int remaining){gateway.start(new DiscordGateway.Info(URI.create("wss://gateway.discord.gg"),remaining,10000));gateway.tick();gateway.tick();}
        void input(String json){listener.text(json);gateway.tick();}
        void hello(){input("{\"op\":10,\"d\":{\"heartbeat_interval\":1000}}");}
        void ready(){input("{\"op\":0,\"s\":1,\"t\":\"READY\",\"d\":{\"user\":{\"id\":\""+BOT+"\"},\"session_id\":\"fake-session\",\"resume_gateway_url\":\"wss://gateway-us-east1-b.discord.gg\"}}");}
        void advance(long amount){time.addAndGet(amount);gateway.tick();}
        void reconnect(){advance(60001);gateway.tick();hello();}
        public void close(){gateway.close();}
    }
    static void run()throws Exception{
        try(var f=new Fixture()){
            f.start(10);check(f.gateway.state()==DiscordGateway.State.HELLO&&f.urls.size()==1,"Gateway opens one bounded connection and waits for HELLO");f.hello();
            var identify=f.wire.sent.get(0).getAsJsonObject("d");check(f.wire.op()==2&&identify.get("intents").getAsInt()==(512|32768)&&identify.getAsJsonObject("presence").get("status").getAsString().equals("online"),"HELLO identifies with GUILD_MESSAGES and MESSAGE_CONTENT intents and explicit online presence");
            check(identify.get("token").getAsString().equals(TOKEN)&&f.statuses.stream().noneMatch(s->s.name().contains(TOKEN)),"Credential exists only in the mock identify payload, never a reported status");
            f.ready();check(f.gateway.state()==DiscordGateway.State.READY,"Only validated READY establishes the online session");f.advance(500);check(f.wire.op()==1&&f.wire.sent.get(1).get("d").getAsLong()==1,"Jittered heartbeat carries the last dispatch sequence");
            f.input("{\"op\":11,\"d\":null}");f.advance(1000);check(f.gateway.state()==DiscordGateway.State.READY&&f.wire.op()==1,"Heartbeat ACK permits the next scheduled heartbeat");
            var old=f.wire;f.advance(1000);check(old.aborted&&f.gateway.state()==DiscordGateway.State.BACKOFF&&f.statuses.contains(DiscordGateway.Status.ACK_TIMEOUT),"Missing ACK aborts the socket and enters bounded backoff");
            f.reconnect();check(f.wire.op()==6&&f.wire.sent.get(0).getAsJsonObject("d").get("session_id").getAsString().equals("fake-session")&&f.urls.get(1).getHost().equals("gateway-us-east1-b.discord.gg"),"Reconnect uses the validated resume endpoint and in-memory session sequence");
            f.input("{\"op\":0,\"s\":2,\"t\":\"RESUMED\",\"d\":{}}");check(f.gateway.state()==DiscordGateway.State.READY,"RESUMED restores state without a second identify");
            var seen=new ArrayList<JsonObject>();f.dispatched=seen::add;
            f.input("{\"op\":0,\"s\":3,\"t\":\"MESSAGE_CREATE\",\"d\":{\"id\":\"7\",\"content\":\"fixture text\"}}");check(f.gateway.state()==DiscordGateway.State.READY&&f.wire.sent.size()==1&&seen.size()==1&&seen.get(0).get("content").getAsString().equals("fixture text"),"Gateway MESSAGE_CREATE is forwarded to the sink without any outbound send");
            f.input("{\"op\":0,\"s\":3,\"t\":\"MESSAGE_CREATE\",\"d\":{\"id\":\"7\",\"content\":\"replayed\"}}");check(seen.size()==1,"A repeated or older dispatch sequence is never forwarded twice");
            f.input("{\"op\":0,\"s\":4,\"t\":\"MESSAGE_UPDATE\",\"d\":{\"id\":\"7\"}}");f.input("{\"op\":0,\"s\":5,\"t\":\"MESSAGE_CREATE\",\"d\":[]}");check(seen.size()==1&&f.gateway.state()==DiscordGateway.State.READY,"Other events and non-object payloads are ignored without disturbing the session");
            f.dispatched=d->{throw new IllegalStateException("sink fault");};f.input("{\"op\":0,\"s\":6,\"t\":\"MESSAGE_CREATE\",\"d\":{\"id\":\"8\"}}");check(f.gateway.state()==DiscordGateway.State.READY,"A faulting message sink cannot tear down the Gateway session");f.dispatched=seen::add;
            f.input("{\"op\":1,\"d\":null}");check(f.wire.op()==1,"Server heartbeat requests receive an immediate heartbeat");
            var listener=f.listener;int connections=f.urls.size();f.gateway.close();listener.text("{\"op\":7}");f.advance(100000);f.gateway.start(new DiscordGateway.Info(URI.create("wss://gateway.discord.gg"),10,10000));f.gateway.tick();check(f.gateway.state()==DiscordGateway.State.STOPPED&&f.urls.size()==connections&&f.wire.closed,"Shutdown and late callbacks cannot reopen or resurrect a session");
        }
        for(int close:List.of(4004,4010,4011,4012,4013,4014))try(var f=new Fixture()){f.start(10);f.listener.closed(close);f.gateway.tick();f.advance(100000);check(f.gateway.state()==DiscordGateway.State.TERMINAL&&f.urls.size()==1,"Fatal Gateway close never reconnects: "+close);}
        for(int close:List.of(4007,4009,1000,1001))try(var f=new Fixture()){f.start(10);f.hello();f.ready();f.listener.closed(close);f.gateway.tick();f.reconnect();check(f.wire.op()==2,"Non-resumable close drops session before bounded re-identify: "+close);}
        for(boolean resumable:List.of(false,true))try(var f=new Fixture()){f.start(10);f.hello();f.ready();f.input("{\"op\":9,\"d\":"+resumable+"}");f.reconnect();check(f.wire.op()==(resumable?6:2),"INVALID_SESSION honors its resumability and delayed reconnect: "+resumable);}
        try(var f=new Fixture()){f.start(0);check(f.urls.isEmpty(),"Exhausted discovery identify budget never opens a connection");f.advance(10000);check(f.gateway.needsDiscovery()&&f.urls.isEmpty(),"Budget reset requires fresh authoritative discovery rather than inventing quota");f.gateway.discovery(new DiscordGateway.Info(URI.create("wss://gateway.discord.gg"),1,10000));f.advance(1000);f.gateway.tick();f.hello();check(f.wire.op()==2,"Fresh discovery quota permits one identify");}
        try(var f=new Fixture()){f.start(10);f.hello();f.ready();var previous=f.listener;f.input("{\"op\":7}");f.reconnect();previous.failed();f.gateway.tick();check(f.gateway.state()==DiscordGateway.State.RESUMING,"Stale transport failures cannot disconnect a newer socket generation");}
        try(var f=new Fixture()){f.start(10);for(int i=0;i<32;i++)f.listener.text("{\"op\":11}");f.gateway.tick();check(f.gateway.state()==DiscordGateway.State.BACKOFF&&f.statuses.contains(DiscordGateway.Status.BOUNDS),"Inbound frame queue overflow aborts rather than retaining an unbounded backlog");}
        try(var f=new Fixture()){f.start(10);f.listener.text("x".repeat(DiscordGateway.FRAME+1));f.gateway.tick();check(f.gateway.state()==DiscordGateway.State.BACKOFF,"Oversized frames fail before JSON parsing");}
        try(var f=new Fixture()){f.start(10);f.hello();f.ready();f.input("{\"op\":9,\"d\":false}");f.advance(3000);check(f.urls.size()==1,"Fresh IDENTIFY attempts respect the same-shard five-second concurrency window");}
        try(var f=new Fixture()){f.start(10);f.hello();f.ready();for(int i=0;i<120;i++)f.input("{\"op\":1,\"d\":null}");check(f.wire.sent.size()<=110&&f.gateway.state()==DiscordGateway.State.BACKOFF,"Gateway event sends share a fixed global minute budget across forced heartbeats");f.advance(59000);check(f.urls.size()==1,"Outbound rate budget backoff cannot be shortened by later failures");}
        for(int status:List.of(401,403))try(var f=new Fixture()){f.start(10);f.listener.rejected(status,0);f.gateway.tick();f.advance(100000);check(f.gateway.state()==DiscordGateway.State.TERMINAL&&f.urls.size()==1,"WebSocket HTTP handshake rejection is terminal without identity retry: "+status);}
        try(var f=new Fixture()){f.start(10);f.listener.rejected(429,120000);f.gateway.tick();f.advance(119000);check(f.urls.size()==1,"WebSocket HTTP 429 respects bounded Retry-After without sending identify");}
        try{DiscordGateway.validateJson("[".repeat(33)+"0"+"]".repeat(33));throw new AssertionError("Depth accepted");}catch(IllegalArgumentException expected){check(true,"Gateway JSON nesting is bounded before recursive Gson parsing");}
        for(String value:List.of("https://gateway.discord.gg","wss://evil.example","wss://gateway.discord.gg.evil.example","wss://user:pass@gateway.discord.gg","wss://gateway.discord.gg/?token=fake","wss://gateway.discord.gg/redirect","wss://gateway.discord.gg:444"))try{DiscordGateway.endpoint(URI.create(value));throw new AssertionError("Endpoint accepted");}catch(IllegalArgumentException expected){check(true,"Noncanonical Gateway destination rejected without credentials: "+checks);}
        try(var f=new Fixture()){var seen=new ArrayList<JsonObject>();f.dispatched=seen::add;f.start(10);f.hello();f.input("{\"op\":0,\"s\":1,\"t\":\"MESSAGE_CREATE\",\"d\":{\"id\":\"9\"}}");check(seen.isEmpty(),"Message dispatch before READY/RESUMING is not forwarded");}
        try(var f=new Fixture()){var seen=new ArrayList<JsonObject>();f.dispatched=seen::add;f.start(10);f.hello();f.ready();f.input("{\"op\":7}");f.reconnect();f.input("{\"op\":0,\"s\":2,\"t\":\"MESSAGE_CREATE\",\"d\":{\"id\":\"11\"}}");check(f.gateway.state()==DiscordGateway.State.RESUMING&&seen.size()==1,"Events replayed while RESUMING are delivered before RESUMED");}
        coexist();
        ingress();
        check(DiscordBotAdapter.mediaType(new byte[]{(byte)0x89,'P','N','G',0,0,0,0}).equals("image/png")&&DiscordBotAdapter.mediaType("GIF89a".getBytes()).equals("image/gif")&&DiscordBotAdapter.mediaType(new byte[]{1,2,3}).equals("application/octet-stream"),"Uploaded item/media parts declare a sniffed image content type");
        System.out.println("PASS "+checks+" Gateway clock/transport/session checks (mock only; no live identity/presence)");
    }
    /** Gateway ingress vs REST fallback through the adapter, against a loopback REST fixture. */
    private static void ingress()throws Exception{
        var gets=new java.util.concurrent.atomic.AtomicInteger();String channel="3234567890123456";var rest=new java.util.concurrent.atomic.AtomicReference<String>("[]");
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{try{String path=exchange.getRequestURI().getPath(),body="[]";
            if(path.equals("/users/@me"))body="{\"id\":\""+BOT+"\",\"bot\":true}";
            else if(path.equals("/channels/"+channel))body="{\"id\":\""+channel+"\",\"guild_id\":\"4234567890123456\",\"type\":0}";
            else if(path.equals("/gateway/bot"))body="{\"url\":\"wss://gateway.discord.gg\",\"shards\":1,\"session_start_limit\":{\"remaining\":10,\"reset_after\":10000,\"max_concurrency\":1}}";
            else if(path.equals("/channels/"+channel+"/messages")){gets.incrementAndGet();body=rest.get();}
            else if(path.endsWith("/members/"+BOT))body="{\"roles\":[]}";
            byte[] bytes=body.getBytes(java.nio.charset.StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
        }finally{exchange.close();}});server.start();
        String a="5234567890123401",c="5234567890123403",b="5234567890123404",other="5234567890123402";
        java.util.function.Function<String,String> message=id->"{\"id\":\""+id+"\",\"channel_id\":\""+channel+"\",\"content\":\"hello "+id+"\",\"author\":{\"id\":\"6234567890123456\",\"username\":\"human\"},\"attachments\":[]}";
        try(var f=new Fixture();var adapter=new DiscordBotAdapter(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/"),channel,"discraft-local-test-token",true,new BridgeDiagnostics((w,s)->{},System::nanoTime,true))){
            f.dispatched=d->adapter.pushed(d);f.reported=adapter::gatewayStatus;adapter.gatewayForTest(info->f.gateway);
            check(adapter.poll().isEmpty()&&gets.get()==1,"First poll establishes the REST baseline cursor");f.gateway.tick();f.gateway.tick();f.hello();f.ready();
            check(adapter.poll().isEmpty()&&gets.get()==2,"One REST catch-up poll runs after the Gateway becomes live");
            int before=gets.get();f.input("{\"op\":0,\"s\":2,\"t\":\"MESSAGE_CREATE\",\"d\":"+message.apply(a)+"}");
            check(adapter.hasPending(),"A pushed message makes the bridge poll immediately");
            var records=adapter.poll();check(records.size()==1&&records.get(0).text().equals("hello "+a)&&gets.get()==before,"Live Gateway delivers MESSAGE_CREATE without any REST message request");
            f.input("{\"op\":0,\"s\":3,\"t\":\"MESSAGE_CREATE\",\"d\":"+message.apply(a)+"}");check(adapter.poll().isEmpty(),"A duplicate Gateway message is not delivered twice");
            f.input("{\"op\":0,\"s\":4,\"t\":\"MESSAGE_CREATE\",\"d\":"+message.apply(other).replace(channel,"9999999999999999")+"}");check(!adapter.hasPending()&&adapter.poll().isEmpty(),"Messages for other channels are ignored");
            f.input("{\"op\":0,\"s\":5,\"t\":\"MESSAGE_CREATE\",\"d\":"+message.apply(c).replace("\"author\":{","\"author\":{\"bot\":true,")+"}");check(adapter.poll().isEmpty(),"Bot-authored Gateway messages are not relayed");
            // Outage: REST resumes as fallback and finds the message missed while down; the Gateway copy of it is deduplicated.
            f.listener.closed(4000);f.gateway.tick();rest.set("["+message.apply(b)+"]");int down=gets.get();
            records=adapter.poll();check(gets.get()==down+1&&records.size()==1&&records.get(0).text().equals("hello "+b),"REST polling takes over while the Gateway is down");
            f.advance(60001);f.gateway.tick();f.hello();f.input("{\"op\":0,\"s\":6,\"t\":\"RESUMED\",\"d\":{}}");f.input("{\"op\":0,\"s\":7,\"t\":\"MESSAGE_CREATE\",\"d\":"+message.apply(b)+"}");
            rest.set("[]");records=adapter.poll();check(records.isEmpty(),"A message seen by REST during the outage is not duplicated by the resumed Gateway");
            int settled=gets.get();adapter.poll();check(gets.get()==settled,"REST stops polling once the post-outage catch-up completes");
        }finally{server.stop(0);}
    }
    private static void coexist()throws Exception{
        for(int status:List.of(200,401,403)){
            var calls=new java.util.concurrent.atomic.AtomicInteger();var posts=new java.util.concurrent.atomic.AtomicInteger();var logs=new ArrayList<String>();String channel="3234567890123456";
            var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/",exchange->{try{String path=exchange.getRequestURI().getPath(),body="[]";int code=200;
                if(path.equals("/users/@me"))body="{\"id\":\""+BOT+"\",\"bot\":true}";
                else if(path.equals("/channels/"+channel))body="{\"id\":\""+channel+"\",\"guild_id\":\"4234567890123456\",\"type\":0}";
                else if(path.equals("/gateway/bot")){calls.incrementAndGet();code=status;body=status==200?"{\"url\":\"wss://gateway.discord.gg\",\"shards\":1,\"session_start_limit\":{\"remaining\":10,\"reset_after\":10000,\"max_concurrency\":1}}":"fake-private-rejection-body";}
                else if(path.endsWith("/members/"+BOT))body="{\"roles\":[]}";
                else if(exchange.getRequestMethod().equals("POST")){posts.incrementAndGet();body="{\"id\":\"5234567890123456\"}";}
                byte[] bytes=body.getBytes(java.nio.charset.StandardCharsets.UTF_8);exchange.sendResponseHeaders(code,bytes.length);exchange.getResponseBody().write(bytes);
            }finally{exchange.close();}});server.start();
            try(var f=new Fixture();var adapter=new DiscordBotAdapter(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/"),channel,"discraft-local-test-token",true,new BridgeDiagnostics((w,s)->logs.add(s),System::nanoTime,true))){
                adapter.gatewayForTest(info->f.gateway);check(adapter.catalog().ready()&&adapter.poll().isEmpty(),"Gateway discovery status "+status+" cannot disable REST catalog or initial read");
                if(status==200){f.gateway.tick();f.gateway.tick();f.hello();f.ready();f.listener.closed(4004);f.gateway.tick();check(f.gateway.state()==DiscordGateway.State.TERMINAL,"A terminal mock Gateway session remains independent of REST");}
                adapter.send(dev.discraft.forge.bridge.RelayRecord.draft(UUID.randomUUID(),"explicit loopback send",null));check(posts.get()==1&&calls.get()==1,"Exactly one explicit REST send succeeds after Gateway status "+status+"; no discovery retry loop");
                check(logs.stream().noneMatch(s->s.contains("discraft-local-test-token")||s.contains(channel)||s.contains("fake-private-rejection-body")),"Gateway discovery diagnostics exclude authorization, target IDs and response bodies");
            }finally{server.stop(0);}
        }
    }
}
