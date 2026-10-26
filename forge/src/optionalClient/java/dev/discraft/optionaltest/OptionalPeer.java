package dev.discraft.optionaltest;

import net.minecraft.client.*;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.*;
import net.minecraft.client.multiplayer.*;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;
import net.minecraftforge.network.*;
import java.nio.file.*;
import java.util.*;

/** Standalone instrumentation mod: no dependency/import/classpath access to DisCraft code. */
@Mod("discraft_optional_test")
public final class OptionalPeer {
    private static void assertIsolated(){if(ModList.get().isLoaded("discraft"))throw new AssertionError("Isolated peer loaded DisCraft");for(String name:List.of("dev.discraft.forge.DisCraft","dev.discraft.core.ChatSession"))try{Class.forName(name,false,OptionalPeer.class.getClassLoader());throw new AssertionError("Isolated peer retained bridge/core classes");}catch(ClassNotFoundException expected){}}
    public OptionalPeer(){if(System.getProperty("discraft.optionalScenario","mixed").equals("incompatible"))NetworkRegistry.newSimpleChannel(new ResourceLocation("discraft","bridge"),()->"3",v->true,v->true);}
    @Mod.EventBusSubscriber(modid="discraft_optional_test",value=Dist.DEDICATED_SERVER)
    public static final class BareServer {
        @SubscribeEvent public static void started(net.minecraftforge.event.server.ServerStartedEvent event)throws Exception{if(System.getProperty("discraft.optionalScenario","").equals("missing_server")){assertIsolated();Files.writeString(Path.of(System.getProperty("discraft.optionalOutput")).resolve("missing-server-ready"),"ready");}}
        @SubscribeEvent(priority=net.minecraftforge.eventbus.api.EventPriority.LOWEST) public static void chat(net.minecraftforge.event.ServerChatEvent event)throws Exception{if(System.getProperty("discraft.optionalScenario","").equals("missing_server")&&event.getRawText().equals("missing-server-chat")&&!event.isCanceled())Files.writeString(Path.of(System.getProperty("discraft.optionalOutput")).resolve("missing-server-chat-ok"),"ready");}
    }
    @Mod.EventBusSubscriber(modid="discraft_optional_test",value=Dist.CLIENT)
    public static final class Client {
        private static final Minecraft MC=Minecraft.getInstance();private static int stage,ticks;private static boolean done;private static final List<String> checks=new ArrayList<>();
        private static Path out(){return Path.of(System.getProperty("discraft.optionalOutput"));}
        private static String scenario(){return System.getProperty("discraft.optionalScenario","mixed");}
        private static void flag(String name)throws Exception{Files.writeString(out().resolve(name),"ready\n");}
        private static boolean has(String name){return Files.exists(out().resolve(name));}
        private static void check(boolean value,String text){if(!value)throw new AssertionError(text);checks.add("PASS "+text);System.out.println("OPTIONAL_BARE PASS "+text);}
        @SuppressWarnings("unchecked") private static List<GuiMessage> messages(){return (List<GuiMessage>)ObfuscationReflectionHelper.getPrivateValue(ChatComponent.class,MC.gui.getChat(),"f_93760_");}
        private static long count(String text){return messages().stream().filter(m->m.content().getString().contains(text)).count();}
        private static void connect(){String address="127.0.0.1:"+System.getProperty("discraft.bridgePort");ConnectScreen.startConnecting(new TitleScreen(),MC,ServerAddress.parseString(address),new ServerData("optional peer local only",address,false),false);}
        private static void next(int value){stage=value;ticks=0;}
        private static void finish()throws Exception{checks.add("PASS "+checks.size()+" actual no-DisCraft Forge client checks ("+scenario()+")");Files.write(out().resolve(System.getProperty("discraft.optionalRole","bare")+"-"+scenario()+"-result.txt"),checks);done=true;MC.stop();}
        @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event){if(!Boolean.getBoolean("discraft.optionalClientSmoke")||done||event.phase!=TickEvent.Phase.END)return;
            try{ticks++;if(ticks%100==0)System.out.println("OPTIONAL_BARE stage="+stage+" screen="+(MC.screen==null?"none":MC.screen.getClass().getSimpleName()));
                if(MC.screen instanceof DeathScreen){MC.player.respawn();MC.setScreen(null);return;}
                switch(stage){
                    case 0->{if(MC.screen instanceof AccessibilityOnboardingScreen){MC.screen.children().stream().filter(c->c instanceof Button b&&b.getMessage().getString().equals("Continue")).map(c->(Button)c).findFirst().ifPresent(Button::onPress);return;}if(!(MC.screen instanceof TitleScreen)||MC.getOverlay()!=null)return;Files.createDirectories(out());assertIsolated();check(true,"Forge client has neither DisCraft loaded nor bridge/core classes available");MC.options.guiScale().set(2);MC.options.renderDistance().set(2);MC.options.cloudStatus().set(CloudStatus.OFF);MC.resizeDisplay();connect();next(1);}
                    case 1->{if(scenario().equals("incompatible")){if(MC.screen instanceof net.minecraftforge.client.gui.ModMismatchDisconnectedScreen){check(MC.player==null&&MC.level==null,"Explicit legacy protocol fixture is rejected before play login");finish();}return;}if(MC.player==null||MC.level==null||MC.screen!=null)return;check(true,"No-DisCraft Forge peer reaches the actual world");next(System.getProperty("discraft.optionalRole","").equals("observer")?100:2);}
                    case 2->{if(count("optional inbound")==0)return;check(messages().stream().anyMatch(m->m.content().getString().startsWith("[D] Optional Peer: optional inbound :Fixture:")&&m.content().getString().contains("media requires DisCraft client")),"Discord loopback ingress becomes native [D] text with safe emoji/media fallback");MC.player.connection.sendChat("bare-chat-one");next(3);}
                    case 3->{if(!has("bare-chat-ok")||ticks<6)return;MC.player.connection.sendChat("bare-item %item");next(4);}
                    case 4->{if(!has("bare-item-ok")||ticks<6)return;MC.player.connection.sendChat("bare-private-original");next(11);}
                    case 11->{if(ticks<30)return;MC.player.connection.sendChat("bare-filter-sensitive");next(12);}
                    case 12->{if(ticks<30)return;MC.player.connection.sendChat("bare-style-only");next(13);}
                    case 13->{if(!has("bare-style-ok"))return;check(count("[redacted]")==1&&count("bare-private-original")==0&&count("bare-filter-sensitive")==0&&count("bare-filter-")==1,"Actual native redaction and partial filter remain visible without exposing the original");boolean[] styled={false};for(var message:messages())if(message.content().getString().contains("bare-style-only"))message.content().visit((style,text)->{if(text.contains("bare-style-only")&&style.isBold()&&style.getColor()!=null&&style.getColor().getValue()==net.minecraft.ChatFormatting.AQUA.getColor())styled[0]=true;return Optional.<Object>empty();},Style.EMPTY);check(styled[0],"Native style-only rewrite retains its actual aqua/bold presentation");MC.player.connection.sendChat("bare-cancelled");flag("bare-sent");next(5);}
                    case 5->{if(!has("events-ready")||MC.player==null||MC.player.isDeadOrDying()||MC.screen!=null)return;check(count("bare-chat-one")==1&&count("bare-item")==1&&count("bare-cancelled")==0,"Native chat and item display appear once; cancelled native chat is absent");boolean[] hover={false};for(var message:messages())if(message.content().getString().contains("bare-item"))message.content().visit((style,text)->{var h=style.getHoverEvent();var stack=h==null?null:h.getValue(HoverEvent.Action.SHOW_ITEM);if(stack!=null){String tag=Objects.toString(stack.getItemStack().getTag());hover[0]=stack.getItemStack().getHoverName().getString().contains("Server Sword")&&!tag.contains("private-book")&&!tag.contains("BlockEntityTag");}return Optional.<Object>empty();},Style.EMPTY);check(hover[0],"No-client vanilla SHOW_ITEM retains authoritative safe hover without private NBT");MC.player.connection.sendChat("bare-after-respawn");next(6);}
                    case 6->{if(!has("bare-respawn-ok"))return;MC.level.disconnect();MC.clearLevel();MC.setScreen(new TitleScreen());next(7);}
                    case 7->{if(!has("bare-reconnect-go"))return;connect();next(8);}
                    case 8->{if(MC.player==null||MC.level==null||MC.screen!=null)return;MC.player.connection.sendChat("bare-chat-reconnected");next(9);}
                    case 9->{if(!has("bare-reconnected-ok"))return;MC.options.chatVisibility().set(net.minecraft.world.entity.player.ChatVisiblity.SYSTEM);MC.options.broadcastOptions();next(10);}
                    case 10->{if(ticks<10)return;MC.player.connection.sendChat("bare-system-chat");next(14);}
                    case 14->{if(!has("system-native-ready"))return;MC.player.connection.sendChat("bare-repeat-race");next(15);}
                    case 15->{if(!has("race-first-posted"))return;MC.player.connection.sendChat("bare-repeat-race");next(16);}
                    case 100->{if(count("bare-repeat-race")==1)flag("observer-race-saw");if(!has("server-complete"))return;check(count("bare-repeat-race")==1,"Second native recipient receives only the first repeated message");flag("observer-done");finish();}
                    case 16->{if(!has("server-complete"))return;check(count("bare-system-chat")==0,"Commands-only sender does not receive its own player chat");MC.options.chatVisibility().set(net.minecraft.world.entity.player.ChatVisiblity.FULL);MC.options.broadcastOptions();check(count("bare-chat-reconnected")==1,"No-client peer reconnects and retains ordinary native chat");flag("bare-done");finish();}
                }
            }catch(Throwable failed){try{checks.add("FAIL stage="+stage+" "+failed.getClass().getSimpleName()+": "+failed.getMessage());Files.write(out().resolve(System.getProperty("discraft.optionalRole","bare")+"-"+scenario()+"-result.txt"),checks);}catch(Exception ignored){}done=true;MC.stop();}
        }
    }
}
