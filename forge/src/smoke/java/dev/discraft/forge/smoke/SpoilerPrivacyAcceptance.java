package dev.discraft.forge.smoke;

import dev.discraft.core.*;
import dev.discraft.forge.*;
import dev.discraft.forge.bridge.*;
import net.minecraft.client.*;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;
import org.lwjgl.glfw.GLFW;
import java.awt.Robot;
import java.awt.event.InputEvent;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Actual authoritative packets, native framebuffer concealment, and physical per-message reveal. */
@net.minecraftforge.fml.common.Mod.EventBusSubscriber(modid=DisCraft.ID,value=net.minecraftforge.api.distmarker.Dist.CLIENT)
public final class SpoilerPrivacyAcceptance {
    private static final Minecraft MC=Minecraft.getInstance();
    private static final Path OUT=Path.of("../../build/evidence/multiplayer").toAbsolutePath().normalize();
    private static Consumer<String> pass;private static int phase,ticks;private static long frames,frameBefore;private static CompletableFuture<Void> send;
    private static String pendingLabel;private static Attachment pendingFile;private static long queuedAt;private static int submissions;
    private static UUID a,b,c,d,e,peerD;private static Attachment source;private static Robot robot;
    private record Region(int index,int x,int width,int height,int band) {}
    static void start(Consumer<String> check)throws Exception{pass=check;phase=0;ticks=0;submissions=0;pendingLabel=null;pendingFile=null;send=null;source=Attachment.fromResource("landscape.png");robot=new Robot();robot.setAutoDelay(35);MC.setScreen(new ChatScreen(""));submit("A",Attachment.of("SPOILER_a.png",source.bytes()));}
    @net.minecraftforge.eventbus.api.SubscribeEvent public static void rendered(net.minecraftforge.client.event.RenderGuiOverlayEvent.Post event){if(event.getOverlay().id().equals(net.minecraftforge.client.gui.overlay.VanillaGuiOverlay.CHAT_PANEL.id()))frames++;}
    private static boolean rendered(){return frames>frameBefore;}
    static String state(){return " spoilerPhase="+phase;}
    private static void check(boolean ok,String label){if(!ok)throw new AssertionError("Spoiler privacy "+phase+": "+label);pass.accept(label);}
    private static void next(int n){phase=n;ticks=0;frameBefore=frames;System.out.println("SPOILER_PRIVACY phase="+n);}
    private static void submit(String label,Attachment file){
        if(pendingLabel!=null||send!=null&&!send.isDone())throw new AssertionError("Overlapping spoiler submissions");
        // Earlier native/picker fixtures share the real five-token/one-token-per-second server budget.
        // Wait for a new token before EVERY send, including the first after that fixture; never retry rejection.
        pendingLabel=label;pendingFile=file;queuedAt=System.nanoTime();send=null;
    }
    private static void submitWhenReady(){
        if(pendingLabel==null||System.nanoTime()-queuedAt<1_100_000_000L)return;
        String label=pendingLabel;Attachment file=pendingFile;pendingLabel=null;pendingFile=null;submissions++;
        System.out.println("SPOILER_PRIVACY pacedSubmission="+submissions+" waitedMillis="+(System.nanoTime()-queuedAt)/1_000_000L);
        send=CompletableFuture.runAsync(()->{try{ClientBridge.send("privacy-"+label+(label.equals("E")?" :discraft:":""),file);}catch(Exception error){throw new CompletionException(error);}});
    }
    @SuppressWarnings("unchecked") private static List<RelayRecord> records()throws Exception{return new ArrayList<>((Collection<RelayRecord>)get(ClientBridge.class,"history"));}
    private static RelayRecord message(String label,boolean peer)throws Exception{return records().stream().filter(r->r.author().equals(peer?"TEST peer (not Discord)":"Alice")&&r.text().startsWith((peer?"Peer received: ":"")+"privacy-"+label)).findFirst().orElse(null);}
    private static boolean arrived(String label)throws Exception{if(send==null||!send.isDone())return false;send.join();return message(label,false)!=null&&message(label,true)!=null;}
    static boolean tick()throws Exception{
        submitWhenReady();
        switch(phase){
        case 0 -> {if(!arrived("A"))return false;a=message("A",false).id();submit("B",Attachment.of("b.png",source.bytes()));next(1);}
        case 1 -> {if(!arrived("B"))return false;b=message("B",false).id();scroll(a);next(2);}
        case 2 -> {if(!rendered())return false;pixels(a,true,false,"Spoiler-before-plain.png");scroll(b);next(3);}
        case 3 -> {if(!rendered())return false;pixels(b,false,false,"Plain-after-spoiler.png");submit("C",Attachment.of("c.png",source.bytes()));next(4);}
        case 4 -> {if(!arrived("C"))return false;c=message("C",false).id();submit("D",Attachment.of("SPOILER_d.png",source.bytes()));next(5);}
        case 5 -> {if(!arrived("D"))return false;d=message("D",false).id();peerD=message("D",true).id();scroll(c);next(6);}
        case 6 -> {if(!rendered())return false;pixels(c,false,false,"Plain-before-spoiler.png");scroll(d);next(7);}
        case 7 -> {if(!rendered())return false;pixels(d,true,false,"Spoiler-after-plain.png");submit("E",Attachment.of("SPOILER_emoji.png",Attachment.fromResource("emoji.png").bytes()));next(8);}
        case 8 -> {if(!arrived("E"))return false;e=message("E",false).id();scroll(e);next(9);}
        case 9 -> {if(!rendered())return false;pixels(e,true,false,"Spoiler-same-bytes-emoji.png");scrollEmoji(e);next(10);}
        case 10 -> {if(!rendered())return false;pixels(e,false,true,"Visible-emoji-same-bytes-spoiler.png");scroll(d);next(11);}
        case 11 -> {if(!rendered())return false;Region r=region(d,false);double[] xy=point(r,r.width()/2d,r.height()/2d);int[] wx={0},wy={0};long window=MC.getWindow().getWindow();GLFW.glfwFocusWindow(window);GLFW.glfwGetWindowPos(window,wx,wy);robot.mouseMove(wx[0]+(int)(xy[0]*MC.getWindow().getGuiScale()),wy[0]+(int)(xy[1]*MC.getWindow().getGuiScale()));robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);next(12);}
        case 12 -> {if(!((Set<?>)get(NativeChat.class,"revealed")).contains(d))return false;next(14);}
        case 14 -> {if(!rendered())return false;pixels(d,false,false,"Revealed-one-message.png");scroll(peerD);next(13);}
        case 13 -> {if(!rendered())return false;pixels(peerD,true,false,"Other-identical-message-still-hidden.png");check(((Set<?>)get(NativeChat.class,"revealed")).contains(d)&&!((Set<?>)get(NativeChat.class,"revealed")).contains(peerD),"Physical reveal is scoped to one authoritative message ID, never shared bytes");check(submissions==5&&pendingLabel==null,"Five spoiler submissions traverse the unchanged production rate gate once each without automatic retries");MC.gui.getChat().resetChatScroll();return true;}
        default -> throw new AssertionError("Unknown spoiler phase");
        }return false;
    }
    @SuppressWarnings("unchecked") private static Region region(UUID id,boolean emoji)throws Exception{
        Map<Integer,Object> tiles=get(NativeChat.class,"tiles");var lines=(List<GuiMessage.Line>)ObfuscationReflectionHelper.findField(ChatComponent.class,"f_93761_").get(MC.gui.getChat());
        for(int i=0;i<lines.size();i++){final int index=i;int[] x={0};Region[] result={null};lines.get(i).content().accept((j,style,ch)->{Object tile=style.getColor()==null?null:tiles.get(style.getColor().getValue());if(tile!=null)try{int height=call(tile,"height");if(id.equals(call(tile,"owner"))&&(Integer)call(tile,"x")==0&&(Integer)call(tile,"y")==0&&(emoji?height==8:height>8))result[0]=new Region(index,x[0],call(tile,"width"),height,call(tile,"band"));}catch(Exception error){throw new RuntimeException(error);}x[0]+=MC.font.width(Component.literal(new String(Character.toChars(ch))).setStyle(style));return result[0]==null;});if(result[0]!=null)return result[0];}
        throw new AssertionError("No region for "+id+" emoji="+emoji);
    }
    private static int scrollValue()throws Exception{return ObfuscationReflectionHelper.findField(ChatComponent.class,"f_93763_").getInt(MC.gui.getChat());}
    private static void scroll(UUID id)throws Exception{var r=region(id,false);MC.gui.getChat().scrollChat(Math.max(0,r.index()-(r.height()+r.band()-1)/r.band()+1)-scrollValue());}
    private static void scrollEmoji(UUID id)throws Exception{var r=region(id,true);MC.gui.getChat().scrollChat(Math.max(0,r.index()-2)-scrollValue());}
    private static double[] point(Region r,double dx,double dy)throws Exception{var chat=MC.gui.getChat();double scale=chat.getScale();int h=(int)(9*(MC.options.chatLineSpacing().get()+1)),offset=(int)Math.round(-8*(MC.options.chatLineSpacing().get()+1)+4*MC.options.chatLineSpacing().get());int bottom=(int)Math.floor((MC.getWindow().getGuiScaledHeight()-40)/scale),cx=get(NativeChat.class,"chatX"),cy=get(NativeChat.class,"chatY");return new double[]{cx+scale*(4+r.x()+dx),(cy-MC.getWindow().getGuiScaledHeight()+40)/scale+scale*(bottom-(r.index()-scrollValue())*h+offset+dy)};}
    private static void pixels(UUID id,boolean hidden,boolean emoji,String name)throws Exception{
        var r=region(id,emoji);System.out.println("SPOILER_PRIVACY capture="+name+" index="+r.index()+" scroll="+scrollValue()+" frames="+frames);double gui=MC.getWindow().getGuiScale();int samples=emoji?1:r.height(),bad=0;var palette=new HashSet<Integer>();var asset=emoji?Attachment.fromResource("emoji.png"):source;var frame=asset.media().frames().get(0).image();if(emoji)palette.add(frame.getRGB(frame.getWidth()/2,frame.getHeight()/2)&0xffffff);else for(int y=0;y<frame.getHeight();y++)palette.add(frame.getRGB(frame.getWidth()-1,y)&0xffffff);
        try(var image=Screenshot.takeScreenshot(MC.getMainRenderTarget())){image.writeToFile(OUT.resolve(name));for(int row=0;row<samples;row++){double[] xy=point(r,emoji?r.width()/2d:r.width()-.5,emoji?r.height()/2d:row+.5);int pixel=image.getPixelRGBA((int)Math.floor(xy[0]*gui),(int)Math.floor(xy[1]*gui));int rgb=(pixel&255)<<16|(pixel&0xff00)|(pixel>>>16&255);if(hidden?rgb!=0x3d3e45:!palette.contains(rgb))bad++;}}
        check(bad==0,"Actual native framebuffer "+name+" preserves "+(hidden?"concealment":"visible pixels")+" with identical-content assets; mismatches="+bad);
    }
    @SuppressWarnings("unchecked") private static <T>T get(Class<?> c,String n)throws Exception{var f=c.getDeclaredField(n);f.setAccessible(true);return (T)f.get(null);}
    @SuppressWarnings("unchecked") private static <T>T call(Object o,String n)throws Exception{var m=o.getClass().getDeclaredMethod(n);m.setAccessible(true);return (T)m.invoke(o);}
}
