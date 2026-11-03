package dev.discraft.forge;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.discraft.core.MediaAsset;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

/**
 * Full-size viewer for an image, sticker or GIF clicked in chat. It is an overlay on the open chat screen, not a new
 * screen, so the half-typed draft, selected mentions and the staged attachment all survive. It only ever shows media the
 * client already decoded and validated for chat; nothing is fetched and no URL is involved.
 */
@Mod.EventBusSubscriber(modid=DisCraft.ID,value=Dist.CLIENT)
public final class MediaViewer {
    static final double MIN_ZOOM=.25,MAX_ZOOM=16,MAX_FIT=16;
    private static final Minecraft MC=Minecraft.getInstance();
    private static final TextureCache textures=new TextureCache(true);
    private static final long started=System.nanoTime();
    private static MediaAsset media;
    private static String name="",key="";
    private static double zoom=1,panX,panY,pressX,pressY;
    private static boolean pressing,moved;
    private static long lastPress;
    private MediaViewer(){}

    static boolean isOpen(){return media!=null;}
    static void open(String digest,String label,MediaAsset asset){
        media=asset;key=digest;name=label==null?"":label;resetView();pressing=false;moved=false;
    }
    public static void close(){
        if(media==null)return;
        media=null;pressing=false;
        // Textures belong to the render thread; close() can also run from a disconnect on the client thread.
        MC.execute(textures::close);
    }
    private static void resetView(){zoom=1;panX=0;panY=0;}

    /** Largest scale that fits the window with margins; small stickers scale up (bounded) so they are actually legible. */
    static double fit(int windowWidth,int windowHeight,int mediaWidth,int mediaHeight){
        double room=Math.min((windowWidth-40)/(double)mediaWidth,(windowHeight-84)/(double)mediaHeight);
        return Math.max(.01,Math.min(MAX_FIT,room));
    }
    static double clampZoom(double value){return Double.isFinite(value)?Math.max(MIN_ZOOM,Math.min(MAX_ZOOM,value)):1;}
    private static int closeX(int width){return width-28;}

    @SubscribeEvent(priority=EventPriority.LOWEST) public static void render(ScreenEvent.Render.Post event){
        if(media==null)return;
        if(!(event.getScreen() instanceof ChatScreen)){close();return;}
        GuiGraphics g=event.getGuiGraphics();int w=g.guiWidth(),h=g.guiHeight();
        textures.begin();g.pose().pushPose();
        try{
            g.pose().translate(0,0,800);
            g.fill(0,0,w,h,0xe0101014);
            double scale=fit(w,h,media.width(),media.height())*zoom;
            int dw=Math.max(1,(int)Math.round(media.width()*scale)),dh=Math.max(1,(int)Math.round(media.height()*scale));
            int x=(int)Math.round((w-dw)/2.0+panX),y=(int)Math.round((h-34-dh)/2.0+panY);
            g.fill(x-1,y-1,x+dw+1,y+dh+1,0xff3b3d44);g.fill(x,y,x+dw,y+dh,0xff1e1f22);
            var texture=textures.frame(key,media,(System.nanoTime()-started)/1_000_000);
            RenderSystem.enableBlend();g.blit(texture,x,y,dw,dh,0,0,media.width(),media.height(),media.width(),media.height());
            g.fill(0,h-28,w,h,0xf0101018);
            String info=name+" · "+media.width()+"×"+media.height()+(media.animated()?" · 動畫 "+media.frames().size()+" 幀":"")+" · "+Math.round(scale*100)+"%";
            UiText.draw(g,UiText.fit(info,w-24),12,h-24,0xffeeeeee);
            String hint="滾輪縮放 · 拖曳移動 · 雙擊或 R 重置 · Esc 關閉";
            if(UiText.font().width(info)+UiText.font().width(hint)+48<w)UiText.draw(g,hint,w-UiText.font().width(hint)-12,h-24,0xffb5bac1);
            int cx=closeX(w);boolean over=event.getMouseX()>=cx&&event.getMouseX()<cx+20&&event.getMouseY()>=8&&event.getMouseY()<28;
            g.fill(cx,8,cx+20,28,over?0xff5865f2:0xff404249);g.drawString(MC.font,"×",cx+7,14,0xffffffff,false);
        }finally{g.pose().popPose();textures.end();}
    }

    @SubscribeEvent(priority=EventPriority.HIGHEST) public static void press(ScreenEvent.MouseButtonPressed.Pre event){
        if(media==null)return;event.setCanceled(true);
        if(event.getButton()!=0)return;
        double x=event.getMouseX(),y=event.getMouseY();int w=MC.getWindow().getGuiScaledWidth();
        if(x>=closeX(w)&&x<closeX(w)+20&&y>=8&&y<28){close();return;}
        long now=System.nanoTime();
        if(now-lastPress<350_000_000L&&Math.abs(x-pressX)<6&&Math.abs(y-pressY)<6){resetView();lastPress=0;pressing=false;return;}
        lastPress=now;pressX=x;pressY=y;pressing=true;moved=false;
    }
    @SubscribeEvent(priority=EventPriority.HIGHEST) public static void drag(ScreenEvent.MouseDragged.Pre event){
        if(media==null)return;event.setCanceled(true);
        if(!pressing||event.getMouseButton()!=0)return;
        panX+=event.getDragX();panY+=event.getDragY();
        if(Math.abs(event.getMouseX()-pressX)>3||Math.abs(event.getMouseY()-pressY)>3)moved=true;
    }
    @SubscribeEvent(priority=EventPriority.HIGHEST) public static void release(ScreenEvent.MouseButtonReleased.Pre event){
        if(media==null)return;event.setCanceled(true);
        if(event.getButton()!=0||!pressing)return;pressing=false;
        // A plain click on the dim backdrop (outside the picture) dismisses the viewer, like most image lightboxes.
        if(!moved){
            int w=MC.getWindow().getGuiScaledWidth(),h=MC.getWindow().getGuiScaledHeight();double scale=fit(w,h,media.width(),media.height())*zoom;
            double dw=media.width()*scale,dh=media.height()*scale,x=(w-dw)/2+panX,y=(h-34-dh)/2+panY;
            boolean inside=event.getMouseX()>=x&&event.getMouseX()<x+dw&&event.getMouseY()>=y&&event.getMouseY()<y+dh;
            if(!inside)close();
        }
    }
    @SubscribeEvent(priority=EventPriority.HIGHEST) public static void scroll(ScreenEvent.MouseScrolled.Pre event){
        if(media==null)return;event.setCanceled(true);
        zoom=clampZoom(zoom*Math.pow(1.2,event.getScrollDelta()));
    }
    @SubscribeEvent(priority=EventPriority.HIGHEST) public static void key(ScreenEvent.KeyPressed.Pre event){
        if(media==null)return;event.setCanceled(true);
        switch(event.getKeyCode()){
            case GLFW.GLFW_KEY_ESCAPE,GLFW.GLFW_KEY_ENTER,GLFW.GLFW_KEY_KP_ENTER,GLFW.GLFW_KEY_SPACE->close();
            case GLFW.GLFW_KEY_R,GLFW.GLFW_KEY_0,GLFW.GLFW_KEY_KP_0->resetView();
            case GLFW.GLFW_KEY_EQUAL,GLFW.GLFW_KEY_KP_ADD->zoom=clampZoom(zoom*1.25);
            case GLFW.GLFW_KEY_MINUS,GLFW.GLFW_KEY_KP_SUBTRACT->zoom=clampZoom(zoom/1.25);
            default->{}
        }
    }
    @SubscribeEvent(priority=EventPriority.HIGHEST) public static void typed(ScreenEvent.CharacterTyped.Pre event){if(media!=null)event.setCanceled(true);}
    @SubscribeEvent public static void closing(ScreenEvent.Closing event){close();}

    /** Discoverability: a small hint while the pointer is over a clickable chat image. */
    @SubscribeEvent(priority=EventPriority.LOW) public static void hint(ScreenEvent.Render.Post event){
        if(media!=null||!(event.getScreen() instanceof ChatScreen))return;
        var target=NativeChat.viewAt(event.getMouseX(),event.getMouseY(),Screen.hasShiftDown());
        if(target!=null)UiText.tooltip(event.getGuiGraphics(),"點擊放大檢視 · "+target.name(),event.getMouseX(),event.getMouseY());
    }
}
