package dev.discraft.forge.server;

import com.google.gson.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.*;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.network.chat.Component;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.security.MessageDigest;
import dev.discraft.forge.bridge.RelayRecord;

/** Explicit isolated benchmark only; fake player, existing TEST queue and loopback HTTP. Never shipped. */
final class ItemPipelineTiming {
    static void run(ServerPlayer player,Path out)throws Exception{
        var previous=player.getMainHandItem().copy();var report=new JsonObject();var capture=new JsonArray();var rows=new JsonArray();
        var text=new ComponentText(ResourcePackIndex.empty(),"zh_tw");var snapshots=new ArrayList<ItemSnapshot>();
        try{
            for(int i=0;i<12;i++){
                var stack=new ItemStack(Items.NETHERITE_PICKAXE);stack.setHoverName(Component.literal("效能測試鎬 "+i));stack.enchant(Enchantments.BLOCK_EFFICIENCY,5);stack.enchant(Enchantments.MENDING,1);stack.enchant(Enchantments.UNBREAKING,3);stack.setDamageValue(100);player.setItemInHand(InteractionHand.MAIN_HAND,stack);
                long start=System.nanoTime();snapshots.add(ItemSnapshot.capture(player,text));capture.add(System.nanoTime()-start);
            }
            var f=ServerBridge.class.getDeclaredField("worker");f.setAccessible(true);var worker=(ThreadPoolExecutor)f.get(null);
            var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
            worker.execute(()->{entered.countDown();try{release.await();}catch(InterruptedException stopped){Thread.currentThread().interrupt();}});
            if(!entered.await(2,TimeUnit.SECONDS))throw new AssertionError("Timing gate did not start");
            worker.execute(BridgeWorkQueue.background(()->{try{Thread.sleep(100);}catch(InterruptedException stopped){Thread.currentThread().interrupt();}}));
            final long queueAt=System.nanoTime();Future<JsonObject> pendingBundle;
            try{pendingBundle=worker.submit(()->{
                var values=new JsonObject();values.addProperty("queue_ns",System.nanoTime()-queueAt);long start=System.nanoTime();var renderer=new HeadlessItemRenderer(ResourcePackIndex.empty());values.addProperty("renderer_init_ns",System.nanoTime()-start);
                for(var snapshot:snapshots){var row=new JsonObject();start=System.nanoTime();var image=renderer.tooltip(snapshot);row.addProperty("render_miss_ns",System.nanoTime()-start);start=System.nanoTime();if(renderer.tooltip(snapshot)!=image)throw new AssertionError("Warm cache changed identity");row.addProperty("render_hit_ns",System.nanoTime()-start);
                    var record=new RelayRecord(UUID.randomUUID(),"TimingFixture","看看 %i",List.of(),Map.of(),Map.of());start=System.nanoTime();var expanded=ItemExpansion.expand(record,snapshot,renderer);expanded.game().encode();expanded.discord().encode();row.addProperty("expand_and_encode_ns",System.nanoTime()-start);row.addProperty("png_bytes",image.attachment().bytes().length);row.addProperty("png_sha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(image.attachment().bytes())));rows.add(row);
                }
                return values;
            });}finally{release.countDown();}
            var bundle=pendingBundle.get(30,TimeUnit.SECONDS);report.addProperty("foreground_behind_queued_background_ns",bundle.get("queue_ns").getAsLong());
            report.add("worker",bundle);report.add("capture_ns",capture);report.add("renders",rows);
            var timings=new LinkedHashMap<String,Long>();var renderer=new HeadlessItemRenderer(ResourcePackIndex.empty());var expanded=ItemExpansion.expand(new RelayRecord(UUID.randomUUID(),"TimingFixture","看看 %i",List.of(),Map.of(),Map.of()),snapshots.get(0),renderer);
            ItemFixtures.sendToLocalDiscord(expanded.discord(),timings);var http=new JsonObject();timings.forEach(http::addProperty);report.add("loopback_http_ns",http);
            report.addProperty("scope","Actual game-thread ItemStack capture; actual TEST bridge queue; bounded CPU tooltip; loopback official adapter only. No WAN/Discord latency claim.");
            Files.writeString(out.resolve("item-timing-"+System.getProperty("discraft.itemTimingTag","baseline")+".json"),report.toString());System.out.println("PASS Actual item pipeline timing saved; isolated loopback only");
        }finally{player.setItemInHand(InteractionHand.MAIN_HAND,previous);}
    }
}
