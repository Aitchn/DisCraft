package dev.discraft.forge.smoke;

import dev.discraft.forge.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import java.util.*;

/** Reflection-only acceptance against the pinned published optional mod; not distributed. */
final class ChatPlusAcceptance {
    static boolean enabled(){return Boolean.getBoolean("discraft.chatPlusSmoke");}
    static Object singleton(String type)throws Exception{return Class.forName("com.ebicep.chatplus."+type).getField("INSTANCE").get(null);}
    static Object get(Object value,String getter)throws Exception{return value.getClass().getMethod(getter).invoke(value);}
    static void set(Object value,String setter,Class<?> type,Object argument)throws Exception{value.getClass().getMethod(setter,type).invoke(value,argument);}
    static void configure(int profile)throws Exception{
        if(!enabled())return;
        var mc=Minecraft.getInstance();var values=get(singleton("config.Config"),"getValues");
        set(values,"setEnabled",boolean.class,true);set(values,"setMovableChatEnabled",boolean.class,false);set(values,"setShowVanillaWhenUnfocused",boolean.class,false);set(values,"setVanillaInputBox",boolean.class,false);
        var input=get(values,"getInputBoxSettings");set(input,"setStartX",int.class,profile==0?28:18);set(input,"setStartY",int.class,mc.getWindow().getGuiScaledHeight()/2);set(input,"setWidth",int.class,Math.max(200,mc.getWindow().getGuiScaledWidth()-70));
        var windows=(List<?>)get(values,"getChatWindows");if(windows.isEmpty())throw new AssertionError("Actual ChatPlus window missing");
        for(var window:windows){if(profile==0){var tabs=(List<?>)get(get(window,"getTabSettings"),"getTabs");for(var tab:tabs)tab.getClass().getMethod("clear").invoke(tab);}var general=get(window,"getGeneralSettings");set(general,"setScale",float.class,profile==0?1f:.65f);set(general,"setLineSpacing",float.class,profile==0?0f:.35f);var renderer=get(window,"getRenderer");set(renderer,"setWidth",int.class,mc.getWindow().getGuiScaledWidth()-65);set(renderer,"setHeight",int.class,mc.getWindow().getGuiScaledHeight()/2);set(renderer,"setX",int.class,25);set(renderer,"setY",int.class,mc.getWindow().getGuiScaledHeight()-24);renderer.getClass().getMethod("updateCachedDimension").invoke(renderer);}
        singleton("hud.ChatManager").getClass().getMethod("rescaleAll").invoke(singleton("hud.ChatManager"));
    }
    static void anchors(EditBox input)throws Exception{
        var type=NativeInteractions.class;for(String method:List.of("px","py","pw","ph","cardX","cardY")){var m=type.getDeclaredMethod(method);m.setAccessible(true);int value=(int)m.invoke(null);if(value<0)throw new AssertionError("Negative actual anchor "+method);}
        var f=type.getDeclaredField("smiley");f.setAccessible(true);var button=(net.minecraft.client.gui.components.AbstractWidget)f.get(null);
        if(input.getX()<=2||input.getY()>=Minecraft.getInstance().getWindow().getGuiScaledHeight()-30||Math.abs(button.getY()-(input.getY()-5))>1)throw new AssertionError("Popup/button ignored actual relocated ChatPlus input");
    }
}
