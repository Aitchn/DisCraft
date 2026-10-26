package dev.discraft.forge.server;

import com.google.gson.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import java.util.List;

/** Bounded bitmap/space/reference font profile. Unsupported providers use the bundled OFL text font. */
final class PackBitmapFont {
    private record Glyph(BufferedImage sheet,int x,int y,int w,int h,int height,int ascent,double advance){}
    private record Face(Map<Integer,Glyph> glyphs,boolean fallback,long pixels){}
    private final ResourcePackIndex resources;private final ResourceImages images;
    private final Map<String,Face> faces=new LinkedHashMap<>(8,.75f,true);
    private final Font fallback;
    PackBitmapFont(ResourcePackIndex resources,ResourceImages images)throws Exception{
        this.resources=resources;this.images=images;
        try(var in=PackBitmapFont.class.getResourceAsStream("/assets/discraft/font/ui.otf")){fallback=Font.createFont(Font.TRUETYPE_FONT,Objects.requireNonNull(in));}
    }
    private Face face(String id){
        if(faces.containsKey(id))return faces.get(id);Face face;
        try{var glyphs=new LinkedHashMap<Integer,Glyph>();var budget=new int[]{64,0};boolean unsupported=providers(id,glyphs,new HashSet<>(),budget,0);face=new Face(Map.copyOf(glyphs),unsupported,budget[1]);}
        catch(Exception rejected){face=new Face(Map.of(),true,0);}
        faces.put(id,face);while(faces.size()>8||faces.values().stream().mapToLong(Face::pixels).sum()>2L*1024*1024)faces.remove(faces.keySet().iterator().next());return face;
    }
    private boolean providers(String id,Map<Integer,Glyph> glyphs,Set<String> path,int[] budget,int depth)throws Exception{
        if(depth>16||!path.add(id))throw new IOException("Font reference cycle or depth");
        byte[] bytes=resources.asset(ResourcePackIndex.resourcePath(id,"font",".json"));if(bytes==null){path.remove(id);return !id.equals("minecraft:default");}
        boolean unsupported=false;var providers=ResourcePackIndex.json(bytes).getAsJsonArray("providers");
        if(providers.size()>64)throw new IOException("Font provider count exceeded");
        for(var value:providers){if(--budget[0]<0)throw new IOException("Font provider budget exceeded");var provider=value.getAsJsonObject();String type=provider.get("type").getAsString().replace("minecraft:","");
            if(type.equals("reference"))unsupported|=providers(provider.get("id").getAsString(),glyphs,path,budget,depth+1);
            else if(type.equals("space")){
                var advances=provider.getAsJsonObject("advances");if(advances.size()>256)throw new IOException("Font space budget exceeded");
                for(var entry:advances.entrySet()){double advance=entry.getValue().getAsDouble();if(entry.getKey().codePointCount(0,entry.getKey().length())!=1||!Double.isFinite(advance)||advance<0||advance>32)throw new IOException("Invalid font space");glyphs.putIfAbsent(entry.getKey().codePointAt(0),new Glyph(null,0,0,0,0,8,7,advance));}
            }else if(type.equals("bitmap")){
                BufferedImage image=images.image(provider.get("file").getAsString());var chars=provider.getAsJsonArray("chars");
                budget[1]+=(long)image.getWidth()*image.getHeight();if(budget[1]>1024*1024)throw new IOException("Font sheet pixel budget exceeded");
                int height=provider.has("height")?provider.get("height").getAsInt():8,ascent=provider.get("ascent").getAsInt();
                if(chars.isEmpty()||chars.size()>256||height<1||height>32||ascent<0||ascent>height)throw new IOException("Invalid bitmap font grid");
                int columns=chars.get(0).getAsString().codePointCount(0,chars.get(0).getAsString().length());if(columns<1||columns>256||columns*chars.size()>4096||image.getWidth()%columns!=0||image.getHeight()%chars.size()!=0)throw new IOException("Invalid bitmap font grid");
                int w=image.getWidth()/columns,h=image.getHeight()/chars.size();
                for(int row=0;row<chars.size();row++){int[] codepoints=chars.get(row).getAsString().codePoints().toArray();if(codepoints.length!=columns)throw new IOException("Inconsistent bitmap font row");
                    for(int col=0;col<columns;col++){if(codepoints[col]==0)continue;int ink=0;for(int x=0;x<w;x++)for(int y=0;y<h;y++)if((image.getRGB(col*w+x,row*h+y)>>>24)!=0)ink=Math.max(ink,x+1);
                        glyphs.putIfAbsent(codepoints[col],new Glyph(image,col*w,row*h,w,h,height,ascent,(double)ink*height/h+1));
                    }
                }
            }else unsupported=true;
            if(glyphs.size()>4096)throw new IOException("Font glyph budget exceeded");
        }
        path.remove(id);return unsupported;
    }
    /** Returns whether a provided unsupported font required fallback. Text is clipped with ellipsis by measured width. */
    boolean draw(Graphics2D g,String text,String id,int size,int x,int baseline,int width){
        Face face=face(id);g.setFont(fallback.deriveFont((float)size));g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        int at=x;int[] points=text.codePoints().limit(128).toArray();
        for(int i=0;i<points.length;i++){
            int cp=points[i];Glyph glyph=face.glyphs().get(cp);double scale=glyph==null?1:(double)size/Math.max(9,glyph.height());
            int advance=glyph==null?g.getFontMetrics().stringWidth(new String(Character.toChars(cp))):(int)Math.ceil(glyph.advance()*scale);
            if(at-x+advance>width-(i+1<points.length?size:0)){g.drawString("…",at,baseline);break;}
            if(glyph==null)g.drawString(new String(Character.toChars(cp)),at,baseline);
            else if(glyph.sheet()!=null){int h=(int)Math.round(glyph.height()*scale),w=(int)Math.round((double)glyph.w()*glyph.height()/glyph.h()*scale),top=baseline-(int)Math.round(glyph.ascent()*scale);g.drawImage(glyph.sheet(),at,top,at+w,top+h,glyph.x(),glyph.y(),glyph.x()+glyph.w(),glyph.y()+glyph.h(),null);}
            at+=advance;
        }return face.fallback();
    }
}
