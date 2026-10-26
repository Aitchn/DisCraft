package dev.discraft.forge.server;

import com.google.gson.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import java.util.List;

/** Bounded bitmap/space/reference font profile. Unsupported providers use the bundled OFL text font. */
final class PackBitmapFont {
    private record Glyph(long identity,BufferedImage sheet,int x,int y,int w,int h,int height,int ascent,double advance){}
    private record Face(Map<Integer,Glyph> glyphs,boolean fallback,long pixels){}
    private final ResourcePackIndex resources;private final ResourceImages images;
    private final Map<String,Face> faces=new LinkedHashMap<>(8,.75f,true);
    private final Font fallback;
    // Primitive identity never retains a Glyph, its source sheet, or a shared raster.
    private long nextGlyphIdentity;
    private long glyphIdentity(){if(nextGlyphIdentity==Long.MAX_VALUE)throw new IllegalStateException("Font glyph identity exhausted");return ++nextGlyphIdentity;}
    private record Ink(long glyphIdentity,int rgb){}
    private final Map<Ink,BufferedImage> inks=new LinkedHashMap<>(128,.75f,true);private long inkPixels;
    private BufferedImage ink(Glyph glyph,int rgb){
        var key=new Ink(glyph.identity(),rgb);var image=inks.get(key);if(image!=null)return image;image=new BufferedImage(glyph.w(),glyph.h(),BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<glyph.h();y++)for(int x=0;x<glyph.w();x++){int source=glyph.sheet().getRGB(glyph.x()+x,glyph.y()+y);int red=((source>>16)&255)*((rgb>>16)&255)/255,green=((source>>8)&255)*((rgb>>8)&255)/255,blue=(source&255)*(rgb&255)/255;image.setRGB(x,y,(source&0xff000000)|(red<<16)|(green<<8)|blue);}
        inks.put(key,image);inkPixels+=(long)image.getWidth()*image.getHeight();while(inks.size()>128||inkPixels>1024*1024){var first=inks.keySet().iterator().next();var removed=inks.remove(first);inkPixels-=(long)removed.getWidth()*removed.getHeight();}return image;
    }
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
                for(var entry:advances.entrySet()){double advance=entry.getValue().getAsDouble();if(entry.getKey().codePointCount(0,entry.getKey().length())!=1||!Double.isFinite(advance)||advance<0||advance>32)throw new IOException("Invalid font space");glyphs.putIfAbsent(entry.getKey().codePointAt(0),new Glyph(glyphIdentity(),null,0,0,0,0,8,7,advance));}
            }else if(type.equals("bitmap")){
                BufferedImage image=images.image(provider.get("file").getAsString());var chars=provider.getAsJsonArray("chars");
                budget[1]+=(long)image.getWidth()*image.getHeight();if(budget[1]>1024*1024)throw new IOException("Font sheet pixel budget exceeded");
                int height=provider.has("height")?provider.get("height").getAsInt():8,ascent=provider.get("ascent").getAsInt();
                if(chars.isEmpty()||chars.size()>256||height<1||height>32||ascent<0||ascent>height)throw new IOException("Invalid bitmap font grid");
                int columns=chars.get(0).getAsString().codePointCount(0,chars.get(0).getAsString().length());if(columns<1||columns>256||columns*chars.size()>4096||image.getWidth()%columns!=0||image.getHeight()%chars.size()!=0)throw new IOException("Invalid bitmap font grid");
                int w=image.getWidth()/columns,h=image.getHeight()/chars.size();
                for(int row=0;row<chars.size();row++){int[] codepoints=chars.get(row).getAsString().codePoints().toArray();if(codepoints.length!=columns)throw new IOException("Inconsistent bitmap font row");
                    for(int col=0;col<columns;col++){if(codepoints[col]==0)continue;int ink=0;for(int x=0;x<w;x++)for(int y=0;y<h;y++)if((image.getRGB(col*w+x,row*h+y)>>>24)!=0)ink=Math.max(ink,x+1);
                        glyphs.putIfAbsent(codepoints[col],new Glyph(glyphIdentity(),image,col*w,row*h,w,h,height,ascent,(double)ink*height/h+1));
                    }
                }
            }else unsupported=true;
            if(glyphs.size()>4096)throw new IOException("Font glyph budget exceeded");
        }
        path.remove(id);return unsupported;
    }
    boolean usesFallback(int cp,String id){return !face(id).glyphs().containsKey(cp);}
    boolean missing(int cp,String id){return usesFallback(cp,id)&&!fallback.canDisplay(cp);}
    private Font styledFont(ItemTooltip.Run run,int size){return fallback.deriveFont((run.bold()?Font.BOLD:Font.PLAIN)|(run.italic()?Font.ITALIC:Font.PLAIN),(float)size);}
    int measure(Graphics2D g,int cp,ItemTooltip.Run run,int size){
        Glyph glyph=face(run.font()).glyphs().get(cp);if(glyph==null){g.setFont(styledFont(run,size));return Math.max(0,g.getFontMetrics().stringWidth(new String(Character.toChars(missing(cp,run.font())?'?':cp))));}
        return Math.max(0,(int)Math.ceil(glyph.advance()*(double)size/Math.max(9,glyph.height()))+(run.bold()?1:0)+(run.italic()?2:0));
    }
    void glyph(Graphics2D g,int cp,ItemTooltip.Run run,int size,int x,int baseline){
        Glyph glyph=face(run.font()).glyphs().get(cp);int advance=measure(g,cp,run,size);
        if(glyph==null){g.setFont(styledFont(run,size));g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);g.drawString(new String(Character.toChars(missing(cp,run.font())?'?':cp)),x,baseline);}
        else if(glyph.sheet()!=null){
            double scale=(double)size/Math.max(9,glyph.height());int h=(int)Math.round(glyph.height()*scale),w=(int)Math.round((double)glyph.w()*glyph.height()/glyph.h()*scale),top=baseline-(int)Math.round(glyph.ascent()*scale);var transform=g.getTransform();
            if(run.italic()){g.translate(x,top);g.shear(-.15,0);g.translate(-x,-top);}
            var ink=ink(glyph,g.getColor().getRGB());g.drawImage(ink,x,top,w,h,null);
            if(run.bold())g.drawImage(ink,x+1,top,w,h,null);g.setTransform(transform);
        }
        if(run.underline())g.fillRect(x,baseline+2,advance,1);if(run.strike())g.fillRect(x,baseline-size/3,advance,1);
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
