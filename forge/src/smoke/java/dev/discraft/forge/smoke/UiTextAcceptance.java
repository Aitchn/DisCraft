package dev.discraft.forge.smoke;

import dev.discraft.core.*;
import dev.discraft.forge.*;
import dev.discraft.forge.bridge.*;
import net.minecraft.client.*;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.*;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;
import org.lwjgl.glfw.GLFW;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.function.Function;

/** Offline fixture-only text evidence. Full framebuffer PNGs, identical matrices before/after. */
@Mod.EventBusSubscriber(modid=DisCraft.ID,value=Dist.CLIENT)
public final class UiTextAcceptance {
 private static final Minecraft MC=Minecraft.getInstance();
 private static final int[][] CASES={{1100,800,1},{1100,800,2},{960,640,1},{1280,900,3}};
 private static final List<String> checks=new ArrayList<>();private static int phase,index,ticks;private static boolean done;private static java.util.concurrent.CompletableFuture<Void> reload;private static Path out;private static String tag;
 private static Object get(Class<?> c,String field)throws Exception{var f=c.getDeclaredField(field);f.setAccessible(true);return f.get(null);}
 private static void set(String field,Object value)throws Exception{var f=NativeInteractions.class.getDeclaredField(field);f.setAccessible(true);f.set(null,value);}
 private static EditBox search()throws Exception{return (EditBox)get(NativeInteractions.class,"search");}
 private static EditBox input(){return MC.screen.children().stream().filter(w->w instanceof EditBox).map(w->(EditBox)w).findFirst().orElseThrow();}
 private static void check(boolean value,String name){if(!value)throw new AssertionError(name);checks.add("PASS "+name);System.out.println("UI_TEXT PASS "+name);}
 private static void capture(String scene)throws Exception{MC.getToasts().clear();try(var image=Screenshot.takeScreenshot(MC.getMainRenderTarget())){check(image.getWidth()==CASES[index][0]&&image.getHeight()==CASES[index][1],"Original framebuffer dimensions "+scene+" "+index);image.writeToFile(out.resolve(tag+"-"+CASES[index][0]+"x"+CASES[index][1]+"-gui"+CASES[index][2]+"-"+scene+".png"));}}
 private static void hover(double x,double y)throws Exception{GLFW.glfwFocusWindow(MC.getWindow().getWindow());int[] wx={0},wy={0};GLFW.glfwGetWindowPos(MC.getWindow().getWindow(),wx,wy);new java.awt.Robot().mouseMove(wx[0]+(int)(x*MC.getWindow().getGuiScale()),wy[0]+(int)(y*MC.getWindow().getGuiScale()));}
 private static boolean fontVector(){try{Class.forName("dev.discraft.forge.UiText");return true;}catch(ClassNotFoundException absent){return false;}}
 private static int coordinate(String name)throws Exception{var method=NativeInteractions.class.getDeclaredMethod(name);method.setAccessible(true);return (Integer)method.invoke(null);}
 private static int px()throws Exception{return coordinate("px");}
 private static int py()throws Exception{return coordinate("py");}
 @SuppressWarnings("unchecked") private static void metrics()throws Exception{
  Font font=MC.font;try{font=(Font)Class.forName("dev.discraft.forge.UiText").getMethod("font").invoke(null);}catch(ClassNotFoundException baseline){}
  Function<ResourceLocation,net.minecraft.client.gui.font.FontSet> sets=ObfuscationReflectionHelper.getPrivateValue(Font.class,font,"f_92713_");
  var lines=new ArrayList<String>();for(int point:new int[]{'A','g','中','體','搜','尋'}){
   var glyph=sets.apply(Minecraft.DEFAULT_FONT).getGlyphInfo(point,false);String prefix=new String(Character.toChars(point))+" provider="+glyph.getClass().getName()+" advance="+glyph.getAdvance();
   glyph.bake(sheet->{if(fontVector()){check(glyph.getClass().getName().contains("TrueType"),"Vector provider for "+new String(Character.toChars(point)));check(sheet.getOversample()==4,"Four-times glyph raster resolution for "+point);check(sheet.getUp()-3+2>=-1&&sheet.getDown()-3+2<=15,"Glyph ink fits UI line for "+point);}
   lines.add(prefix+" atlas="+sheet.getPixelWidth()+"x"+sheet.getPixelHeight()+" oversample="+sheet.getOversample()+" top="+(sheet.getUp()-3)+" bottom="+(sheet.getDown()-3));return null;});
  }
  Files.write(out.resolve(tag+"-glyph-metrics.txt"),lines);
 }
 @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event){
  if(!Boolean.getBoolean("discraft.uiTextSmoke")||done||event.phase!=TickEvent.Phase.END)return;
  try{
   if(out==null){out=Path.of(System.getProperty("discraft.uiTextOutput"));Files.createDirectories(out);tag=System.getProperty("discraft.uiTextTag","after");}
   if(phase==0){
    if(MC.screen!=null&&MC.screen.getClass().getSimpleName().equals("AccessibilityOnboardingScreen")){MC.screen.children().stream().filter(w->w instanceof Button b&&b.getMessage().getString().equals("Continue")).map(w->(Button)w).findFirst().ifPresent(Button::onPress);return;}
    if(!(MC.screen instanceof TitleScreen)||MC.getOverlay()!=null)return;MC.options.renderDistance().set(2);MC.getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);String address="127.0.0.1:"+System.getProperty("discraft.bridgePort");ConnectScreen.startConnecting(new TitleScreen(),MC,ServerAddress.parseString(address),new ServerData("DisCraft offline UI text fixtures",address,false),false);phase=1;return;
   }
   if(phase==1){if(MC.player==null||MC.level==null||!ClientBridge.available()||MC.screen!=null||MC.getOverlay()!=null||!ClientGuildEmoji.snapshot().ready())return;metrics();phase=2;return;}
   if(phase==2){GLFW.glfwSetWindowSize(MC.getWindow().getWindow(),CASES[index][0],CASES[index][1]);ticks=0;phase=30;return;}
   if(phase==30){if(++ticks<10)return;MC.options.guiScale().set(CASES[index][2]);MC.resizeDisplay();ticks=0;phase=3;return;}
   if(phase==3){if(++ticks<12)return;check((int)MC.getWindow().getGuiScale()==CASES[index][2],"Actual GUI scale matches requested case "+index);MC.setScreen(new ChatScreen(""));set("category",5);set("guildFilter",0);search().setValue("");NativeInteractions.openPicker();var stage=NativeComposer.class.getDeclaredMethod("stage",Callable.class);stage.setAccessible(true);stage.invoke(null,(Callable<Attachment>)()->Attachment.of("繁體中文 English.png",Attachment.fromResource("landscape.png").bytes()));ticks=0;phase=4;return;}
   if(phase==4){if(++ticks<16||ClientGuildEmoji.preview("1111111111111111")==null)return;hover(px()+48,py()+94);phase=5;ticks=0;return;}
   if(phase==5){if(++ticks<5)return;capture("picker");search().setValue("繁體中文 English 123");phase=6;ticks=0;return;}
   if(phase==6){if(++ticks<6)return;check(search().getValue().equals("繁體中文 English 123"),"Mixed Chinese/English search preserved "+index);capture("search");search().setValue("");var close=NativeInteractions.class.getDeclaredMethod("closePicker");close.setAccessible(true);close.invoke(null);hover(143,MC.screen.height-113);phase=7;ticks=0;return;}
   if(phase==7){if(++ticks<6)return;capture("tooltip");
    // The search widget's hit/caret behavior must use the exact font used for drawing.
    NativeInteractions.openPicker();search().setValue("繁體中文 English 123");search().mouseClicked(search().getX()+5,search().getY()+8,0);check(search().getCursorPosition()==0,"Search left-edge click/caret alignment "+index);var fontField=EditBox.class.getDeclaredField("font");fontField.setAccessible(true);Font actualFont=(Font)fontField.get(search());int middle=search().getX()+4+actualFont.width("繁體");search().mouseClicked(middle,search().getY()+8,0);check(search().getCursorPosition()==2,"Mixed-text middle caret uses rendered glyph advances "+index);search().moveCursorToEnd();check(search().getCursorPosition()==search().getValue().length(),"Search end caret covers complete Unicode text "+index);
    check(search().getX()>=0&&search().getX()+search().getWidth()<=MC.screen.width&&search().getY()>=0&&search().getY()+search().getHeight()<=MC.screen.height,"Search stays within logical viewport "+index);
    var tabClick=new net.minecraftforge.client.event.ScreenEvent.MouseButtonPressed.Pre(MC.screen,px()+30,py()+15,0);NativeInteractions.mouse(tabClick);check((Integer)get(NativeInteractions.class,"tab")==1,"Sticker tab click area remains aligned "+index);
    NativeInteractions.mouse(new net.minecraftforge.client.event.ScreenEvent.MouseButtonPressed.Pre(MC.screen,px()+94,py()+15,0));check((Integer)get(NativeInteractions.class,"tab")==2,"Emoji tab click area remains aligned "+index);
    NativeInteractions.mouse(new net.minecraftforge.client.event.ScreenEvent.MouseButtonPressed.Pre(MC.screen,px()+38+2*coordinate("filterStep")+3,py()+70,0));check((Integer)get(NativeInteractions.class,"guildFilter")==2,"Animated filter click area remains aligned "+index);
    if(++index<CASES.length){MC.setScreen(null);phase=2;return;}reload=MC.reloadResourcePacks();phase=8;return;
   }
   if(phase==8){if(!reload.isDone()||MC.getOverlay()!=null)return;reload.join();metrics();check(!fontVector()||search().getValue().isEmpty(),"Resource reload preserves a usable scoped font and search widget");Files.write(out.resolve(tag+"-result.txt"),checks);Files.writeString(out.resolve(tag+"-result.txt"),"PASS "+checks.size()+" UI text/framebuffer/input/reload checks\n",StandardOpenOption.APPEND);done=true;MC.stop();}
  }catch(Throwable failed){done=true;try{Files.writeString(out.resolve(tag+"-result.txt"),"FAIL UI text acceptance phase="+phase+" case="+index+" type="+failed.getClass().getSimpleName()+" assertion="+(failed instanceof AssertionError?String.valueOf(failed.getMessage()).replaceAll("[\\r\\n]"," ").substring(0,Math.min(300,String.valueOf(failed.getMessage()).length())):"unavailable")+"\n");}catch(Exception ignored){}MC.stop();}
 }
}
