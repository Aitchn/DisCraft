package dev.discraft.forge.smoke;

import dev.discraft.core.*;
import dev.discraft.forge.*;
import dev.discraft.forge.bridge.*;
import net.minecraft.client.*;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.*;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;
import org.lwjgl.glfw.GLFW;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.datatransfer.*;
import java.awt.event.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import javax.imageio.ImageIO;

/** Real X11 clipboard owner, Robot key/mouse input, game packets and disk screenshot evidence. */
final class NativeUxAcceptance {
    private static final Minecraft MC=Minecraft.getInstance();
    private static final Path OUT=Path.of("../../build/evidence/multiplayer").toAbsolutePath().normalize();
    private static int phase,ticks;private static Consumer<String> pass;private static Robot robot;
    private static Process owner;private static Path ready,stop,source,saved;private static Set<String> beforeFiles;private static Set<UUID> beforeOwn;
    private static ActorRef target;private static CompletableFuture<String> request;private static String digest,replacedDraft;
    private static ExecutorService robotWorker;private static Future<?> action;
    static void start(Consumer<String> check)throws Exception{pass=check;phase=0;ticks=0;robot=new Robot();robot.setAutoDelay(35);robotWorker=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"Native UX physical input");t.setDaemon(true);return t;});focus();}
    static String state(){return " uxPhase="+phase;}
    private static boolean sending()throws Exception{return get(NativeComposer.class,"sending");}
    static void cleanup(){if(robotWorker!=null)robotWorker.shutdownNow();cleanupOwner();}
    private static void cleanupOwner(){try{if(stop!=null)Files.writeString(stop,"stop");if(owner!=null&&owner.isAlive()){owner.destroy();if(!owner.waitFor(2,TimeUnit.SECONDS))owner.destroyForcibly();}}catch(Exception ignored){}}
    private static void check(boolean yes,String label){if(!yes)throw new AssertionError("Native UX "+phase+": "+label);pass.accept(label);}
    private static void next(int p){phase=p;ticks=0;System.out.println("NATIVE_UX phase="+p);}
    private static void focus(){GLFW.glfwFocusWindow(MC.getWindow().getWindow());}
    private static void key(int key){focus();action=robotWorker.submit(()->{robot.keyPress(key);robot.delay(80);robot.keyRelease(key);});}
    private static void chord(int key){focus();action=robotWorker.submit(()->{robot.keyPress(KeyEvent.VK_CONTROL);robot.keyPress(key);robot.delay(80);robot.keyRelease(key);robot.keyRelease(KeyEvent.VK_CONTROL);});}
    private static void mouse(double x,double y){focus();int[] wx={0},wy={0};GLFW.glfwGetWindowPos(MC.getWindow().getWindow(),wx,wy);int px=wx[0]+(int)(x*MC.getWindow().getGuiScale()),py=wy[0]+(int)(y*MC.getWindow().getGuiScale());action=robotWorker.submit(()->{robot.mouseMove(px,py);robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);robot.delay(80);robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);});}
    private static EditBox input(){return MC.screen.children().stream().filter(w->w instanceof EditBox).map(w->(EditBox)w).findFirst().orElseThrow();}
    private static Attachment selected()throws Exception{return get(NativeComposer.class,"attachment");}
    private static boolean emptyNativeRoute()throws Exception{var method=NativeComposer.class.getDeclaredMethod("requiresPayload",String.class,List.class);method.setAccessible(true);return !(Boolean)method.invoke(null,"ordinary 😀",List.of());}
    private static boolean loading()throws Exception{return get(NativeComposer.class,"loading");}
    private static void textClipboard(String text){Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text),null);}
    private static Set<String> screenshots()throws Exception{var path=MC.gameDirectory.toPath().resolve("screenshots");if(!Files.exists(path))return Set.of();try(var stream=Files.list(path)){return stream.filter(p->p.toString().endsWith(".png")).map(p->p.getFileName().toString()).collect(java.util.stream.Collectors.toSet());}}
    private static boolean noAutoSend(){return ClientBridge.snapshot().stream().filter(m->m.author().equals("Alice")).noneMatch(m->!beforeOwn.contains(m.id()));}
    private static void capture(String name)throws Exception{try(var image=Screenshot.takeScreenshot(MC.getMainRenderTarget())){image.writeToFile(OUT.resolve(name));}}
    @SuppressWarnings("unchecked") private static List<RelayRecord> records()throws Exception{return new ArrayList<>((Collection<RelayRecord>)get(ClientBridge.class,"history"));}
    private static boolean cardMouse(boolean remove)throws Exception{
        var valid=NativeInteractions.class.getDeclaredMethod("currentCard");valid.setAccessible(true);if(!(Boolean)valid.invoke(null))return false;
        var layout=(PendingCardLayout.Placement)get(NativeInteractions.class,"displayedCard");boolean picker=(Boolean)get(NativeInteractions.class,"open");
        if(layout.mode()==PendingCardLayout.Mode.HIDDEN&&!picker){NativeInteractions.openPicker();return false;}
        if(picker&&layout.mode()==PendingCardLayout.Mode.HIDDEN){var method=NativeInteractions.class.getDeclaredMethod("footer");method.setAccessible(true);layout=(PendingCardLayout.Placement)method.invoke(null);}var button=remove?layout.remove():layout.spoiler();mouse(button.x()+9,button.y()+9);return true;
    }
    static boolean tick()throws Exception{
        if(action!=null){if(!action.isDone())return false;action.get();action=null;}
        switch(phase){
        case 0 -> {
            if(!(MC.screen instanceof ChatScreen))MC.setScreen(new ChatScreen(""));input().setValue("");textClipboard("clipboard vanilla 世界");beforeFiles=screenshots();
            Screenshot.grab(MC.gameDirectory,MC.getMainRenderTarget(),message->MC.execute(()->MC.gui.getChat().addMessage(message)));next(1);
        }
        case 1 -> {if(screenshots().equals(beforeFiles))return false;check(Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor).equals("clipboard vanilla 世界"),"Programmatic screenshot saves without writing OS clipboard");chord(KeyEvent.VK_V);next(2);}
        case 2 -> {
            if(!input().getValue().equals("clipboard vanilla 世界"))return false;check(selected()==null,"Real X11 text clipboard paste retains vanilla input behavior");input().setValue("");
            source=MC.gameDirectory.toPath().resolve("config/discraft/uploads/landscape.png");ready=MC.gameDirectory.toPath().resolve("clipboard-owner.ready");stop=MC.gameDirectory.toPath().resolve("clipboard-owner.stop");Files.deleteIfExists(ready);Files.deleteIfExists(stop);
            owner=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java").toString(),"-Xmx128m","-XX:ActiveProcessorCount=1","-cp",System.getProperty("java.class.path"),ClipboardFixture.class.getName(),source.toString(),ready.toString(),stop.toString()).redirectErrorStream(true).redirectOutput(OUT.resolve("clipboard-owner.log").toFile()).start();System.out.println("OS_CLIPBOARD ownerPid="+owner.pid());beforeOwn=ClientBridge.snapshot().stream().filter(m->m.author().equals("Alice")).map(Message::id).collect(java.util.stream.Collectors.toSet());next(3);
        }
        case 3 -> {if(!Files.exists(ready)){if(!owner.isAlive())throw new AssertionError("External clipboard owner exited before ready");return false;}chord(KeyEvent.VK_V);next(4);}
        case 4 -> {if(loading()||selected()==null)return false;if(!cardMouse(true))return false;check(selected().name().equals("clipboard.png")&&selected().media().width()==192&&noAutoSend(),"Actual external-process X11 image Ctrl+V stages bounded pixels without auto-send");next(5);}
        case 5 -> {if(selected()!=null)return false;check(Files.exists(source)&&noAutoSend(),"Real remove-card click removes only staged data, preserving original disk file");cleanupOwner();beforeFiles=screenshots();key(KeyEvent.VK_ESCAPE);key(KeyEvent.VK_F2);next(6);}
        case 6 -> {
            var changed=new HashSet<>(screenshots());changed.removeAll(beforeFiles);if(changed.isEmpty())return false;
            saved=MC.gameDirectory.toPath().resolve("screenshots").resolve(changed.iterator().next());java.awt.image.BufferedImage disk;try{disk=ImageIO.read(saved.toFile());}catch(Exception incomplete){return false;}if(disk==null)return false;
            Object value=Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.imageFlavor);if(!(value instanceof java.awt.Image image))return false;
            check(disk.getWidth()==MC.getMainRenderTarget().width&&disk.getHeight()==MC.getMainRenderTarget().height&&image.getWidth(null)==disk.getWidth(),"Physical F2 copies an OS image and retains vanilla full-resolution screenshot saving");
            var clipboardPixels=new java.awt.image.BufferedImage(disk.getWidth(),disk.getHeight(),java.awt.image.BufferedImage.TYPE_INT_RGB);var graphics=clipboardPixels.createGraphics();try{graphics.drawImage(image,0,0,null);}finally{graphics.dispose();}boolean same=true;for(int y=0;y<disk.getHeight();y++)for(int x=0;x<disk.getWidth();x++)same&=(disk.getRGB(x,y)&0xffffff)==(clipboardPixels.getRGB(x,y)&0xffffff);check(same,"Physical F2 clipboard RGB pixels exactly match the original saved screenshot");
            check(selected()==null&&noAutoSend(),"F2 neither stages nor sends the copied screenshot automatically");key(KeyEvent.VK_T);next(7);
        }
        case 7 -> {if(!(MC.screen instanceof ChatScreen))return false;chord(KeyEvent.VK_V);next(8);}
        case 8 -> {if(loading()||selected()==null)return false;if(!cardMouse(false))return false;check(selected().bytes().length<=RelayRecord.FILE_BYTES&&selected().media().width()<=1024&&selected().media().height()<=1024,"F2 image paste is resized/encoded inside bridge byte and dimension limits");digest=selected().digest();next(9);}
        case 9 -> {if(!(Boolean)get(NativeComposer.class,"spoiler"))return false;check(!(Boolean)invoke(NativeComposer.class,"previewVisible"),"Real spoiler-card click conceals the local image without auto-send");mouse(MC.screen.width-14,MC.screen.height-9);next(10);}
        case 10 -> {if(!(Boolean)get(NativeInteractions.class,"open"))return false;if(++ticks<4)return false;capture("Native-X11-F2-picker-card.png");int x=Math.max(4,MC.screen.width-Math.min(350,MC.screen.width-8)-5),y=MC.screen.height-22-Math.min(260,MC.screen.height-28);mouse(x+100,y+45);textClipboard("grinning");chord(KeyEvent.VK_V);next(11);}
        case 11 -> {if(!((EditBox)get(NativeInteractions.class,"search")).getValue().equals("grinning"))return false;check(input().getValue().isEmpty()&&selected().digest().equals(digest),"Picker search uses normal text paste without mutating the draft or staged image");capture("Native-picker-search-X11.png");key(KeyEvent.VK_ESCAPE);next(12);}
        case 12 -> {if((Boolean)get(NativeInteractions.class,"open"))return false;input().setValue("os-clipboard-native");key(KeyEvent.VK_ENTER);next(13);}
        case 13 -> {
            var delivered=records().stream().filter(r->r.author().equals("Alice")&&r.text().equals("os-clipboard-native")&&!r.attachments().isEmpty()).toList();if(delivered.isEmpty()||sending()||selected()!=null||records().stream().noneMatch(r->r.author().equals("TEST peer (not Discord)")&&r.text().contains("os-clipboard-native")))return false;
            check(delivered.size()==1&&delivered.get(0).attachments().get(0).digest().equals(digest)&&delivered.get(0).attachments().get(0).name().startsWith("SPOILER_"),"Real OS-pasted F2 screenshot traverses authoritative packets once with spoiler metadata");MC.setScreen(new ChatScreen(""));next(14);
        }
        case 14 -> {if(++ticks<4)return false;capture("Native-X11-spoiler-received.png");input().setValue("");scrollActor(ActorRef.Kind.DISCORD);next(15);}
        case 15 -> {if(++ticks<4)return false;clickActor(ActorRef.Kind.DISCORD);next(16);}
        case 16 -> {
            if(!input().getValue().startsWith(target.label()))return false;
            List<Mention> mentions=mentionSnapshot(input().getValue());check(mentions.size()==1&&mentions.get(0).target().id().equals(target.id()),"Real Discord author-name click inserts D@display bound to stable server identity");
            replacedDraft=input().getValue();textClipboard(replacedDraft);chord(KeyEvent.VK_A);next(27);
        }
        case 27 -> {chord(KeyEvent.VK_V);next(17);}
        case 17 -> {if(++ticks<3)return false;check(input().getValue().equals(replacedDraft),"Actual physical Ctrl+A/Ctrl+V completes identical-text replacement");check(mentionSnapshot(input().getValue()).isEmpty(),"Pasting identical display text over a selection cannot inherit a real mention token");input().setValue("");scrollActor(ActorRef.Kind.DISCORD);next(18);}
        case 18 -> {if(++ticks<3)return false;clickActor(ActorRef.Kind.DISCORD);next(19);}
        case 19 -> {if(!input().getValue().startsWith(target.label()))return false;beforeEdit();input().insertText("hello-stable");key(KeyEvent.VK_ENTER);next(20);}
        case 20 -> {
            var found=records().stream().filter(r->r.author().equals("Alice")&&r.text().contains("hello-stable")).toList();if(found.isEmpty()||sending()||records().stream().noneMatch(r->r.author().equals("TEST peer (not Discord)")&&r.text().contains("hello-stable")))return false;
            check(found.size()==1&&found.get(0).mentions().size()==1&&found.get(0).mentions().get(0).target().id().equals(target.id()),"Selected Discord mention survives native edit/send and server validation");MC.setScreen(new ChatScreen(""));scrollActor(ActorRef.Kind.MINECRAFT);next(21);
        }
        case 21 -> {if(++ticks<3)return false;clickActor(ActorRef.Kind.MINECRAFT);next(22);}
        case 22 -> {if(!input().getValue().startsWith(target.label()))return false;check(target.id().equals(MC.player.getUUID().toString())&&mentionSnapshot(input().getValue()).get(0).target().kind()==ActorRef.Kind.MINECRAFT,"Minecraft author click inserts M@playerId bound to server profile UUID");beforeEdit();input().insertText("hello-minecraft");key(KeyEvent.VK_ENTER);next(23);}
        case 23 -> {if(sending()||records().stream().noneMatch(r->r.text().contains("hello-minecraft")&&!r.mentions().isEmpty()))return false;var bad=new ActorRef(ActorRef.Kind.DISCORD,"9999999999999999","測試成員");request=MultiplayerAcceptance.raw(new RelayRecord(UUID.randomUUID(),"draft",bad.label(),List.of(),Map.of(),Map.of(),null,List.of(new Mention(0,bad.label().length(),bad))));next(24);}
        case 24 -> {if(!request.isDone())return false;check(request.isCompletedExceptionally(),"Actual server rejects an untrusted Discord target despite matching visible display");var bad=new ActorRef(ActorRef.Kind.MINECRAFT,UUID.randomUUID().toString(),"Alice");request=MultiplayerAcceptance.raw(new RelayRecord(UUID.randomUUID(),"draft",bad.label(),List.of(),Map.of(),Map.of(),null,List.of(new Mention(0,bad.label().length(),bad))));next(25);}
        case 25 -> {if(!request.isDone())return false;check(request.isCompletedExceptionally(),"Actual server rejects a spoofed Minecraft mention UUID");request=MultiplayerAcceptance.raw(new RelayRecord(UUID.randomUUID(),"draft","forged-actor",List.of(),Map.of(),Map.of(),new ActorRef(ActorRef.Kind.MINECRAFT,MC.player.getUUID().toString(),"Alice"),List.of()));next(26);}
        case 26 -> {if(!request.isDone())return false;check(request.isCompletedExceptionally(),"Actual server rejects client-supplied sender actor metadata");var service=Class.forName("dev.discraft.forge.DesktopClipboardService");Process helper=get(service,"process");if(helper!=null)Files.writeString(OUT.resolve("clipboard-helper-pid.txt"),Long.toString(helper.pid()));selectionRegression();MC.setScreen(null);key(KeyEvent.VK_T);next(28);}
        case 28 -> {if(!(MC.screen instanceof ChatScreen))return false;check(emptyNativeRoute()&&selected()==null,"Physical normal T opens with ordinary native routing and no staged file");scrollActor(ActorRef.Kind.DISCORD);next(29);}
        case 29 -> {if(++ticks<3)return false;clickActor(ActorRef.Kind.DISCORD);next(30);}
        case 30 -> {if(!input().getValue().startsWith(target.label()))return false;key(KeyEvent.VK_ENTER);next(31);}
        case 31 -> {if(ordinaryContains(target.label()))throw new AssertionError("Enter-only mention leaked to normal Minecraft chat");var delivered=records().stream().filter(r->r.author().equals("Alice")&&r.text().equals(target.label())).toList();if(sending()||delivered.isEmpty()||records().stream().noneMatch(r->r.author().equals("TEST peer (not Discord)")&&r.text().equals("Peer received: "+target.label())))return false;check(delivered.size()==1&&delivered.get(0).mentions().size()==1&&delivered.get(0).mentions().get(0).target().equals(target),"Physical normal T author-click Enter-only routes once with exact stable mention ID");key(KeyEvent.VK_T);next(32);}
        case 32 -> {if(!(MC.screen instanceof ChatScreen))return false;check(emptyNativeRoute(),"Whitespace regression starts with ordinary native routing");input().setValue("  prefix   ");input().moveCursorToEnd();scrollActor(ActorRef.Kind.DISCORD);next(33);}
        case 33 -> {if(++ticks<3)return false;clickActor(ActorRef.Kind.DISCORD);next(34);}
        case 34 -> {if(!input().getValue().contains(target.label()))return false;key(KeyEvent.VK_ENTER);next(35);}
        case 35 -> {String expected="prefix "+target.label();if(ordinaryContains(expected))throw new AssertionError("Normalized mention leaked to normal Minecraft chat");var delivered=records().stream().filter(r->r.author().equals("Alice")&&r.text().equals(expected)).toList();if(sending()||delivered.isEmpty())return false;check(delivered.size()==1&&delivered.get(0).mentions().size()==1&&delivered.get(0).mentions().get(0).start()==7&&delivered.get(0).mentions().get(0).target().equals(target),"Physical normal T leading/doubled spaces preserve normalized wire text and exact target ID");cleanup();return true;}
        default -> throw new AssertionError("Unknown OS UX phase");
        }return false;
    }
    private record NamePoint(int line,int x,ActorRef actor) {}
    @SuppressWarnings("unchecked") private static NamePoint namePoint(ActorRef.Kind kind)throws Exception{
        var lines=(List<GuiMessage.Line>)ObfuscationReflectionHelper.findField(ChatComponent.class,"f_93761_").get(MC.gui.getChat());Map<ClickEvent,ActorRef> names=get(NativeChat.class,"nameEvents");
        for(int n=0;n<lines.size();n++){final int[] x={0};final NamePoint[] result={null};final int index=n;lines.get(n).content().accept((j,style,point)->{var actor=names.get(style.getClickEvent());if(actor!=null&&actor.kind()==kind)result[0]=new NamePoint(index,x[0],actor);x[0]+=MC.font.width(Component.literal(new String(Character.toChars(point))).setStyle(style));return result[0]==null;});if(result[0]!=null)return result[0];}throw new AssertionError("No native author of kind "+kind);
    }
    private static void scrollActor(ActorRef.Kind kind)throws Exception{var p=namePoint(kind);int scroll=ObfuscationReflectionHelper.findField(ChatComponent.class,"f_93763_").getInt(MC.gui.getChat());MC.gui.getChat().scrollChat(p.line()-2-scroll);}
    private static void clickActor(ActorRef.Kind kind)throws Exception{var p=namePoint(kind);target=p.actor();var chat=MC.gui.getChat();int scroll=ObfuscationReflectionHelper.findField(ChatComponent.class,"f_93763_").getInt(chat);double scale=chat.getScale();int band=(int)(9*(MC.options.chatLineSpacing().get()+1)),offset=(int)Math.round(-8*(MC.options.chatLineSpacing().get()+1)+4*MC.options.chatLineSpacing().get());int bottom=(int)Math.floor((MC.getWindow().getGuiScaledHeight()-40)/scale);int cx=get(NativeChat.class,"chatX"),cy=get(NativeChat.class,"chatY");double x=cx+scale*(4+p.x()+3),y=(cy-MC.getWindow().getGuiScaledHeight()+40)/scale+scale*(bottom-(p.line()-scroll)*band+offset+4);var hit=NativeChat.class.getDeclaredMethod("actorAt",double.class,double.class);hit.setAccessible(true);Object actual=hit.invoke(null,x,y);if(!target.equals(actual))throw new AssertionError("Native author coordinate mismatch: expected="+kind+" hit="+actual+" line="+p.line()+" scroll="+scroll+" x="+x+" y="+y);System.out.println("NATIVE_UX click="+kind+" line="+p.line()+" scroll="+scroll+" x="+x+" y="+y);mouse(x,y);}
    @SuppressWarnings("unchecked") private static boolean ordinaryContains(String text)throws Exception{var messages=(List<GuiMessage>)ObfuscationReflectionHelper.findField(ChatComponent.class,"f_93760_").get(MC.gui.getChat());Map<UUID,Component> enhanced=get(NativeChat.class,"components");var owned=Collections.newSetFromMap(new IdentityHashMap<Component,Boolean>());owned.addAll(enhanced.values());return messages.stream().anyMatch(m->!owned.contains(m.content())&&m.content().getString().contains("<Alice> "+text));}
    private static void selectionRegression()throws Exception{
        if(!(MC.screen instanceof ChatScreen))MC.setScreen(new ChatScreen(""));
        var box=input();var first=new ActorRef(ActorRef.Kind.DISCORD,"2234567890123456","Same");var second=new ActorRef(ActorRef.Kind.DISCORD,"3234567890123456","Same");var adapter=Class.forName("dev.discraft.forge.DraftMentions");var insert=adapter.getDeclaredMethod("insert",EditBox.class,ActorRef.class);insert.setAccessible(true);box.setValue("");insert.invoke(null,box,first);box.setCursorPosition(0);box.setHighlightPos(0);insert.invoke(null,box,second);var tokens=mentionSnapshot(box.getValue());check(tokens.size()==2&&tokens.get(0).target().equals(second)&&tokens.get(1).target().equals(first),"Actual native EditBox same-display prepend preserves both selected Discord IDs");
        box.setCursorPosition(0);box.setHighlightPos(0);beforeEdit();box.insertText(first.label()+" ");tokens=mentionSnapshot(box.getValue());check(tokens.size()==2&&tokens.get(0).start()==first.label().length()+1&&tokens.get(0).target().equals(second)&&tokens.get(1).target().equals(first),"Actual native EditBox identical pasted prefix cannot acquire an existing mention binding");box.setValue("");
    }
    private static void beforeEdit()throws Exception{var c=Class.forName("dev.discraft.forge.DraftMentions");var m=c.getDeclaredMethod("beforeEdit",EditBox.class);m.setAccessible(true);m.invoke(null,input());}
    @SuppressWarnings("unchecked") private static List<Mention> mentionSnapshot(String text)throws Exception{var c=Class.forName("dev.discraft.forge.DraftMentions");var m=c.getDeclaredMethod("snapshot",String.class);m.setAccessible(true);return (List<Mention>)m.invoke(null,text);}
    @SuppressWarnings("unchecked") private static <T>T get(Class<?> c,String n)throws Exception{var f=c.getDeclaredField(n);f.setAccessible(true);return (T)f.get(null);}
    private static Object invoke(Class<?> c,String n)throws Exception{var m=c.getDeclaredMethod(n);m.setAccessible(true);return m.invoke(null);}
}
