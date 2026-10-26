package dev.discraft.forge.smoke;

import dev.discraft.core.*;
import dev.discraft.forge.*;
import dev.discraft.forge.bridge.*;
import net.minecraft.client.*;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.*;
import net.minecraft.client.multiplayer.*;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Actual local server/client wire, Unicode original text and original framebuffer evidence; no Discord. */
@Mod.EventBusSubscriber(modid=DisCraft.ID,value=Dist.CLIENT)
public final class TwemojiAcceptance {
 private static final Minecraft MC=Minecraft.getInstance();private static int phase,ticks;private static boolean done;private static CompletableFuture<?> send;private static final List<String> checks=new ArrayList<>();
 private static final String BODY="TEST Twemoji 😀 👍🏽 👩🏽‍💻 👨‍👩‍👧‍👦 🇹🇼 1️⃣ 🏳️‍🌈 ❤️ 文本❤︎ 繁體中文 English :discraft: :dance:";
 private static final String NATIVE="Native Twemoji 🧑🏿‍🚀 🇯🇵 2️⃣";
 private static Path out(){return Path.of(System.getProperty("discraft.twemojiOutput"));}
 private static Object get(Class<?> type,String name)throws Exception{var f=type.getDeclaredField(name);f.setAccessible(true);return f.get(null);}
 private static void set(String name,Object value)throws Exception{var f=NativeInteractions.class.getDeclaredField(name);f.setAccessible(true);f.set(null,value);}
 private static Object call(Class<?> type,String name)throws Exception{var m=type.getDeclaredMethod(name);m.setAccessible(true);return m.invoke(null);}
 private static EditBox search()throws Exception{return (EditBox)get(NativeInteractions.class,"search");}
 private static EditBox input(){return MC.screen.children().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast).findFirst().orElseThrow();}
 private static void check(boolean value,String name){if(!value)throw new AssertionError(name);checks.add("PASS "+name);System.out.println("TWEMOJI PASS "+name);}
 private static void next(int value){phase=value;ticks=0;}
 private static void capture(String name)throws Exception{MC.getToasts().clear();try(var image=Screenshot.takeScreenshot(MC.getMainRenderTarget())){check(image.getWidth()==(name.contains("gui3")?1280:1100)&&image.getHeight()==(name.contains("gui3")?900:800),"Original framebuffer "+name);image.writeToFile(out().resolve(name+".png"));}}
 @SuppressWarnings("unchecked") @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event){if(!Boolean.getBoolean("discraft.twemojiSmoke")||done||event.phase!=TickEvent.Phase.END)return;try{ticks++;if(ticks%100==0)System.out.println("TWEMOJI phase="+phase);switch(phase){
 case 0->{if(MC.screen instanceof AccessibilityOnboardingScreen){MC.screen.children().stream().filter(c->c instanceof Button b&&b.getMessage().getString().equals("Continue")).map(c->(Button)c).findFirst().ifPresent(Button::onPress);return;}if(!(MC.screen instanceof TitleScreen)||MC.getOverlay()!=null)return;Files.createDirectories(out());MC.options.guiScale().set(2);MC.options.renderDistance().set(2);MC.options.chatWidth().set(1d);MC.options.chatHeightFocused().set(1d);GLFW.glfwSetWindowSize(MC.getWindow().getWindow(),1100,800);MC.resizeDisplay();String address="127.0.0.1:"+System.getProperty("discraft.bridgePort");ConnectScreen.startConnecting(new TitleScreen(),MC,ServerAddress.parseString(address),new ServerData("Twemoji local TEST only",address,false),false);next(1);}
 case 1->{if(MC.player==null||MC.level==null||MC.screen!=null||!ClientBridge.available()||!ClientGuildEmoji.snapshot().ready())return;MC.gui.getChat().clearMessages(false);NativeChat.reset();MC.setScreen(new ChatScreen(""));send=CompletableFuture.runAsync(()->{try{ClientBridge.send(BODY,null);}catch(Exception e){throw new CompletionException(e);}});next(2);}
 case 2->{if(!send.isDone()||ClientBridge.snapshot().stream().noneMatch(m->Content.plain(m.content()).equals(BODY)))return;send.join();check(true,"Actual server/client protocol preserves Unicode, VS selectors and custom aliases byte-for-byte");MC.player.connection.sendChat(NATIVE);next(3);}
 case 3->{if(ticks>=22)MC.getToasts().clear();if(ticks<25||NativeChat.renderedTiles<10)return;var media=(Map<String,Attachment>)get(NativeChat.class,"unicodeMedia");check(media.size()>=10&&media.size()<=96,"Native bridge and ordinary player chat use bounded Twemoji assets");for(String value:List.of("👍🏽","👩🏽‍💻","👨‍👩‍👧‍👦","🇹🇼","1️⃣","❤️","🧑🏿‍🚀")){var entry=TwemojiCatalog.exact(value);check(media.containsKey(entry.asset())&&media.get(entry.asset()).digest().equals(entry.sha256()),"Visible native asset matches pinned source "+value);}var originals=(Map<UUID,Component>)get(NativeChat.class,"nativeOriginals");check(originals.values().stream().anyMatch(c->c.getString().contains(NATIVE)),"Ordinary native player-chat original component remains available for reflow");capture("twemoji-native-gui2");set("category",0);set("tab",2);NativeInteractions.openPicker();search().setValue("👩🏽‍💻");next(4);}
 case 4->{if(ticks<10)return;var hits=(List<?>)get(NativeInteractions.class,"hits");check(hits.size()==1,"Complete ZWJ/skin-tone search returns one picker entry");search().setValue("woman technologist");next(40);}
 case 40->{if(ticks<8)return;capture("twemoji-picker-gui2");search().setValue("👩🏽‍💻");next(41);}
 case 41->{if(ticks<6)return;int x=Math.max(4,MC.screen.width-Math.min(350,MC.screen.width-8)-5)+50;int y=MC.screen.height-22-Math.min(260,MC.screen.height-28)+78;NativeInteractions.mouse(new net.minecraftforge.client.event.ScreenEvent.MouseButtonPressed.Pre(MC.screen,x,y,0));check(input().getValue().equals("👩🏽‍💻"),"Real picker click inserts unchanged complete Unicode sequence");NativeInteractions.openPicker();set("category",5);search().setValue("");next(5);}
 case 5->{if(ticks<12||ClientGuildEmoji.preview("1111111111111111")==null)return;check(ClientGuildEmoji.snapshot().entries().stream().anyMatch(e->e.animated()),"Guild static/animated catalog remains independently available");set("tab",0);var catalog=ClientGuildEmoji.snapshot();ClientGuildEmoji.catalog(new GuildEmojiCatalog(catalog.version()+1,"TEST production-state metadata",false,true,true,catalog.entries()).encode());next(6);}
 case 6->{if(ticks<8)return;check(((List<?>)call(NativeInteractions.class,"filtered")).isEmpty(),"Unconfigured production GIF tab offers no fixture/provider results");call(NativeInteractions.class,"closePicker");MC.setScreen(new ChatScreen(""));GLFW.glfwSetWindowSize(MC.getWindow().getWindow(),1280,900);next(7);}
 case 7->{if(ticks<10)return;MC.options.guiScale().set(3);MC.resizeDisplay();next(8);}
 case 8->{if(ticks<16)return;check(NativeChat.renderedTiles>5,"Native Twemoji images reflow at GUI scale 3 without a new screen");capture("twemoji-native-gui3");check(TwemojiCatalog.cachedAssets()<=96&&TwemojiCatalog.cachedPixels()<=497664,"Decoded Twemoji cache remains bounded after native/picker churn");send=MC.reloadResourcePacks();next(9);}
 case 9->{if(!send.isDone()||MC.getOverlay()!=null||ticks<20)return;send.join();check(NativeChat.renderedTiles>5,"Native Twemoji sprites remain usable after resource reload");MC.level.disconnect();MC.clearLevel();MC.setScreen(new TitleScreen());check(((Map<?,?>)get(NativeChat.class,"unicodeMedia")).isEmpty()&&((Map<?,?>)get(NativeChat.class,"nativeOriginals")).isEmpty(),"Disconnect releases native emoji/component ownership");checks.add("PASS "+checks.size()+" actual Twemoji native/picker/wire/scale/cache checks; local TEST only");Files.write(out().resolve("result.txt"),checks);done=true;MC.stop();}
 }}catch(Throwable failure){try{checks.add("FAIL phase="+phase+" "+failure.getClass().getSimpleName()+": "+failure.getMessage());Files.write(out().resolve("result.txt"),checks);}catch(Exception ignored){}done=true;MC.stop();}}
}
