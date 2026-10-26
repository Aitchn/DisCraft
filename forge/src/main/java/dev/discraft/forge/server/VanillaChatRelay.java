package dev.discraft.forge.server;

import dev.discraft.core.Limits;
import dev.discraft.forge.DisCraft;
import dev.discraft.forge.bridge.*;
import io.netty.channel.*;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.*;
import net.minecraftforge.eventbus.api.*;
import net.minecraftforge.fml.common.Mod;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Actual native-broadcast confirmation preserves asynchronous Forge cancellation and signed bodies. */
@Mod.EventBusSubscriber(modid=DisCraft.ID)
public final class VanillaChatRelay {
    private static volatile MinecraftServer active;
    private static final Map<UUID,Peer> peers=new ConcurrentHashMap<>();
    private static final AtomicInteger admissions=new AtomicInteger();
    private static int pendingCount;
    private record Pending(ServerChatEvent event,ItemSnapshot item,String expected,boolean recipientFallback,long started){}
    private record Observed(String raw,boolean unfiltered,Component unsigned,UUID recipient){}
    private record Admission(Component replacement){}
    private static final class Peer {
        ServerPlayer player;volatile boolean recipientFallback;final AtomicBoolean alive=new AtomicBoolean(true),scheduled=new AtomicBoolean();final AtomicInteger pending=new AtomicInteger();
        final ArrayBlockingQueue<Observed> observed=new ArrayBlockingQueue<>(16);final Deque<Pending> messages=new ArrayDeque<>();
        Peer(ServerPlayer player){this.player=player;}
    }
    @SubscribeEvent(priority=EventPriority.LOWEST) public static void start(ServerStartedEvent event){active=event.getServer();}
    @SubscribeEvent(priority=EventPriority.LOWEST) public static void login(PlayerEvent.PlayerLoggedInEvent event){
        if(!(event.getEntity() instanceof ServerPlayer player)||active!=player.server||!ServerBridge.nativeObserverEnabled(player)||peers.size()>=256)return;
        var peer=new Peer(player);var channel=player.connection.connection.channel();if(channel==null||channel.pipeline().get("packet_handler")==null)return;
        peers.put(player.getUUID(),peer);UUID senderId=player.getUUID();
        channel.pipeline().addBefore("packet_handler","discraft_native_chat",new ChannelOutboundHandlerAdapter(){
            @Override public void write(ChannelHandlerContext context,Object message,ChannelPromise promise)throws Exception{
                try{if(peer.alive.get()&&message instanceof ClientboundPlayerChatPacket packet){
                    var source=peers.get(packet.sender());if(source!=null&&source.alive.get()&&source.pending.get()>0&&(packet.sender().equals(senderId)||source.recipientFallback)){String raw=packet.body().content();if(raw.length()<=1800&&source.observed.offer(new Observed(raw,packet.filterMask().isEmpty(),packet.unsignedContent(),senderId)))schedule(source);}
                }}catch(Exception ignored){/* Observation never changes, drops or retries a native packet. */}finally{context.write(message,promise);}
            }
        });
    }
    private static void schedule(Peer peer){var server=active;if(server!=null&&peer.scheduled.compareAndSet(false,true))server.execute(()->{
        try{if(active!=server||!peer.alive.get()||peers.get(peer.player.getUUID())!=peer)return;for(Observed observed;(observed=peer.observed.poll())!=null;){
            // FULL chat uses the sender's own filtered view. SYSTEM has no self player-chat packet;
            // confirm it from a real recipient instead, without fabricating a native delivery.
            if(!observed.recipient().equals(peer.player.getUUID())&&peer.player.getChatVisibility()!=net.minecraft.world.entity.player.ChatVisiblity.SYSTEM)continue;
            Pending chosen=null;for(var message:peer.messages){if(!observed.recipient().equals(peer.player.getUUID())&&!message.recipientFallback())continue;if(message.event().isCanceled()||!message.event().getRawText().equals(observed.raw()))continue;
                if(observed.unsigned()!=null?message.event().getMessage()==observed.unsigned():message.item()==null){chosen=message;break;}}
            if(chosen!=null){peer.messages.remove(chosen);peer.pending.decrementAndGet();pendingCount--;try{Component visible=observed.unsigned()!=null?observed.unsigned():Component.literal(observed.raw());if(observed.unfiltered()&&chosen.expected().equals(NativeChatProjection.shape(visible)))ServerBridge.relayVanilla(peer.player,observed.raw(),chosen.item());}catch(IllegalArgumentException unsupported){/* A moderation/decorator rewrite or unmappable filter never falls back to raw text. */}}
        }}finally{peer.scheduled.set(false);if(!peer.observed.isEmpty()&&peer.alive.get())schedule(peer);else if(!peer.alive.get())peer.observed.clear();}
    });}
    @SubscribeEvent(priority=EventPriority.LOWEST,receiveCanceled=true) public static void chat(ServerChatEvent event){
        var server=active;if(server==null||ServerBridge.mode().equals("disabled")||ServerBridge.mode().equals("test")&&!Boolean.getBoolean("discraft.optionalClientSmoke"))return;
        if(admissions.incrementAndGet()>64){admissions.decrementAndGet();return;}var expired=new AtomicBoolean();CompletableFuture<Admission> future=null;
        try{
            future=server.submit(()->{
                if(expired.get()||active!=server||pendingCount>=128)return null;var player=event.getPlayer();var peer=peers.get(player.getUUID());
                if(peer==null||!peer.alive.get()||peer.player!=player||server.getPlayerList().getPlayer(player.getUUID())!=player||!ServerBridge.admitVanillaChat(player))return null;
                String raw=event.getRawText();Limits.text(raw,1800,"native chat");if(!NativeChatProjection.shape(event.getMessage()).equals(NativeChatProjection.shape(Component.literal(raw))))return null;var item=ServerBridge.vanillaItem(player,raw);Component replacement=null;
                if(item!=null){var nativeItem=item.nativeItem()!=null?item.nativeItem():new NativeItemData("minecraft:air",0,"{}");replacement=NativeItemComponents.replaceMarkers(event.getMessage(),nativeItem,item.label());}
                if(expired.get())return null;peer.recipientFallback=player.getChatVisibility()==net.minecraft.world.entity.player.ChatVisiblity.SYSTEM;peer.messages.addLast(new Pending(event,item,NativeChatProjection.shape(replacement!=null?replacement:event.getMessage()),peer.recipientFallback,System.nanoTime()));peer.pending.incrementAndGet();pendingCount++;return new Admission(replacement);
            });
            var result=future.get(2,TimeUnit.SECONDS);if(result!=null&&result.replacement()!=null)event.setMessage(result.replacement());
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();}catch(Exception ignored){/* Original raw body and vanilla delivery remain owned by Forge. */}
        finally{expired.set(true);if(future!=null&&!future.isDone())future.cancel(false);admissions.decrementAndGet();}
    }
    @SubscribeEvent public static void tick(TickEvent.ServerTickEvent event){if(event.phase!=TickEvent.Phase.END||active==null)return;long now=System.nanoTime();for(var peer:peers.values()){var iterator=peer.messages.iterator();while(iterator.hasNext()){var message=iterator.next();if(now-message.started()>10_000_000_000L){iterator.remove();peer.pending.decrementAndGet();pendingCount--;}}}}
    private static void remove(Peer peer){if(peer==null)return;peer.alive.set(false);pendingCount-=peer.messages.size();peer.messages.clear();peer.pending.set(0);peer.observed.clear();}
    @SubscribeEvent public static void respawn(PlayerEvent.PlayerRespawnEvent event){if(event.getEntity() instanceof ServerPlayer player){var peer=peers.get(player.getUUID());if(peer!=null)peer.player=player;}}
    @SubscribeEvent public static void dimension(PlayerEvent.PlayerChangedDimensionEvent event){if(event.getEntity() instanceof ServerPlayer player){var peer=peers.get(player.getUUID());if(peer!=null)peer.player=player;}}
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent event){remove(peers.remove(event.getEntity().getUUID()));}
    @SubscribeEvent(priority=EventPriority.HIGHEST) public static void stop(ServerStoppingEvent event){if(active!=event.getServer())return;active=null;peers.values().forEach(VanillaChatRelay::remove);peers.clear();pendingCount=0;}
}
