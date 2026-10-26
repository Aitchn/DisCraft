package dev.discraft.forge.server;

import com.mojang.authlib.GameProfile;
import dev.discraft.forge.DisCraft;
import net.minecraft.advancements.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Items;
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
        if(!enabled())return;server=event.getServer();var field=ServerBridge.class.getDeclaredField("adapter");field.setAccessible(true);adapter=(TestDiscordAdapter)field.get(null);
    }
    @SubscribeEvent(priority=EventPriority.LOWEST) public static void starting(ServerStartingEvent event){
        if(enabled()&&Boolean.getBoolean("discraft.serverEventsFailureSmoke"))throw new IllegalStateException("Expected isolated fixture startup failure");
    }
    @SubscribeEvent(priority=EventPriority.LOWEST) public static void started(ServerStartedEvent event)throws Exception{
        if(!enabled())return;
        player=FakePlayerFactory.get(server.overworld(),new GameProfile(UUID.fromString("5b52ba38-7083-4de2-888f-8fe83c0bf240"),"EventTester"));player.setHealth(20);
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
        Files.write(output.resolve("result.txt"),results);System.out.println(results.get(results.size()-1));
    }
}
