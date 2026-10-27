package dev.discraft.forge.smoke;

import dev.discraft.forge.*;
import dev.discraft.forge.bridge.*;
import net.minecraft.client.*;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.*;
import net.minecraft.client.multiplayer.*;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.*;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;
import java.awt.Robot;
import java.awt.event.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Physical native chat proof from loopback REST -> production adapter -> server packets -> native components. */
@Mod.EventBusSubscriber(modid=DisCraft.ID,value=Dist.CLIENT)
public final class DiscordAuthorAcceptance {
    private static final Minecraft MC=Minecraft.getInstance();private static int phase,ticks;private static boolean done;private static Robot robot;private static Path out;private static ChatScreen chat;private static CompletableFuture<Void> reload;private static final List<String> checks=new ArrayList<>();
    private static final List<String> NAMES=List.of("HoverTester","小雨 Global","plain_user","Mira [x] \"Hi\"");
    private static void check(boolean value,String label){if(!value)throw new AssertionError(label);checks.add("PASS "+label);System.out.println("DISCORD_AUTHOR PASS "+label);}
    @SuppressWarnings("unchecked") private static Map<UUID,RelayRecord> records()throws Exception{var f=NativeChat.class.getDeclaredField("records");f.setAccessible(true);return (Map<UUID,RelayRecord>)f.get(null);}
    @SuppressWarnings("unchecked") private static Map<UUID,Component> components()throws Exception{var f=NativeChat.class.getDeclaredField("components");f.setAccessible(true);return (Map<UUID,Component>)f.get(null);}
    private static EditBox input(){return MC.screen.children().stream().filter(c->c instanceof EditBox).map(c->(EditBox)c).findFirst().orElseThrow();}
    private static void move(double x,double y){GLFW.glfwFocusWindow(MC.getWindow().getWindow());int[] wx={0},wy={0};GLFW.glfwGetWindowPos(MC.getWindow().getWindow(),wx,wy);robot.mouseMove(wx[0]+(int)(x*MC.getWindow().getGuiScale()),wy[0]+(int)(y*MC.getWindow().getGuiScale()));}
    private static void capture(String name)throws Exception{MC.getToasts().clear();try(var image=Screenshot.takeScreenshot(MC.getMainRenderTarget())){check(image.getWidth()==1100&&image.getHeight()==800,"Original framebuffer "+name);image.writeToFile(out.resolve(name+".png"));}}
    private record Hit(int x,int y,Style style){}
    private static Hit author(){for(int y=MC.screen.height-44;y>=Math.max(0,MC.screen.height-245);y-=2)for(int x=4;x<Math.min(MC.screen.width,MC.gui.getChat().getWidth()+12);x+=2){var s=MC.gui.getChat().getClickedComponentStyleAt(x,y);if(s!=null&&s.getClickEvent()!=null&&s.getClickEvent().getValue().equals("D@HoverTester "))return new Hit(x,y,s);}return null;}
    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event){
        if(!Boolean.getBoolean("discraft.discordAuthorSmoke")||done||event.phase!=TickEvent.Phase.END)return;
        try{
            if(out==null){out=Path.of(System.getProperty("discraft.discordAuthorOutput"));Files.createDirectories(out);robot=new Robot();}
            ticks++;
            switch(phase){
                case 0->{if(MC.screen instanceof AccessibilityOnboardingScreen){MC.screen.children().stream().filter(w->w instanceof Button b&&b.getMessage().getString().equals("Continue")).map(w->(Button)w).findFirst().ifPresent(Button::onPress);return;}if(!(MC.screen instanceof TitleScreen)||MC.getOverlay()!=null)return;MC.options.languageCode="zh_tw";MC.getLanguageManager().setSelected("zh_tw");MC.options.guiScale().set(2);MC.options.renderDistance().set(2);MC.options.cloudStatus().set(CloudStatus.OFF);MC.options.chatWidth().set(1d);MC.resizeDisplay();reload=MC.reloadResourcePacks();phase=1;}
                case 1->{if(!reload.isDone()||MC.getOverlay()!=null)return;reload.join();String address="127.0.0.1:"+System.getProperty("discraft.bridgePort");ConnectScreen.startConnecting(new TitleScreen(),MC,ServerAddress.parseString(address),new ServerData("DisCraft local author fixture",address,false),false);phase=2;}
                case 2->{if(MC.player==null||!ClientBridge.available()||MC.screen!=null||records().size()!=4)return;robot.keyPress(KeyEvent.VK_T);robot.keyRelease(KeyEvent.VK_T);phase=3;ticks=0;}
                case 3->{if(!(MC.screen instanceof ChatScreen)||ticks<8)return;chat=(ChatScreen)MC.screen;check(true,"Physical T opens native ChatScreen");var rs=records();var cs=components();for(String name:NAMES){var r=rs.values().stream().filter(v->v.actor()!=null&&v.actor().display().equals(name)).findFirst().orElseThrow();check(cs.get(r.id()).getString().equals("[D] "+name+": "+r.text()),"Exact received [D] guild/global/username/special display: "+name);}move(MC.screen.width-5,5);phase=4;ticks=0;}
                case 4->{if(ticks<8)return;capture("discord-author-chat");var hit=author();check(hit!=null&&hit.style().getHoverEvent().getValue(HoverEvent.Action.SHOW_TEXT).getString().contains("4234567890123456"),"Author hover retains server-bound stable Discord identity");move(hit.x(),hit.y());phase=5;ticks=0;}
                case 5->{if(ticks<8)return;check(MC.screen==chat,"Author hover stays in same ChatScreen");capture("discord-author-hover");robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);phase=6;ticks=0;}
                case 6->{if(ticks<8)return;check(input().getValue().equals("D@HoverTester "),"Physical display-name click inserts D@ mention");check(MC.screen==chat,"Author click stays in same ChatScreen");input().setValue("");checks.add("PASS "+checks.size()+" Discord author native checks; production adapter with local REST fixture, no live Discord");Files.write(out.resolve("client-result.txt"),checks);done=true;MC.stop();}
            }
        }catch(Throwable failed){try{checks.add("FAIL "+failed.getClass().getSimpleName()+": "+failed.getMessage());Files.write(out.resolve("client-result.txt"),checks);}catch(Exception ignored){}done=true;MC.stop();}
    }
}
