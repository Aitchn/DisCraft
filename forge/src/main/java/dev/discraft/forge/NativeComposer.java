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

/** Adds explicit bridge routing and local attachment controls to vanilla T chat. Slash commands retain vanilla routing. */
@Mod.EventBusSubscriber(modid=DisCraft.ID,value=Dist.CLIENT)
public final class NativeComposer {
    private static final Minecraft MC=Minecraft.getInstance();
    private static final ThreadPoolExecutor worker=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(2),r->{var t=new Thread(r,"DisCraft native composer");t.setDaemon(true);return t;});
    private static boolean bridge,sending;
    private static Attachment attachment;
    private static long epoch,revision;
    private static String filename="";
    public static void reset(){epoch++;revision++;bridge=false;sending=false;attachment=null;filename="";worker.getQueue().clear();}
    public static void openBridge(){bridge=ClientBridge.available();MC.setScreen(new ChatScreen(""));}
    private static Component target(){return Component.literal(bridge?"To: bridge":"To: Minecraft");}
    private static void notice(String text){MC.gui.getChat().addMessage(Component.literal("[DisCraft] "+text));}
    @SubscribeEvent public static void init(ScreenEvent.Init.Post event){
        if(!(event.getScreen() instanceof ChatScreen screen))return;
        var route=Button.builder(target(),button->{bridge=!bridge;button.setMessage(target());}).bounds(4,screen.height-36,86,18).build();
        route.active=ClientBridge.available();event.addListener(route);
        int attachX=Math.min(238,screen.width-90);
        var file=new EditBox(MC.font,94,screen.height-36,attachX-98,18,Component.literal("Attachment filename"));
        file.setMaxLength(120);file.setHint(Component.literal("file.png / file.gif"));file.setValue(filename);file.setResponder(value->filename=value);event.addListener(file);
        event.addListener(Button.builder(Component.literal("Attach"),button->{
            long version=++revision,connection=epoch;String name=file.getValue();
            try{worker.execute(()->{
                try{var root=MC.gameDirectory.toPath().resolve("discraft-uploads");java.nio.file.Files.createDirectories(root);var decoded=Attachment.fromUpload(root,name);
                    if(decoded.bytes().length>dev.discraft.forge.bridge.RelayRecord.FILE_BYTES)throw new IllegalArgumentException();
                    MC.execute(()->{if(epoch==connection&&revision==version){attachment=decoded;notice("Attached "+decoded.name()+". Select To: bridge, then send with Enter.");}});
                }catch(Exception invalid){MC.execute(()->{if(epoch==connection&&revision==version)notice("Attachment rejected. Use a PNG/JPEG/GIF filename in discraft-uploads (max 256 KiB).");});}
            });}catch(RejectedExecutionException full){notice("Attachment worker busy; try again later.");}
        }).bounds(attachX,screen.height-36,42,18).build());
        event.addListener(Button.builder(Component.literal("Clear"),button->{revision++;attachment=null;notice("Attachment cleared.");}).bounds(attachX+46,screen.height-36,40,18).build());
    }
    @SubscribeEvent public static void sendingKeys(ScreenEvent.KeyPressed.Pre event){
        if(!bridge||!sending||!(event.getScreen() instanceof ChatScreen screen)||(event.getKeyCode()!=GLFW.GLFW_KEY_ENTER&&event.getKeyCode()!=GLFW.GLFW_KEY_KP_ENTER))return;
        var input=screen.children().stream().filter(w->w instanceof EditBox).map(w->(EditBox)w).findFirst();
        if(input.isPresent()&&!input.get().getValue().startsWith("/"))event.setCanceled(true);
    }
    @SubscribeEvent public static void chat(ClientChatEvent event){
        if(!bridge||event.getMessage().startsWith("/"))return;
        event.setCanceled(true);if(sending){notice("A bridge message is still awaiting acknowledgement.");return;}
        if(!ClientBridge.available()){notice("Server bridge unavailable. Switch To: Minecraft for ordinary chat.");return;}
        String text=event.getMessage();Attachment file=attachment;long connection=epoch,version=revision;sending=true;
        try{worker.execute(()->{
            try{ClientBridge.send(text,file);MC.execute(()->{if(epoch==connection&&revision==version&&attachment==file){attachment=null;revision++;}});}
            catch(Exception failed){MC.execute(()->{if(epoch==connection)notice("Bridge send rejected or delivery uncertain; no automatic retry. Draft remains in vanilla input history.");});}
            finally{MC.execute(()->{if(epoch==connection)sending=false;});}
        });}catch(RejectedExecutionException full){sending=false;notice("Bridge worker busy; message was not sent.");}
    }
}
