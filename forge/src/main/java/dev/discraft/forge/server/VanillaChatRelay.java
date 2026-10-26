package dev.discraft.forge.server;

import dev.discraft.core.Limits;
import dev.discraft.forge.DisCraft;
import dev.discraft.forge.bridge.*;
import io.netty.channel.*;
import net.minecraft.network.chat.*;
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
    /** Component equality is mutable/structural; correlation requires actual object identity. */
    private record Identity(Component component){@Override public int hashCode(){return System.identityHashCode(component);}@Override public boolean equals(Object other){return other instanceof Identity key&&component==key.component;}}
    private record Pending(UUID generation,long sequence,ServerChatEvent event,ItemSnapshot item,String expected,
                           boolean recipientFallback,long started,Component presentation,AtomicReference<Receipt> receipt){}
    private record Receipt(UUID generation,long admission,int index,MessageSignature signature){}
    private record Observed(Pending admission,Receipt receipt,String raw,boolean unfiltered,Component unsigned,UUID recipient){}
    private record Admission(Component replacement){}
    private static final class Peer {
        final UUID generation=UUID.randomUUID();long sequence;ServerPlayer player;
        final AtomicBoolean alive=new AtomicBoolean(true),scheduled=new AtomicBoolean();
        final ArrayBlockingQueue<Observed> observed=new ArrayBlockingQueue<>(16);final Deque<Pending> messages=new ArrayDeque<>();
        final ConcurrentHashMap<Identity,Pending> presentations=new ConcurrentHashMap<>();
        Peer(ServerPlayer player){this.player=player;}
    }
    @SubscribeEvent(priority=EventPriority.LOWEST) public static void start(ServerStartedEvent event){active=event.getServer();}
    @SubscribeEvent(priority=EventPriority.LOWEST) public static void login(PlayerEvent.PlayerLoggedInEvent event){
        if(!(event.getEntity() instanceof ServerPlayer player)||active!=player.server||!ServerBridge.nativeObserverEnabled(player)||peers.size()>=256)return;
        var peer=new Peer(player);var channel=player.connection.connection.channel();if(channel==null||channel.pipeline().get("packet_handler")==null)return;
        peers.put(player.getUUID(),peer);UUID recipient=player.getUUID();
        channel.pipeline().addBefore("packet_handler","discraft_native_chat",new ChannelOutboundHandlerAdapter(){
            @Override public void write(ChannelHandlerContext context,Object message,ChannelPromise promise)throws Exception{
                try{if(peer.alive.get()&&message instanceof ClientboundPlayerChatPacket packet&&packet.unsignedContent()!=null){
                    var source=peers.get(packet.sender());if(source!=null&&source.alive.get()){
                        var admission=source.presentations.get(new Identity(packet.unsignedContent()));
                        // Never match by text or by index alone (unsigned vanilla messages can all use index 0).
                        if(admission!=null&&admission.generation().equals(source.generation)&&(packet.sender().equals(recipient)||admission.recipientFallback())&&packet.body().content().length()<=1800){
                            var receipt=new Receipt(source.generation,admission.sequence(),packet.index(),packet.signature());
                            if(admission.receipt().compareAndSet(null,receipt)&&source.observed.offer(new Observed(admission,receipt,packet.body().content(),packet.filterMask().isEmpty(),packet.unsignedContent(),recipient)))schedule(source);
                        }
                    }
                }}catch(Exception ignored){/* Observation never changes, drops or retries a native packet. */}finally{context.write(message,promise);}
            }
        });
    }
    private static void release(Peer peer,Pending admission){if(peer.messages.remove(admission)){peer.presentations.remove(new Identity(admission.presentation()),admission);pendingCount--;}}
    private static void schedule(Peer peer){var server=active;if(server!=null&&peer.scheduled.compareAndSet(false,true))server.execute(()->{
        try{if(active!=server||!peer.alive.get()||peers.get(peer.player.getUUID())!=peer)return;for(Observed observed;(observed=peer.observed.poll())!=null;){
            var admission=observed.admission();var receipt=observed.receipt();
            if(admission.receipt().get()!=receipt||!receipt.generation().equals(peer.generation)||receipt.admission()!=admission.sequence()||peer.presentations.get(new Identity(observed.unsigned()))!=admission)continue;
            release(peer,admission);
            // FULL chat requires the sender's own view. SYSTEM may use a real recipient's view.
            if(!observed.recipient().equals(peer.player.getUUID())&&(!admission.recipientFallback()||peer.player.getChatVisibility()!=net.minecraft.world.entity.player.ChatVisiblity.SYSTEM))continue;
            if(admission.event().isCanceled()||admission.event().getMessage()!=admission.presentation()||observed.unsigned()!=admission.presentation()||!observed.raw().equals(admission.event().getRawText()))continue;
            try{if(observed.unfiltered()&&admission.expected().equals(NativeChatProjection.shape(observed.unsigned())))ServerBridge.relayVanilla(peer.player,observed.raw(),admission.item());}
            catch(IllegalArgumentException unsupported){/* Moderation/decorator rewrites never fall back to original text. */}
        }}finally{peer.scheduled.set(false);if(!peer.observed.isEmpty()&&peer.alive.get())schedule(peer);else if(!peer.alive.get())peer.observed.clear();}
    });}
    @SubscribeEvent(priority=EventPriority.LOWEST,receiveCanceled=true) public static void chat(ServerChatEvent event){
        var server=active;if(server==null||ServerBridge.mode().equals("disabled")||ServerBridge.mode().equals("test")&&!Boolean.getBoolean("discraft.optionalClientSmoke"))return;
        if(admissions.incrementAndGet()>64){admissions.decrementAndGet();return;}var expired=new AtomicBoolean();CompletableFuture<Admission> future=null;
        try{
            future=server.submit(()->{
                if(expired.get()||active!=server||pendingCount>=128)return null;var player=event.getPlayer();var peer=peers.get(player.getUUID());
                if(peer==null||!peer.alive.get()||peer.player!=player||server.getPlayerList().getPlayer(player.getUUID())!=player||!ServerBridge.admitVanillaChat(player))return null;
                String raw=event.getRawText();Limits.text(raw,1800,"native chat");if(!NativeChatProjection.shape(event.getMessage()).equals(NativeChatProjection.shape(Component.literal(raw))))return null;
                var item=ServerBridge.vanillaItem(player,raw);Component presentation=event.getMessage();
                if(item!=null){var nativeItem=item.nativeItem()!=null?item.nativeItem():new NativeItemData("minecraft:air",0,"{}");presentation=NativeItemComponents.replaceMarkers(presentation,nativeItem,item.label());}
                // A fresh invisible structural wrapper survives 1.20.1 withUnsignedContent's equality optimization.
                // It adds no text/style/action/ID to the wire, leaves the signed body intact, and gives each event
                // a unique presentation identity. A later replacement root is deliberately uncorrelatable.
                Component replacement=Component.empty().append(presentation);
                var admission=new Pending(peer.generation,++peer.sequence,event,item,NativeChatProjection.shape(replacement),player.getChatVisibility()==net.minecraft.world.entity.player.ChatVisiblity.SYSTEM,System.nanoTime(),replacement,new AtomicReference<>());
                if(expired.get())return null;peer.messages.addLast(admission);peer.presentations.put(new Identity(replacement),admission);pendingCount++;return new Admission(replacement);
            });
            var result=future.get(2,TimeUnit.SECONDS);if(result!=null)event.setMessage(result.replacement());
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();}catch(Exception ignored){/* Original raw body and vanilla delivery remain owned by Forge. */}
        finally{expired.set(true);if(future!=null&&!future.isDone())future.cancel(false);admissions.decrementAndGet();}
    }
    @SubscribeEvent public static void tick(TickEvent.ServerTickEvent event){if(event.phase!=TickEvent.Phase.END||active==null)return;long now=System.nanoTime();for(var peer:peers.values())for(var admission:new ArrayList<>(peer.messages))if(now-admission.started()>10_000_000_000L)release(peer,admission);}
    private static void remove(Peer peer){if(peer==null)return;peer.alive.set(false);pendingCount-=peer.messages.size();peer.messages.clear();peer.presentations.clear();peer.observed.clear();}
    @SubscribeEvent public static void respawn(PlayerEvent.PlayerRespawnEvent event){if(event.getEntity() instanceof ServerPlayer player){var peer=peers.get(player.getUUID());if(peer!=null)peer.player=player;}}
    @SubscribeEvent public static void dimension(PlayerEvent.PlayerChangedDimensionEvent event){if(event.getEntity() instanceof ServerPlayer player){var peer=peers.get(player.getUUID());if(peer!=null)peer.player=player;}}
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent event){remove(peers.remove(event.getEntity().getUUID()));}
    @SubscribeEvent(priority=EventPriority.HIGHEST) public static void stop(ServerStoppingEvent event){if(active!=event.getServer())return;active=null;peers.values().forEach(VanillaChatRelay::remove);peers.clear();pendingCount=0;}
}
