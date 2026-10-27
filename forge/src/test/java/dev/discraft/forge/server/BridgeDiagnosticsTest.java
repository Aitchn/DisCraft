package dev.discraft.forge.server;

import com.sun.net.httpserver.HttpServer;
import dev.discraft.forge.bridge.RelayRecord;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Loopback-only failures and fake secrets. Never contacts Discord. */
final class BridgeDiagnosticsTest {
    private static final String SECRET="FAKE-private-body-query-message",TOKEN="discraft-local-test-token",CHANNEL="1234567890123456";
    private static int checks;
    private static void check(boolean value,String label){if(!value)throw new AssertionError(label);checks++;System.out.println("PASS "+label);}
    static void run()throws Exception{
        var clock=new AtomicLong();var messages=new ArrayList<String>();var bounded=new BridgeDiagnostics((w,s)->messages.add(s),clock::get,true);
        for(int i=0;i<100;i++)bounded.report(BridgeDiagnostics.Code.OPERATION_FAILED,BridgeDiagnostics.Operation.POLL,0,BridgeDiagnostics.Terminal.NO);
        check(messages.size()==1,"Repeated identical bridge failures log once per minute");
        for(int i=100;i<500;i++)bounded.report(BridgeDiagnostics.Code.HTTP_FAILED,BridgeDiagnostics.Operation.POLL,i,BridgeDiagnostics.Terminal.NO);
        check(messages.size()==8&&bounded.retainedKeys()<=64,"Changing HTTP failures share a bounded global log budget and bounded dedup state");
        bounded.report(BridgeDiagnostics.Code.AUTH_REJECTED,BridgeDiagnostics.Operation.IDENTITY,401,BridgeDiagnostics.Terminal.YES);
        check(messages.size()==9&&messages.get(8).contains("terminal=YES"),"First terminal cause remains visible even after the ordinary log budget is exhausted");
        clock.addAndGet(TimeUnit.SECONDS.toNanos(61));bounded.report(BridgeDiagnostics.Code.OPERATION_FAILED,BridgeDiagnostics.Operation.POLL,0,BridgeDiagnostics.Terminal.NO);
        check(messages.size()==10,"Suppressed failures can be reported again after the bounded interval");
        var status=new AtomicInteger(200);var calls=new AtomicInteger();var posts=new AtomicInteger();var malformed=new AtomicBoolean();var slow=new AtomicBoolean();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var executor=Executors.newFixedThreadPool(2,r->{var t=new Thread(r,"diagnostic-loopback");t.setDaemon(true);return t;});server.setExecutor(executor);
        server.createContext("/",exchange->{try{
            calls.incrementAndGet();if(slow.get())try{Thread.sleep(700);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
            String path=exchange.getRequestURI().getPath(),body=SECRET;int code=status.get();
            if(code==200&&!malformed.get()){
                if(path.equals("/users/@me"))body="{\"id\":\"2234567890123456\",\"bot\":true}";
                else if(path.equals("/channels/"+CHANNEL))body="{\"id\":\""+CHANNEL+"\",\"type\":0,\"guild_id\":\"3234567890123456\"}";
                else if(path.endsWith("/members/2234567890123456"))body="{\"roles\":[]}";
                else if(exchange.getRequestMethod().equals("POST")){posts.incrementAndGet();body="{\"id\":\"4234567890123456\"}";}
                else body="[]";
            }
            if(code==429)body="{\"retry_after\":60,\"private\":\""+SECRET+"\"}";
            exchange.getResponseHeaders().add("X-Private",SECRET);byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(code,bytes.length);exchange.getResponseBody().write(bytes);
        }catch(Exception ignored){}finally{exchange.close();}});server.start();URI uri=URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/");
        try{
            for(int code:new int[]{401,403,404,429,503}){
                status.set(code);messages.clear();var diagnostics=new BridgeDiagnostics((w,s)->messages.add(s),System::nanoTime,true);int before=calls.get();
                try(var adapter=new DiscordBotAdapter(uri,CHANNEL,TOKEN,true,diagnostics)){
                    adapter.catalog();String joined=String.join("\n",messages);
                    check(joined.contains("http="+code)&&joined.contains(code==401?"AUTH_REJECTED":code==403?"PERMISSION_REJECTED":code==404?"NOT_FOUND":code==429?"RATE_LIMITED":"HTTP_FAILED"),"Mock HTTP "+code+" produces a safe explicit diagnostic category");
                    if(code==401||code==403){for(int i=0;i<20;i++){adapter.catalog();adapter.poll();NoticeDelivery.task(adapter,RelayRecord.draft(UUID.randomUUID(),SECRET,null)).run();}check(calls.get()==before+1&&String.join("\n",messages).contains("terminal=YES"),"Terminal HTTP "+code+" remains stopped without requests or automatic notice retries");}
                    if(code==429){adapter.poll();try{adapter.send(RelayRecord.draft(UUID.randomUUID(),SECRET,null));}catch(Exception expected){}check(calls.get()==before+1,"Rate-limit diagnostics preserve the shared retry gate without an early resend");}
                    check(messages.size()<=8&&messages.stream().allMatch(s->s.contains("TEST bridge"))&&messages.stream().noneMatch(s->s.contains(SECRET)||s.contains(TOKEN)||s.contains(CHANNEL)||s.contains("127.0.0.1")||s.contains("Authorization")||s.contains("X-Private")),"Failure logs omit response/header/message/query/identity data and clearly identify mock mode");
                }
            }
            status.set(200);malformed.set(true);messages.clear();
            try(var adapter=new DiscordBotAdapter(uri,CHANNEL,TOKEN,true,new BridgeDiagnostics((w,s)->messages.add(s),System::nanoTime,true))){adapter.catalog();check(messages.stream().anyMatch(s->s.contains("INVALID_RESPONSE"))&&messages.stream().noneMatch(s->s.contains(SECRET)||s.contains("TARGET_VERIFIED")||s.contains("READ_READY")),"Malformed success-body data is redacted and never reported as API readiness");}
            malformed.set(false);slow.set(true);messages.clear();
            try(var adapter=new DiscordBotAdapter(uri,CHANNEL,TOKEN,true,new BridgeDiagnostics((w,s)->messages.add(s),System::nanoTime,true))){
                var request=DiscordBotAdapter.class.getDeclaredMethod("request",String.class,String.class,byte[].class,String.class,long.class,int.class);request.setAccessible(true);
                try{request.invoke(adapter,"GET","users/@me?private="+SECRET,null,null,System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(120),8192);throw new AssertionError("Delayed request accepted");}catch(java.lang.reflect.InvocationTargetException expected){}
                check(messages.stream().anyMatch(s->s.contains("code=TIMEOUT"))&&messages.stream().noneMatch(s->s.contains(SECRET)||s.contains("127.0.0.1")),"Actual bounded loopback timeout is classified without endpoint, query or exception disclosure");
            }
            slow.set(false);messages.clear();
            try(var adapter=new DiscordBotAdapter(uri,CHANNEL,TOKEN,true,new BridgeDiagnostics((w,s)->messages.add(s),System::nanoTime,true))){adapter.poll();check(messages.stream().anyMatch(s->s.contains("TARGET_VERIFIED"))&&messages.stream().anyMatch(s->s.contains("READ_READY"))&&messages.stream().noneMatch(s->s.contains("SEND_ACCEPTED")),"Successful mock identity/read checks explicitly stop short of claiming outbound delivery");}
            check(posts.get()==0,"Diagnostic scenarios produce no POST requests or automatic resend loops");
        }finally{server.stop(0);executor.shutdownNow();}
        messages.clear();int port;try(var reservation=new java.net.ServerSocket(0,0,InetAddress.getByName("127.0.0.1"))){port=reservation.getLocalPort();}
        try(var adapter=new DiscordBotAdapter(URI.create("http://127.0.0.1:"+port+"/"),CHANNEL,TOKEN,true,new BridgeDiagnostics((w,s)->messages.add(s),System::nanoTime,true))){adapter.catalog();check(messages.stream().anyMatch(s->s.contains("NETWORK_FAILED"))&&messages.stream().noneMatch(s->s.contains("127.0.0.1")||s.contains(TOKEN)||s.contains(CHANNEL)),"Actual refused loopback connection yields a redacted network category");}
        System.out.println("PASS "+checks+" safe diagnostic and HTTP failure checks (loopback only; not live Discord)");
    }
}
