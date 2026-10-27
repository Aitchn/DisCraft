package dev.discraft.forge;

import dev.discraft.core.*;
import dev.discraft.forge.bridge.*;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

/** Client endpoint. Only server-authored, locally revalidated records enter the transcript. */
@Mod.EventBusSubscriber(modid=DisCraft.ID,value=Dist.CLIENT)
public final class ClientBridge {
    private static final MentionNotifications notifications=new MentionNotifications();
    private static final UUID STATUS=new UUID(0,0);
    private static final Map<UUID,Assembly> transfers=new HashMap<>();
    private static final Deque<RelayRecord> history=new ArrayDeque<>();
    private static final Map<UUID,CompletableFuture<String>> pending=new ConcurrentHashMap<>();
    private static final Set<UUID> seen=new LinkedHashSet<>();
    private static final ThreadPoolExecutor decoder=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(16),r->{var t=new Thread(r,"DisCraft client decoder");t.setDaemon(true);return t;});
    private static volatile long generation;
    private static volatile boolean enabled;
    private static boolean replaying=true;
    private static volatile String status="Server bridge unavailable";
    private static final class Assembly {final int total;final byte[] bytes;final long started=System.nanoTime();final boolean historical;int at;Assembly(int total){this.total=total;historical=replaying;bytes=new byte[total];}}
    @SubscribeEvent public static void login(ClientPlayerNetworkEvent.LoggingIn e){reset("Awaiting server bridge");}
    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut e){reset("Disconnected");}
    private static void reset(String reason){notifications.reset();replaying=true;NativeChat.reset();ClientGuildEmoji.reset();generation++;enabled=false;status=reason;history.clear();seen.clear();transfers.clear();decoder.getQueue().clear();pending.values().forEach(f->f.completeExceptionally(new IOException(reason)));pending.clear();}
    public static String status(){return status;}
    public static boolean available(){var c=Minecraft.getInstance().getConnection();return enabled&&c!=null&&BridgeNetwork.present(c.getConnection());}
    public static List<Message> snapshot(){return history.stream().map(RelayRecord::message).toList();}
    public static Map<String,Content.CustomEmoji> catalog(){var all=new LinkedHashMap<String,Content.CustomEmoji>();for(var r:history)all.putAll(r.catalog());return Map.copyOf(all);}
    public static Attachment emoji(String key){for(var r:history)if(r.emoji().containsKey(key))return r.emoji().get(key);return null;}
    public static String emojiShortcut(int index){
        var snapshot=ClientGuildEmoji.snapshot();var entries=snapshot.entries().stream().filter(GuildEmojiCatalog.Entry::usable).toList();
        if(index<0||index>=entries.size())return "";var entry=entries.get(index);return snapshot.fixture()?":"+entry.name()+":":entry.markup();
    }
    public static String send(String text,Attachment attachment)throws Exception{return send(text,attachment,List.of());}
    public static String send(String text,Attachment attachment,List<Mention> mentions)throws Exception{
        long epoch=generation;var connection=Minecraft.getInstance().getConnection();
        if(!available())throw new IOException("Server bridge disabled, unavailable, or player not authorized");
        if(pending.size()>=4)throw new IOException("Too many pending sends");
        UUID id=UUID.randomUUID();byte[] bytes=new RelayRecord(id,"draft",text,attachment==null?List.of():List.of(attachment),Map.of(),Map.of(),null,mentions).encode();
        var accepted=new CompletableFuture<String>();pending.put(id,accepted);
        Minecraft.getInstance().execute(()->{
            if(epoch!=generation||Minecraft.getInstance().getConnection()!=connection||!available()){accepted.completeExceptionally(new IOException("Connection changed"));return;}
            for(int at=0;at<bytes.length;at+=BridgeNetwork.CHUNK)BridgeNetwork.CHANNEL.sendToServer(new BridgeNetwork.Upload(id,bytes.length,at,Arrays.copyOfRange(bytes,at,Math.min(bytes.length,at+BridgeNetwork.CHUNK))));
        });
        try{return accepted.get(15,TimeUnit.SECONDS);}catch(TimeoutException timeout){throw new IOException("Delivery timed out and may be uncertain; no automatic retry");}finally{pending.remove(id);}
    }
    public static void notice(BridgeNetwork.Notice n){if(n.request().equals(BridgeNetwork.HISTORY_BEGIN)){replaying=true;return;}if(n.request().equals(BridgeNetwork.HISTORY_END)){replaying=false;return;}if(n.request().equals(STATUS)){enabled=n.accepted();status=n.status();return;}var f=pending.get(n.request());if(f!=null){if(n.accepted())f.complete(n.status());else f.completeExceptionally(new IOException(n.status()));}}
    public static void download(BridgeNetwork.Download p){
        transfers.entrySet().removeIf(e->System.nanoTime()-e.getValue().started>15_000_000_000L);
        if(p.total()<1||p.total()>RelayRecord.MAX_BYTES||p.offset()<0||p.bytes().length<1){status="Rejected server packet bounds";return;}
        if(p.offset()==0){if(transfers.size()>=8){status="Receive queue full";return;}transfers.put(p.id(),new Assembly(p.total()));}
        var a=transfers.get(p.id());
        if(a==null||a.total!=p.total()||a.at!=p.offset()||p.bytes().length>a.total-a.at){transfers.remove(p.id());status="Rejected server packet sequence";return;}
        System.arraycopy(p.bytes(),0,a.bytes,a.at,p.bytes().length);a.at+=p.bytes().length;if(a.at<a.total)return;transfers.remove(p.id());long epoch=generation;
        try{decoder.execute(()->{try{var r=RelayRecord.decode(a.bytes);if(!r.id().equals(p.id()))throw new IOException();Minecraft.getInstance().execute(()->{
            if(epoch!=generation||seen.contains(r.id()))return;seen.add(r.id());if(seen.size()>256)seen.remove(seen.iterator().next());history.addLast(r);
            while(history.size()>12||history.stream().mapToLong(ClientBridge::pixels).sum()>2*Limits.MEDIA_TOTAL_PIXELS)history.removeFirst();
            NativeChat.receive(r,history);
            var mc=Minecraft.getInstance();if(notifications.accept(r,mc.player==null?null:mc.player.getUUID(),ClientPreferences.MENTION_SOUND.get()&&!a.historical))mc.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(net.minecraft.sounds.SoundEvents.NOTE_BLOCK_PLING.get(),1f,0.7f));
        });}catch(Exception invalid){Minecraft.getInstance().execute(()->{if(epoch==generation)status="Rejected invalid server media";});}});}catch(RejectedExecutionException full){status="Client decode queue full";}
    }
    private static long pixels(RelayRecord r){return java.util.stream.Stream.concat(r.attachments().stream(),r.emoji().values().stream()).mapToLong(a->(long)a.media().width()*a.media().height()*a.media().frames().size()).sum();}
}
