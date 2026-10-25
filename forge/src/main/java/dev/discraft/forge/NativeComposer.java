package dev.discraft.forge;

import dev.discraft.core.Attachment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.ChatScreen;
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
    private static boolean bridge,sending,loading;
    private static Attachment attachment;
    private static long epoch,revision;
    private static String filename="";

    public static void reset(){epoch++;clear();bridge=false;sending=false;filename="";worker.getQueue().clear();}
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
        revision++;attachment=null;loading=false;
        if(decoding!=null){decoding.cancel(true);decoding=null;worker.purge();}
    }
    private static void attach(String name){
        clear();loading=true;long version=revision,connection=epoch;
        try{decoding=worker.submit(()->{
            try{
                var decoded=decoder.apply(name);
                if(decoded.bytes().length>dev.discraft.forge.bridge.RelayRecord.FILE_BYTES)throw new IllegalArgumentException();
                MC.execute(()->{if(epoch==connection&&revision==version){loading=false;decoding=null;attachment=decoded;notice("Attached "+decoded.name()+". Select To: bridge, then send with Enter.");}});
            }catch(Exception invalid){MC.execute(()->{if(epoch==connection&&revision==version){loading=false;decoding=null;notice("Attachment rejected. Use a PNG/JPEG/GIF filename in discraft-uploads (max 256 KiB).");}});}
        });}catch(RejectedExecutionException full){loading=false;notice("Attachment worker busy; try again later.");}
    }
    @SubscribeEvent public static void closing(ScreenEvent.Closing event){if(event.getScreen() instanceof ChatScreen&&loading)clear();}
    @SubscribeEvent public static void init(ScreenEvent.Init.Post event){
        if(!(event.getScreen() instanceof ChatScreen screen))return;
        var route=Button.builder(target(),button->{bridge=!bridge;button.setMessage(target());}).bounds(4,screen.height-36,86,18).build();
        route.active=ClientBridge.available();event.addListener(route);
        int attachX=Math.min(238,screen.width-90);
        var file=new EditBox(MC.font,94,screen.height-36,Math.max(1,attachX-98),18,Component.literal("Attachment filename"));
        file.setMaxLength(120);file.setHint(Component.literal("file.png / file.gif"));file.setValue(filename);file.setResponder(value->filename=value);event.addListener(file);
        event.addListener(Button.builder(Component.literal("Attach"),button->attach(file.getValue())).bounds(attachX,screen.height-36,42,18).build());
        event.addListener(Button.builder(Component.literal("Clear"),button->{clear();notice("Attachment cleared.");}).bounds(attachX+46,screen.height-36,40,18).build());
    }
    @SubscribeEvent public static void keys(ScreenEvent.KeyPressed.Pre event){
        if(!bridge||!(event.getScreen() instanceof ChatScreen screen)||(event.getKeyCode()!=GLFW.GLFW_KEY_ENTER&&event.getKeyCode()!=GLFW.GLFW_KEY_KP_ENTER))return;
        String text=input(screen).getValue();if(text.startsWith("/"))return;
        if(sending||loading){event.setCanceled(true);return;} // Preserve the draft until the selected decode/ACK finishes.
        if(text.isBlank()&&attachment!=null){
            // Vanilla rejects empty text before ClientChatEvent; an attachment-only submission needs its own path.
            event.setCanceled(true);if(send(""))MC.setScreen(null);
        }
    }
    @SubscribeEvent public static void chat(ClientChatEvent event){
        if(!bridge||event.getMessage().startsWith("/"))return;
        event.setCanceled(true);send(event.getMessage());
    }
    private static boolean send(String text){
        if(sending||loading){notice("Attachment validation or a bridge acknowledgement is still pending.");return false;}
        if(!ClientBridge.available()){notice("Server bridge unavailable. Switch To: Minecraft for ordinary chat.");return false;}
        Attachment file=attachment;long connection=epoch,version=revision;sending=true;
        try{worker.execute(()->{
            try{ClientBridge.send(text,file);MC.execute(()->{if(epoch==connection&&revision==version&&attachment==file){attachment=null;revision++;}});}
            catch(Exception failed){MC.execute(()->{if(epoch==connection)notice("Bridge send rejected or delivery uncertain; no automatic retry. Draft remains in vanilla input history.");});}
            finally{MC.execute(()->{if(epoch==connection)sending=false;});}
        });return true;}catch(RejectedExecutionException full){sending=false;notice("Bridge worker busy; message was not sent.");return false;}
    }
}
