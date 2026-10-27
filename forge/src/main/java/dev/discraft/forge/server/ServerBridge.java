package dev.discraft.forge.server;

import dev.discraft.core.*;
import dev.discraft.forge.DisCraft;
import dev.discraft.forge.DisCraftPaths;
import dev.discraft.forge.bridge.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.AdvancementEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraft.advancements.Advancement;
import net.minecraft.world.level.GameRules;
import net.minecraftforge.event.server.*;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Mod.EventBusSubscriber(modid=DisCraft.ID)
public final class ServerBridge {
    private static final UUID STATUS=new UUID(0,0);
    private static MinecraftServer server;
    private static DiscordAdapter adapter;
    private static ThreadPoolExecutor worker;
    private static String mode="disabled";
    private static ServerConfig config=ServerConfig.disabled();
    private static ComponentText text=new ComponentText(ResourcePackIndex.empty(),"en_us");
    private static HeadlessItemRenderer itemRenderer;
    private static boolean running,stopping;
    private record DeathNotice(ServerPlayer player,LivingDeathEvent event,Component message){}
    private record AdvancementNotice(ServerPlayer player,Advancement advancement){}
    private static final Map<UUID,DeathNotice> deaths=new LinkedHashMap<>();
    private static final Map<String,AdvancementNotice> advancements=new LinkedHashMap<>();
    private static final Set<String> announcedAdvancements=new LinkedHashSet<>();
    private static final Map<UUID,ServerPlayer> announcedDeaths=new HashMap<>();
    private static final Map<UUID,Transfer> transfers=new HashMap<>();
    private static final Map<UUID,Peer> peers=new HashMap<>();
    private static final Deque<RelayRecord> history=new ArrayDeque<>();
    private static final Map<String,ActorRef> actors=new LinkedHashMap<>();
    private static final Set<String> requests=new LinkedHashSet<>();
    private static final AtomicBoolean polling=new AtomicBoolean(),thumbnailBusy=new AtomicBoolean();
    private static long nextPoll,nextCatalogBroadcast,nextThumbnail;
    private static GuildEmojiCatalog guildCatalog=GuildEmojiCatalog.empty(0);
    private static final class Peer {
        final AtomicBoolean alive=new AtomicBoolean(true); final Set<UUID> seen=new LinkedHashSet<>();
        double tokens=5; long updated=System.nanoTime(),nextThumbnail;
        boolean consume(){long now=System.nanoTime();tokens=Math.min(5,tokens+(now-updated)/1e9);updated=now;if(tokens<1)return false;tokens--;return true;}
    }
    private static final class Transfer {
        final UUID id; final byte[] bytes; final long started=System.nanoTime();int used;
        Transfer(UUID id,int total){this.id=id;bytes=new byte[total];}
    }
    public static String mode(){return mode;}
    private static boolean allowed(ServerPlayer p){
        if(mode.equals("test"))return true;
        return config.allows(p.getUUID());
    }
    @SubscribeEvent public static void starting(ServerAboutToStartEvent event){
        server=event.getServer();mode="disabled";config=ServerConfig.disabled();
        running=false;stopping=false;text=new ComponentText(ResourcePackIndex.empty(),"en_us");itemRenderer=null;
        worker=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(16),r->{var t=new Thread(r,"DisCraft server bridge");t.setDaemon(true);return t;});
        try {
            java.nio.file.Path configFile=server.isDedicatedServer()?ServerConfig.initialize(net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get(),DisCraftPaths::warn):null;
            if(Boolean.getBoolean("discraft.multiplayerSmoke")){mode="test";adapter=new TestDiscordAdapter();if(Boolean.getBoolean("discraft.serverEventsSmoke"))config=ServerConfig.testEvents();if(Boolean.getBoolean("discraft.optionalClientSmoke")){config=ServerConfig.testOptional();itemRenderer=new HeadlessItemRenderer(ResourcePackIndex.empty());}if(Boolean.getBoolean("discraft.nativeHoverSmoke")){config=ServerConfig.testNativeHover();itemRenderer=new HeadlessItemRenderer(ResourcePackIndex.empty());}}
            else if(server.isDedicatedServer()&&server.usesAuthentication()){
                config=ServerConfig.load(configFile);
                if(config.canConnect(server.isDedicatedServer(),server.usesAuthentication())){
                    var resources=ResourcePackIndex.load(DisCraftPaths.resourcePacks(net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get(),DisCraftPaths::warn),config.resourcePacks()).withBase(InstalledModLanguages.load(config.locale()));
                    text=new ComponentText(resources,config.locale());if(config.itemDisplay())itemRenderer=new HeadlessItemRenderer(resources);adapter=DiscordBotAdapter.fromConfig(config);mode="discord";
                }
                else adapter=null;
            }else adapter=null;
        } catch(Exception failure){
            if(mode.equals("test")&&Boolean.getBoolean("discraft.multiplayerSmoke"))throw new IllegalStateException("DisCraft TEST adapter initialization failed",failure);
            DisCraftPaths.warn("DisCraft: server configuration or Discord bridge setup failed; bridge disabled. Check the server config and local resource packs. Secret values and exception details are not logged.");
            mode="disabled";adapter=null;config=ServerConfig.disabled();
        }
        nextPoll=0;nextCatalogBroadcast=0;nextThumbnail=0;guildCatalog=GuildEmojiCatalog.empty(0);polling.set(false);thumbnailBusy.set(false);
        notice("lifecycle",Component.translatableWithFallback("discraft.notice.starting","Server is starting"));
    }
    @SubscribeEvent public static void start(ServerStartedEvent event){
        if(server!=event.getServer()||stopping)return;running=true;
        notice("lifecycle",Component.translatableWithFallback("discraft.notice.ready","Server is ready"));
    }
    @SubscribeEvent public static void stop(ServerStoppingEvent event){
        if(server!=event.getServer()||stopping)return;stopping=true;
        peers.values().forEach(p->p.alive.set(false));peers.clear();transfers.clear();history.clear();requests.clear();actors.clear();
        Runnable finalNotice=running?noticeTask("lifecycle",Component.translatableWithFallback("discraft.notice.stopping","Server is shutting down normally")):null;
        NoticeDelivery.drain(worker,finalNotice,TimeUnit.SECONDS.toNanos(8));
        if(adapter!=null)adapter.close();worker=null;adapter=null;server=null;running=false;mode="disabled";config=ServerConfig.disabled();guildCatalog=GuildEmojiCatalog.empty(0);deaths.clear();advancements.clear();announcedAdvancements.clear();announcedDeaths.clear();text=new ComponentText(ResourcePackIndex.empty(),"en_us");itemRenderer=null;
    }
    @SubscribeEvent public static void stopped(ServerStoppedEvent event){
        // Startup failure can skip ServerStoppingEvent; release transport without claiming ready/normal shutdown.
        if(server==event.getServer()){
            if(worker!=null)worker.shutdownNow();if(adapter!=null)adapter.close();worker=null;adapter=null;server=null;running=false;mode="disabled";config=ServerConfig.disabled();
            deaths.clear();advancements.clear();announcedAdvancements.clear();announcedDeaths.clear();text=new ComponentText(ResourcePackIndex.empty(),"en_us");itemRenderer=null;
        }
    }
    private static Runnable noticeTask(String kind,Component message){
        if(adapter==null||!config.notifies(kind))return null;
        try{return NoticeDelivery.task(adapter,NoticeDelivery.record(text.render(message)));}catch(RuntimeException oversized){return null;}
    }
    private static void notice(String kind,Component message){var task=noticeTask(kind,message);if(task!=null&&!stopping)NoticeDelivery.submit(worker,task);}
    @SubscribeEvent(priority=EventPriority.LOWEST) public static void death(LivingDeathEvent event){
        if(!running||stopping||!config.notifies("death")||event.isCanceled()||!(event.getEntity() instanceof ServerPlayer p)||!allowed(p)||deaths.size()>=128)return;
        // The event finishes before END tick; a later handler may still cancel or rescue the player.
        deaths.putIfAbsent(p.getUUID(),new DeathNotice(p,event,p.getCombatTracker().getDeathMessage().copy()));
    }
    @SubscribeEvent public static void advancement(AdvancementEvent.AdvancementEarnEvent event){
        if(!running||stopping||!config.notifies("advancement")||!(event.getEntity() instanceof ServerPlayer p)||!allowed(p)||advancements.size()>=128)return;
        var advancement=event.getAdvancement();advancements.putIfAbsent(p.getUUID()+":"+advancement.getId(),new AdvancementNotice(p,advancement));
    }
    @SubscribeEvent public static void respawn(PlayerEvent.PlayerRespawnEvent event){deaths.remove(event.getEntity().getUUID());announcedDeaths.remove(event.getEntity().getUUID());}
    @SubscribeEvent public static void login(PlayerEvent.PlayerLoggedInEvent event){
        if(!(event.getEntity() instanceof ServerPlayer p))return;
        boolean first=peers.putIfAbsent(p.getUUID(),new Peer())==null;var actor=new ActorRef(ActorRef.Kind.MINECRAFT,p.getUUID().toString(),p.getGameProfile().getName());remember(actor);
        BridgeNetwork.notice(p,STATUS,adapter!=null&&allowed(p),"SERVER "+mode.toUpperCase(Locale.ROOT)+(allowed(p)?"":" (not authorized)"));
        if(allowed(p)){BridgeNetwork.catalog(p,guildCatalog);for(var record:history)deliver(p,record);}
        if(first&&running&&allowed(p))notice("join",Component.translatable("multiplayer.player.joined",p.getDisplayName().copy()));
    }
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent event){
        var peer=peers.remove(event.getEntity().getUUID());if(peer!=null)peer.alive.set(false);transfers.remove(event.getEntity().getUUID());BridgeNetwork.forget(event.getEntity().getUUID());
        deaths.remove(event.getEntity().getUUID());announcedDeaths.remove(event.getEntity().getUUID());
        if(peer!=null&&running&&!stopping&&event.getEntity() instanceof ServerPlayer p&&allowed(p))notice("quit",Component.translatable("multiplayer.player.left",p.getDisplayName().copy()));
    }
    @SubscribeEvent public static void tick(TickEvent.ServerTickEvent event){
        if(event.phase!=TickEvent.Phase.END||server==null||!running||stopping)return;
        for(var death:deaths.values())if(!death.event().isCanceled()&&death.player().isDeadOrDying()&&announcedDeaths.get(death.player().getUUID())!=death.player()){
            if(announcedDeaths.size()<128||announcedDeaths.containsKey(death.player().getUUID())){announcedDeaths.put(death.player().getUUID(),death.player());notice("death",death.message());}
        }deaths.clear();
        for(var entry:advancements.entrySet()){
            var pending=entry.getValue();var p=pending.player();var advancement=pending.advancement();var display=advancement.getDisplay();
            if(display==null||!display.shouldAnnounceChat()||display.isHidden()||advancement.getId().getPath().startsWith("recipes/")||!p.level().getGameRules().getBoolean(GameRules.RULE_ANNOUNCE_ADVANCEMENTS)||!p.getAdvancements().getOrStartProgress(advancement).isDone()||!announcedAdvancements.add(entry.getKey()))continue;
            if(announcedAdvancements.size()>4096)announcedAdvancements.remove(announcedAdvancements.iterator().next());
            notice("advancement",Component.translatable("chat.type.advancement."+display.getFrame().getName(),p.getDisplayName().copy(),advancement.getChatComponent().copy()));
        }advancements.clear();
        transfers.entrySet().removeIf(e->{if(System.nanoTime()-e.getValue().started<15_000_000_000L)return false;var p=server.getPlayerList().getPlayer(e.getKey());if(p!=null)BridgeNetwork.notice(p,e.getValue().id,false,"Upload expired");return true;});
        if(adapter==null||System.nanoTime()<nextPoll||!polling.compareAndSet(false,true))return;
        nextPoll=System.nanoTime()+2_000_000_000L;
        var active=server;var transport=adapter;
        try {worker.execute(()->{try{var snapshot=transport.catalog();active.execute(()->{if(server==active)applyCatalog(snapshot);});for(var record:transport.poll())active.execute(()->{if(server==active)publish(record);});}
            catch(Exception unavailable){/* No token, response body, URL, or user content is logged. */}
            finally{polling.set(false);}});}catch(RejectedExecutionException full){polling.set(false);}
    }
    private static void applyCatalog(GuildEmojiCatalog snapshot){
        if(snapshot.equals(guildCatalog)&&System.nanoTime()<nextCatalogBroadcast)return;
        guildCatalog=snapshot;nextCatalogBroadcast=System.nanoTime()+60_000_000_000L;
        for(var player:server.getPlayerList().getPlayers())if(allowed(player))BridgeNetwork.catalog(player,snapshot);
    }
    public static void thumbnails(ServerPlayer player,BridgeNetwork.ThumbRequest request){
        var peer=peers.get(player.getUUID());long now=System.nanoTime();
        if(server==null||adapter==null||peer==null||!allowed(player)||!BridgeNetwork.present(player)||request.version()!=guildCatalog.version()||request.ids().size()<1||request.ids().size()>GuildEmojiCatalog.THUMB_BATCH||now<peer.nextThumbnail||now<nextThumbnail)return;
        var ids=request.ids().stream().distinct().filter(id->{var e=guildCatalog.find(id);return e!=null&&e.usable();}).toList();if(ids.isEmpty())return;
        if(!thumbnailBusy.compareAndSet(false,true))return;
        peer.nextThumbnail=now+1_000_000_000L;nextThumbnail=now+500_000_000L;var active=server;var transport=adapter;
        try{worker.execute(()->{try{var images=transport.thumbnails(ids);var current=transport.catalog();active.execute(()->{
            if(server!=active||!peer.alive.get())return;applyCatalog(current);if(current.version()!=request.version())return;
            for(String id:ids)BridgeNetwork.thumbnail(player,current.version(),id,images.get(id));
        });}finally{thumbnailBusy.set(false);}});}catch(RejectedExecutionException ignored){thumbnailBusy.set(false);}
    }
    public static void upload(ServerPlayer p,BridgeNetwork.Upload packet){
        if(server==null||server.getPlayerList().getPlayer(p.getUUID())!=p)return;
        UUID uuid=p.getUUID();var peer=peers.get(uuid);
        if(adapter==null||peer==null||!allowed(p)){BridgeNetwork.notice(p,packet.id(),false,"Bridge disabled or player not authorized");return;}
        if(packet.total()<1||packet.total()>RelayRecord.MAX_BYTES||packet.offset()<0||packet.bytes().length==0||packet.bytes().length>BridgeNetwork.CHUNK){reject(p,packet.id(),"Invalid upload bounds");return;}
        var transfer=transfers.get(uuid);
        if(packet.offset()==0){
            String requestKey=uuid+":"+packet.id();
            if(transfer!=null||peer.seen.contains(packet.id())||requests.contains(requestKey)||transfers.size()>=8||!peer.consume()){BridgeNetwork.notice(p,packet.id(),false,"Busy, duplicate, or rate limited");return;}
            requests.add(requestKey);if(requests.size()>2048)requests.remove(requests.iterator().next());
            peer.seen.add(packet.id());if(peer.seen.size()>128)peer.seen.remove(peer.seen.iterator().next());
            transfer=new Transfer(packet.id(),packet.total());transfers.put(uuid,transfer);
        }
        if(transfer==null||!transfer.id.equals(packet.id())||transfer.bytes.length!=packet.total()||transfer.used!=packet.offset()||packet.bytes().length>transfer.bytes.length-transfer.used){reject(p,packet.id(),"Invalid upload sequence");return;}
        System.arraycopy(packet.bytes(),0,transfer.bytes,transfer.used,packet.bytes().length);transfer.used+=packet.bytes().length;
        if(transfer.used!=transfer.bytes.length)return;
        transfers.remove(uuid);byte[] bytes=transfer.bytes;String author=p.getGameProfile().getName();var active=server;var transport=adapter;var knownActors=Map.copyOf(actors);var sender=new ActorRef(ActorRef.Kind.MINECRAFT,uuid.toString(),author);
        final ItemSnapshot held;final HeadlessItemRenderer renderer=itemRenderer;
        final boolean itemEnabled=config.itemDisplay();
        try{held=itemEnabled&&ItemExpansion.requested(bytes)?ItemSnapshot.capture(p,text):null;}
        catch(Exception invalid){BridgeNetwork.notice(p,packet.id(),false,"Invalid item display draft");return;}
        try {worker.execute(()->{
            try {
                if(!peer.alive.get())return;
                var draft=RelayRecord.decode(bytes);
                if(!draft.id().equals(packet.id())||!draft.author().equals("draft")||!draft.catalog().isEmpty()||!draft.emoji().isEmpty()||draft.actor()!=null||!draft.nativeItems().isEmpty())throw new IllegalArgumentException();
                Limits.text(draft.text(),1800,"bridge text");
                for(var mention:draft.mentions())if(!mention.target().equals(knownActors.get(mention.target().key())))throw new IllegalArgumentException("Unknown or spoofed mention identity");
                if(!itemEnabled&&!ItemExpansion.positions(draft).isEmpty()){active.execute(()->{if(server==active&&peer.alive.get())BridgeNetwork.notice(p,packet.id(),false,"Item display is disabled by server configuration");});return;}
                if(held!=null&&!ItemExpansion.positions(draft).isEmpty()&&!draft.attachments().isEmpty()){
                    active.execute(()->{if(server==active&&peer.alive.get())BridgeNetwork.notice(p,packet.id(),false,"Item display cannot be combined with an uploaded attachment");});return;
                }
                Map<String,Content.CustomEmoji> catalog=mode.equals("test")?MockTransport.EMOJI:Map.of();var emoji=new HashMap<String,Attachment>();
                for(var c:catalog.values())emoji.put(c.mediaKey(),Attachment.fromResource(c.mediaKey()));
                var prepared=transport.prepare(new RelayRecord(UUID.randomUUID(),author,draft.text(),draft.attachments(),catalog,emoji,sender,draft.mentions()));
                var expanded=ItemExpansion.expand(prepared,held,renderer);var record=expanded.game();record.encode();expanded.discord().encode();
                if(!peer.alive.get())return;
                transport.send(expanded.discord());
                active.execute(()->{if(server!=active)return;publish(record);if(peer.alive.get())BridgeNetwork.notice(p,packet.id(),true,"Accepted by SERVER "+mode.toUpperCase(Locale.ROOT));});
            }catch(Exception rejected){active.execute(()->{if(server==active&&peer.alive.get())BridgeNetwork.notice(p,packet.id(),false,"Rejected or delivery uncertain; no automatic retry");});}
        });}catch(RejectedExecutionException full){BridgeNetwork.notice(p,packet.id(),false,"Bridge queue full");}
    }
    private static void reject(ServerPlayer p,UUID id,String why){transfers.remove(p.getUUID());BridgeNetwork.notice(p,id,false,why);}
    private static void remember(ActorRef actor){if(actor==null)return;actors.put(actor.key(),actor);while(actors.size()>128)actors.remove(actors.keySet().iterator().next());}
    static boolean nativeObserverEnabled(ServerPlayer player){return server==player.server&&running&&!stopping&&adapter!=null&&(!mode.equals("test")||Boolean.getBoolean("discraft.optionalClientSmoke"));}
    static boolean vanillaChatEnabled(ServerPlayer player){return nativeObserverEnabled(player)&&allowed(player);}
    static boolean admitVanillaChat(ServerPlayer player){var peer=peers.get(player.getUUID());return vanillaChatEnabled(player)&&peer!=null&&peer.alive.get()&&peer.consume();}
    static ItemSnapshot vanillaItem(ServerPlayer player,String raw){return config.itemDisplay()&&dev.discraft.core.ItemMarkers.contains(raw)?ItemSnapshot.capture(player,text):null;}
    static void relayVanilla(ServerPlayer player,String body,ItemSnapshot item){
        var peer=peers.get(player.getUUID());if(!vanillaChatEnabled(player)||peer==null||!peer.alive.get())return;var active=server;var transport=adapter;var renderer=itemRenderer;String author=player.getGameProfile().getName();var actor=new ActorRef(ActorRef.Kind.MINECRAFT,player.getUUID().toString(),author);
        try{worker.execute(()->{try{if(!peer.alive.get())return;var prepared=transport.prepare(new RelayRecord(UUID.randomUUID(),author,body,List.of(),Map.of(),Map.of(),actor,List.of()));var discord=ItemExpansion.expand(prepared,item,renderer).discord();discord.encode();transport.send(discord);}
            catch(Exception rejected){active.execute(()->{if(server==active&&peer.alive.get())player.sendSystemMessage(Component.literal("DisCraft relay rejected or delivery uncertain; no automatic retry"));});}});}catch(RejectedExecutionException full){player.sendSystemMessage(Component.literal("DisCraft relay queue full"));}
    }
    private static void publish(RelayRecord record){
        remember(record.actor());for(var mention:record.mentions())remember(mention.target());
        if(history.stream().anyMatch(r->r.id().equals(record.id())))return;
        history.addLast(record);
        while(history.size()>12||history.stream().mapToLong(r->r.message().attachments().stream().mapToLong(a->(long)a.media().width()*a.media().height()*a.media().frames().size()).sum()+r.emoji().values().stream().mapToLong(a->(long)a.media().width()*a.media().height()*a.media().frames().size()).sum()).sum()>2*Limits.MEDIA_TOTAL_PIXELS)history.removeFirst();
        for(var p:server.getPlayerList().getPlayers())if(allowed(p))deliver(p,record);
    }
    private static void deliver(ServerPlayer p,RelayRecord record){
        if(BridgeNetwork.present(p)){try{BridgeNetwork.deliver(p,record);}catch(Exception rejected){BridgeNetwork.notice(p,record.id(),false,"Message exceeds bridge budget");}}
        else p.sendSystemMessage(NativeFallbackChat.compose(record));
    }
}
