package dev.discraft.forge.smoke;

import dev.discraft.core.*;
import dev.discraft.forge.*;
import net.minecraft.client.*;
import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.*;
import net.minecraft.client.multiplayer.*;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;
import org.lwjgl.glfw.GLFW;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Actual game keyboard/network/framebuffer regressions; gates wrap the normal local decoder. */
final class NativeChatRegression {
    private static final Minecraft MC=Minecraft.getInstance();
    private static int phase,ticks,errors,sendErrors,emptyBefore,profile;
    private static Consumer<String> pass;
    private static Function<String,Attachment> original;
    private static ExecutorService worker;
    private static Future<?> fence;
    private static CountDownLatch entered,release,entered2,release2;
    private static final List<CountDownLatch> gates=new ArrayList<>();
    private static List<GuiMessage> normal;
    private static Set<UUID> beforeCaptionless;private static UUID captionlessId;private static long arrivalDeadline;private static Future<?> arrivalBarrier;
    private static final Path OUT=Path.of("../../build/evidence/multiplayer").toAbsolutePath().normalize();
    static void start(Consumer<String> check)throws Exception {
        pass=check;original=get(NativeComposer.class,"decoder");worker=get(NativeComposer.class,"worker");
        var root=MC.gameDirectory.toPath().resolve("discraft-uploads");Files.createDirectories(root);
        for(String name:List.of("landscape.png","dance.gif"))try(var in=Attachment.class.getResourceAsStream("/fixtures/"+name)){Files.copy(in,root.resolve(name),StandardCopyOption.REPLACE_EXISTING);}
        phase=0;ticks=0;captionlessId=null;
    }
    static int phase(){return phase;}
    private static void check(boolean yes,String label){if(!yes)throw new AssertionError("Native regression "+phase+": "+label);pass.accept(label);}
    private static void next(int p){phase=p;ticks=0;System.out.println("NATIVE_REGRESSION phase="+p);}
    private static boolean settled(){return fence.isDone()&&++ticks>2;}
    private static boolean loading()throws Exception{return get(NativeComposer.class,"loading");}
    private static boolean sending()throws Exception{return get(NativeComposer.class,"sending");}
    private static Attachment selected()throws Exception{return get(NativeComposer.class,"attachment");}
    private static EditBox input(){return MC.screen.children().stream().filter(w->w instanceof EditBox).map(w->(EditBox)w).findFirst().orElseThrow();}
    private static void file(String name){try{var m=NativeComposer.class.getDeclaredMethod("attach",String.class);m.setAccessible(true);m.invoke(null,name);}catch(Exception e){throw new RuntimeException(e);}}
    private static void click(String label){if(label.equals("Clear")){try{var m=NativeComposer.class.getDeclaredMethod("removeStaged");m.setAccessible(true);m.invoke(null);}catch(Exception e){throw new RuntimeException(e);}return;}MC.screen.children().stream().filter(w->w instanceof Button b&&b.getMessage().getString().equals(label)).map(w->(Button)w).findFirst().orElseThrow().onPress();}

    private static void key(int key){MC.keyboardHandler.keyPress(MC.getInstance().getWindow().getWindow(),key,0,GLFW.GLFW_PRESS,0);MC.keyboardHandler.keyPress(MC.getInstance().getWindow().getWindow(),key,0,GLFW.GLFW_RELEASE,0);}
    private static void enter(){key(GLFW.GLFW_KEY_ENTER);}
    private static void waitGate(CountDownLatch gate){long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(gate.getCount()>0&&System.nanoTime()<until)try{if(gate.await(100,TimeUnit.MILLISECONDS))return;}catch(InterruptedException cancelled){/* Simulate bounded decoder work that cannot stop immediately. */}if(gate.getCount()>0)throw new AssertionError("Decoder gate expired");}
    private static void slow(boolean two)throws Exception{
        entered=new CountDownLatch(1);release=new CountDownLatch(1);entered2=new CountDownLatch(1);release2=new CountDownLatch(1);gates.add(release);gates.add(release2);var calls=new AtomicInteger();
        set(NativeComposer.class,"decoder",(Function<String,Attachment>)name->{int n=calls.incrementAndGet();if(n==1){entered.countDown();waitGate(release);}else if(two&&n==2){entered2.countDown();waitGate(release2);}return original.apply(name);});
    }
    private static void began()throws Exception{if(!entered.await(2,TimeUnit.SECONDS))throw new AssertionError("Decoder did not begin");}
    private static void drain(){fence=worker.submit(()->{});ticks=0;}
    private static int errorCount()throws Exception{return (int)messages().stream().filter(m->m.content().getString().startsWith("[DisCraft] Attachment rejected")).count();}
    @SuppressWarnings("unchecked") private static List<GuiMessage> messages()throws Exception{return (List<GuiMessage>)ObfuscationReflectionHelper.findField(ChatComponent.class,"f_93760_").get(MC.gui.getChat());}
    private static boolean received(String text){return ClientBridge.snapshot().stream().anyMatch(m->m.author().equals("Alice")&&Content.plain(m.content()).equals(text));}
    static void cleanup(){gates.forEach(CountDownLatch::countDown);try{if(original!=null)set(NativeComposer.class,"decoder",original);}catch(Exception ignored){}}
    static boolean tick()throws Exception{
        switch(phase){
        case 0 -> {NativeComposer.openBridge();input().setValue("decode-clear draft");slow(false);file("landscape.png");enter();check(MC.screen instanceof ChatScreen&&input().getValue().equals("decode-clear draft")&&!sending()&&!received("decode-clear draft"),"Immediate Enter during Attach preserves draft and sends no stale file");began();click("Clear");release.countDown();drain();next(1);}
        case 1 -> {if(!settled())return false;check(selected()==null&&!loading(),"Clear invalidates an active decoder completion");set(NativeComposer.class,"decoder",original);slow(true);file("landscape.png");began();file("dance.gif");release.countDown();if(!entered2.await(2,TimeUnit.SECONDS))throw new AssertionError("Replacement decoder did not start");next(2);}
        case 2 -> {if(++ticks<3)return false;check(selected()==null&&loading(),"Old decode success cannot install while replacement decode is pending");release2.countDown();drain();next(3);}
        case 3 -> {if(!settled()||loading())return false;check(selected()!=null&&selected().name().equals("dance.gif"),"Replacement decode installs only the current attachment revision");set(NativeComposer.class,"decoder",original);errors=errorCount();slow(false);file("missing-native-test.png");began();click("Clear");release.countDown();drain();next(4);}
        case 4 -> {if(!settled())return false;check(selected()==null&&!loading()&&errorCount()==errors,"Clear suppresses an obsolete decoder error callback");set(NativeComposer.class,"decoder",original);file("landscape.png");next(5);}
        case 5 -> {if(loading())return false;check(selected()!=null,"Valid file can be selected after interrupted decoding");errors=errorCount();file("missing-native-test.png");input().setValue("failure draft");enter();check(MC.screen instanceof ChatScreen&&input().getValue().equals("failure draft")&&selected()==null,"Replacing a valid file clears its stale snapshot before validation");next(6);}
        case 6 -> {if(loading()||errorCount()==errors)return false;check(selected()==null&&!received("failure draft")&&input().getValue().equals("failure draft"),"Current decode failure keeps the draft and cannot send the previous file");slow(false);file("landscape.png");began();key(GLFW.GLFW_KEY_ESCAPE);release.countDown();drain();next(7);}
        case 7 -> {if(!settled())return false;check(MC.screen==null&&selected()==null&&!loading(),"Closing native chat cancels pending decode without resurrection");set(NativeComposer.class,"decoder",original);NativeComposer.openBridge();file("landscape.png");next(8);}
        case 8 -> {if(loading())return false;entered=new CountDownLatch(1);release=new CountDownLatch(1);gates.add(release);worker.submit(()->{entered.countDown();waitGate(release);});began();input().setValue("revision-send");enter();check(sending()&&MC.screen==null,"Real Enter snapshots a selected file for a queued send");NativeComposer.openBridge();input().setValue("new draft survives delayed send");file("dance.gif");enter();check(MC.screen instanceof ChatScreen&&input().getValue().equals("new draft survives delayed send"),"Repeated Enter preserves the next draft while send and decode are pending");release.countDown();next(9);}
        case 9 -> {if(sending()||loading()||!received("revision-send"))return false;check(selected()!=null&&selected().name().equals("dance.gif")&&input().getValue().equals("new draft survives delayed send")&&!received("new draft survives delayed send"),"Old send success preserves newer native draft and attachment selection");check(ClientBridge.snapshot().stream().filter(m->m.author().equals("Alice")&&Content.plain(m.content()).equals("revision-send")&&!m.attachments().isEmpty()&&m.attachments().get(0).media().format().equals("png")).count()==1,"Delayed native send carries exactly one original PNG over game packets");sendErrors=(int)messages().stream().filter(m->m.content().getString().startsWith("[DisCraft] Bridge send rejected")).count();entered=new CountDownLatch(1);release=new CountDownLatch(1);gates.add(release);worker.submit(()->{entered.countDown();waitGate(release);});began();input().setValue("fixture:reject-send");enter();NativeComposer.openBridge();input().setValue("error callback preserves this draft");file("landscape.png");release.countDown();next(30);}
        case 30 -> {if(sending()||loading()||messages().stream().filter(m->m.content().getString().startsWith("[DisCraft] Bridge send rejected")).count()==sendErrors)return false;check(!received("fixture:reject-send")&&selected()!=null&&selected().name().equals("landscape.png")&&input().getValue().equals("error callback preserves this draft"),"Native send rejection preserves the newer draft/file and publishes no failed message");file("dance.gif");next(31);}
        case 31 -> {if(loading())return false;beforeCaptionless=ClientBridge.snapshot().stream().map(Message::id).collect(java.util.stream.Collectors.toSet());arrivalDeadline=0;input().setValue("");enter();check(MC.screen==null&&sending(),"Attachment-only Enter bypasses vanilla's empty-text filter");next(10);}
        case 10 -> {
            if(sending())return false;if(arrivalDeadline==0)arrivalDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(6);
            var arrived=ClientBridge.snapshot().stream().filter(m->!beforeCaptionless.contains(m.id())&&m.author().equals("Alice")&&Content.plain(m.content()).isBlank()&&!m.attachments().isEmpty()&&m.attachments().get(0).media().animated()).toList();
            if(arrived.isEmpty()){if(System.nanoTime()>arrivalDeadline)throw new AssertionError("ACK completed without authoritative captionless arrival");return false;}
            if(arrived.size()!=1)throw new AssertionError("Duplicate captionless authoritative arrivals");if(captionlessId==null){check(true,"Captionless GIF reaches authoritative history after ACK/decode barrier");captionlessId=arrived.get(0).id();}
            boolean peer=ClientBridge.snapshot().stream().anyMatch(m->!beforeCaptionless.contains(m.id())&&m.author().equals("TEST peer (not Discord)")&&Content.plain(m.content()).equals("Peer received: ")&&!m.attachments().isEmpty());
            if(!peer){if(System.nanoTime()>arrivalDeadline)throw new AssertionError("Captionless TEST peer did not arrive");return false;}
            arrivalBarrier=worker.submit(()->{try{ClientBridge.send("captionless-arrival-barrier",null);}catch(Exception e){throw new CompletionException(e);}});next(32);
        }
        case 32 -> {
            if(!arrivalBarrier.isDone()||!received("captionless-arrival-barrier"))return false;arrivalBarrier.get();
            var arrived=ClientBridge.snapshot().stream().filter(m->!beforeCaptionless.contains(m.id())&&m.author().equals("Alice")&&Content.plain(m.content()).isBlank()&&!m.attachments().isEmpty()).toList();
            check(arrived.size()==1&&arrived.get(0).id().equals(captionlessId),"Captionless exact-once remains true after peer and a later authoritative roundtrip");check(selected()==null,"Successful captionless send clears only its submitted attachment");
            NativeComposer.openBridge();slow(false);file("landscape.png");began();MC.level.disconnect();MC.clearLevel();MC.setScreen(new TitleScreen());NativeComposer.openBridge();check(MC.screen instanceof DisCraftScreen,"Out-of-world F8 action uses safe local preview after disconnect");((DisCraftScreen)MC.screen).onClose();release.countDown();drain();next(11);
        }
        case 11 -> {if(!settled())return false;check(selected()==null&&!loading()&&!sending()&&((Map<?,?>)get(NativeChat.class,"assets")).isEmpty()&&ClientBridge.snapshot().isEmpty(),"Disconnect invalidates active decoder and clears native assets");set(NativeComposer.class,"decoder",original);String address="127.0.0.1:"+System.getProperty("discraft.bridgePort");ConnectScreen.startConnecting(new TitleScreen(),MC,ServerAddress.parseString(address),new ServerData("native reconnect",address,false),false);next(12);}
        case 12 -> {if(MC.player==null||!ClientBridge.available()||MC.screen!=null)return false;key(GLFW.GLFW_KEY_T);next(13);}
        case 13 -> {if(!(MC.screen instanceof ChatScreen))return false;check(MC.screen.getClass()==ChatScreen.class&&selected()==null,"Actual T key reopens vanilla chat after reconnect without a stale attachment");key(GLFW.GLFW_KEY_ESCAPE);key(GLFW.GLFW_KEY_F8);next(14);}
        case 14 -> {if(!(MC.screen instanceof ChatScreen))return false;check(MC.screen.getClass()==ChatScreen.class,"Actual in-world F8 key opens guarded native ChatScreen");key(GLFW.GLFW_KEY_ESCAPE);key(GLFW.GLFW_KEY_T);next(15);}
        case 15 -> {if(!(MC.screen instanceof ChatScreen))return false;input().setValue("native-layout-ordinary");enter();next(16);}
        case 16 -> {if(messages().stream().noneMatch(m->m.content().getString().contains("native-layout-ordinary")))return false;check(!received("native-layout-ordinary"),"Ordinary server chat still routes once after native reconnect");NativeComposer.openBridge();var owned=Collections.newSetFromMap(new IdentityHashMap<Component,Boolean>());owned.addAll(((Map<UUID,Component>)get(NativeChat.class,"components")).values());normal=new ArrayList<>(messages().stream().filter(m->!owned.contains(m.content())).toList());profile=0;next(20);}
        case 20 -> {double[] widths={1,0,0};double[] scales={1,1,.5};double[] spaces={1,1,.5};MC.options.chatWidth().set(widths[profile]);MC.options.chatScale().set(scales[profile]);MC.options.chatLineSpacing().set(spaces[profile]);MC.gui.getChat().rescaleChat();next(21);}
        case 21 -> {if(++ticks<5)return false;scrollTo(false);next(22);}
        case 22 -> {if(++ticks<5)return false;pixels(false,"Native-layout-"+profile+"-PNG.png");scrollTo(true);next(23);}
        case 23 -> {if(++ticks<5)return false;pixels(true,"Native-layout-"+profile+"-GIF.png");if(++profile<3)next(20);else {MC.options.chatWidth().set(1d);MC.options.chatScale().set(1d);MC.options.chatLineSpacing().set(0d);next(24);}}
        case 24 -> {if(++ticks<5)return false;var current=messages();int last=-1;boolean intact=!normal.isEmpty();for(var old:normal){int index=-1;for(int i=0;i<current.size();i++)if(current.get(i)==old){index=i;break;}if(index<=last){intact=false;break;}last=index;}check(intact,"Native reflow retains ordinary message identity, relative order, signatures and timestamps");cleanup();return true;}
        default -> throw new AssertionError("Unknown native phase");
        }return false;
    }
    private record Region(MediaAsset asset,int index,int x,int width,int height,int band) {}
    @SuppressWarnings("unchecked") private static List<GuiMessage.Line> lines()throws Exception{return (List<GuiMessage.Line>)ObfuscationReflectionHelper.findField(ChatComponent.class,"f_93761_").get(MC.gui.getChat());}
    private static Region region(boolean animated)throws Exception {
        Map<Integer,Object> tiles=get(NativeChat.class,"tiles");Map<String,MediaAsset> assets=get(NativeChat.class,"assets");var lines=lines();
        for(int i=0;i<lines.size();i++){
            final int[] x={0};final Region[] found={null};final int index=i;
            lines.get(i).content().accept((j,style,point)->{var color=style.getColor();Object tile=color==null?null:tiles.get(color.getValue());if(tile!=null)try{MediaAsset a=assets.get(call(tile,"digest"));if(a!=null&&a.animated()==animated&&(Integer)call(tile,"x")==0&&(Integer)call(tile,"y")==0&&(Integer)call(tile,"height")>8)found[0]=new Region(a,index,x[0],call(tile,"width"),call(tile,"height"),call(tile,"band"));}catch(Exception e){throw new RuntimeException(e);}x[0]+=MC.font.width(net.minecraft.network.chat.Component.literal(new String(Character.toChars(point))).setStyle(style));return found[0]==null;});
            if(found[0]!=null)return found[0];
        }throw new AssertionError("No native media region for animated="+animated);
    }
    private static void scrollTo(boolean animated)throws Exception{var r=region(animated);int current=ObfuscationReflectionHelper.findField(ChatComponent.class,"f_93763_").getInt(MC.gui.getChat());MC.gui.getChat().scrollChat(Math.max(0,r.index()-(r.height()+r.band()-1)/r.band()+1)-current);}
    private static void pixels(boolean animated,String name)throws Exception {
        var r=region(animated);var chat=MC.gui.getChat();int scroll=ObfuscationReflectionHelper.findField(ChatComponent.class,"f_93763_").getInt(chat),line=r.index()-scroll;
        int band=(int)(9*(MC.options.chatLineSpacing().get()+1)),offset=(int)Math.round(-8*(MC.options.chatLineSpacing().get()+1)+4*MC.options.chatLineSpacing().get());
        double scale=chat.getScale(),gui=MC.getWindow().getGuiScale();int bottom=(int)Math.floor((MC.getWindow().getGuiScaledHeight()-40)/scale);
        int x=(int)Math.floor((4+r.x()+r.width()-.5)*scale*gui),y=(int)Math.floor((bottom-line*band+offset)*scale*gui);
        var palette=new HashSet<Integer>();var source=r.asset().frames().get(0).image();for(int sy=0;sy<source.getHeight();sy++)palette.add(source.getRGB(source.getWidth()-1,sy)&0xffffff);
        try(var image=Screenshot.takeScreenshot(MC.getMainRenderTarget())){
            image.writeToFile(OUT.resolve(name));int bad=0;for(int row=0;row<r.height();row++){int py=(int)Math.floor(y+(row+.5)*scale*gui);int abgr=image.getPixelRGBA(x,py);int rgb=(abgr&255)<<16|(abgr&0xff00)|(abgr>>>16&255);if(!palette.contains(rgb))bad++;}
            check(bad==0,"Actual "+(animated?"GIF":"PNG")+" framebuffer has continuous media bands: width="+chat.getWidth()+", scale="+scale+", spacing="+MC.options.chatLineSpacing().get()+", gaps="+bad);
        }
    }
    private static List<GuiMessage> messagesUnchecked(){try{return messages();}catch(Exception e){throw new RuntimeException(e);}}
    @SuppressWarnings("unchecked") private static <T>T get(Class<?> type,String name)throws Exception{var f=type.getDeclaredField(name);f.setAccessible(true);return (T)f.get(null);}
    private static void set(Class<?> type,String name,Object value)throws Exception{var f=type.getDeclaredField(name);f.setAccessible(true);f.set(null,value);}
    @SuppressWarnings("unchecked") private static <T>T call(Object value,String name)throws Exception{var m=value.getClass().getDeclaredMethod(name);m.setAccessible(true);return (T)m.invoke(value);}
}
