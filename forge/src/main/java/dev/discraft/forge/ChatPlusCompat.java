package dev.discraft.forge;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.GuiMessage;
import net.minecraft.network.chat.Style;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.Consumer;

/** Optional public-event interoperability. No ChatPlus/Kotlin classes, code or dependency are bundled. */
@Mod.EventBusSubscriber(modid=DisCraft.ID,value=Dist.CLIENT,bus=Mod.EventBusSubscriber.Bus.MOD)
public final class ChatPlusCompat {
    private static final Minecraft MC=Minecraft.getInstance();
    private static boolean initialized,present,ready,warned;private static Object config,unit;private static ClassLoader loader;
    private static final Map<Class<?>,Map<String,Method>> getters=new HashMap<>();
    private static Method tabStyle;
    record Bounds(int x,int y,int width,int height){}
    private static Bounds bounds(Object renderer)throws ReflectiveOperationException{return new Bounds(((Number)get(renderer,"getInternalX")).intValue(),((Number)get(renderer,"getInternalY")).intValue()-((Number)get(renderer,"getInternalHeight")).intValue(),((Number)get(renderer,"getInternalWidth")).intValue(),((Number)get(renderer,"getInternalHeight")).intValue());}
    @SubscribeEvent public static void setup(FMLClientSetupEvent event){event.enqueueWork(ChatPlusCompat::initialize);}
    private static void warning(){if(!warned){warned=true;com.mojang.logging.LogUtils.getLogger().warn("DisCraft: optional ChatPlus renderer API unavailable; original text is retained instead of unpositioned rich-media cells");}}
    private static Object get(Object value,String name)throws ReflectiveOperationException{
        var methods=getters.get(value.getClass());if(methods==null){if(getters.size()>=24)throw new IllegalStateException("Optional renderer API bounds");methods=new HashMap<>();getters.put(value.getClass(),methods);}var method=methods.get(name);if(method==null){if(methods.size()>=24)throw new IllegalStateException("Optional renderer API bounds");method=value.getClass().getMethod(name);methods.put(name,method);}return method.invoke(value);
    }
    public static void initialize(){
        if(initialized)return;initialized=true;present=ModList.get().isLoaded("chatplus");if(!present)return;
        try{
            loader=ChatPlusCompat.class.getClassLoader();var type=Class.forName("com.ebicep.chatplus.config.Config",true,loader);config=type.getField("INSTANCE").get(null);
            if(!ModList.get().getModContainerById("chatplus").orElseThrow().getModInfo().getVersion().toString().equals("2.8.1")){warning();return;}
            unit=Class.forName("kotlin.Unit",true,loader).getField("INSTANCE").get(null);
            tabStyle=Class.forName("com.ebicep.chatplus.features.chattabs.ChatTab",true,loader).getMethod("getComponentStyleAt",double.class,double.class);
            subscribe("com.ebicep.chatplus.hud.ChatRenderLineTextEvent",ChatPlusCompat::line);
            subscribe("com.ebicep.chatplus.hud.ChatScreenSendMessagePostEvent",ChatPlusCompat::send);
            ready=true;
        }catch(Exception unavailable){ready=false;warning();}
    }
    private static void subscribe(String event,Consumer<Object> callback)throws ReflectiveOperationException{
        var zero=Class.forName("kotlin.jvm.functions.Function0",true,loader);var one=Class.forName("kotlin.jvm.functions.Function1",true,loader);
        Object priority=function(zero,args->Integer.MIN_VALUE),skip=function(zero,args->false),handler=function(one,args->{try{callback.accept(args[0]);}catch(Exception unsupported){warning();}return unit;});
        var bus=Class.forName("com.ebicep.chatplus.events.EventBus",true,loader);bus.getMethod("register",zero,zero,Class.class,one).invoke(bus.getField("INSTANCE").get(null),priority,skip,Class.forName(event,true,loader),handler);
    }
    private static Object function(Class<?> type,java.util.function.Function<Object[],Object> invoke){return Proxy.newProxyInstance(loader,new Class<?>[]{type},(proxy,method,args)->switch(method.getName()){case "invoke"->invoke.apply(args==null?new Object[0]:args);case "hashCode"->System.identityHashCode(proxy);case "equals"->args!=null&&args.length==1&&proxy==args[0];case "toString"->"DisCraft optional renderer callback";default->throw new UnsupportedOperationException();});}
    static boolean custom(){if(!initialized)initialize();if(!present)return false;try{return (Boolean)get(get(config,"getValues"),"getEnabled");}catch(Exception unavailable){warning();return true;}}
    static boolean rendering(){if(!custom())return false;try{return MC.screen instanceof ChatScreen||!(Boolean)get(get(config,"getValues"),"getShowVanillaWhenUnfocused");}catch(Exception unavailable){return true;}}
    static boolean installed(){if(!initialized)initialize();return present;}
    static boolean active(){return ready&&rendering();}
    static boolean canRender(){return !custom()||ready;}
    static int width(int fallback){
        if(!active())return fallback;
        try{Object windows=get(get(config,"getValues"),"getChatWindows");if(!(windows instanceof List<?> list)||list.size()>8)return Math.min(fallback,96);int width=fallback;for(var window:list){int actual=((Number)get(get(window,"getRenderer"),"getRescaledWidth")).intValue();if(actual>0)width=Math.min(width,actual);}return Math.max(16,width);}catch(Exception unavailable){return Math.min(fallback,96);}
    }
    private static void line(Object event){
        if(!active()||!MC.isSameThread())return;
        try{var graphics=(GuiGraphics)get(event,"getGuiGraphics");var line=(GuiMessage.Line)get(event,"getLine");Object renderer=get(get(event,"getChatWindow"),"getRenderer");float x=((Number)get(renderer,"getRescaledX")).floatValue(),y=((Number)get(event,"getVerticalTextOffset")).floatValue();int color=((Number)get(event,"getTextColor")).intValue();int alpha=color>=0&&color<=255?color:color>>>24;
            int height=((Number)get(renderer,"getLineHeight")).intValue();if(height<1||height>256)return;
            NativeChat.externalLine(graphics,line.content(),x,y,height,bounds(renderer),Math.max(0,Math.min(255,alpha))/255f);
        }catch(Exception unsupported){warning();}
    }
    private static void send(Object event){
        if(!active()||!MC.isSameThread())return;
        try{if((Boolean)get(event,"getDontSendMessage")||get(event,"getScreen")!=MC.screen)return;String text=(String)get(event,"getNormalizeChatMessage");if(NativeComposer.submitCustom(text)){
            event.getClass().getMethod("setDontSendMessage",boolean.class).invoke(event,true);
            var manager=Class.forName("com.ebicep.chatplus.hud.ChatManager",true,loader);manager.getMethod("addSentMessage",String.class).invoke(manager.getField("INSTANCE").get(null),text);
        }}catch(Exception unsupported){warning();}
    }
    static List<PendingCardLayout.Rect> protectedChatAreas(int width,int height){
        if(!custom())return List.of();
        try{if(!ready)throw new IllegalStateException();var windows=(List<?>)get(get(config,"getValues"),"getChatWindows");if(windows.isEmpty()||windows.size()>8)throw new IllegalStateException();var result=new ArrayList<PendingCardLayout.Rect>();
            for(var window:windows){if((Boolean)get(get(window,"getGeneralSettings"),"getDisabled"))continue;var b=bounds(get(window,"getRenderer"));if(b.width()<=0||b.height()<=0)throw new IllegalStateException();long left=Math.max(0L,(long)b.x()-16),top=Math.max(0L,(long)b.y()-20);long right=Math.min((long)width,(long)b.x()+b.width()+12),bottom=Math.min((long)height,(long)b.y()+b.height()+20);if(right>left&&bottom>top)result.add(new PendingCardLayout.Rect((int)left,(int)top,(int)(right-left),(int)(bottom-top)));}return List.copyOf(result);
        }catch(Exception unavailable){return List.of(new PendingCardLayout.Rect(0,0,width,height));}
    }
    static Style styleAt(double x,double y){
        if(!ready||!(MC.screen instanceof ChatScreen))return null;
        try{var windows=(List<?>)get(get(config,"getValues"),"getChatWindows");if(windows.size()>8)return null;
            for(int i=windows.size()-1;i>=0;i--){var window=windows.get(i);if((Boolean)get(get(window,"getGeneralSettings"),"getDisabled"))continue;var area=bounds(get(window,"getRenderer"));if(x>=area.x()&&x<area.x()+area.width()&&y>=area.y()&&y<area.y()+area.height())return (Style)tabStyle.invoke(get(get(window,"getTabSettings"),"getSelectedTab"),x,y);}
        }catch(Exception unsupported){warning();}return null;
    }
}
