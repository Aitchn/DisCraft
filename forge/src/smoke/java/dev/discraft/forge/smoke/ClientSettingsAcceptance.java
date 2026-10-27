package dev.discraft.forge.smoke;

import dev.discraft.forge.*;
import net.minecraft.client.*;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.*;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.*;
import net.minecraftforge.client.gui.ModListScreen;
import net.minecraftforge.client.gui.widget.ModListWidget;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;
import java.awt.Robot;
import java.awt.event.InputEvent;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Fresh local client config and actual Forge Mods/Config screens; no world or Discord connection. */
@Mod.EventBusSubscriber(modid=DisCraft.ID,value=Dist.CLIENT)
public final class ClientSettingsAcceptance {
    private static final Minecraft MC=Minecraft.getInstance();
    private static final List<String> checks=new ArrayList<>();
    private static int phase,ticks;private static boolean done;private static ModListScreen mods;private static Robot mouse;private static ExecutorService io;private static CompletableFuture<Void> reload;
    private static Path out(){return Path.of(System.getProperty("discraft.clientSettingsOutput"));}
    private static boolean restart(){return Boolean.getBoolean("discraft.settingsRestart");}
    private static void next(int p){phase=p;ticks=0;}
    private static void check(boolean ok,String text){if(!ok)throw new AssertionError(text);checks.add("PASS "+text);}
    private static void capture(String name)throws Exception{try(var image=Screenshot.takeScreenshot(MC.getMainRenderTarget())){check(image.getWidth()==1280&&image.getHeight()==900,"Original framebuffer "+name);image.writeToFile(out().resolve(name+".png"));}}
    private static Button button(int index){return MC.screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast).toList().get(index);}
    private static void click(Button b){GLFW.glfwFocusWindow(MC.getWindow().getWindow());int[] x={0},y={0};GLFW.glfwGetWindowPos(MC.getWindow().getWindow(),x,y);double s=MC.getWindow().getGuiScale();int px=x[0]+(int)((b.getX()+b.getWidth()/2d)*s),py=y[0]+(int)((b.getY()+b.getHeight()/2d)*s);io.submit(()->{mouse.mouseMove(px,py);mouse.mousePress(InputEvent.BUTTON1_DOWN_MASK);mouse.delay(60);mouse.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);});}
    private static void finish()throws Exception{checks.add("PASS "+checks.size()+" actual client preferences "+(restart()?"restart":"initial")+" checks; no Discord or auto-send");Files.write(out().resolve(restart()?"restart-result.txt":"result.txt"),checks);done=true;io.shutdownNow();MC.stop();}
    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent e){
        if(!Boolean.getBoolean("discraft.clientSettingsSmoke")||done||e.phase!=TickEvent.Phase.END)return;
        try{if(++ticks>900)throw new AssertionError("Timeout phase "+phase);switch(phase){
        case 0->{if(MC.screen instanceof AccessibilityOnboardingScreen){for(var c:MC.screen.children())if(c instanceof Button b&&(b.getMessage().getString().equals("Continue")||b.getMessage().getString().equals("Done"))){b.onPress();break;}return;}if(!(MC.screen instanceof TitleScreen)||MC.getOverlay()!=null)return;Files.createDirectories(out());MC.options.guiScale().set(2);GLFW.glfwSetWindowSize(MC.getWindow().getWindow(),1280,900);GLFW.glfwSetWindowPos(MC.getWindow().getWindow(),0,0);MC.resizeDisplay();mouse=new Robot();io=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"Settings physical mouse");t.setDaemon(true);return t;});
            check(ClientPreferences.SPEC.isLoaded(),"Forge loads client config at config/discraft/client.toml");
            if(restart()){check(!ClientPreferences.SCREENSHOT_CLIPBOARD.get()&&!ClientPreferences.MENTION_SOUND.get(),"Explicit false values survive actual JVM restart, not replaced by defaults");finish();return;}
            check(ClientPreferences.SCREENSHOT_CLIPBOARD.get()&&ClientPreferences.MENTION_SOUND.get(),"Both new preferences default true on fresh client");
            MC.options.languageCode="zh_tw";MC.getLanguageManager().setSelected("zh_tw");reload=MC.reloadResourcePacks();next(1);}
        case 1->{if(!reload.isDone()||MC.getOverlay()!=null)return;reload.join();check(Component.translatable("discraft.settings.title").getString().equals("DisCraft 客戶端設定"),"Traditional Chinese text loads from mod language resource");mods=new ModListScreen(new TitleScreen());MC.setScreen(mods);next(2);}
        case 2->{if(ticks<6)return;var f=ModListScreen.class.getDeclaredField("modList");f.setAccessible(true);var list=(ModListWidget)f.get(mods);mods.setSelected(list.children().stream().filter(m->m.getInfo().getModId().equals("discraft")).findFirst().orElseThrow());next(3);}
        case 3->{if(ticks<8)return;capture("mod-list-disCraft");var f=ModListScreen.class.getDeclaredField("configButton");f.setAccessible(true);var b=(Button)f.get(mods);check(b.active,"Real Forge Config button is enabled");click(b);next(4);}
        case 4->{if(!(MC.screen instanceof ClientSettingsScreen)||ticks<6)return;capture("settings-zh-tw");check(button(0).getMessage().getString().contains("剪貼簿")&&button(1).getMessage().getString().contains("被提及"),"Both localized settings render in actual config screen");click(button(0));next(5);}
        case 5->{if(ticks<8)return;check(ClientPreferences.SCREENSHOT_CLIPBOARD.get(),"Toggle edits draft, not live preference");click(button(1));next(6);}
        case 6->{if(ticks<8)return;click(button(3));next(7);}
        case 7->{if(MC.screen!=mods||ticks<6)return;check(ClientPreferences.SCREENSHOT_CLIPBOARD.get()&&ClientPreferences.MENTION_SOUND.get(),"Physical Cancel preserves original preferences and parent screen");var f=ModListScreen.class.getDeclaredField("configButton");f.setAccessible(true);var b=(Button)f.get(mods);check(b.active,"Config remains enabled after Cancel");click(b);next(70);}
        case 70->{if(!(MC.screen instanceof ClientSettingsScreen)||ticks<6)return;click(button(0));next(8);}
        case 8->{if(ticks<8)return;click(button(1));next(9);}
        case 9->{if(ticks<8)return;click(button(2));next(10);}
        case 10->{if(MC.screen!=mods||ticks<8)return;check(!ClientPreferences.SCREENSHOT_CLIPBOARD.get()&&!ClientPreferences.MENTION_SOUND.get(),"Physical Save applies both false values");var file=net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get().resolve("discraft/client.toml");check(Files.isRegularFile(file)&&Files.readString(file).contains("mentionSound = false")&&Files.readString(file).contains("screenshotClipboard = false"),"Save persists false values in actual local client TOML");var f=ModListScreen.class.getDeclaredField("configButton");f.setAccessible(true);click((Button)f.get(mods));next(11);}
        case 11->{if(!(MC.screen instanceof ClientSettingsScreen)||ticks<8)return;capture("settings-disabled");check(button(0).getMessage().getString().contains("關閉")&&button(1).getMessage().getString().contains("關閉"),"Reopened screen shows persisted disabled values");MC.screen.onClose();MC.options.languageCode="zh_cn";MC.getLanguageManager().setSelected("zh_cn");reload=MC.reloadResourcePacks();next(12);}
        case 12->{if(!reload.isDone()||MC.getOverlay()!=null)return;reload.join();check(Component.translatable("discraft.settings.title").getString().equals("DisCraft 客户端设置"),"Simplified Chinese text loads correctly");MC.setScreen(new ClientSettingsScreen(mods));next(13);}
        case 13->{if(ticks<8)return;capture("settings-zh-cn");io.submit(()->{mouse.keyPress(java.awt.event.KeyEvent.VK_ESCAPE);mouse.delay(60);mouse.keyRelease(java.awt.event.KeyEvent.VK_ESCAPE);});next(14);}
        case 14->{if(MC.screen!=mods||ticks<8)return;check(!ClientPreferences.SCREENSHOT_CLIPBOARD.get()&&!ClientPreferences.MENTION_SOUND.get(),"Physical Escape returns to Mods without changing saved preferences");finish();}
        }}catch(Throwable failure){try{checks.add("FAIL phase="+phase+" "+failure.getClass().getSimpleName()+": "+failure.getMessage());Files.write(out().resolve(restart()?"restart-result.txt":"result.txt"),checks);}catch(Exception ignored){}done=true;if(io!=null)io.shutdownNow();MC.stop();}
    }
}
