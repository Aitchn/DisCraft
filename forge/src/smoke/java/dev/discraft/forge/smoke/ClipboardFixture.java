package dev.discraft.forge.smoke;

import java.awt.*;
import java.awt.datatransfer.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import javax.imageio.ImageIO;

/** A separate OS clipboard owner, not an in-game substitute. Bounded lifetime and explicit ready/stop markers. */
public final class ClipboardFixture {
    public static void main(String[] args)throws Exception {
        BufferedImage image=ImageIO.read(Path.of(args[0]).toFile());Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new Transferable(){
            public DataFlavor[] getTransferDataFlavors(){return new DataFlavor[]{DataFlavor.imageFlavor};}
            public boolean isDataFlavorSupported(DataFlavor f){return DataFlavor.imageFlavor.equals(f);}
            public Object getTransferData(DataFlavor f)throws UnsupportedFlavorException{if(!isDataFlavorSupported(f))throw new UnsupportedFlavorException(f);return image;}
        },null);
        Files.writeString(Path.of(args[1]),"ready");long deadline=System.nanoTime()+30_000_000_000L;
        while(System.nanoTime()<deadline&&!Files.exists(Path.of(args[2])))Thread.sleep(50);
        System.exit(0);
    }
}
