package dev.discraft.forge.smoke;

import dev.discraft.core.MediaAsset;
import java.awt.image.BufferedImage;
import java.lang.reflect.*;
import java.util.*;

/** Real render-thread texture upload/disposal checks; no network or system clipboard. */
final class TextureAnimationAcceptance {
    static void run()throws Exception{
        Class<?> type=Class.forName("dev.discraft.forge.TextureCache");var ctor=type.getDeclaredConstructor();ctor.setAccessible(true);Object cache=ctor.newInstance();
        Method begin=method(type,"begin"),end=method(type,"end"),close=method(type,"close"),frame=method(type,"frame",String.class,MediaAsset.class,long.class);var entries=type.getDeclaredField("entries");entries.setAccessible(true);var origins=type.getDeclaredField("origins");origins.setAccessible(true);
        var red=new BufferedImage(2,2,BufferedImage.TYPE_INT_ARGB);red.setRGB(0,0,0xffff0000);var blue=new BufferedImage(2,2,BufferedImage.TYPE_INT_ARGB);blue.setRGB(0,0,0xff0000ff);
        var media=new MediaAsset("gif",2,2,List.of(new MediaAsset.Frame(red,50),new MediaAsset.Frame(blue,50)),2);
        try{begin.invoke(cache);Object location=frame.invoke(cache,"message-a",media,1000L);verify(entries.get(cache),"message-a",0);frame.invoke(cache,"message-a",media,1050L);verify(entries.get(cache),"message-a",1);frame.invoke(cache,"message-a",media,1100L);verify(entries.get(cache),"message-a",0);frame.invoke(cache,"message-a",media,1200L);verify(entries.get(cache),"message-a",1);if(!location.equals(frame.invoke(cache,"message-a",media,1300L)))throw new AssertionError("Animation must reuse one GPU texture");
            frame.invoke(cache,"message-b",media,1300L);verify(entries.get(cache),"message-b",0);end.invoke(cache);begin.invoke(cache);end.invoke(cache);if(!((Map<?,?>)entries.get(cache)).isEmpty())throw new AssertionError("Offscreen GPU textures must be released");
            begin.invoke(cache);frame.invoke(cache,"message-a",media,1400L);verify(entries.get(cache),"message-a",1);end.invoke(cache);close.invoke(cache);if(!((Map<?,?>)entries.get(cache)).isEmpty()||!((Map<?,?>)origins.get(cache)).isEmpty())throw new AssertionError("Disconnect clears texture and playback state");
            System.out.println("PASS 9 real GPU GIF finite-playback/independent-message/culling/cleanup checks");
        }finally{close.invoke(cache);red.flush();blue.flush();}
    }
    private static Method method(Class<?> type,String name,Class<?>...args)throws Exception{var m=type.getDeclaredMethod(name,args);m.setAccessible(true);return m;}
    private static void verify(Object raw,String key,int expected)throws Exception{Object entry=((Map<?,?>)raw).get(key);var frame=entry.getClass().getDeclaredMethod("frame");frame.setAccessible(true);if((Integer)frame.invoke(entry)!=expected)throw new AssertionError("GIF visible-frame timing mismatch");}
}
