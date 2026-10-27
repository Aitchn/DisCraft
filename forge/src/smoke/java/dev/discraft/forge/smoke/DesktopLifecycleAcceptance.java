package dev.discraft.forge.smoke;

import java.awt.*;
import java.awt.datatransfer.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;

/** Real X11 helper lifecycle/byte checks; no Minecraft launch and no network access. */
public final class DesktopLifecycleAcceptance {
    private static Class<?> service;private static int checks;
    private static Object call(String name,Class<?>[] types,Object...args)throws Exception{var m=service.getDeclaredMethod(name,types);m.setAccessible(true);try{return m.invoke(null,args);}catch(java.lang.reflect.InvocationTargetException e){if(e.getCause() instanceof Exception x)throw x;throw e;}}
    private static Object field(String name)throws Exception{var f=service.getDeclaredField(name);f.setAccessible(true);return f.get(null);}
    private static void check(boolean yes,String label){if(!yes)throw new AssertionError(label);System.out.println("PASS "+label);checks++;}
    private static Transferable pixels(BufferedImage image,boolean slow){return new Transferable(){
        public DataFlavor[] getTransferDataFlavors(){return new DataFlavor[]{DataFlavor.imageFlavor};}
        public boolean isDataFlavorSupported(DataFlavor f){return DataFlavor.imageFlavor.equals(f);}
        public Object getTransferData(DataFlavor f)throws UnsupportedFlavorException,IOException{if(!isDataFlavorSupported(f))throw new UnsupportedFlavorException(f);if(slow)try{Thread.sleep(10000);}catch(InterruptedException e){throw new IOException(e);}return image;}
    };}
    public static void main(String[] args)throws Exception{
        service=Class.forName("dev.discraft.forge.DesktopClipboardService");var clipboard=Toolkit.getDefaultToolkit().getSystemClipboard();
        boolean success=false;try{
            check(field("process")==null,"Desktop helper does not start before an explicit request");
            clipboard.setContents(new StringSelection("vanilla text 世界"),null);check(call("read",new Class<?>[0])==null,"Text-only X11 clipboard does not become an attachment");
            Process original=(Process)field("process");Path directory=(Path)field("directory");check(original.isAlive(),"Explicit desktop request starts one app-owned helper");
            var image=new BufferedImage(16,16,BufferedImage.TYPE_INT_ARGB);for(int y=0;y<16;y++)for(int x=0;x<16;x++)image.setRGB(x,y,0xff000000|x*16<<16|y*16<<8|123);
            clipboard.setContents(pixels(image,false),null);byte[] actual=(byte[])call("read",new Class<?>[0]);var decoded=ImageIO.read(new ByteArrayInputStream(actual));boolean equal=true;for(int y=0;y<16;y++)for(int x=0;x<16;x++)equal&=decoded.getRGB(x,y)==image.getRGB(x,y);check(equal,"Cross-process X11 image clipboard bytes preserve every source pixel");
            var bytes=new ByteArrayOutputStream();ImageIO.write(image,"png",bytes);call("write",new Class<?>[]{byte[].class},(Object)bytes.toByteArray());var copied=(Image)clipboard.getData(DataFlavor.imageFlavor);var checkImage=new BufferedImage(16,16,BufferedImage.TYPE_INT_ARGB);var g=checkImage.createGraphics();g.drawImage(copied,0,0,null);g.dispose();equal=true;for(int y=0;y<16;y++)for(int x=0;x<16;x++)equal&=checkImage.getRGB(x,y)==image.getRGB(x,y);check(equal,"Helper-written X11 image preserves every pixel for another process");
            check(field("process")==original,"Repeated explicit reads/writes reuse only the app-owned helper");
            check(new ProcessBuilder("kill","-STOP",Long.toString(original.pid())).start().waitFor()==0,"Owned helper is deliberately paused for deadline regression");long started=System.nanoTime();boolean failed=false;try{call("read",new Class<?>[0]);}catch(IOException expected){failed=true;}long elapsed=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started);
            check(failed&&elapsed<4500,"Unresponsive desktop helper fails within bounded request deadline");check(original.waitFor(2,TimeUnit.SECONDS),"Timed-out helper actually exits without an orphan process");check(!Files.exists(directory),"Timed-out helper removes its temporary bundled classes");
            call("stop",new Class<?>[0]);System.out.println("PASS "+checks+" real X11 clipboard/lifecycle checks");success=true;
        }catch(Throwable failure){failure.printStackTrace();}finally{call("stop",new Class<?>[0]);System.exit(success?0:1);}
    }
}
