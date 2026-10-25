package dev.discraft.forge.bridge;

import dev.discraft.forge.DisCraft;
import dev.discraft.forge.server.ServerBridge;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.*;
import net.minecraftforge.network.simple.SimpleChannel;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

public final class BridgeNetwork {
    public static final int CHUNK=16*1024;
    public static final SimpleChannel CHANNEL=NetworkRegistry.newSimpleChannel(new ResourceLocation(DisCraft.ID,"bridge"),()->"2",
        v->v.equals("2")||v.equals(NetworkRegistry.ABSENT)||v.equals(NetworkRegistry.ACCEPTVANILLA),
        v->v.equals("2")||v.equals(NetworkRegistry.ABSENT)||v.equals(NetworkRegistry.ACCEPTVANILLA));
    private static final AtomicInteger QUEUED=new AtomicInteger();
    private static final java.util.concurrent.ConcurrentHashMap<UUID,Ingress> ingress=new java.util.concurrent.ConcurrentHashMap<>();
    private static final class Ingress {
        double bytes=2*RelayRecord.MAX_BYTES, packets=128;long updated=System.nanoTime();
        synchronized boolean admit(int size){long now=System.nanoTime();double elapsed=(now-updated)/1e9;updated=now;bytes=Math.min(2*RelayRecord.MAX_BYTES,bytes+elapsed*RelayRecord.MAX_BYTES);packets=Math.min(128,packets+elapsed*64);if(bytes<size||packets<1)return false;bytes-=size;packets--;return true;}
    }
    public static void forget(UUID id){ingress.remove(id);}
    public record Upload(UUID id,int total,int offset,byte[] bytes){}
    public record Download(UUID id,int total,int offset,byte[] bytes){}
    public record Notice(UUID request,boolean accepted,String status){}
    public static void init(){
        CHANNEL.registerMessage(0,Upload.class,(p,b)->write(b,p.id,p.total,p.offset,p.bytes),b->new Upload(b.readUUID(),b.readVarInt(),b.readVarInt(),b.readByteArray(CHUNK)),BridgeNetwork::upload,Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(1,Download.class,(p,b)->write(b,p.id,p.total,p.offset,p.bytes),b->new Download(b.readUUID(),b.readVarInt(),b.readVarInt(),b.readByteArray(CHUNK)),(p,s)->client(s,()->dev.discraft.forge.ClientBridge.download(p)),Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(2,Notice.class,(p,b)->{b.writeUUID(p.request);b.writeBoolean(p.accepted);b.writeUtf(p.status,160);},b->new Notice(b.readUUID(),b.readBoolean(),b.readUtf(160)),(p,s)->client(s,()->dev.discraft.forge.ClientBridge.notice(p)),Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }
    private static void write(FriendlyByteBuf b,UUID id,int total,int offset,byte[] bytes){b.writeUUID(id);b.writeVarInt(total);b.writeVarInt(offset);b.writeByteArray(bytes);}
    private static void upload(Upload p,Supplier<NetworkEvent.Context> supplied){
        var c=supplied.get();var player=c.getSender();c.setPacketHandled(true);
        if(player==null||(!ingress.containsKey(player.getUUID())&&ingress.size()>=64))return;
        if(!ingress.computeIfAbsent(player.getUUID(),id->new Ingress()).admit(p.bytes.length+48))return;
        if(player==null||QUEUED.incrementAndGet()>64){if(player!=null)QUEUED.decrementAndGet();return;}
        c.enqueueWork(()->{try{ServerBridge.upload(player,p);}finally{QUEUED.decrementAndGet();}});
    }
    private static void client(Supplier<NetworkEvent.Context> supplied,Runnable work){var c=supplied.get();c.setPacketHandled(true);c.enqueueWork(()->DistExecutor.unsafeRunWhenOn(Dist.CLIENT,()->()->work.run()));}
    public static boolean present(ServerPlayer p){return CHANNEL.isRemotePresent(p.connection.connection);}
    public static void notice(ServerPlayer p,UUID id,boolean accepted,String text){if(present(p))CHANNEL.send(PacketDistributor.PLAYER.with(()->p),new Notice(id,accepted,text));}
    public static void deliver(ServerPlayer p,RelayRecord record)throws java.io.IOException{
        byte[] b=record.encode();for(int at=0;at<b.length;at+=CHUNK)CHANNEL.send(PacketDistributor.PLAYER.with(()->p),new Download(record.id(),b.length,at,Arrays.copyOfRange(b,at,Math.min(b.length,at+CHUNK))));
    }
}

