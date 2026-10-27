package dev.discraft.forge.server;

import com.google.gson.*;
import java.awt.Color;
import java.awt.image.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import javax.imageio.ImageIO;

/** Structural ownership regression: no GC timing and no reliance on pixel accounting counters. */
final class FontInkCacheTest {
    private static final int FONTS=18,EXTRA_TEXTURES=6,SIDE=1024;
    private static int color(int i){return 0xff000000|((32+i*7)<<16)|(24<<8)|(64+i*3);}
    private static void entry(ZipOutputStream zip,String name,byte[] bytes)throws IOException{zip.putNextEntry(new ZipEntry(name));zip.write(bytes);zip.closeEntry();}
    private static Path fixture(Path directory)throws Exception{
        Path pack=directory.resolve("churn.zip");
        try(var zip=new ZipOutputStream(Files.newOutputStream(pack))){
            entry(zip,"pack.mcmeta","{\"pack\":{\"pack_format\":15,\"description\":\"Original font cache ownership fixtures\"}}".getBytes(StandardCharsets.UTF_8));
            for(int i=0;i<FONTS+EXTRA_TEXTURES;i++){
                // Each glyph is only 8x32 but owns a 1M-pixel source sheet: the original leak amplifier.
                var sheet=new BufferedImage(SIDE,SIDE,BufferedImage.TYPE_INT_ARGB);for(int y=0;y<32;y++)for(int x=0;x<8;x++)sheet.setRGB(x,y,color(i));var png=new ByteArrayOutputStream();ImageIO.write(sheet,"png",png);entry(zip,"assets/churn/textures/sheet_"+i+".png",png.toByteArray());
                if(i<FONTS){var provider=new JsonObject();provider.addProperty("type","bitmap");provider.addProperty("file","churn:sheet_"+i+".png");provider.addProperty("height",8);provider.addProperty("ascent",7);var chars=new JsonArray();chars.add("A"+"\0".repeat(127));for(int row=1;row<32;row++)chars.add("\0".repeat(128));provider.add("chars",chars);var providers=new JsonArray();providers.add(provider);var face=new JsonObject();face.add("providers",providers);entry(zip,"assets/churn/font/face_"+i+".json",face.toString().getBytes(StandardCharsets.UTF_8));}
            }
        }return pack;
    }
    private static Object field(Object object,String name)throws Exception{var f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);}
    private static Set<BufferedImage> images(Object root)throws Exception{Set<BufferedImage> images=Collections.newSetFromMap(new IdentityHashMap<>());walk(root,Collections.newSetFromMap(new IdentityHashMap<>()),images);return images;}
    private static void walk(Object object,Set<Object> seen,Set<BufferedImage> images)throws Exception{
        if(object==null||!seen.add(object))return;if(object instanceof BufferedImage image){images.add(image);return;}
        if(object instanceof Map<?,?> map){for(var e:map.entrySet()){walk(e.getKey(),seen,images);walk(e.getValue(),seen,images);}return;}
        if(object instanceof Iterable<?> values){for(var value:values)walk(value,seen,images);return;}
        if(object.getClass().getName().startsWith("dev.discraft.forge.server."))for(var f:object.getClass().getDeclaredFields())if(!Modifier.isStatic(f.getModifiers())){f.setAccessible(true);walk(f.get(object),seen,images);}
    }
    private static long reachablePixels(Set<BufferedImage> images){Set<DataBuffer> buffers=Collections.newSetFromMap(new IdentityHashMap<>());long pixels=0;for(var image:images){var buffer=image.getRaster().getDataBuffer();if(buffers.add(buffer))pixels+=(long)buffer.getSize()*buffer.getNumBanks()*DataBuffer.getDataTypeSize(buffer.getDataType())/32;}return pixels;}
    private static void draw(PackBitmapFont font,int index)throws Exception{
        var target=new BufferedImage(32,32,BufferedImage.TYPE_INT_ARGB);var g=target.createGraphics();try{g.setColor(Color.WHITE);font.glyph(g,'A',new ItemTooltip.Run("",0xffffff,"churn:face_"+index,false,false,false,false,false),18,4,24);}finally{g.dispose();}
        if(target.getRGB(5,16)!=color(index))throw new AssertionError("Ink identity mixed different font sheets at identical glyph coordinates");
    }
    static void run()throws Exception{
        Path directory=Files.createTempDirectory("discraft-font-ownership-");try{
            fixture(directory);var resources=ResourcePackIndex.load(directory,List.of("churn.zip"));var textures=new ResourceImages(resources);var font=new PackBitmapFont(resources,textures);
            Set<DataBuffer> originalBuffers=Collections.newSetFromMap(new IdentityHashMap<>());
            for(int i=0;i<FONTS;i++){originalBuffers.add(textures.image("churn:sheet_"+i).getRaster().getDataBuffer());draw(font,i);}
            // Force source textures out of their own cache while the last two faces still retain theirs.
            for(int i=FONTS;i<FONTS+EXTRA_TEXTURES;i++)textures.image("churn:sheet_"+i);
            @SuppressWarnings("unchecked") var inks=(Map<Object,BufferedImage>)field(font,"inks");
            if(!images(inks.keySet()).isEmpty())throw new AssertionError("Ink keys retain source BufferedImage sheets through their object graph");
            for(var ink:inks.values())if(originalBuffers.contains(ink.getRaster().getDataBuffer()))throw new AssertionError("Ink crops share a full source sheet raster");
            Set<BufferedImage> reachable=images(font);long actual=reachablePixels(reachable);
            if(actual>7L*1024*1024)throw new AssertionError("Font ownership graph retains more than 2M face + 4M texture + 1M ink pixels: "+actual);
            if(reachablePixels(images(field(font,"faces")))>2L*1024*1024||reachablePixels(images(field(textures,"cache")))>4L*1024*1024||reachablePixels(images(inks.values()))>1024*1024)throw new AssertionError("A decoded ownership graph exceeds its own cache budget");
            // Re-loading an evicted face creates a fresh identity and still resolves the correct pixels.
            draw(font,0);draw(font,FONTS-1);
            System.out.println("PASS 3 font ink ownership checks: 18 fonts/24 large sheets, primitive-only keys, independent ink rasters, actual reachable buffers bounded, reload identity preserved");
        }finally{try(var paths=Files.list(directory)){for(var path:paths.toList())Files.delete(path);}Files.delete(directory);}
    }
}
