package dev.discraft.forge;

import dev.discraft.core.*;
import dev.discraft.forge.bridge.*;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import java.util.*;
import java.util.concurrent.*;

/** Client metadata/preview endpoint. Only the server selects URLs, credentials and guild membership. */
@Mod.EventBusSubscriber(modid=DisCraft.ID,value=Dist.CLIENT)
public final class ClientGuildEmoji {
    private static GuildEmojiCatalog catalog=GuildEmojiCatalog.empty(0);
    private static long expires,nextRequest,generation;
    private static final LinkedHashMap<String,Cached> images=new LinkedHashMap<>(16,.75f,true);
    private static final Map<String,Long> pending=new HashMap<>();
    private static final LinkedHashSet<String> wanted=new LinkedHashSet<>();
    private static final Map<String,Assembly> transfers=new HashMap<>();
    private static final ThreadPoolExecutor decoder=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(4),r->{var t=new Thread(r,"DisCraft emoji preview decoder");t.setDaemon(true);return t;});
    private record Cached(Attachment image,long expires){}
    private static final class Assembly{final UUID id;final byte[] bytes;int at;Assembly(UUID id,int size){this.id=id;bytes=new byte[size];}}
    public static void reset(){generation++;catalog=GuildEmojiCatalog.empty(0);expires=0;images.clear();pending.clear();wanted.clear();transfers.clear();decoder.getQueue().clear();}
    public static GuildEmojiCatalog snapshot(){
        if(!ClientBridge.available()||System.nanoTime()>expires){if(catalog.ready()){catalog=GuildEmojiCatalog.empty(catalog.version());images.clear();pending.clear();wanted.clear();transfers.clear();generation++;}}
        return catalog;
    }
    public static void catalog(byte[] bytes){
        try{
            var next=GuildEmojiCatalog.decode(bytes);if(next.version()<catalog.version()||next.version()==catalog.version()&&catalog.ready()&&!next.equals(catalog))return;
            if(next.version()!=catalog.version()||!next.ready()){
                generation++;pending.clear();transfers.clear();wanted.clear();decoder.getQueue().clear();
                images.keySet().removeIf(id->{var before=catalog.find(id);var after=next.find(id);return before==null||after==null||!after.usable()||before.animated()!=after.animated();});
            }
            catalog=next;expires=System.nanoTime()+GuildEmojiCatalog.CLIENT_TTL_NANOS;
        }catch(Exception ignored){/* A malformed catalog cannot replace the last validated snapshot. */}
    }
    public static Attachment preview(String id){
        var e=snapshot().find(id);if(e==null||!e.usable())return null;var cached=images.get(id);
        if(cached==null||System.nanoTime()>=cached.expires()){images.remove(id);return null;}return cached.image();
    }
    public static void visible(Collection<String> ids){wanted.clear();for(String id:ids){if(wanted.size()>=64)break;var e=snapshot().find(id);if(e!=null&&e.usable())wanted.add(id);}}
    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event){
        if(event.phase!=TickEvent.Phase.END||!snapshot().ready())return;long now=System.nanoTime();
        for(String id:new ArrayList<>(pending.keySet()))if(now-pending.get(id)>10_000_000_000L){pending.remove(id);transfers.remove(id);images.put(id,new Cached(null,now+30_000_000_000L));}
        trim();
        if(now<nextRequest||!pending.isEmpty())return;
        var ids=new ArrayList<String>();for(String id:wanted){var cached=images.get(id);if(cached!=null&&now<cached.expires())continue;ids.add(id);if(ids.size()==GuildEmojiCatalog.THUMB_BATCH)break;}
        if(ids.isEmpty())return;nextRequest=now+1_250_000_000L;for(String id:ids)pending.put(id,now);
        BridgeNetwork.CHANNEL.sendToServer(new BridgeNetwork.ThumbRequest(catalog.version(),List.copyOf(ids)));
    }
    public static void thumbnail(BridgeNetwork.ThumbChunk packet){
        if(packet.version()!=snapshot().version()||!pending.containsKey(packet.emoji()))return;
        var entry=catalog.find(packet.emoji());if(entry==null||!entry.usable())return;
        if(packet.total()==0&&packet.offset()==0&&packet.bytes().length==0){pending.remove(packet.emoji());images.put(packet.emoji(),new Cached(null,System.nanoTime()+30_000_000_000L));trim();return;}
        if(packet.total()<1||packet.total()>RelayRecord.MAX_BYTES||packet.offset()<0||packet.bytes().length<1||packet.bytes().length>BridgeNetwork.CHUNK)return;
        if(packet.offset()==0){if(transfers.size()>=GuildEmojiCatalog.THUMB_BATCH)return;transfers.put(packet.emoji(),new Assembly(packet.transfer(),packet.total()));}
        var transfer=transfers.get(packet.emoji());
        if(transfer==null||!transfer.id.equals(packet.transfer())||transfer.bytes.length!=packet.total()||transfer.at!=packet.offset()||packet.bytes().length>transfer.bytes.length-transfer.at){transfers.remove(packet.emoji());return;}
        System.arraycopy(packet.bytes(),0,transfer.bytes,transfer.at,packet.bytes().length);transfer.at+=packet.bytes().length;if(transfer.at<transfer.bytes.length)return;
        transfers.remove(packet.emoji());long epoch=generation,version=catalog.version();
        try{decoder.execute(()->{
            Attachment image=null;
            try{var record=RelayRecord.decode(transfer.bytes);if(!record.id().equals(transfer.id)||!record.author().equals("emoji-preview")||!record.text().equals(packet.emoji())||record.attachments().size()!=1||!record.catalog().isEmpty()||!record.emoji().isEmpty()||record.actor()!=null||!record.mentions().isEmpty())throw new IllegalArgumentException();
                image=record.attachments().get(0);if(pixels(image)>GuildEmojiCatalog.THUMB_SINGLE_PIXELS)throw new IllegalArgumentException();
            }catch(Exception ignored){}
            var decoded=image;Minecraft.getInstance().execute(()->{if(epoch!=generation||version!=catalog.version())return;pending.remove(packet.emoji());images.put(packet.emoji(),new Cached(decoded,System.nanoTime()+(decoded==null?30_000_000_000L:600_000_000_000L)));trim();});
        });}catch(RejectedExecutionException ignored){pending.remove(packet.emoji());images.put(packet.emoji(),new Cached(null,System.nanoTime()+30_000_000_000L));trim();}
    }
    private static long pixels(Attachment image){return (long)image.media().width()*image.media().height()*image.media().frames().size();}
    private static void trim(){
        while(images.size()>64||images.values().stream().filter(c->c.image()!=null).count()>GuildEmojiCatalog.THUMB_IMAGES||images.values().stream().filter(c->c.image()!=null).mapToLong(c->c.image().bytes().length).sum()>GuildEmojiCatalog.THUMB_BYTES||images.values().stream().filter(c->c.image()!=null).mapToLong(c->pixels(c.image())).sum()>GuildEmojiCatalog.THUMB_PIXELS)images.remove(images.keySet().iterator().next());
    }
}
