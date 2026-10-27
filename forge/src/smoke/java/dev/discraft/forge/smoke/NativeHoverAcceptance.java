package dev.discraft.forge.smoke;

import dev.discraft.core.*;
import dev.discraft.forge.*;
import dev.discraft.forge.bridge.*;
import net.minecraft.ChatFormatting;
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
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;
import org.lwjgl.glfw.GLFW;
import java.awt.Robot;
import java.awt.event.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Predicate;

/** Physical native T/hover/scroll acceptance with a loopback TEST adapter, no Discord transport. */
@Mod.EventBusSubscriber(modid=DisCraft.ID,value=Dist.CLIENT)
public final class NativeHoverAcceptance {
    private static final Minecraft MC=Minecraft.getInstance();private static int phase,ticks,scroll;private static boolean done;private static Robot robot;private static Path out;private static ChatScreen chat;
    private static CompletableFuture<Void> reload;private static CompletableFuture<?> send;private static final List<String> checks=new ArrayList<>();
    private static void check(boolean value,String label){if(!value)throw new AssertionError(label);checks.add("PASS "+label);System.out.println("NATIVE_HOVER PASS "+label);}
    @SuppressWarnings("unchecked") private static List<GuiMessage> messages()throws Exception{return (List<GuiMessage>)ObfuscationReflectionHelper.getPrivateValue(ChatComponent.class,MC.gui.getChat(),"f_93760_");}
    @SuppressWarnings("unchecked") private static Map<UUID,RelayRecord> records()throws Exception{var f=NativeChat.class.getDeclaredField("records");f.setAccessible(true);return (Map<UUID,RelayRecord>)f.get(null);}
    private static long count(String text)throws Exception{return messages().stream().filter(m->m.content().getString().contains(text)).count();}
    private static EditBox input(){return MC.screen.children().stream().filter(c->c instanceof EditBox).map(c->(EditBox)c).findFirst().orElseThrow();}
    private static void key(int key){robot.keyPress(key);robot.keyRelease(key);}
    private static void move(double x,double y){GLFW.glfwFocusWindow(MC.getWindow().getWindow());int[] wx={0},wy={0};GLFW.glfwGetWindowPos(MC.getWindow().getWindow(),wx,wy);robot.mouseMove(wx[0]+(int)(x*MC.getWindow().getGuiScale()),wy[0]+(int)(y*MC.getWindow().getGuiScale()));}
    private record Hit(double x,double y,Style style){}
    private static Hit find(Predicate<Style> predicate){for(int y=MC.screen.height-44;y>=Math.max(0,MC.screen.height-245);y-=2)for(int x=4;x<Math.min(MC.screen.width,MC.gui.getChat().getWidth()+12);x+=2){var s=MC.gui.getChat().getClickedComponentStyleAt(x,y);if(s!=null&&predicate.test(s))return new Hit(x,y,s);}return null;}
    private static boolean item(Style s){var h=s.getHoverEvent();var value=h==null?null:h.getValue(HoverEvent.Action.SHOW_ITEM);return value!=null&&value.getItemStack().getHoverName().getString().contains("星光劍")&&s.getColor()!=null&&s.getColor().getValue()==ChatFormatting.GOLD.getColor();}
    private static boolean advancement(Style s,String name){var h=s.getHoverEvent();var value=h==null?null:h.getValue(HoverEvent.Action.SHOW_TEXT);return value!=null&&value.getString().contains(name);}
    private static void capture(String name)throws Exception{MC.getToasts().clear();try(var image=Screenshot.takeScreenshot(MC.getMainRenderTarget())){check(image.getWidth()==1100&&image.getHeight()==800,"Original framebuffer "+name);image.writeToFile(out.resolve(name+".png"));}}
    private static void sameScreen(){check(MC.screen==chat&&MC.screen instanceof ChatScreen,"Hover/click stays in the same native ChatScreen");}
    private static void forged()throws Exception{UUID id=UUID.randomUUID();String body="forged-native-item";var fake=new RelayRecord(id,"draft",body,List.of(),Map.of(),Map.of(),null,List.of(),List.of(new ItemInline(0,body.length(),new NativeItemData("minecraft:diamond_block",64,"{}"))));byte[] bytes=fake.encode();BridgeNetwork.CHANNEL.sendToServer(new BridgeNetwork.Upload(id,bytes.length,0,bytes));}
    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event){
        if(!Boolean.getBoolean("discraft.nativeHoverSmoke")||done||event.phase!=TickEvent.Phase.END)return;
        try{
            if(out==null){out=Path.of(System.getProperty("discraft.nativeHoverOutput"));Files.createDirectories(out);robot=new Robot();}
            ticks++;if(ticks%100==0)System.out.println("NATIVE_HOVER phase="+phase+" screen="+(MC.screen==null?"none":MC.screen.getClass().getSimpleName()));
            switch(phase){
                case 0->{if(MC.screen instanceof AccessibilityOnboardingScreen){MC.screen.children().stream().filter(w->w instanceof Button b&&b.getMessage().getString().equals("Continue")).map(w->(Button)w).findFirst().ifPresent(Button::onPress);return;}if(!(MC.screen instanceof TitleScreen)||MC.getOverlay()!=null)return;MC.options.languageCode="zh_tw";MC.getLanguageManager().setSelected("zh_tw");MC.options.guiScale().set(2);MC.options.renderDistance().set(2);MC.options.cloudStatus().set(CloudStatus.OFF);MC.options.chatWidth().set(.55);MC.resizeDisplay();MC.getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);reload=MC.reloadResourcePacks();phase=1;}
                case 1->{if(!reload.isDone()||MC.getOverlay()!=null)return;reload.join();String address="127.0.0.1:"+System.getProperty("discraft.bridgePort");ConnectScreen.startConnecting(new TitleScreen(),MC,ServerAddress.parseString(address),new ServerData("DisCraft native hover TEST",address,false),false);phase=2;}
                case 2->{if(MC.player==null||MC.level==null||!ClientBridge.available()||MC.screen!=null||count("家務活兒")!=1||count("紫色挑戰")!=1)return;key(KeyEvent.VK_T);phase=3;ticks=0;}
                case 3->{if(!(MC.screen instanceof ChatScreen)||ticks<5)return;check(true,"Physical T opens original ChatScreen");input().setValue("物品展示：這是一段用來驗證換行的原生聊天文字 "+"wrap ".repeat(6)+"%i / %item");key(KeyEvent.VK_ENTER);phase=4;ticks=0;}
                case 4->{if(records().values().stream().noneMatch(r->!r.nativeItems().isEmpty())||MC.screen!=null)return;key(KeyEvent.VK_T);phase=5;ticks=0;}
                case 5->{if(!(MC.screen instanceof ChatScreen)||ticks<10)return;chat=(ChatScreen)MC.screen;check((int)MC.getWindow().getGuiScale()==2,"Actual GUI scale two");check(count("家務活兒")==1&&count("紫色挑戰")==1,"Original advancement announcements appear once after repeated award attempts");check(count("物品展示")==1,"Physical T item request creates one bridge message without a duplicate raw vanilla marker");var record=records().values().stream().filter(r->!r.nativeItems().isEmpty()).findFirst().orElseThrow();check(record.attachments().isEmpty()&&record.nativeItems().size()==2&&NativeChat.renderedTiles==0,"Native item is inline with no automatically expanded PNG");var rendered=messages().stream().filter(m->m.content().getString().contains("物品展示")).findFirst().orElseThrow().content();check(rendered.getString().startsWith("<HoverTester> ")&&!rendered.getString().contains("[Bridge]")&&!rendered.getString().contains("M@HoverTester"),"Actual enhanced mixed %i/%item message uses one native-style player name without source labels");String tag=record.nativeItems().get(0).item().tag();check(tag.contains("Lore")&&tag.contains("sharpness")&&!tag.contains("private-book")&&!tag.contains("private-container")&&!tag.contains("BlockEntityTag"),"Authoritative wire hover retains necessary lore/enchantment while excluding book/container data");check(MC.font.width(record.text())>MC.gui.getChat().getWidth(),"Long item message exercises native wrapping");MC.getToasts().clear();move(MC.screen.width-5,5);phase=6;ticks=0;}
                case 6->{if(ticks<8)return;capture("item-unhover");var hit=find(NativeHoverAcceptance::item);check(hit!=null&&hit.style().isBold()&&hit.style().getClickEvent()==null,"Inline custom item preserves gold/bold style and has no screen-opening click action");move(hit.x(),hit.y());forged();phase=7;ticks=0;}
                case 7->{if(ticks<8)return;sameScreen();capture("item-hover");robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);phase=8;ticks=0;}
                case 8->{if(ticks<5)return;sameScreen();check(input().getValue().isEmpty(),"Clicking the inline item does not insert a mention or open another screen");move(MC.screen.width-5,5);phase=9;ticks=0;}
                case 9->{if(ticks<8)return;var s=MC.gui.getChat().getClickedComponentStyleAt(MC.screen.width-5,5);check(s==null||s.getHoverEvent()==null,"Moving away removes the native hover target");capture("item-hover-left");capture("advancement-unhover");var hit=find(st->advancement(st,"家務活兒"));check(hit!=null&&hit.style().getHoverEvent().getValue(HoverEvent.Action.SHOW_TEXT).getString().contains("讓你的孩子開始工作。"),"Original advancement component retains native title and description hover");move(hit.x(),hit.y());phase=10;ticks=0;}
                case 10->{if(ticks<8)return;sameScreen();capture("advancement-hover");var hit=find(st->advancement(st,"紫色挑戰"));check(hit!=null&&hit.style().getColor()!=null&&hit.style().getColor().getValue()!=ChatFormatting.GREEN.getColor(),"Challenge uses its original non-green framework style");move(hit.x(),hit.y());phase=11;ticks=0;}
                case 11->{if(ticks<8)return;capture("challenge-hover");move(MC.screen.width-5,5);for(int i=0;i<16;i++)MC.gui.getChat().addMessage(Component.literal("fixture scroll row "+i));MC.options.guiScale().set(3);MC.resizeDisplay();MC.gui.getChat().resetChatScroll();phase=12;ticks=0;}
                case 12->{if(ticks<8)return;var hit=find(NativeHoverAcceptance::item);if(hit==null&&scroll++<40){MC.gui.getChat().scrollChat(1);return;}check(hit!=null&&(int)MC.getWindow().getGuiScale()==3,"Native item hover survives GUI scale three, reflow and scrolling");move(hit.x(),hit.y());phase=13;ticks=0;scroll=0;}
                case 13->{if(ticks<8)return;if(scroll==0){sameScreen();capture("scaled-scroll-item-hover");}var hit=find(s->s.getClickEvent()!=null&&s.getClickEvent().getAction()==ClickEvent.Action.SUGGEST_COMMAND&&s.getClickEvent().getValue().contains("M@"));if(hit==null&&scroll++<40){MC.gui.getChat().scrollChat(1);return;}check(hit!=null,"Author mention target remains available after native item reflow");move(hit.x(),hit.y());phase=14;ticks=0;}
                case 14->{if(ticks<6)return;robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);phase=15;ticks=0;}
                case 15->{if(ticks<6)return;check(input().getValue().contains("M@HoverTester"),"Physical author click still inserts the stable Minecraft mention");input().setValue("");send=CompletableFuture.runAsync(()->{try{ClientBridge.send("普通圖片行為回歸測試",Attachment.fromResource("emoji.png"));}catch(Exception failed){throw new CompletionException(failed);}});MC.gui.getChat().resetChatScroll();phase=16;ticks=0;}
                case 16->{if(!send.isDone()||ticks<15)return;send.join();if(NativeChat.renderedTiles==0)return;check(records().values().stream().anyMatch(r->r.nativeItems().isEmpty()&&!r.attachments().isEmpty()),"Ordinary uploaded images retain their native inline image rendering");check(records().values().stream().noneMatch(r->r.text().contains("forged-native-item")),"Client-supplied item metadata is rejected by the authoritative server");check(count("家務活兒")==1&&count("紫色挑戰")==1,"No duplicate advancement announcement introduced by item rendering or reflow");sameScreen();capture("ordinary-image");checks.add("PASS "+checks.size()+" native inline/hover game checks; TEST recipient only");Files.write(out.resolve("client-result.txt"),checks);done=true;MC.stop();}
            }
        }catch(Throwable failed){try{checks.add("FAIL "+failed.getClass().getSimpleName()+": "+failed.getMessage());Files.write(out.resolve("client-result.txt"),checks);}catch(Exception ignored){}done=true;MC.stop();}
    }
}
