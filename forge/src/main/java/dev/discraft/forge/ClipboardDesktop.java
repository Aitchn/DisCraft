package dev.discraft.forge;

import java.awt.*;
import java.awt.datatransfer.*;
import java.awt.image.BufferedImage;
import java.io.*;
import javax.imageio.ImageIO;

/** Standalone desktop process: Minecraft deliberately initializes headless AWT in its own JVM. */
public final class ClipboardDesktop {
    private record Pixels(BufferedImage image) implements Transferable {
        public DataFlavor[] getTransferDataFlavors(){return new DataFlavor[]{DataFlavor.imageFlavor};}
        public boolean isDataFlavorSupported(DataFlavor f){return DataFlavor.imageFlavor.equals(f);}
        public Object getTransferData(DataFlavor f)throws UnsupportedFlavorException{if(!isDataFlavorSupported(f))throw new UnsupportedFlavorException(f);return image;}
    }
    public static void main(String[] args)throws Exception {
        var input=new DataInputStream(System.in);var output=new DataOutputStream(System.out);
        try {while(true){int command=input.read();if(command<0)break;
            try {
                var clipboard=Toolkit.getDefaultToolkit().getSystemClipboard();
                if(command==1){
                    Transferable value=clipboard.getContents(null);
                    if(value==null||!value.isDataFlavorSupported(DataFlavor.imageFlavor)){output.writeInt(0);output.flush();continue;}
                    if(!(value.getTransferData(DataFlavor.imageFlavor) instanceof Image image))throw new IOException();
                    int width=image.getWidth(null),height=image.getHeight(null);
                    if(width<1||height<1||width>16384||height>16384||(long)width*height>16_777_216)throw new IOException();
                    double scale=Math.min(1,Math.min(1024d/width,1024d/height));byte[] encoded=null;
                    for(int attempt=0;attempt<9;attempt++){
                        var copy=new BufferedImage(Math.max(1,(int)(width*scale)),Math.max(1,(int)(height*scale)),BufferedImage.TYPE_INT_ARGB);
                        var g=copy.createGraphics();try{g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BILINEAR);g.drawImage(image,0,0,copy.getWidth(),copy.getHeight(),null);}finally{g.dispose();}
                        var bytes=new ByteArrayOutputStream();ImageIO.write(copy,"png",bytes);copy.flush();if(bytes.size()<=262144){encoded=bytes.toByteArray();break;}scale*=.75;
                    }
                    if(encoded==null)throw new IOException();output.writeInt(encoded.length);output.write(encoded);
                }else if(command==2){int count=input.readInt();if(count<1||count>16_777_216)throw new IOException();byte[] bytes=input.readNBytes(count);if(bytes.length!=count)throw new EOFException();var image=ImageIO.read(new ByteArrayInputStream(bytes));if(image==null||image.getWidth()>2048||image.getHeight()>2048)throw new IOException();clipboard.setContents(new Pixels(image),null);output.writeInt(0);
                }else throw new IOException();
            }catch(Exception|LinkageError unavailable){output.writeInt(-1);}output.flush();
        }}finally{System.exit(0);}
    }
}
