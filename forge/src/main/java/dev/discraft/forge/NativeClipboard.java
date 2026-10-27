package dev.discraft.forge;

import com.mojang.blaze3d.platform.InputConstants;
import dev.discraft.core.Attachment;
import dev.discraft.forge.bridge.RelayRecord;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.*;
import net.minecraftforge.eventbus.api.*;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;
import java.awt.*;
import java.awt.datatransfer.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;

/** OS clipboard access occurs only for an explicit paste or a physically held screenshot key. */
@Mod.EventBusSubscriber(modid=DisCraft.ID,value=Dist.CLIENT)
public final class NativeClipboard {
    private static final Minecraft MC=Minecraft.getInstance();
    @SubscribeEvent(priority=EventPriority.HIGHEST) public static void paste(ScreenEvent.KeyPressed.Pre event){
        if(!(event.getScreen() instanceof ChatScreen)||event.getKeyCode()!=GLFW.GLFW_KEY_V||!Screen.hasControlDown())return;
        try{
            byte[] png=DesktopClipboardService.read();
            if(png==null)return; // Vanilla retains text paste, including command editing.
            event.setCanceled(true);NativeInteractions.closePicker();NativeComposer.stage(()->Attachment.of("clipboard.png",png));
        }catch(Exception|LinkageError unavailable){notice("系統圖片剪貼簿無法使用；文字貼上仍使用原版。");}
    }

    @SubscribeEvent public static void screenshot(ScreenshotEvent event){
        // This excludes programmatic screenshot captures and never polls clipboard or screen pixels.
        if(!InputConstants.isKeyDown(MC.getWindow().getWindow(),GLFW.GLFW_KEY_F2)||!ClientPreferences.SCREENSHOT_CLIPBOARD.get())return;
        try{
            var nativeImage=event.getImage();double scale=Math.min(1,Math.min(2048d/nativeImage.getWidth(),2048d/nativeImage.getHeight()));
            int w=Math.max(1,(int)(nativeImage.getWidth()*scale)),h=Math.max(1,(int)(nativeImage.getHeight()*scale));var copy=new BufferedImage(w,h,BufferedImage.TYPE_INT_ARGB);
            for(int y=0;y<h;y++)for(int x=0;x<w;x++){int color=nativeImage.getPixelRGBA(Math.min(nativeImage.getWidth()-1,(int)(x/scale)),Math.min(nativeImage.getHeight()-1,(int)(y/scale)));copy.setRGB(x,y,(color&0xff00ff00)|(color&255)<<16|(color>>>16&255));}
            var bytes=new ByteArrayOutputStream();ImageIO.write(copy,"png",bytes);copy.flush();DesktopClipboardService.write(bytes.toByteArray());
            notice(scale<1?"截圖已複製至圖片剪貼簿（剪貼簿縮至 2048 像素；原檔維持原尺寸）。尚未傳送。":"截圖已複製至圖片剪貼簿，尚未傳送。");
        }catch(Exception|LinkageError unavailable){notice("此平台無法複製圖片剪貼簿；原版截圖仍照常儲存。");}
        // Do not cancel or replace ScreenshotEvent: vanilla saves its original image and message.
    }
    private static void notice(String text){MC.execute(()->MC.gui.getChat().addMessage(Component.literal("[DisCraft] "+text)));}
}
