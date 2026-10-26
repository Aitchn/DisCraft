package dev.discraft.forge.server;

import dev.discraft.forge.DisCraft;
import net.minecraft.ChatFormatting;
import net.minecraft.advancements.*;
import net.minecraft.advancements.critereon.ImpossibleTrigger;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.*;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.*;
import net.minecraftforge.eventbus.api.*;
import net.minecraftforge.fml.common.Mod;
import java.nio.file.*;
import java.util.*;

@Mod.EventBusSubscriber(modid=DisCraft.ID)
public final class NativeHoverServerAcceptance {
    private static TestDiscordAdapter recipient;private static boolean awarded;
    private static boolean enabled(){return Boolean.getBoolean("discraft.multiplayerSmoke")&&Boolean.getBoolean("discraft.nativeHoverSmoke");}
    @SubscribeEvent(priority=EventPriority.LOWEST) public static void start(ServerStartedEvent event)throws Exception{if(enabled()){var f=ServerBridge.class.getDeclaredField("adapter");f.setAccessible(true);recipient=(TestDiscordAdapter)f.get(null);}}
    @SubscribeEvent(priority=EventPriority.LOWEST) public static void login(PlayerEvent.PlayerLoggedInEvent event){
        if(!enabled()||awarded||!(event.getEntity() instanceof ServerPlayer p))return;awarded=true;
        var stack=new ItemStack(Items.DIAMOND_SWORD);stack.setHoverName(Component.literal("星光劍").withStyle(ChatFormatting.GOLD,ChatFormatting.BOLD));stack.enchant(Enchantments.SHARPNESS,3);stack.setDamageValue(7);
        var lore=new ListTag();lore.add(StringTag.valueOf(Component.Serializer.toJson(Component.literal("來自服務端的有界說明").withStyle(ChatFormatting.GRAY))));lore.add(StringTag.valueOf(Component.Serializer.toJson(Component.literal("附魔、屬性與耐久度使用原版 tooltip").withStyle(ChatFormatting.BLUE))));stack.getOrCreateTagElement("display").put("Lore",lore);
        var pages=new ListTag();pages.add(StringTag.valueOf("private-book-body-native-fixture"));stack.getOrCreateTag().put("pages",pages);var container=new CompoundTag();container.putString("private","private-container-native-fixture");stack.getOrCreateTag().put("BlockEntityTag",container);p.setItemInHand(InteractionHand.MAIN_HAND,stack);
        award(p,"household","家務活兒","讓你的孩子開始工作。",FrameType.TASK);award(p,"challenge","紫色挑戰","不是所有進度都是綠色。",FrameType.CHALLENGE);
    }
    private static void award(ServerPlayer p,String id,String name,String description,FrameType frame){
        var advancement=Advancement.Builder.advancement().display(Items.DIAMOND,Component.literal(name),Component.literal(description),null,frame,false,true,false).addCriterion("only",new ImpossibleTrigger.TriggerInstance()).build(new ResourceLocation("discraft_test",id));
        if(!p.getAdvancements().award(advancement,"only")||p.getAdvancements().award(advancement,"only"))throw new AssertionError("Advancement completion fixture must award exactly once");
    }
    @SubscribeEvent(priority=EventPriority.LOWEST) public static void stop(ServerStoppedEvent event)throws Exception{
        if(!enabled())return;Path out=Path.of(System.getProperty("discraft.nativeHoverOutput"));Files.createDirectories(out);
        boolean valid=recipient!=null&&recipient.eventReceipts.size()==4&&recipient.eventReceipts.stream().filter(r->r.author().equals("Server")&&r.attachments().isEmpty()).count()==2&&recipient.eventReceipts.stream().filter(r->!r.author().equals("Server")&&r.attachments().size()==1).count()==2&&recipient.eventReceipts.stream().allMatch(r->r.nativeItems().isEmpty()&&!r.text().contains("private-book-body")&&!r.text().contains("private-container"));
        Files.writeString(out.resolve("server-result.txt"),valid?"PASS four local TEST sends: two advancement notices, one retained Discord item PNG and one normal uploaded image; no native hover metadata or private book/container data exported\n":"FAIL unexpected native hover fixture recipient output\n");
    }
}
