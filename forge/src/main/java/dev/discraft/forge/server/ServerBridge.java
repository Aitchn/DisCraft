package dev.discraft.forge.server;

import dev.discraft.core.*;
import dev.discraft.forge.DisCraft;
import dev.discraft.forge.bridge.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
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
    private static final Map<UUID,Transfer> transfers=new HashMap<>();
    private static final Map<UUID,Peer> peers=new HashMap<>();
    private static final Deque<RelayRecord> history=new ArrayDeque<>();
    private static final Set<String> requests=new LinkedHashSet<>();
    private static final AtomicBoolean polling=new AtomicBoolean();
    private static long nextPoll;
    private static final class Peer {
        final AtomicBoolean alive=new AtomicBoolean(true); final Set<UUID> seen=new LinkedHashSet<>();
        double tokens=5; long updated=System.nanoTime();
        boolean consume(){long now=System.nanoTime();tokens=Math.min(5,tokens+(now-updated)/1e9);updated=now;if(tokens<1)return false;tokens--;return true;}
    }
    private static final class Transfer {
        final UUID id; final byte[] bytes; final long started=System.nanoTime();int used;
        Transfer(UUID id,int total){this.id=id;bytes=new byte[total];}
    }
    public static String mode(){return mode;}
    private static boolean allowed(ServerPlayer p){
        if(mode.equals("test"))return true;
        String list=System.getenv().getOrDefault("DISCRAFT_ALLOWED_PLAYERS","");
        return Arrays.stream(list.split(",")).map(String::trim).anyMatch(s->s.equals(p.getUUID().toString())||s.equals("*"));
    }
    @SubscribeEvent public static void start(ServerStartedEvent event){
        server=event.getServer();mode=System.getenv().getOrDefault("DISCRAFT_MODE","disabled");
        worker=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(16),r->{var t=new Thread(r,"DisCraft server bridge");t.setDaemon(true);return t;});
        try {
            if(mode.equals("test")&&Boolean.getBoolean("discraft.multiplayerSmoke"))adapter=new TestDiscordAdapter();
            else if(mode.equals("discord")&&Boolean.getBoolean("discraft.discord.enabled")&&server.usesAuthentication())adapter=DiscordBotAdapter.fromEnvironment();
            else {mode="disabled";adapter=null;}
        } catch(Exception failure){mode="disabled";adapter=null;}
        nextPoll=0;polling.set(false);
    }
    @SubscribeEvent public static void stop(ServerStoppingEvent event){
        peers.values().forEach(p->p.alive.set(false));peers.clear();transfers.clear();history.clear();requests.clear();
        if(adapter!=null)adapter.close();if(worker!=null)worker.shutdownNow();adapter=null;server=null;mode="disabled";
    }
    @SubscribeEvent public static void login(PlayerEvent.PlayerLoggedInEvent event){
        if(!(event.getEntity() instanceof ServerPlayer p))return;
        peers.put(p.getUUID(),new Peer());
        BridgeNetwork.notice(p,STATUS,adapter!=null&&allowed(p),"SERVER "+mode.toUpperCase(Locale.ROOT)+(allowed(p)?"":" (not authorized)"));
        if(allowed(p))for(var record:history)deliver(p,record);
    }
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent event){
        var peer=peers.remove(event.getEntity().getUUID());if(peer!=null)peer.alive.set(false);transfers.remove(event.getEntity().getUUID());BridgeNetwork.forget(event.getEntity().getUUID());
    }
    @SubscribeEvent public static void tick(TickEvent.ServerTickEvent event){
        if(event.phase!=TickEvent.Phase.END||server==null)return;
        transfers.entrySet().removeIf(e->{if(System.nanoTime()-e.getValue().started<15_000_000_000L)return false;var p=server.getPlayerList().getPlayer(e.getKey());if(p!=null)BridgeNetwork.notice(p,e.getValue().id,false,"Upload expired");return true;});
        if(adapter==null||System.nanoTime()<nextPoll||!polling.compareAndSet(false,true))return;
        nextPoll=System.nanoTime()+2_000_000_000L;
        var active=server;var transport=adapter;
        try {worker.execute(()->{try{for(var record:transport.poll())active.execute(()->{if(server==active)publish(record);});}
            catch(Exception unavailable){/* No token, response body, URL, or user content is logged. */}
            finally{polling.set(false);}});}catch(RejectedExecutionException full){polling.set(false);}
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
        transfers.remove(uuid);byte[] bytes=transfer.bytes;String author=p.getGameProfile().getName();var active=server;var transport=adapter;
        try {worker.execute(()->{
            try {
                if(!peer.alive.get())return;
                var draft=RelayRecord.decode(bytes);
                if(!draft.id().equals(packet.id())||!draft.author().equals("draft")||!draft.catalog().isEmpty()||!draft.emoji().isEmpty())throw new IllegalArgumentException();
                Limits.text(draft.text(),1800,"bridge text");
                Map<String,Content.CustomEmoji> catalog=mode.equals("test")?MockTransport.EMOJI:Map.of();var emoji=new HashMap<String,Attachment>();
                for(var c:catalog.values())emoji.put(c.mediaKey(),Attachment.fromResource(c.mediaKey()));
                var record=transport.prepare(new RelayRecord(UUID.randomUUID(),author,draft.text(),draft.attachments(),catalog,emoji));record.encode();
                if(!peer.alive.get())return;
                transport.send(record);
                active.execute(()->{if(server!=active)return;publish(record);if(peer.alive.get())BridgeNetwork.notice(p,packet.id(),true,"Accepted by SERVER "+mode.toUpperCase(Locale.ROOT));});
            }catch(Exception rejected){active.execute(()->{if(server==active&&peer.alive.get())BridgeNetwork.notice(p,packet.id(),false,"Rejected or delivery uncertain; no automatic retry");});}
        });}catch(RejectedExecutionException full){BridgeNetwork.notice(p,packet.id(),false,"Bridge queue full");}
    }
    private static void reject(ServerPlayer p,UUID id,String why){transfers.remove(p.getUUID());BridgeNetwork.notice(p,id,false,why);}
    private static void publish(RelayRecord record){
        if(history.stream().anyMatch(r->r.id().equals(record.id())))return;
        history.addLast(record);
        while(history.size()>12||history.stream().mapToLong(r->r.message().attachments().stream().mapToLong(a->(long)a.media().width()*a.media().height()*a.media().frames().size()).sum()+r.emoji().values().stream().mapToLong(a->(long)a.media().width()*a.media().height()*a.media().frames().size()).sum()).sum()>2*Limits.MEDIA_TOTAL_PIXELS)history.removeFirst();
        for(var p:server.getPlayerList().getPlayers())if(allowed(p))deliver(p,record);
    }
    private static void deliver(ServerPlayer p,RelayRecord record){
        if(BridgeNetwork.present(p)){try{BridgeNetwork.deliver(p,record);}catch(Exception rejected){BridgeNetwork.notice(p,record.id(),false,"Message exceeds bridge budget");}}
        else p.sendSystemMessage(Component.literal("[DisCraft "+mode+"] "+record.author()+": "+record.text()+(record.attachments().isEmpty()?"":" [media requires DisCraft client]")));
    }
}
