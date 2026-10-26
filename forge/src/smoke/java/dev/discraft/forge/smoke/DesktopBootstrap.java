package dev.discraft.forge.smoke;

/** Only the Robot acceptance launcher initializes desktop AWT before Minecraft forces its property. */
public final class DesktopBootstrap {
    public static void main(String[] args)throws Exception {
        System.setProperty("java.awt.headless","false");
        if(java.awt.GraphicsEnvironment.isHeadless())throw new IllegalStateException("Desktop acceptance requires X11");
        Class.forName(args[0]).getMethod("main",String[].class).invoke(null,(Object)java.util.Arrays.copyOfRange(args,1,args.length));
    }
}
