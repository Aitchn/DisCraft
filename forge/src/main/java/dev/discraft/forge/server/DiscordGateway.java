package dev.discraft.forge.server;

import com.google.gson.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

/** Presence/session only. REST remains the sole message ingress; no gateway message is relayed. */
final class DiscordGateway implements AutoCloseable {
    enum State { STOPPED, CONNECTING, HELLO, IDENTIFYING, RESUMING, READY, BACKOFF, TERMINAL }
    enum Status { CONNECTING, READY, RESUMED, ACK_TIMEOUT, RECONNECTING, IDENTIFY_LIMIT, INVALID_SESSION, TERMINAL, AUTH_FAILURE, CONFIG_FAILURE, BOUNDS, TRANSPORT, STOPPED }
    record Info(URI url,int remaining,long resetMillis){
        Info{endpoint(url);if(remaining<0||remaining>1000||resetMillis<1000||resetMillis>172800000L)throw new IllegalArgumentException("Gateway discovery bounds");}
    }
    interface Socket {void text(String value);void close();void abort();}
    interface Listener {void open(Socket socket);void text(String value);void closed(int code);void failed();void rejected(int status,long retryMillis);}
    interface Connector {void connect(URI uri,Listener listener);}
    private record Input(long generation,int kind,String text,int code,Socket socket){}
    static final int FRAME=262144,QUEUE=16,TOTAL_CHARS=524288;
    private final String token,botId;private final Connector connector;private final LongSupplier clock;private final DoubleSupplier jitter;private final Consumer<Status> report;
    private final ArrayBlockingQueue<Input> inputs=new ArrayBlockingQueue<>(QUEUE);private final AtomicInteger chars=new AtomicInteger();private final AtomicBoolean overflow=new AtomicBoolean();
    private final ScheduledThreadPoolExecutor loop;
    private State state=State.STOPPED;private Info info;private Socket socket;private long generation,sequence=-1,deadline,nextBeat,interval,ackDeadline,nextConnect,identifyAfter;private boolean awaitingAck,needsInfo,closed;
    private String session="";private URI resume;private int failures,remaining,identifies,sent;private long dailyReset,sentWindow,identifyNotBefore;
    DiscordGateway(String token,String botId,Connector connector,LongSupplier clock,DoubleSupplier jitter,Consumer<Status> report,boolean automatic){
        this.token=token;this.botId=botId;this.connector=connector;this.clock=clock;this.jitter=jitter;this.report=report;
        loop=automatic?new ScheduledThreadPoolExecutor(1,r->{var t=new Thread(r,"DisCraft gateway");t.setDaemon(true);return t;}):null;
        if(loop!=null)loop.setRemoveOnCancelPolicy(true);
    }
    static DiscordGateway production(String token,String botId,HttpClient http,Consumer<Status> report){return new DiscordGateway(token,botId,jdk(http),()->TimeUnit.NANOSECONDS.toMillis(System.nanoTime()),Math::random,report,true);}
    static URI endpoint(URI uri){
        String host=uri.getHost();if(!"wss".equals(uri.getScheme())||host==null||!host.matches("gateway(?:-[a-z0-9-]+)?\\.discord\\.gg")||uri.getUserInfo()!=null||uri.getRawQuery()!=null||uri.getRawFragment()!=null||(uri.getPort()!=-1&&uri.getPort()!=443)||!(uri.getPath().isEmpty()||uri.getPath().equals("/")))throw new IllegalArgumentException("Unsupported gateway endpoint");return uri;
    }
    synchronized void start(Info value){if(closed||state!=State.STOPPED)return;info=value;remaining=value.remaining();identifyAfter=clock.getAsLong()+value.resetMillis();dailyReset=clock.getAsLong()+86400000L;sentWindow=clock.getAsLong();state=State.BACKOFF;nextConnect=clock.getAsLong();if(loop!=null)loop.scheduleWithFixedDelay(()->{try{tick();}catch(Exception failure){fail(Status.TRANSPORT,false,1000);}},0,100,TimeUnit.MILLISECONDS);}
    synchronized boolean needsDiscovery(){return needsInfo&&state!=State.STOPPED&&state!=State.TERMINAL;}
    synchronized void discovery(Info value){if(!needsDiscovery())return;info=value;remaining=value.remaining();identifyAfter=clock.getAsLong()+value.resetMillis();needsInfo=false;nextConnect=Math.max(nextConnect,clock.getAsLong());}
    synchronized State state(){return state;}
    private synchronized void offer(Input value){if(state==State.STOPPED||state==State.TERMINAL||value.generation()!=generation){if(value.socket()!=null)value.socket().abort();return;}if(value.text()!=null&&chars.addAndGet(value.text().length())>TOTAL_CHARS){chars.addAndGet(-value.text().length());overflow.set(true);return;}if(!inputs.offer(value)){if(value.text()!=null)chars.addAndGet(-value.text().length());if(value.socket()!=null)value.socket().abort();overflow.set(true);}}
    private void connect(){
        long current=++generation;state=State.CONNECTING;deadline=clock.getAsLong()+10000;report.accept(Status.CONNECTING);
        URI selected=resume!=null&&!session.isEmpty()?resume:info.url();URI target=URI.create(endpoint(selected).toASCIIString()+(selected.getPath().isEmpty()?"/":"")+"?v=10&encoding=json");
        try{connector.connect(target,new Listener(){public void open(Socket s){offer(new Input(current,0,null,0,s));}public void text(String text){if(text.length()>FRAME){synchronized(DiscordGateway.this){if(current==generation&&state!=State.STOPPED&&state!=State.TERMINAL)overflow.set(true);}return;}offer(new Input(current,1,text,0,null));}public void closed(int code){offer(new Input(current,2,null,code,null));}public void failed(){offer(new Input(current,3,null,0,null));}public void rejected(int status,long retryMillis){synchronized(DiscordGateway.this){if(current!=generation||state==State.STOPPED||state==State.TERMINAL)return;offer(new Input(current,4,null,status,null));if(status==429)nextConnect=Math.max(nextConnect,clock.getAsLong()+Math.max(60000,Math.min(3600000,retryMillis)));}}});}catch(Exception failure){fail(Status.TRANSPORT,false,1000);}
    }
    synchronized void tick(){
        if(state==State.STOPPED||state==State.TERMINAL){discard();return;}long now=clock.getAsLong();if(now>=dailyReset){dailyReset=now+86400000L;identifies=0;}
        if(overflow.getAndSet(false)){discard();fail(Status.BOUNDS,false,1000);return;}
        for(int i=0;i<8;i++){var input=inputs.poll();if(input==null)break;if(input.text()!=null)chars.addAndGet(-input.text().length());if(input.generation()!=generation){if(input.socket()!=null)input.socket().abort();continue;}
            try{switch(input.kind()){
                case 0->{if(state!=State.CONNECTING){input.socket().abort();break;}socket=input.socket();state=State.HELLO;deadline=now+10000;}
                case 1->receive(input.text(),now);
                case 2->{int code=input.code();if(Set.of(4004,4010,4011,4012,4013,4014).contains(code)){terminal(code==4004?Status.AUTH_FAILURE:Status.CONFIG_FAILURE);}else fail(Status.RECONNECTING,code==1000||code==1001||code==4007||code==4009,code==4008?60000:1000);}
                case 3->fail(Status.TRANSPORT,false,1000);
                case 4->{long retry=nextConnect;if(input.code()==401)terminal(Status.AUTH_FAILURE);else if(input.code()>=400&&input.code()<500&&input.code()!=429)terminal(Status.CONFIG_FAILURE);else{fail(Status.TRANSPORT,false,input.code()==429?60000:1000);if(input.code()==429)nextConnect=Math.max(nextConnect,retry);}}
            }}catch(Exception invalid){if(state!=State.BACKOFF)fail(Status.BOUNDS,false,1000);}
        }
        if(state==State.BACKOFF&&now>=nextConnect){if(needsInfo)return;if(session.isEmpty()&&now<identifyNotBefore){nextConnect=identifyNotBefore;return;}if(session.isEmpty()&&(remaining==0||identifies>=50)){needsInfo=now>=identifyAfter;nextConnect=Math.max(now+1000,identifyAfter);report.accept(Status.IDENTIFY_LIMIT);return;}connect();return;}
        if(Set.of(State.CONNECTING,State.HELLO,State.IDENTIFYING,State.RESUMING).contains(state)&&now>=deadline){fail(Status.TRANSPORT,false,1000);return;}
        if(interval>0&&socket!=null&&state!=State.BACKOFF&&state!=State.TERMINAL){if(awaitingAck&&now>=ackDeadline){fail(Status.ACK_TIMEOUT,false,1000);return;}if(now>=nextBeat){beat(now);nextBeat=now+interval;}}
    }
    private void receive(String text,long now){
        validateJson(text);var root=JsonParser.parseString(text).getAsJsonObject();int op=(int)number(root.get("op"),0,255);
        if(op==0&&root.has("s")&&!root.get("s").isJsonNull()){long next=number(root.get("s"),0,Long.MAX_VALUE);if(next<=sequence)return;sequence=next;}
        switch(op){
            case 10->{if(state!=State.HELLO)throw new IllegalArgumentException();interval=number(root.getAsJsonObject("d").get("heartbeat_interval"),1000,300000);nextBeat=now+(long)(interval*random());deadline=now+30000;
                var payload=new JsonObject();if(!session.isEmpty()&&resume!=null){payload.addProperty("op",6);var d=new JsonObject();d.addProperty("token",token);d.addProperty("session_id",session);d.addProperty("seq",sequence);payload.add("d",d);state=State.RESUMING;}
                else{if(remaining<=0||identifies>=50){fail(Status.IDENTIFY_LIMIT,true,1000);return;}remaining--;identifies++;identifyNotBefore=now+5500;payload.addProperty("op",2);var d=new JsonObject();d.addProperty("token",token);d.addProperty("intents",0);var properties=new JsonObject();properties.addProperty("os","java");properties.addProperty("browser","DisCraft");properties.addProperty("device","DisCraft");d.add("properties",properties);var presence=new JsonObject();presence.add("since",JsonNull.INSTANCE);presence.add("activities",new JsonArray());presence.addProperty("status","online");presence.addProperty("afk",false);d.add("presence",presence);payload.add("d",d);state=State.IDENTIFYING;}send(payload);}
            case 11->awaitingAck=false;
            case 1->{if(socket==null||interval==0)throw new IllegalArgumentException();beat(now);}
            case 7->fail(Status.RECONNECTING,false,1000);
            case 9->{if(!root.get("d").isJsonPrimitive()||!root.getAsJsonPrimitive("d").isBoolean())throw new IllegalArgumentException();boolean resumable=root.get("d").getAsBoolean();fail(Status.INVALID_SESSION,!resumable,1000+(long)(4000*random()));}
            case 0->{String event=root.get("t").getAsString();if(event.equals("READY")){if(state!=State.IDENTIFYING)throw new IllegalArgumentException();var data=root.getAsJsonObject("d");String id=data.getAsJsonObject("user").get("id").getAsString();String value=data.get("session_id").getAsString();URI url=endpoint(URI.create(data.get("resume_gateway_url").getAsString()));if(!id.equals(botId)||!value.matches("[A-Za-z0-9._-]{1,256}"))throw new IllegalArgumentException();session=value;resume=url;state=State.READY;failures=0;report.accept(Status.READY);}else if(event.equals("RESUMED")){if(state!=State.RESUMING)throw new IllegalArgumentException();state=State.READY;failures=0;report.accept(Status.RESUMED);}}
            default->{/* No event/presence/member/message state is retained or delivered. */}
        }
    }
    private void beat(long now){var p=new JsonObject();p.addProperty("op",1);if(sequence<0)p.add("d",JsonNull.INSTANCE);else p.addProperty("d",sequence);send(p);if(!awaitingAck){awaitingAck=true;ackDeadline=now+interval;}}
    private static long number(JsonElement value,long min,long max){if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isNumber()||!value.toString().matches("[0-9]{1,19}"))throw new IllegalArgumentException();long parsed=Long.parseLong(value.toString());if(parsed<min||parsed>max)throw new IllegalArgumentException();return parsed;}
    private void send(JsonObject payload){String text=payload.toString();if(text.length()>15000||socket==null)throw new IllegalArgumentException();long now=clock.getAsLong();if(now-sentWindow>=60000){sentWindow=now;sent=0;}if(sent>=110){fail(Status.IDENTIFY_LIMIT,false,60000);throw new IllegalStateException("Gateway outbound rate budget");}sent++;socket.text(text);}
    private double random(){double value=jitter.getAsDouble();return Double.isFinite(value)?Math.max(0,Math.min(.999999,value)):.5;}
    private synchronized void fail(Status status,boolean clear,long delay){if(state==State.STOPPED||state==State.TERMINAL||state==State.BACKOFF)return;report.accept(status);if(socket!=null)socket.abort();socket=null;generation++;interval=0;awaitingAck=false;if(clear){session="";resume=null;sequence=-1;}state=State.BACKOFF;long backoff=Math.min(60000L,1000L<<Math.min(6,failures++));nextConnect=clock.getAsLong()+Math.max(delay,(long)(backoff*(.5+random()/2)));}
    private void terminal(Status status){report.accept(status);if(socket!=null)socket.abort();socket=null;generation++;session="";resume=null;state=State.TERMINAL;interval=0;discard();}
    private void discard(){for(Input i;(i=inputs.poll())!=null;)if(i.socket()!=null)i.socket().abort();chars.set(0);}
    @Override public synchronized void close(){if(closed)return;closed=true;state=State.STOPPED;generation++;session="";resume=null;interval=0;if(socket!=null){socket.close();socket=null;}discard();if(loop!=null)loop.shutdownNow();report.accept(Status.STOPPED);}
    static void validateJson(String text){if(text.length()>FRAME)throw new IllegalArgumentException();int depth=0;boolean string=false,escape=false;for(int i=0;i<text.length();i++){char c=text.charAt(i);if(string){if(escape)escape=false;else if(c=='\\')escape=true;else if(c=='"')string=false;}else if(c=='"')string=true;else if(c=='{'||c=='['){if(++depth>32)throw new IllegalArgumentException();}else if(c=='}'||c==']'){if(--depth<0)throw new IllegalArgumentException();}}if(string||depth!=0)throw new IllegalArgumentException();}
    private static Connector jdk(HttpClient http){return (uri,sink)->http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5)).buildAsync(uri,new WebSocket.Listener(){
        private final StringBuilder text=new StringBuilder();private int fragments;
        public void onOpen(WebSocket ws){sink.open(new Socket(){public void text(String value){ws.sendText(value,true).whenComplete((v,e)->{if(e!=null)sink.failed();});}public void abort(){ws.abort();}public void close(){try{ws.sendClose(WebSocket.NORMAL_CLOSURE,"").orTimeout(500,TimeUnit.MILLISECONDS).whenComplete((v,e)->ws.abort());}catch(Exception ignored){ws.abort();}}});ws.request(1);}
        public CompletionStage<?> onText(WebSocket ws,CharSequence data,boolean last){if(data.length()>FRAME-text.length()||++fragments>128){text.setLength(0);ws.abort();sink.failed();return CompletableFuture.completedFuture(null);}text.append(data);if(last){sink.text(text.toString());text.setLength(0);fragments=0;}ws.request(1);return CompletableFuture.completedFuture(null);}
        public CompletionStage<?> onBinary(WebSocket ws,java.nio.ByteBuffer data,boolean last){ws.abort();sink.failed();return CompletableFuture.completedFuture(null);}
        public CompletionStage<?> onClose(WebSocket ws,int code,String reason){sink.closed(code);return CompletableFuture.completedFuture(null);}
        public void onError(WebSocket ws,Throwable error){sink.failed();}
    }).whenComplete((ws,error)->{if(error!=null){Throwable cause=error;for(int n=0;n<8&&cause instanceof CompletionException&&cause.getCause()!=null;n++)cause=cause.getCause();if(cause instanceof WebSocketHandshakeException failed){int status=failed.getResponse().statusCode();long wait=3600000;try{double seconds=Double.parseDouble(failed.getResponse().headers().firstValue("retry-after").orElse("60"));if(Double.isFinite(seconds)&&seconds>=0&&seconds<=3600)wait=(long)Math.ceil(seconds*1000);}catch(Exception ignored){}sink.rejected(status,wait);}else sink.failed();}});}
}
