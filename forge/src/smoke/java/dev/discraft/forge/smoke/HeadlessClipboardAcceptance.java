package dev.discraft.forge.smoke;

import java.awt.GraphicsEnvironment;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;

/** Production-like headless game caller; desktop ownership remains in the on-demand helper. */
public final class HeadlessClipboardAcceptance {
    public static void main(String[] args)throws Exception {
        if(!GraphicsEnvironment.isHeadless())throw new AssertionError("Caller must retain headless AWT");
        var service=Class.forName("dev.discraft.forge.DesktopClipboardService");var write=service.getDeclaredMethod("write",byte[].class);write.setAccessible(true);var read=service.getDeclaredMethod("read");read.setAccessible(true);var stop=service.getDeclaredMethod("stop");stop.setAccessible(true);var process=service.getDeclaredField("process");process.setAccessible(true);
        try {
            var image=new BufferedImage(16,16,BufferedImage.TYPE_INT_ARGB);image.setRGB(7,9,0xff1469ab);var output=new ByteArrayOutputStream();ImageIO.write(image,"png",output);write.invoke(null,(Object)output.toByteArray());byte[] bytes=(byte[])read.invoke(null);var actual=ImageIO.read(new ByteArrayInputStream(bytes));if(actual.getRGB(7,9)!=image.getRGB(7,9)||!GraphicsEnvironment.isHeadless())throw new AssertionError("Headless caller pixel roundtrip failed");
            Process child=(Process)process.get(null);stop.invoke(null);if(!child.waitFor(2,TimeUnit.SECONDS))throw new AssertionError("Headless caller left helper alive");System.out.println("PASS headless game caller preserves AWT state, transfers image pixels and exits its desktop helper");
        }finally{stop.invoke(null);}
    }
}
