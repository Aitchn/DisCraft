package dev.discraft.forge.smoke;

import dev.discraft.forge.*;
import dev.discraft.core.Content;
import net.minecraft.client.*;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.*;
import net.minecraft.client.multiplayer.*;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

@Mod.EventBusSubscriber(modid=DisCraft.ID,value=Dist.CLIENT)
public final class OptionalEnhancedAcceptance {
    private static final Minecraft MC=Minecraft.getInstance();private static int stage,ticks;private static boolean done;private static CompletableFuture<?> send;private static final List<String> checks=new ArrayList<>();
    private static Path out(){return Path.of(System.getProperty("discraft.optionalOutput"));}
    private static void check(boolean value,String text){if(!value)throw new AssertionError(text);checks.add("PASS "+text);}
    private static boolean has(String name){return Files.exists(out().resolve(name));}
    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event){if(!Boolean.getBoolean("discraft.optionalClientSmoke")||done||event.phase!=TickEvent.Phase.END)return;
        try{ticks++;if(ticks%100==0)System.out.println("OPTIONAL_ENHANCED stage="+stage+" screen="+(MC.screen==null?"none":MC.screen.getClass().getSimpleName()));switch(stage){
            case 0->{if(MC.screen instanceof AccessibilityOnboardingScreen){MC.screen.children().stream().filter(c->c instanceof Button b&&b.getMessage().getString().equals("Continue")).map(c->(Button)c).findFirst().ifPresent(Button::onPress);return;}if(!(MC.screen instanceof TitleScreen)||MC.getOverlay()!=null)return;MC.options.guiScale().set(2);MC.options.renderDistance().set(2);MC.options.cloudStatus().set(CloudStatus.OFF);MC.resizeDisplay();String address="127.0.0.1:"+System.getProperty("discraft.bridgePort");ConnectScreen.startConnecting(new TitleScreen(),MC,ServerAddress.parseString(address),new ServerData("enhanced optional test",address,false),false);stage=1;}
            case 1->{if(System.getProperty("discraft.optionalScenario","").equals("missing_server")){if(MC.player==null||MC.level==null||MC.screen!=null)return;check(!ClientBridge.available()&&ClientBridge.snapshot().isEmpty(),"Enhanced client joins actual Forge server without DisCraft and disables bridge capabilities");MC.player.connection.sendChat("missing-server-chat");stage=10;return;}if(MC.player==null||MC.level==null||MC.screen!=null||!ClientBridge.available()||ClientBridge.snapshot().stream().noneMatch(m->Content.plain(m.content()).contains("optional inbound")))return;check(true,"Enhanced DisCraft peer coexists with the no-DisCraft peer");MC.setScreen(new ChatScreen(""));MC.player.connection.sendChat("enhanced-normal");send=CompletableFuture.runAsync(()->{try{ClientBridge.send("enhanced-custom",null);}catch(Exception e){throw new CompletionException(e);}});stage=2;}
            case 2->{if(!send.isDone()||!has("enhanced-custom-ok"))return;send.join();send=CompletableFuture.runAsync(()->{try{ClientBridge.send("enhanced-item %item/%i",null);}catch(Exception e){throw new CompletionException(e);}});stage=3;}
            case 10->{if(!has("missing-server-chat-ok"))return;check(MC.player!=null&&!ClientBridge.available(),"Missing server keeps ordinary native chat available without bridge traffic");MC.level.disconnect();MC.clearLevel();MC.setScreen(new TitleScreen());check(ClientBridge.snapshot().isEmpty(),"Disconnect from missing-server clears client bridge state");checks.add("PASS "+checks.size()+" missing-server client checks");Files.write(out().resolve("enhanced-result.txt"),checks);done=true;MC.stop();}
            case 3->{@SuppressWarnings("unchecked") var nativeMessages=(List<net.minecraft.client.GuiMessage>)net.minecraftforge.fml.util.ObfuscationReflectionHelper.getPrivateValue(net.minecraft.client.gui.components.ChatComponent.class,MC.gui.getChat(),"f_93760_");if(nativeMessages.stream().anyMatch(m->m.content().getString().contains("bare-system-chat")))Files.writeString(out().resolve("system-recipient-saw"),"ready");if(nativeMessages.stream().filter(m->m.content().getString().contains("bare-repeat-race")).count()==1)Files.writeString(out().resolve("enhanced-race-saw"),"ready");if(!send.isDone()||!has("server-complete")||!has("bare-done"))return;send.join();check(ClientBridge.snapshot().stream().anyMatch(m->Content.plain(m.content()).contains("enhanced-item")),"Existing custom upload/item protocol still reaches authoritative client history");check(ClientBridge.snapshot().stream().noneMatch(m->m.author().equals("EnhancedTester")&&Content.plain(m.content()).equals("enhanced-normal")),"Ordinary vanilla chat does not also publish a duplicate custom bridge record");check(NativeChat.renderedTiles>0,"Enhanced peer still renders Discord media/emoji assets");checks.add("PASS "+checks.size()+" enhanced peer coexistence checks");Files.write(out().resolve("enhanced-result.txt"),checks);done=true;MC.stop();}
        }}catch(Throwable failure){try{checks.add("FAIL stage="+stage+" "+failure.getClass().getSimpleName()+": "+failure.getMessage());Files.write(out().resolve("enhanced-result.txt"),checks);}catch(Exception ignored){}done=true;MC.stop();}
    }
}
