package dev.discraft.forge.server;

import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import javax.imageio.ImageIO;

/** Worker-confined bounded decoded texture cache; animations intentionally use their first specified frame. */
final class ResourceImages {
    private final ResourcePackIndex resources;
    private final Map<String,BufferedImage> cache=new LinkedHashMap<>(16,.75f,true);
    private long pixels;
    ResourceImages(ResourcePackIndex resources){this.resources=resources;}
    BufferedImage image(String id)throws IOException{
        if(id.endsWith(".png"))id=id.substring(0,id.length()-4);
        if(cache.containsKey(id))return cache.get(id);
        String path=ResourcePackIndex.resourcePath(id,"textures",".png");byte[] bytes=resources.asset(path);
        if(bytes==null)throw new IOException("Missing texture");
        var image=ImageIO.read(new ByteArrayInputStream(bytes));if(image==null)throw new IOException("Invalid texture");
        byte[] metadata=resources.asset(path+".mcmeta");
        if(metadata!=null){var root=ResourcePackIndex.json(metadata);if(root.has("animation")){
            var animation=root.getAsJsonObject("animation");boolean width=animation.has("width"),height=animation.has("height");
            // 1.20.1 AnimationMetadataSection.calculateFrameSize: absent both -> min axis;
            // one explicit axis -> preserve the full image on the other axis.
            int minimum=Math.min(image.getWidth(),image.getHeight());
            int w=width?animation.get("width").getAsInt():height?image.getWidth():minimum;
            int h=height?animation.get("height").getAsInt():width?image.getHeight():minimum;
            if(w<1||h<1||w>image.getWidth()||h>image.getHeight()||image.getWidth()%w!=0||image.getHeight()%h!=0)throw new IOException("Unsupported animation frame bounds");
            int frame=0;if(animation.has("frames")&&!animation.getAsJsonArray("frames").isEmpty()){var first=animation.getAsJsonArray("frames").get(0);frame=first.isJsonObject()?first.getAsJsonObject().get("index").getAsInt():first.getAsInt();}
            int columns=image.getWidth()/w;if(frame<0||frame>=(long)columns*(image.getHeight()/h))throw new IOException("Invalid animation frame");
            var selected=new BufferedImage(w,h,BufferedImage.TYPE_INT_ARGB);var g=selected.createGraphics();g.drawImage(image,-(frame%columns)*w,-(frame/columns)*h,null);g.dispose();image=selected;
        }}
        pixels+=(long)image.getWidth()*image.getHeight();cache.put(id,image);
        while(cache.size()>16||pixels>4L*1024*1024){var key=cache.keySet().iterator().next();var old=cache.remove(key);pixels-=(long)old.getWidth()*old.getHeight();}
        return image;
    }
    long pixels(){return pixels;}
}
