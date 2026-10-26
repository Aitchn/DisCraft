package dev.discraft.forge;

import dev.discraft.core.Attachment;
import dev.discraft.forge.bridge.ActorRef;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.*;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;
import java.util.concurrent.*;
import java.util.function.Function;

/** Explicit bridge routing and revision-scoped local attachments on vanilla T chat. */
@Mod.EventBusSubscriber(modid=DisCraft.ID,value=Dist.CLIENT)
public final class NativeComposer {
    private static final Minecraft MC=Minecraft.getInstance();
    private static final ThreadPoolExecutor worker=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(2),r->{var t=new Thread(r,"DisCraft native composer");t.setDaemon(true);return t;});
    private static Function<String,Attachment> decoder=NativeComposer::decodeUpload;
    private static Future<?> decoding;
    private static boolean bridge,sending,loading,spoiler,previewReveal;
    private static Attachment attachment;
    private static long epoch,revision;
    private static String filename="";

    public static void reset(){epoch++;clear();bridge=false;sending=false;filename="";worker.getQueue().clear();DraftMentions.clear();NativeInteractions.reset();}
    public static void openBridge(){
        if(MC.player==null||MC.getConnection()==null){
            bridge=false;if(!(MC.screen instanceof DisCraftScreen))MC.setScreen(new DisCraftScreen(MC.screen));return;
        }
        if(MC.screen instanceof ChatScreen)return; // F8 must not erase an already open native draft.
        bridge=ClientBridge.available();MC.setScreen(new ChatScreen(""));
    }
    private static Component target(){return Component.literal(bridge?"To: bridge":"To: Minecraft");}
    private static void notice(String text){MC.gui.getChat().addMessage(Component.literal("[DisCraft] "+text));}
    private static EditBox input(ChatScreen screen){return screen.children().stream().filter(w->w instanceof EditBox).map(w->(EditBox)w).findFirst().orElseThrow();}
    private static Attachment decodeUpload(String name){
        try{var root=MC.gameDirectory.toPath().resolve("discraft-uploads");java.nio.file.Files.createDirectories(root);return Attachment.fromUpload(root,name);}
        catch(Exception invalid){throw new CompletionException(invalid);}
    }
    private static void clear(){
        revision++;attachment=null;loading=false;spoiler=false;previewReveal=false;
        if(decoding!=null){decoding.cancel(true);decoding=null;worker.purge();}
    }
    private static void attach(String name){stage(()->decoder.apply(name));}
    static void stage(Callable<Attachment> source){
        clear();loading=true;long version=revision,connection=epoch;
        try{decoding=worker.submit(()->{
            try{
                var decoded=source.call();if(decoded.bytes().length>dev.discraft.forge.bridge.RelayRecord.FILE_BYTES)throw new IllegalArgumentException();
                MC.execute(()->{if(epoch==connection&&revision==version){loading=false;decoding=null;attachment=decoded;}});
            }catch(Exception invalid){MC.execute(()->{if(epoch==connection&&revision==version){loading=false;decoding=null;notice("Attachment rejected. 圖片驗證失敗或來源無法使用；尚未傳送。");}});}
        });}catch(RejectedExecutionException full){loading=false;notice("圖片工作忙碌，請稍後再試。");}
    }
    static Attachment selected(){return attachment;}
    static boolean validating(){return loading;}
    static boolean isSpoiler(){return spoiler;}
    static boolean previewVisible(){return !spoiler||previewReveal;}
    static void toggleSpoiler(){if(attachment!=null){spoiler=!spoiler;previewReveal=false;revision++;}}
    static void togglePreview(){if(spoiler)previewReveal=!previewReveal;}
    static void removeStaged(){clear();}
    static void insertMention(ActorRef actor){if(MC.screen instanceof ChatScreen screen){DraftMentions.insert(input(screen),actor);screen.setFocused(input(screen));input(screen).setFocused(true);}}
    static void insertEmoji(String value,boolean custom){if(MC.screen instanceof ChatScreen screen){DraftMentions.beforeEdit(input(screen));try{input(screen).insertText(value);}finally{DraftMentions.endEdit();}if(custom)bridge=true;}}
    @SubscribeEvent public static void closing(ScreenEvent.Closing event){if(event.getScreen() instanceof ChatScreen){if(loading)clear();bridge=false;DraftMentions.clear();NativeInteractions.closePicker();}}
    @SubscribeEvent public static void init(ScreenEvent.Init.Post event){if(event.getScreen() instanceof ChatScreen screen){DraftMentions.install(input(screen));NativeInteractions.attach(screen,event,input(screen));}}
    @SubscribeEvent(priority=net.minecraftforge.eventbus.api.EventPriority.HIGH) public static void editKeys(ScreenEvent.KeyPressed.Pre event){
        DraftMentions.endEdit();if(!(event.getScreen() instanceof ChatScreen screen)||!input(screen).isFocused())return;int key=event.getKeyCode();
        if(key==GLFW.GLFW_KEY_BACKSPACE||key==GLFW.GLFW_KEY_DELETE)DraftMentions.beforeEdit(input(screen),key==GLFW.GLFW_KEY_BACKSPACE?-1:1,Screen.hasControlDown());
        else if(Screen.hasControlDown()&&(key==GLFW.GLFW_KEY_V||key==GLFW.GLFW_KEY_X))DraftMentions.beforeEdit(input(screen));
    }
    @SubscribeEvent(priority=net.minecraftforge.eventbus.api.EventPriority.HIGH) public static void editCharacter(ScreenEvent.CharacterTyped.Pre event){DraftMentions.endEdit();if(event.getScreen() instanceof ChatScreen screen&&input(screen).isFocused())DraftMentions.beforeEdit(input(screen));}
    @SubscribeEvent public static void editsFinished(net.minecraftforge.event.TickEvent.ClientTickEvent event){if(event.phase==net.minecraftforge.event.TickEvent.Phase.END)DraftMentions.endEdit();}
    @SubscribeEvent public static void keys(ScreenEvent.KeyPressed.Pre event){
        if(!(event.getScreen() instanceof ChatScreen screen)||(event.getKeyCode()!=GLFW.GLFW_KEY_ENTER&&event.getKeyCode()!=GLFW.GLFW_KEY_KP_ENTER))return;
        String text=input(screen).getValue();String normalized=screen.normalizeChatMessage(text);if(normalized.startsWith("/")||(!bridge&&attachment==null&&!loading&&DraftMentions.snapshot(text).isEmpty()&&!(ClientBridge.available()&&normalized.contains("%item"))))return;
        if(sending||loading){event.setCanceled(true);return;} // Preserve the draft until the selected decode/ACK finishes.
        if(normalized.isEmpty()&&attachment!=null){
            // Vanilla rejects empty text before ClientChatEvent; an attachment-only submission needs its own path.
            event.setCanceled(true);if(send("",java.util.List.of()))MC.setScreen(null);
        }
    }
    @SubscribeEvent public static void chat(ClientChatEvent event){
        if(event.getMessage().startsWith("/"))return;
        String raw=MC.screen instanceof ChatScreen screen?input(screen).getValue():event.getMessage();var selected=DraftMentions.snapshot(raw);
        if(!bridge&&attachment==null&&!loading&&selected.isEmpty()&&!(ClientBridge.available()&&event.getMessage().contains("%item")))return;
        // Capture routing from the actual raw selection before vanilla's known whitespace transform.
        var mapped=dev.discraft.forge.bridge.MentionDraft.normalized(raw,selected,event.getMessage());
        event.setCanceled(true);send(event.getMessage(),mapped);
    }
    private static boolean send(String text,java.util.List<dev.discraft.forge.bridge.Mention> mentions){
        if(sending||loading){notice("Attachment validation or a bridge acknowledgement is still pending.");return false;}
        if(!ClientBridge.available()){notice("Server bridge unavailable. Switch To: Minecraft for ordinary chat.");return false;}
        Attachment file=attachment;boolean hidden=spoiler;long connection=epoch,version=revision;sending=true;
        try{worker.execute(()->{
            try{Attachment submitted=file;if(hidden&&file!=null&&!file.name().startsWith("SPOILER_")){String name=file.name();if(name.length()>112)name=name.substring(0,112);submitted=Attachment.of("SPOILER_"+name,file.bytes());}ClientBridge.send(text,submitted,mentions);MC.execute(()->{if(epoch==connection&&revision==version&&attachment==file){attachment=null;revision++;}});}
            catch(Exception failed){MC.execute(()->{if(epoch==connection)notice("Bridge send rejected or delivery uncertain; no automatic retry. Draft remains in vanilla input history.");});}
            finally{MC.execute(()->{if(epoch==connection)sending=false;});}
        });return true;}catch(RejectedExecutionException full){sending=false;notice("Bridge worker busy; message was not sent.");return false;}
    }
}
