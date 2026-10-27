package dev.discraft.forge.server;

import com.mojang.authlib.GameProfile;
import dev.discraft.forge.DisCraft;
import net.minecraft.advancements.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.InteractionHand;
import net.minecraft.network.chat.Style;
import net.minecraft.world.level.*;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.*;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.*;
import net.minecraftforge.event.server.*;
import net.minecraftforge.eventbus.api.*;
import net.minecraftforge.fml.common.Mod;
import java.nio.file.*;
import java.util.*;

/** Actual headless dedicated runtime with synthetic Forge events and a recording TEST recipient. */
@Mod.EventBusSubscriber(modid=DisCraft.ID)
public final class ServerEventAcceptance {
    private static MinecraftServer server;private static FakePlayer player;private static TestDiscordAdapter adapter;
    private static int ticks,stage,checks;private static String failure;private static final List<String> results=new ArrayList<>();
    private static boolean enabled(){return Boolean.getBoolean("discraft.multiplayerSmoke")&&Boolean.getBoolean("discraft.serverEventsSmoke");}
    private static void check(boolean value,String label){if(!value)throw new AssertionError(label);results.add("PASS "+label);checks++;}
    private static int count(){return adapter.eventReceipts.size();}
    private static void death(boolean cancel,boolean cancelLater,boolean rescue){
        player.setHealth(0);var event=new LivingDeathEvent(player,player.damageSources().generic());if(cancel)event.setCanceled(true);MinecraftForge.EVENT_BUS.post(event);if(cancelLater)event.setCanceled(true);if(rescue)player.setHealth(20);
    }
    private static Advancement advancement(String path,boolean display,boolean announce,boolean hidden,boolean complete){
        var builder=Advancement.Builder.advancement().addCriterion("one",new Criterion()).addCriterion("two",new Criterion());
        if(display)builder.display(Items.DIAMOND,Component.literal("星光 **自訂** @everyone"),Component.literal("fixture"),null,FrameType.TASK,false,announce,hidden);
        var advancement=builder.build(new ResourceLocation("discraft_test",path));var progress=player.getAdvancements().getOrStartProgress(advancement);progress.grantProgress("one");if(complete)progress.grantProgress("two");
        MinecraftForge.EVENT_BUS.post(new AdvancementEvent.AdvancementEarnEvent(player,advancement));return advancement;
    }
    @SubscribeEvent(priority=EventPriority.LOWEST) public static void about(ServerAboutToStartEvent event)throws Exception{
        if(!enabled())return;server=event.getServer();
        check(Files.isRegularFile(dev.discraft.forge.DisCraftPaths.serverConfig(net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get(),dev.discraft.forge.DisCraftPaths::warn)),"Actual dedicated startup provides server config before TEST transport, without a Discord JVM gate");
        var field=ServerBridge.class.getDeclaredField("adapter");field.setAccessible(true);adapter=(TestDiscordAdapter)field.get(null);
    }
    @SubscribeEvent(priority=EventPriority.LOWEST) public static void starting(ServerStartingEvent event){
        if(enabled()&&Boolean.getBoolean("discraft.serverEventsFailureSmoke"))throw new IllegalStateException("Expected isolated fixture startup failure");
    }
    @SubscribeEvent(priority=EventPriority.LOWEST) public static void started(ServerStartedEvent event)throws Exception{
        if(!enabled())return;
        try{InstalledLanguageAcceptance.run(Path.of(System.getProperty("discraft.serverEventsOutput")));}catch(Throwable rejected){failure="Installed language: "+rejected.getClass().getSimpleName()+": "+rejected.getMessage();server.halt(false);return;}
        player=FakePlayerFactory.get(server.overworld(),new GameProfile(UUID.fromString("5b52ba38-7083-4de2-888f-8fe83c0bf240"),"EventTester"));player.setHealth(20);
        if(Boolean.getBoolean("discraft.serverItemsSmoke"))try{if(Boolean.getBoolean("discraft.itemTimingSmoke"))ItemPipelineTiming.run(player,Path.of(System.getProperty("discraft.serverEventsOutput")));items();}catch(Throwable rejected){failure="Item pipeline: "+rejected.getClass().getSimpleName()+": "+rejected.getMessage();server.halt(false);}
    }
    private static void items()throws Exception{
        attributeOverrides();
        Path output=Path.of(System.getProperty("discraft.serverEventsOutput"));Path packs=output.resolve("item-fixture-packs");ItemFixtures.create(packs);
        var resources=ResourcePackIndex.load(packs,List.of("fixture.zip"));var text=new ComponentText(resources,"zh_tw");var renderer=new HeadlessItemRenderer(resources);
        var stack=new ItemStack(Items.DIAMOND_SWORD,3);stack.setHoverName(Component.literal("星光劍 · TEST").withStyle(Style.EMPTY.withFont(new ResourceLocation("test","custom"))));stack.getOrCreateTag().putInt("CustomModelData",1);
        String privateFixture="private-book-container-fixture-do-not-export";stack.getOrCreateTag().putString("pages",privateFixture);stack.getOrCreateTag().putString("BlockEntityTag",privateFixture);stack.getOrCreateTag().putString("Lore",privateFixture);
        player.setItemInHand(InteractionHand.MAIN_HAND,stack);player.setItemInHand(InteractionHand.OFF_HAND,new ItemStack(Items.STONE,7));
        var captured=ItemSnapshot.capture(player,text);stack.setCount(1);stack.getOrCreateTag().putInt("CustomModelData",2);
        check(captured.id().equals("minecraft:diamond_sword")&&captured.count()==3&&captured.customModelData()==1&&captured.name().equals("星光劍 · TEST"),"Real authoritative main-hand ItemStack snapshot retains custom name/count/CMD independently of later mutations and offhand");
        check(!captured.toString().contains(privateFixture),"Appearance whitelist excludes arbitrary lore, book and container fixture data");
        var expanded=ItemExpansion.expand(new dev.discraft.forge.bridge.RelayRecord(UUID.randomUUID(),player.getGameProfile().getName(),"展示 %item",List.of(),Map.of(),Map.of()),captured,renderer);
        var payload=ItemFixtures.sendToLocalDiscord(expanded.discord());
        check(payload.get("content").getAsString().contains("星光劍 · TEST")&&payload.getAsJsonObject("allowed_mentions").getAsJsonArray("users").isEmpty()&&!payload.toString().contains(privateFixture),"Actual ItemStack to localized text and PNG reaches only loopback official-adapter mock with no pings or private NBT");
        var image=javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(expanded.discord().attachments().get(0).bytes()));
        check(image.getWidth()<=ItemTooltipRenderer.MAX_WIDTH&&image.getHeight()<=ItemTooltipRenderer.MAX_HEIGHT&&captured.tooltip().lines().get(0).plain().equals("星光劍 · TEST"),"Actual ItemStack exports a measured tooltip PNG rather than a fixed model card");
        Files.write(output.resolve("authoritative-main-hand.png"),expanded.discord().attachments().get(0).bytes());
        player.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Items.DIAMOND_SWORD));
        check(ItemSnapshot.capture(player,text).name().equals("測試鑽石劍"),"Unrenamed real ItemStack resolves its description component through imported Traditional Chinese language key");
        player.setItemInHand(InteractionHand.MAIN_HAND,ItemStack.EMPTY);
        check(ItemSnapshot.capture(player,text).empty(),"Actual empty main hand stays empty even while offhand holds an item");
        String local=System.getProperty("discraft.vanillaItemPack");
        var tooltipResources=ResourcePackIndex.empty();if(local!=null){
            Path pack=Path.of(local);var vanillaResources=ResourcePackIndex.load(pack.getParent(),List.of(pack.getFileName().toString()));tooltipResources=vanillaResources;var vanillaText=new ComponentText(vanillaResources,"zh_tw");var vanillaRenderer=new HeadlessItemRenderer(vanillaResources);
            var vanillaItems=List.of(Items.DIAMOND_SWORD,Items.STONE);var expected=List.of("鑽石劍","石頭");var names=List.of("diamond-sword","stone");
            for(int i=0;i<vanillaItems.size();i++){
                player.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(vanillaItems.get(i)));var real=ItemSnapshot.capture(player,vanillaText);var rendered=vanillaRenderer.render(real);
                check(real.name().equals(expected.get(i))&&rendered.status().equals("Standard model"),"Cached original Minecraft 1.20.1 "+names.get(i)+" models/textures and Traditional Chinese language render without model fallback");
                Files.write(output.resolve("vanilla-"+names.get(i)+".png"),rendered.attachment().bytes());
            }
            player.setItemInHand(InteractionHand.MAIN_HAND,ItemStack.EMPTY);
        }
        ItemTooltipAcceptance.run(player,output,tooltipResources,local!=null);
    }
    private static void attributeOverrides(){
        // Pinned 1.20.1 ItemStack.getAttributeModifiers branches on LIST presence,
        // not whether any entries survived. Use the actual native API as oracle.
        String damage=net.minecraft.network.chat.Component.translatable(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE.getDescriptionId()).getString();
        String speed=net.minecraft.network.chat.Component.translatable(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_SPEED.getDescriptionId()).getString();
        for(String kind:List.of("explicit-empty","filtered-empty","missing-key","wrong-field-type","wrong-element-type")){
            var original=new ItemStack(Items.DIAMOND_SWORD);boolean empty=kind.equals("explicit-empty")||kind.equals("filtered-empty")||kind.equals("wrong-element-type");
            if(kind.equals("explicit-empty"))original.getOrCreateTag().put("AttributeModifiers",new net.minecraft.nbt.ListTag());
            if(kind.equals("filtered-empty")){var entries=new net.minecraft.nbt.ListTag();var entry=new net.minecraft.nbt.CompoundTag();entry.putString("AttributeName","minecraft:generic.attack_damage");entry.putString("Name","fixture invalid slot");entry.putDouble("Amount",2);entry.putInt("Operation",0);entry.putUUID("UUID",new UUID(123,456));entry.putString("Slot","not-an-equipment-slot");entries.add(entry);original.getOrCreateTag().put("AttributeModifiers",entries);}
            if(kind.equals("wrong-field-type"))original.getOrCreateTag().putString("AttributeModifiers","fixture-private-wrong-type");
            if(kind.equals("wrong-element-type")){var entries=new net.minecraft.nbt.ListTag();entries.add(net.minecraft.nbt.StringTag.valueOf("fixture-private-wrong-element"));original.getOrCreateTag().put("AttributeModifiers",entries);}
            var captured=dev.discraft.forge.bridge.NativeItemData.capture(original,"Diamond Sword");var safe=new ItemStack(Items.DIAMOND_SWORD);safe.setTag(captured.safeTag());
            boolean originalEmpty=original.getAttributeModifiers(net.minecraft.world.entity.EquipmentSlot.MAINHAND).isEmpty(),safeEmpty=safe.getAttributeModifiers(net.minecraft.world.entity.EquipmentSlot.MAINHAND).isEmpty();
            check(originalEmpty==empty&&safeEmpty==originalEmpty,"Pinned native attribute override semantics retained: "+kind);
            check(captured.safeTag().contains("AttributeModifiers",net.minecraft.nbt.Tag.TAG_LIST)==empty&&!captured.tag().contains("fixture-private"),"Canonical override presence and wrong-type data safety: "+kind);
            var before=original.getTooltipLines(player,net.minecraft.world.item.TooltipFlag.Default.NORMAL).stream().map(Component::getString).filter(s->s.contains(damage)||s.contains(speed)).toList();
            var after=safe.getTooltipLines(player,net.minecraft.world.item.TooltipFlag.Default.NORMAL).stream().map(Component::getString).filter(s->s.contains(damage)||s.contains(speed)).toList();
            check(before.equals(after)&&after.isEmpty()==empty,"Pinned native tooltip attack damage/speed match original: "+kind);
        }
        var pressure=new ItemStack(Items.DIAMOND_SWORD);pressure.setHoverName(Component.literal("N".repeat(512)).withStyle(Style.EMPTY.withFont(new ResourceLocation("minecraft","f".repeat(246)))));
        var source=pressure.getOrCreateTag();var attributes=new net.minecraft.nbt.ListTag();
        for(int i=0;i<8;i++){var entry=new net.minecraft.nbt.CompoundTag();entry.putString("AttributeName","minecraft:"+"a".repeat(244)+"_"+i);entry.putString("Name","original-private-modifier-name");entry.putDouble("Amount",123456.12345678);entry.putInt("Operation",0);entry.putUUID("UUID",new UUID(0x7fffffff7fffffffL-i,0x7fffffff7fffffffL-i));entry.putString("Slot","mainhand");attributes.add(entry);}source.put("AttributeModifiers",attributes);
        for(String key:List.of("Enchantments","StoredEnchantments")){var entries=new net.minecraft.nbt.ListTag();for(int i=0;i<8;i++){var entry=new net.minecraft.nbt.CompoundTag();entry.putString("id","minecraft:"+"e".repeat(244)+i);entry.putShort("lvl",(short)255);entries.add(entry);}source.put(key,entries);}
        var lore=new net.minecraft.nbt.ListTag();for(int i=0;i<8;i++)lore.add(net.minecraft.nbt.StringTag.valueOf(Component.Serializer.toJson(Component.literal("L".repeat(400)))));pressure.getOrCreateTagElement("display").put("Lore",lore);
        var bounded=dev.discraft.forge.bridge.NativeItemData.capture(pressure,"Bounded tooltip");var boundedTag=bounded.safeTag();
        check(boundedTag.contains("AttributeModifiers",net.minecraft.nbt.Tag.TAG_LIST)&&boundedTag.getList("AttributeModifiers",net.minecraft.nbt.Tag.TAG_COMPOUND).isEmpty()&&!boundedTag.getCompound("display").contains("Lore")&&bounded.tag().length()<=dev.discraft.forge.bridge.NativeItemData.MAX_TAG,"Oversize downgrade removes optional detail but retains an explicit empty attribute override");
        var reconstructed=new ItemStack(Items.DIAMOND_SWORD);reconstructed.setTag(boundedTag);
        check(pressure.getAttributeModifiers(net.minecraft.world.entity.EquipmentSlot.MAINHAND).isEmpty()&&reconstructed.getAttributeModifiers(net.minecraft.world.entity.EquipmentSlot.MAINHAND).isEmpty(),"Oversize native reconstruction never restores default weapon modifiers");
        check(reconstructed.getTooltipLines(player,net.minecraft.world.item.TooltipFlag.Default.NORMAL).stream().map(Component::getString).noneMatch(s->s.contains(damage)||s.contains(speed)),"Oversize native tooltip has no fabricated attack damage or attack speed lines");
    }
    @SubscribeEvent(priority=EventPriority.LOWEST) public static void tick(TickEvent.ServerTickEvent event){
        if(!enabled()||server==null||event.phase!=TickEvent.Phase.END||failure!=null||++ticks%10!=0)return;
        try{
            switch(stage++){
                case 0->{check(count()==2&&adapter.eventReceipts.get(0).text().equals("Server is starting")&&adapter.eventReceipts.get(1).text().equals("Server is ready"),"Actual about-to-start and started hooks emit ordered lifecycle notices");ServerBridge.login(new PlayerEvent.PlayerLoggedInEvent(player));ServerBridge.login(new PlayerEvent.PlayerLoggedInEvent(player));}
                case 1->{check(count()==3&&adapter.eventReceipts.get(2).text().contains("EventTester"),"Duplicate login produces one authoritative join notice");MinecraftForge.EVENT_BUS.post(new PlayerEvent.PlayerRespawnEvent(player,false));MinecraftForge.EVENT_BUS.post(new PlayerEvent.PlayerChangedDimensionEvent(player,Level.OVERWORLD,Level.NETHER));death(true,false,false);}
                case 2->{check(count()==3,"Respawn, dimension change and initially cancelled death produce no join/quit/death notices");death(false,true,false);}
                case 3->{check(count()==3,"Death cancelled after the bridge handler finishes is still suppressed");death(false,false,true);}
                case 4->{check(count()==3,"Player rescued before END tick produces no death notice");death(false,false,false);}
                case 5->{check(count()==4&&adapter.eventReceipts.get(3).text().contains("EventTester"),"Final dead state emits one combat-tracker component notice");death(false,false,false);advancement("partial",true,true,false,false);}
                case 6->{check(count()==4,"Repeated same death and incomplete advancement are suppressed");var done=advancement("complete",true,true,false,true);MinecraftForge.EVENT_BUS.post(new AdvancementEvent.AdvancementEarnEvent(player,done));}
                case 7->{check(count()==5&&adapter.eventReceipts.get(4).text().contains("星光")&&adapter.eventReceipts.get(4).text().contains("@\u200beveryone"),"Completed advancement deduplicates and preserves a safe literal custom title");advancement("hidden",true,true,true,true);advancement("silent",true,false,false,true);advancement("no_display",false,true,false,true);advancement("recipes/fixture",true,true,false,true);server.getGameRules().getRule(GameRules.RULE_ANNOUNCE_ADVANCEMENTS).set(false,server);advancement("gamerule_off",true,true,false,true);}
                case 8->{check(count()==5,"Hidden, silent, no-display, recipe and disabled-gamerule advancements produce no notice");server.getGameRules().getRule(GameRules.RULE_ANNOUNCE_ADVANCEMENTS).set(true,server);ServerBridge.logout(new PlayerEvent.PlayerLoggedOutEvent(player));ServerBridge.logout(new PlayerEvent.PlayerLoggedOutEvent(player));}
                case 9->{check(count()==6&&adapter.eventReceipts.get(5).text().contains("EventTester"),"Duplicate logout produces one quit notice");server.halt(false);}
            }
        }catch(Throwable rejected){failure=rejected.getClass().getSimpleName()+": "+rejected.getMessage();server.halt(false);}
    }
    @SubscribeEvent(priority=EventPriority.LOWEST) public static void stopped(ServerStoppedEvent event)throws Exception{
        if(!enabled())return;
        if(Boolean.getBoolean("discraft.serverEventsFailureSmoke")){
            Path output=Path.of(System.getProperty("discraft.serverEventsOutput"));Files.createDirectories(output);
            boolean valid=count()==1&&adapter.eventReceipts.get(0).text().equals("Server is starting");
            Files.writeString(output.resolve("startup-failure-result.txt"),valid?"PASS Actual failed startup emits starting only, no ready or normal-stop notice; local TEST recipient only\n":"FAIL Failed startup emitted incorrect lifecycle notices\n");return;
        }
        if(failure==null)try{check(count()==7&&adapter.eventReceipts.get(6).text().equals("Server is shutting down normally"),"Actual normal stop drains final notice before closing TEST adapter");check(adapter.eventReceipts.stream().allMatch(r->r.mentions().isEmpty()&&r.author().equals("Server")),"All automatic notices retain server identity and empty allowed-mention selections");}catch(Throwable rejected){failure=rejected.getMessage();}
        Path output=Path.of(System.getProperty("discraft.serverEventsOutput"));Files.createDirectories(output);
        results.add(failure==null?"PASS "+checks+" actual headless Forge lifecycle and synthetic event checks; local TEST recipient only":"FAIL "+failure);
        Files.write(output.resolve(Boolean.getBoolean("discraft.serverItemsSmoke")?"items-result.txt":"result.txt"),results);System.out.println(results.get(results.size()-1));
    }
}
