package dev.discraft.forge;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.discraft.core.*;
import dev.discraft.forge.bridge.RelayRecord;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.*;
import net.minecraft.world.entity.player.ChatVisiblity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.*;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.*;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;
import java.lang.reflect.Field;
import java.util.*;

/** Native ChatComponent owns wrapping, scrolling, timestamps and history. Only reserved image cells are overlaid. */
@Mod.EventBusSubscriber(modid=DisCraft.ID,value=Dist.CLIENT)
public final class NativeChat {
    private static final Minecraft MC=Minecraft.getInstance();
    private static final Field LINES=ObfuscationReflectionHelper.findField(ChatComponent.class,"f_93761_");
    private static final Field SCROLL=ObfuscationReflectionHelper.findField(ChatComponent.class,"f_93763_");
    private record Tile(String digest,int width,int height,int x,int y,int cell) {}
    private static final Map<Integer,Tile> tiles=new HashMap<>();
    private static final Map<UUID,Set<Integer>> markers=new HashMap<>();
    private static final Map<String,Attachment> assets=new HashMap<>();
    private static final TextureCache textures=new TextureCache();
    private static int nextColor=0x100000,chatX,chatY;
    public static int renderedTiles;
    private static final long started=System.nanoTime();

    public static void reset(){assets.clear();tiles.clear();markers.clear();textures.close();NativeComposer.reset();}
    public static void receive(RelayRecord record,Collection<RelayRecord> retained){
        var ids=new HashSet<UUID>();assets.clear();
        for(var r:retained){ids.add(r.id());for(var a:r.attachments())assets.put(a.digest(),a);for(var a:r.emoji().values())assets.put(a.digest(),a);}
        markers.entrySet().removeIf(e->{if(ids.contains(e.getKey()))return false;e.getValue().forEach(tiles::remove);return true;});
        var owned=new HashSet<Integer>();markers.put(record.id(),owned);
        var component=Component.empty().append(Component.literal("[Bridge] "+record.author()+": ").withStyle(ChatFormatting.AQUA));
        for(var part:Content.parse(record.text(),record.catalog())){
            if(part instanceof Content.Text t)component.append(Component.literal(t.value()));
            else if(part instanceof Content.CustomEmoji e){var a=record.emoji().get(e.mediaKey());if(a!=null)cells(component,a,8,8,owned);else component.append(":"+e.name()+":");}
        }
        for(var a:record.attachments()){
            component.append(Component.literal("\n["+a.name()+"]").withStyle(ChatFormatting.GRAY));
            double scale=Math.min(96d/a.media().width(),54d/a.media().height());
            int width=Math.max(1,(int)Math.round(a.media().width()*scale)),height=Math.max(1,(int)Math.round(a.media().height()*scale));
            for(int y=0;y<height;y+=9){component.append("\n");row(component,a,width,height,y,owned);}
        }
        MC.gui.getChat().addMessage(component);
    }
    private static void cells(MutableComponent text,Attachment a,int width,int height,Set<Integer> owned){row(text,a,width,height,0,owned);}
    private static void row(MutableComponent text,Attachment a,int width,int height,int y,Set<Integer> owned){
        int cell=Math.max(1,MC.font.width("\u00a0"));
        for(int x=0;x<width;x+=cell){
            int color=nextColor++ & 0xffffff;owned.add(color);tiles.put(color,new Tile(a.digest(),width,height,x,y,cell));
            // NBSP reserves native glyph advance without a visible character or clickable metadata.
            text.append(Component.literal("\u00a0").withStyle(Style.EMPTY.withColor(color)));
        }
    }
    @SubscribeEvent(priority=EventPriority.LOWEST) public static void position(CustomizeGuiOverlayEvent.Chat event){chatX=event.getPosX();chatY=event.getPosY();}
    @SubscribeEvent public static void render(RenderGuiOverlayEvent.Post event){
        if(!event.getOverlay().id().equals(VanillaGuiOverlay.CHAT_PANEL.id()))return;
        renderedTiles=0;
        if(MC.options.hideGui||MC.options.chatVisibility().get()==ChatVisiblity.HIDDEN||assets.isEmpty())return;
        var chat=MC.gui.getChat();var graphics=event.getGuiGraphics();double scale=chat.getScale();
        try{
            @SuppressWarnings("unchecked") var lines=(List<GuiMessage.Line>)LINES.get(chat);int scroll=SCROLL.getInt(chat);
            int height=(int)(9*(MC.options.chatLineSpacing().get()+1));
            int offset=(int)Math.round(-8*(MC.options.chatLineSpacing().get()+1)+4*MC.options.chatLineSpacing().get());
            int bottom=(int)Math.floor((graphics.guiHeight()-40)/scale);
            boolean focused=MC.screen instanceof ChatScreen;
            final long elapsed=(System.nanoTime()-started)/1_000_000;
            textures.begin();graphics.pose().pushPose();
            graphics.pose().translate(chatX,(chatY-graphics.guiHeight()+40)/scale,0);graphics.pose().scale((float)scale,(float)scale,1);graphics.pose().translate(4,0,150);
            try{
                for(int n=0;n<chat.getLinesPerPage()&&n+scroll<lines.size();n++){
                    var line=lines.get(n+scroll);int age=MC.gui.getGuiTicks()-line.addedTime();
                    double fade=focused?1:Math.pow(Math.max(0,Math.min(1,(1-age/200d)*10)),2);
                    int alpha=(int)(255*fade*(MC.options.chatOpacity().get()*.9+.1));if(alpha<=3)continue;
                    final int y=bottom-n*height+offset;final float opacity=alpha/255f;final int[] x={0};
                    line.content().accept((index,style,point)->{
                        var color=style.getColor();var tile=point==160&&color!=null?tiles.get(color.getValue()):null;
                        if(tile!=null){var a=assets.get(tile.digest());if(a!=null){
                            var texture=textures.frame(a.digest(),a.media(),elapsed);
                            int dw=Math.min(tile.cell(),tile.width()-tile.x()),dh=Math.min(9,tile.height()-tile.y());
                            int sx=tile.x()*a.media().width()/tile.width(),sy=tile.y()*a.media().height()/tile.height();
                            int sw=Math.max(1,(tile.x()+dw)*a.media().width()/tile.width()-sx),sh=Math.max(1,(tile.y()+dh)*a.media().height()/tile.height()-sy);
                            RenderSystem.enableBlend();RenderSystem.setShaderColor(1,1,1,opacity);
                            graphics.blit(texture,x[0],y,dw,dh,sx,sy,sw,sh,a.media().width(),a.media().height());renderedTiles++;
                        }}
                        x[0]+=MC.font.width(Component.literal(new String(Character.toChars(point))).setStyle(style));return true;
                    });
                }
            }finally{RenderSystem.setShaderColor(1,1,1,1);graphics.pose().popPose();textures.end();}
        }catch(IllegalAccessException impossible){throw new IllegalStateException("Native chat access failed",impossible);}
    }
}
