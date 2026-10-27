package dev.discraft.optionaltest;
/** Standalone test launcher; DisCraft is absent from this client's classpath and mod roots. */
public final class DesktopBootstrap {
    public static void main(String[] args)throws Exception{System.setProperty("java.awt.headless","false");if(java.awt.GraphicsEnvironment.isHeadless())throw new IllegalStateException("Optional client acceptance requires X11");Class.forName(args[0]).getMethod("main",String[].class).invoke(null,(Object)java.util.Arrays.copyOfRange(args,1,args.length));}
}
