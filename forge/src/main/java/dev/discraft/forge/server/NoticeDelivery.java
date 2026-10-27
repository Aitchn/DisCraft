package dev.discraft.forge.server;

import dev.discraft.forge.bridge.RelayRecord;
import java.util.*;
import java.util.concurrent.*;

/** Bounded best-effort notices; no retries, webhook identity changes or member pings. */
final class NoticeDelivery {
    private NoticeDelivery(){}
    static RelayRecord record(String text){
        var out=new StringBuilder();
        text.codePoints().forEach(c->{if(c=='@')out.append("@\u200b");else{if("\\`*_{}[]()<>#+-.!|~".indexOf(c)>=0)out.append('\\');if(!Character.isISOControl(c)||c=='\n')out.appendCodePoint(c);}});
        if(out.codePointCount(0,out.length())>1800){
            out.setLength(out.offsetByCodePoints(0,1799));int slashes=0;for(int i=out.length()-1;i>=0&&out.charAt(i)=='\\';i--)slashes++;
            if(slashes%2!=0)out.setLength(out.length()-1);out.append('…');
        }
        return new RelayRecord(UUID.randomUUID(),"Server",out.toString(),List.of(),Map.of(),Map.of());
    }
    static Runnable task(DiscordAdapter adapter,RelayRecord record){return ()->{try{adapter.send(record);}catch(Exception uncertain){adapter.failed(BridgeDiagnostics.Operation.NOTICE);/* Never retry or log body, player content or credentials. */}};}
    static boolean submit(ThreadPoolExecutor worker,Runnable notice){
        if(worker==null)return false;try{worker.execute(notice);return true;}catch(RejectedExecutionException full){BridgeDiagnostics.queueFull(BridgeDiagnostics.Operation.NOTICE);return false;}
    }
    /** One total deadline includes queue admission, final notice and all pending jobs. */
    static void drain(ThreadPoolExecutor worker,Runnable finalNotice,long timeoutNanos){
        if(worker==null)return;long deadline=System.nanoTime()+timeoutNanos;
        try{
            if(finalNotice!=null){try{worker.execute(finalNotice);}catch(RejectedExecutionException full){
                if(!worker.isShutdown())worker.getQueue().offer(finalNotice,Math.max(0,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);
            }}
            worker.shutdown();worker.awaitTermination(Math.max(0,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
        finally{worker.shutdownNow();}
    }
}
