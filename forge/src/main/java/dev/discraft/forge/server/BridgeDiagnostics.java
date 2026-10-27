package dev.discraft.forge.server;

import java.nio.file.Path;
import java.util.*;
import java.util.function.*;
import java.util.concurrent.TimeUnit;

/** Only closed enums/numeric HTTP status reach logs. No Throwable, URI, body, IDs or user text API. */
public final class BridgeDiagnostics {
    public enum Operation { IDENTITY, CHANNEL, CATALOG, MEMBER, POLL, SEND, NOTICE, MEDIA, BACKGROUND, GATEWAY, NATIVE }
    enum Terminal { YES, NO, UNKNOWN }
    enum Code {
        NATIVE_ADMITTED(false,"Native chat admission recorded; no send is implied"),
        NATIVE_ORIGIN_HOOK(false,"Native origin hook observed a player packet before transport conversion"),
        NATIVE_CONVERTED(false,"Converted system/disguised packet retains a nested native origin and recipient view"),
        NATIVE_CONFIRMED(false,"Native recipient delivery confirmed; queued for independent Discord send validation"),
        NATIVE_NOT_ADMITTED(true,"Native chat not admitted: unavailable peer, policy, lifecycle or rate budget"),
        NATIVE_NO_RECEIPT(true,"Native admission expired without a correlatable recipient packet; chat mod integration may be required"),
        NATIVE_CHANGED(true,"Native presentation cancelled, filtered or changed; original text was not forwarded"),
        NATIVE_UNSUPPORTED(true,"Native packet/presentation cannot be safely correlated; original text was not forwarded"),
        NATIVE_OBSERVER_MISSING(true,"Native packet encoder observation unavailable; original text was not forwarded"),
        TARGET_VERIFIED(false,"Bot identity and configured guild text channel validated; read/send delivery not yet proven"),
        READ_READY(false,"A valid message page was read; no outbound delivery is implied"),
        AUTH_REJECTED(true,"Bot authorization rejected; correct server config and restart"),
        PERMISSION_REJECTED(true,"Access or permissions rejected; operator correction and restart required"),
        NOT_FOUND(true,"Requested resource not found or inaccessible; check configured target or membership"),
        RATE_LIMITED(true,"Rate limited; existing shared retry_after gate applies; messages are never automatically resent"),
        SEND_ACCEPTED(false,"A valid send receipt was received; receipt IDs and message data are not logged"),
        QUEUE_FULL(true,"Bounded work queue refused this operation; no automatic resend"),
        HTTP_FAILED(true,"HTTP request failed; messages are never automatically resent"),
        NETWORK_FAILED(true,"Network or HTTP stream failed; no endpoint or exception details are logged"),
        TIMEOUT(true,"Bounded HTTP request timed out; messages are never automatically resent"),
        RESPONSE_LIMIT(true,"HTTP response exceeded its byte budget"),
        INTERRUPTED(true,"HTTP work interrupted or cancelled"),
        INVALID_RESPONSE(true,"Response could not be safely validated; response data is not logged"),
        NOT_BOT(true,"Configured account is not an official bot; operator correction and restart required"),
        UNSUPPORTED_TARGET(true,"Configured target is not the designated ordinary guild text channel"),
        GATEWAY_CONNECTING(false,"Gateway session connecting; REST remains the only message ingress"),
        GATEWAY_READY(false,"Gateway READY/RESUMED received; online session established, no send receipt implied"),
        GATEWAY_RECONNECT(true,"Gateway disconnected, unacknowledged or unavailable; bounded reconnect applies; REST ingress is independent"),
        GATEWAY_LIMIT(true,"Gateway identify/session-start budget exhausted; no identify loop"),
        GATEWAY_TERMINAL(true,"Gateway cannot continue; check configuration/status; REST ingress remains independently evaluated"),
        GATEWAY_STOPPED(false,"Gateway stopped; no reconnection after shutdown"),
        GATEWAY_ACK_TIMEOUT(true,"Gateway heartbeat ACK deadline expired; reconnect/resume with bounded backoff"),
        GATEWAY_AUTH(true,"Gateway authentication rejected; Gateway stopped pending operator correction"),
        GATEWAY_CONFIG(true,"Gateway API version, shard or intents rejected; Gateway stopped"),
        OPERATION_FAILED(true,"Operation rejected, unavailable or delivery uncertain; no automatic resend");
        final boolean warning;final String message;Code(boolean warning,String message){this.warning=warning;this.message=message;}
    }
    enum Source {
        NEW("config/discraft/server.properties"),LEGACY("config/discraft-server.properties"),UNRESOLVED("not-selected");
        final String label;Source(String label){this.label=label;}
        static Source of(Path config,Path selected){if(selected==null)return UNRESOLVED;var actual=selected.toAbsolutePath().normalize();if(actual.equals(config.resolve("discraft/server.properties").toAbsolutePath().normalize()))return NEW;if(actual.equals(config.resolve("discraft-server.properties").toAbsolutePath().normalize()))return LEGACY;return UNRESOLVED;}
    }
    enum Startup {
        INITIALIZING("Logical server initialization started"),TEST_MODE("Explicit TEST adapter; no real Discord connection"),
        DISABLED_CONFIG("enabled=false; no Discord connection"),DISABLED_OFFLINE("Authenticated dedicated server required; config values were not loaded"),
        DISABLED_INTEGRATED("Integrated server/client scope; server credentials not loaded and no Discord connection"),
        CONNECTING_UNVERIFIED("Config enabled; REST adapter created but bot/channel/read/send are not yet verified"),
        CONFIG_INIT_FAILED("Config initialization failed; bridge disabled"),CONFIG_LOAD_FAILED("Config invalid or unreadable; bridge disabled"),
        RESOURCES_FAILED("Local language/resource-pack initialization failed; bridge disabled"),ADAPTER_FAILED("Adapter setup failed; bridge disabled");
        final String message;Startup(String message){this.message=message;}
    }
    private record Key(Code code,Operation operation,int status,Terminal terminal){}
    private static final long WINDOW=TimeUnit.MINUTES.toNanos(1);
    private static final BridgeDiagnostics SYSTEM=production(false);
    private final BiConsumer<Boolean,String> sink;private final LongSupplier clock;private final boolean test;
    private final LinkedHashMap<Key,Long> seen=new LinkedHashMap<>();private long window;private int count;private boolean terminalAnnounced;
    BridgeDiagnostics(BiConsumer<Boolean,String> sink,LongSupplier clock,boolean test){this.sink=sink;this.clock=clock;this.test=test;window=clock.getAsLong();}
    static BridgeDiagnostics production(boolean test){return new BridgeDiagnostics((warning,message)->{var logger=com.mojang.logging.LogUtils.getLogger();if(warning)logger.warn(message);else logger.info(message);},System::nanoTime,test);}
    BridgeDiagnostics separate(){return new BridgeDiagnostics(sink,clock,test);}
    static void startup(Startup state,Source source){com.mojang.logging.LogUtils.getLogger().info("DisCraft bridge: state={} config={} — {}",state.name(),source.label,state.message);}
    static void policy(ServerConfig.PlayerAccess players,boolean lifecycle){com.mojang.logging.LogUtils.getLogger().info("DisCraft bridge policy: players={} lifecycle-notices={} (from config; no API verification implied)",players.name(),lifecycle);}
    static void queueFull(Operation operation){SYSTEM.report(Code.QUEUE_FULL,operation,0,Terminal.UNKNOWN);}
    static void failed(Operation operation){SYSTEM.report(Code.OPERATION_FAILED,operation,0,Terminal.UNKNOWN);}
    synchronized void report(Code code,Operation operation,int status,Terminal terminal){
        if(status!=0&&(status<100||status>599))status=0;
        long now=clock.getAsLong();if(now-window>=WINDOW||now<window){window=now;count=0;}
        var key=new Key(code,operation,status,terminal);var last=seen.get(key);
        boolean urgent=terminal==Terminal.YES&&!terminalAnnounced;
        if(!urgent&&(last!=null&&now-last<WINDOW||count>=8))return;
        if(terminal==Terminal.YES)terminalAnnounced=true;
        seen.put(key,now);while(seen.size()>64)seen.remove(seen.keySet().iterator().next());count++;
        sink.accept(code.warning,"DisCraft "+(test?"TEST ":"")+"bridge: code="+code.name()+" operation="+operation.name()+" http="+status+" terminal="+terminal.name()+" — "+code.message);
    }
    synchronized int retainedKeys(){return seen.size();}
}
