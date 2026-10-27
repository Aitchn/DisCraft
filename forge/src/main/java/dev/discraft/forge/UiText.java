package dev.discraft.forge;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.font.FontSet;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;
import java.util.function.Function;

/** Scoped vector UI font. Uses Minecraft's resource reload, glyph atlas, clipping and text metrics. */
public final class UiText {
    public static final ResourceLocation ID=new ResourceLocation(DisCraft.ID,"ui");
    public static final int HEIGHT=14;
    private static Font font;
    private UiText(){}
    public static Font font(){
        if(font==null){
            // Delegate to the existing manager, so resource reloads never retain an obsolete FontSet.
            Function<ResourceLocation,FontSet> manager=ObfuscationReflectionHelper.getPrivateValue(Font.class,Minecraft.getInstance().font,"f_92713_");
            font=new Font(ignored->manager.apply(ID),false);
        }
        return font;
    }
    public static String fit(String text,int width){if(font().width(text)<=width)return text;String suffix="…";int room=width-font().width(suffix);return room<0?"":font().plainSubstrByWidth(text,room)+suffix;}
    public static void draw(GuiGraphics graphics,String text,int x,int y,int color){graphics.drawString(font(),text,x,y+2,color,false);}
    public static void centered(GuiGraphics graphics,String text,int center,int y,int color){draw(graphics,text,center-font().width(text)/2,y,color);}
    public static void tooltip(GuiGraphics graphics,String text,int mouseX,int mouseY){
        int width=Math.min(graphics.guiWidth()-8,font().width(text)+12),height=HEIGHT+10;
        int x=Math.max(4,Math.min(mouseX+10,graphics.guiWidth()-width-4)),y=Math.max(4,Math.min(mouseY-height-4,graphics.guiHeight()-height-4));
        graphics.pose().pushPose();graphics.pose().translate(0,0,400);graphics.fill(x,y,x+width,y+height,0xf0101018);graphics.fill(x,y,x+width,y+1,0xff777a90);draw(graphics,fit(text,width-12),x+6,y+4,0xffeeeeee);graphics.pose().popPose();
    }
}
