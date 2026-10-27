package dev.discraft.forge.server;

import dev.discraft.core.Limits;
import dev.discraft.forge.DisCraft;
import dev.discraft.forge.bridge.*;
import io.netty.channel.*;
import net.minecraft.network.chat.*;
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.ClientboundDisguisedChatPacket;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
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
    private static final Map<Thread,Origin> origins=new ConcurrentHashMap<>();
    private static final Map<Thread,Dispatch> dispatches=new ConcurrentHashMap<>();
    private static final StackWalker STACK=StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
    private static volatile BridgeDiagnostics diagnostics=BridgeDiagnostics.production(false);
    /** Component equality is mutable/structural; correlation requires actual object identity. */
    private record Identity(Component component){@Override public int hashCode(){return System.identityHashCode(component);}@Override public boolean equals(Object other){return other instanceof Identity key&&component==key.component;}}
    private record Pending(UUID generation,long sequence,ServerChatEvent event,ItemSnapshot item,String expected,
                           boolean recipientFallback,long started,Component presentation,AtomicReference<Receipt> receipt,AtomicBoolean complete,AtomicReference<Observed> deferred){}
    private record Receipt(UUID generation,long admission,int index,MessageSignature signature){}
    private record Observed(Pending admission,Receipt receipt,String raw,boolean unfiltered,Component unsigned,UUID recipient){}
    private record Admission(Component replacement,Peer peer,Pending pending){}
    private record Dispatch(Peer peer,Pending admission,int depth){}
    private record Origin(Peer source,Pending admission,ClientboundPlayerChatPacket packet,UUID recipient,int depth,long started){}
    private record Converted(Origin origin,Component view,String shape){}
    private static final class Peer {
        final UUID generation=UUID.randomUUID();long sequence;ServerPlayer player;
        final AtomicBoolean alive=new AtomicBoolean(true),scheduled=new AtomicBoolean();
        final ArrayBlockingQueue<Observed> observed=new ArrayBlockingQueue<>(16);final Deque<Pending> messages=new ArrayDeque<>();
        final ConcurrentHashMap<Identity,Pending> presentations=new ConcurrentHashMap<>();
        final ConcurrentHashMap<Identity,Converted> converted=new ConcurrentHashMap<>();
        Peer(ServerPlayer player){this.player=player;}
    }
    @SubscribeEvent(priority=EventPriority.LOWEST) public static void start(ServerStartedEvent event){active=event.getServer();diagnostics=BridgeDiagnostics.production(ServerBridge.mode().equals("test"));}
    private static void status(BridgeDiagnostics.Code code){diagnostics.report(code,BridgeDiagnostics.Operation.NATIVE,0,BridgeDiagnostics.Terminal.NO);}
    /** Narrow HEAD observation before NCR conversion. Never changes a packet, event or signature. */
    public static void beforeNativeSend(ServerPlayer recipient,Packet<?> packet){
        if(active==null||!ServerBridge.nativeObserverEnabled(recipient)||!(packet instanceof ClientboundPlayerChatPacket||packet instanceof ClientboundSystemChatPacket||packet instanceof ClientboundDisguisedChatPacket))return;
        try{
            Thread thread=Thread.currentThread();var target=peers.get(recipient.getUUID());if(target==null||!target.alive.get())return;
            if(packet instanceof ClientboundPlayerChatPacket original){
                status(BridgeDiagnostics.Code.NATIVE_ORIGIN_HOOK);
                var source=peers.get(original.sender());var admission=source==null||original.unsignedContent()==null?null:source.presentations.get(new Identity(original.unsignedContent()));
                if(admission==null||!source.alive.get()||!admission.generation().equals(source.generation)||!original.sender().equals(recipient.getUUID())&&!admission.recipientFallback()){origins.remove(thread);return;}
                int depth=sendDepth();if(depth<1||origins.size()>=128&&!origins.containsKey(thread))return;
                origins.put(thread,new Origin(source,admission,original,recipient.getUUID(),depth,System.nanoTime()));return;
            }
            var origin=origins.remove(thread);if(origin==null||!origin.recipient().equals(recipient.getUUID())||System.nanoTime()-origin.started()>1_000_000_000L||sendDepth()<=origin.depth())return;
            var source=origin.source();var admission=origin.admission();
            if(!source.alive.get()||!admission.generation().equals(source.generation)||source.presentations.get(new Identity(admission.presentation()))!=admission||!origin.packet().filterMask().isEmpty()||target.converted.size()>=32)return;
            Component view=packet instanceof ClientboundSystemChatPacket system?system.overlay()?null:system.content():((ClientboundDisguisedChatPacket)packet).message();if(view==null)return;
            String shape=NativeConvertedPresentation.shape(view,admission.presentation());target.converted.putIfAbsent(new Identity(view),new Converted(origin,view,shape));
        }catch(Exception unsupported){status(BridgeDiagnostics.Code.NATIVE_UNSUPPORTED);}
    }
    private static int sendDepth(){return STACK.walk(frames->(int)frames.limit(64).filter(frame->frame.getDeclaringClass()==ServerGamePacketListenerImpl.class&&Set.of("send","m_9829_","m_243119_").contains(frame.getMethodName())).count());}
    private static int dispatchDepth(){return STACK.walk(frames->(int)frames.limit(64).filter(frame->frame.getDeclaringClass()==net.minecraftforge.common.ForgeHooks.class&&frame.getMethodName().equals("onServerChatSubmittedEvent")).count());}
    public static void nativeDispatchComplete(ServerPlayer player){
        var thread=Thread.currentThread();var dispatch=dispatches.get(thread);if(dispatch==null||dispatch.depth()!=dispatchDepth()||!dispatch.peer().player.getUUID().equals(player.getUUID())||!dispatches.remove(thread,dispatch))return;
        dispatch.admission().complete().set(true);releaseDeferred(dispatch.peer(),dispatch.admission());
    }
    private static void releaseDeferred(Peer peer,Pending admission){var observed=admission.deferred().getAndSet(null);if(observed!=null&&peer.alive.get()){if(peer.observed.offer(observed))schedule(peer);else status(BridgeDiagnostics.Code.QUEUE_FULL);}}
    @SubscribeEvent(priority=EventPriority.LOWEST) public static void login(PlayerEvent.PlayerLoggedInEvent event){
        if(!(event.getEntity() instanceof ServerPlayer player)||active!=player.server||!ServerBridge.nativeObserverEnabled(player)||peers.size()>=256)return;
        var peer=new Peer(player);var channel=player.connection.connection.channel();if(channel==null||channel.pipeline().get("encoder")==null){status(BridgeDiagnostics.Code.NATIVE_OBSERVER_MISSING);return;}
        peers.put(player.getUUID(),peer);UUID recipient=player.getUUID();
        channel.pipeline().addAfter("encoder","discraft_native_chat",new ChannelOutboundHandlerAdapter(){
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
                }else if(peer.alive.get()){
                    Component view=message instanceof ClientboundSystemChatPacket system&&!system.overlay()?system.content():message instanceof ClientboundDisguisedChatPacket disguised?disguised.message():null;
                    var converted=view==null?null:peer.converted.remove(new Identity(view));
                    if(converted!=null&&converted.view()==view&&converted.shape().equals(NativeConvertedPresentation.shape(view,converted.origin().admission().presentation()))){
                        var origin=converted.origin();var source=origin.source();var admission=origin.admission();var packet=origin.packet();
                        if(source.alive.get()&&admission.generation().equals(source.generation)&&source.presentations.get(new Identity(admission.presentation()))==admission&&packet.body().content().length()<=1800){
                            var receipt=new Receipt(source.generation,admission.sequence(),packet.index(),packet.signature());
                            if(admission.receipt().compareAndSet(null,receipt)&&source.observed.offer(new Observed(admission,receipt,packet.body().content(),packet.filterMask().isEmpty(),admission.presentation(),recipient))){status(BridgeDiagnostics.Code.NATIVE_CONVERTED);schedule(source);}
                        }
                    }
                }}catch(Exception ignored){status(BridgeDiagnostics.Code.NATIVE_UNSUPPORTED);}finally{context.write(message,promise);}
            }
        });
    }
    private static void release(Peer peer,Pending admission){if(peer.messages.remove(admission)){peer.presentations.remove(new Identity(admission.presentation()),admission);origins.values().removeIf(o->o.admission()==admission);dispatches.values().removeIf(d->d.admission()==admission);admission.deferred().set(null);pendingCount--;}}
    private static void schedule(Peer peer){var server=active;if(server!=null&&peer.scheduled.compareAndSet(false,true))server.execute(()->{
        try{if(active!=server||!peer.alive.get()||peers.get(peer.player.getUUID())!=peer)return;for(Observed observed;(observed=peer.observed.poll())!=null;){
            var admission=observed.admission();var receipt=observed.receipt();
            if(admission.receipt().get()!=receipt||!receipt.generation().equals(peer.generation)||receipt.admission()!=admission.sequence()||peer.presentations.get(new Identity(observed.unsigned()))!=admission)continue;
            if(!admission.complete().get()){admission.deferred().set(observed);if(admission.complete().get())releaseDeferred(peer,admission);continue;}
            release(peer,admission);
            // FULL chat requires the sender's own view. SYSTEM may use a real recipient's view.
            if(!observed.recipient().equals(peer.player.getUUID())&&(!admission.recipientFallback()||peer.player.getChatVisibility()!=net.minecraft.world.entity.player.ChatVisiblity.SYSTEM))continue;
            if(admission.event().isCanceled()||admission.event().getMessage()!=admission.presentation()||observed.unsigned()!=admission.presentation()||!observed.raw().equals(admission.event().getRawText())){status(BridgeDiagnostics.Code.NATIVE_CHANGED);continue;}
            try{if(observed.unfiltered()&&admission.expected().equals(NativeChatProjection.shape(observed.unsigned()))){status(BridgeDiagnostics.Code.NATIVE_CONFIRMED);ServerBridge.relayVanilla(peer.player,observed.raw(),admission.item());}else status(BridgeDiagnostics.Code.NATIVE_CHANGED);}
            catch(IllegalArgumentException unsupported){/* Moderation/decorator rewrites never fall back to original text. */}
        }}finally{peer.scheduled.set(false);if(!peer.observed.isEmpty()&&peer.alive.get())schedule(peer);else if(!peer.alive.get())peer.observed.clear();}
    });}
    @SubscribeEvent(priority=EventPriority.LOWEST,receiveCanceled=true) public static void chat(ServerChatEvent event){
        var server=active;if(server==null||ServerBridge.mode().equals("disabled")||ServerBridge.mode().equals("test")&&!Boolean.getBoolean("discraft.optionalClientSmoke"))return;
        if(admissions.incrementAndGet()>64){admissions.decrementAndGet();return;}var expired=new AtomicBoolean();CompletableFuture<Admission> future=null;
        try{
            future=server.submit(()->{
                if(expired.get()||active!=server||pendingCount>=128)return null;var player=event.getPlayer();var peer=peers.get(player.getUUID());
                if(peer==null||!peer.alive.get()||peer.player!=player||server.getPlayerList().getPlayer(player.getUUID())!=player||!ServerBridge.admitVanillaChat(player)){status(BridgeDiagnostics.Code.NATIVE_NOT_ADMITTED);return null;}
                String raw=event.getRawText();Limits.text(raw,1800,"native chat");if(!NativeChatProjection.shape(event.getMessage()).equals(NativeChatProjection.shape(Component.literal(raw))))return null;
                var item=ServerBridge.vanillaItem(player,raw);Component presentation=event.getMessage();
                if(item!=null){var nativeItem=item.nativeItem()!=null?item.nativeItem():new NativeItemData("minecraft:air",0,"{}");presentation=NativeItemComponents.replaceMarkers(presentation,nativeItem,item.label());}
                // A fresh invisible structural wrapper survives 1.20.1 withUnsignedContent's equality optimization.
                // It adds no text/style/action/ID to the wire, leaves the signed body intact, and gives each event
                // a unique presentation identity. A later replacement root is deliberately uncorrelatable.
                Component replacement=Component.empty().append(presentation);
                var admission=new Pending(peer.generation,++peer.sequence,event,item,NativeChatProjection.shape(replacement),player.getChatVisibility()==net.minecraft.world.entity.player.ChatVisiblity.SYSTEM,System.nanoTime(),replacement,new AtomicReference<>(),new AtomicBoolean(),new AtomicReference<>());
                if(expired.get())return null;peer.messages.addLast(admission);peer.presentations.put(new Identity(replacement),admission);pendingCount++;status(BridgeDiagnostics.Code.NATIVE_ADMITTED);return new Admission(replacement,peer,admission);
            });
            var result=future.get(2,TimeUnit.SECONDS);if(result!=null){int depth=dispatchDepth();var thread=Thread.currentThread();if(depth<1||dispatches.size()>=64||dispatches.putIfAbsent(thread,new Dispatch(result.peer(),result.pending(),depth))!=null){server.execute(()->release(result.peer(),result.pending()));status(BridgeDiagnostics.Code.NATIVE_UNSUPPORTED);return;}event.setMessage(result.replacement());}
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();}catch(Exception ignored){/* Original raw body and vanilla delivery remain owned by Forge. */}
        finally{expired.set(true);if(future!=null&&!future.isDone())future.cancel(false);admissions.decrementAndGet();}
    }
    @SubscribeEvent public static void tick(TickEvent.ServerTickEvent event){if(event.phase!=TickEvent.Phase.END||active==null)return;long now=System.nanoTime();origins.values().removeIf(o->now-o.started()>1_000_000_000L||!o.source().alive.get());for(var peer:peers.values()){peer.converted.values().removeIf(c->now-c.origin().started()>10_000_000_000L);for(var admission:new ArrayList<>(peer.messages))if(now-admission.started()>10_000_000_000L){status(BridgeDiagnostics.Code.NATIVE_NO_RECEIPT);release(peer,admission);}}}
    private static void remove(Peer peer){if(peer==null)return;peer.alive.set(false);pendingCount-=peer.messages.size();peer.messages.clear();peer.presentations.clear();peer.observed.clear();peer.converted.clear();origins.values().removeIf(o->o.source()==peer);dispatches.values().removeIf(d->d.peer()==peer);}
    @SubscribeEvent public static void respawn(PlayerEvent.PlayerRespawnEvent event){if(event.getEntity() instanceof ServerPlayer player){var peer=peers.get(player.getUUID());if(peer!=null)peer.player=player;}}
    @SubscribeEvent public static void dimension(PlayerEvent.PlayerChangedDimensionEvent event){if(event.getEntity() instanceof ServerPlayer player){var peer=peers.get(player.getUUID());if(peer!=null)peer.player=player;}}
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent event){remove(peers.remove(event.getEntity().getUUID()));}
    @SubscribeEvent(priority=EventPriority.HIGHEST) public static void stop(ServerStoppingEvent event){if(active!=event.getServer())return;active=null;peers.values().forEach(VanillaChatRelay::remove);peers.clear();origins.clear();dispatches.clear();pendingCount=0;}
}
