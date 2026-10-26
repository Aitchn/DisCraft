package dev.discraft.forge.server;

import dev.discraft.core.Attachment;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import javax.imageio.ImageIO;
import java.util.*;
import java.util.List;

/** Independently drawn native tooltip-style PNG. Never crops/clips semantic text or invokes client rendering. */
final class ItemTooltipRenderer {
    static final int MAX_WIDTH=768,MAX_HEIGHT=1536,MAX_ROWS=60;
    record Result(Attachment attachment,int width,int height,boolean fontFallback,List<String> missingGlyphs){}
    private record Cell(int cp,ItemTooltip.Run run,int width){}
    private final PackBitmapFont font;private final Map<ItemTooltip,Result> cache=new LinkedHashMap<>(32,.75f,true);private long cacheBytes;
    ItemTooltipRenderer(ResourcePackIndex resources)throws Exception{this(new PackBitmapFont(resources,new ResourceImages(resources)));}
    ItemTooltipRenderer(PackBitmapFont font){this.font=font;}
    int cachedItems(){return cache.size();}
    long cachedBytes(){return cacheBytes;}
    private static void deadline(long started){if(System.nanoTime()-started>2_000_000_000L)throw new IllegalArgumentException("Tooltip rendering deadline exceeded");}
    Result render(ItemTooltip tooltip)throws Exception{
        var cached=cache.get(tooltip);if(cached!=null)return cached;
        long started=System.nanoTime();
        final int size=18,padding=10,lineHeight=24;var measure=new BufferedImage(1,1,BufferedImage.TYPE_INT_ARGB);var g=measure.createGraphics();var rows=new ArrayList<List<Cell>>();int contentRows=0;boolean fallback=false;var missing=new TreeSet<String>();
        try{
            for(var line:tooltip.lines()){
                var row=new ArrayList<Cell>();int width=0;
                for(var run:line.runs())for(int cp:run.text().codePoints().toArray()){
                    deadline(started);if(cp=='\r')continue;if(cp=='\n'){rows.add(List.copyOf(row));row.clear();width=0;if(rows.size()>MAX_ROWS)throw new IllegalArgumentException("Tooltip needs too many rows");continue;}
                    if(cp=='\t')cp=' ';if(Character.isISOControl(cp))continue;
                    if(run.obfuscated()&&!Character.isWhitespace(cp))cp='?';int advance=font.measure(g,cp,run,size);
                    if(advance>MAX_WIDTH-padding*2)throw new IllegalArgumentException("Tooltip glyph exceeds width budget");
                    if(width+advance>MAX_WIDTH-padding*2){rows.add(List.copyOf(row));row.clear();width=0;}
                    if(rows.size()>MAX_ROWS)throw new IllegalArgumentException("Tooltip needs too many rows");row.add(new Cell(cp,run,advance));width+=advance;fallback|=font.usesFallback(cp,run.font());if(font.missing(cp,run.font()))missing.add("U+"+Integer.toHexString(cp).toUpperCase(Locale.ROOT));
                }rows.add(List.copyOf(row));if(rows.size()>MAX_ROWS)throw new IllegalArgumentException("Tooltip needs too many rows");
            }
            if(rows.isEmpty())throw new IllegalArgumentException("Empty tooltip");
            // Font fallback remains internal metadata; only actual missing glyphs need a visible notice.
            contentRows=rows.size();
            if(!missing.isEmpty()){var run=new ItemTooltip.Run("",0xaaaaaa,"minecraft:default",false,false,false,false,false);if(missing.size()>16)throw new IllegalArgumentException("Tooltip missing glyph budget exceeded");String notice="Missing glyphs: "+String.join(", ",missing);
                var row=new ArrayList<Cell>();int width=0;for(int cp:notice.codePoints().toArray()){int advance=font.measure(g,cp,run,12);if(width+advance>MAX_WIDTH-padding*2){rows.add(List.copyOf(row));row.clear();width=0;}row.add(new Cell(cp,run,advance));width+=advance;}rows.add(List.copyOf(row));}
        }finally{g.dispose();}
        int width=Math.max(64,rows.stream().mapToInt(r->r.stream().mapToInt(Cell::width).sum()).max().orElse(0)+padding*2),height=padding*2+rows.size()*lineHeight+4;
        if(width>MAX_WIDTH||height>MAX_HEIGHT)throw new IllegalArgumentException("Tooltip dimensions exceed budget");
        var image=new BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB);g=image.createGraphics();
        try{
            g.setColor(new Color(16,0,16));g.fillRect(1,1,width-2,height-2);g.setColor(new Color(36,0,56));g.drawRect(0,0,width-1,height-1);g.setPaint(new GradientPaint(0,1,new Color(92,32,148),0,height-2,new Color(42,11,72)));g.drawRect(2,2,width-5,height-5);
            for(int y=0;y<rows.size();y++){int x=padding,baseline=padding+18+y*lineHeight+(y>0?4:0);boolean notice=y>=contentRows;for(var cell:rows.get(y)){deadline(started);
                    int drawSize=notice?12:18;g.setColor(new Color((cell.run().color()&0xfcfcfc)>>2));font.glyph(g,cell.cp(),cell.run(),drawSize,x+1,baseline+1);g.setColor(new Color(cell.run().color()));font.glyph(g,cell.cp(),cell.run(),drawSize,x,baseline);x+=cell.width();}
            }
        }finally{g.dispose();}
        var bytes=new ByteArrayOutputStream();ImageIO.write(image,"png",bytes);if(bytes.size()>256*1024)throw new IOException("Tooltip PNG exceeds media budget");var result=new Result(Attachment.of("item.png",bytes.toByteArray()),width,height,fallback,List.copyOf(missing));cache.put(tooltip,result);cacheBytes+=bytes.size();while(cache.size()>32||cacheBytes>4L*1024*1024){var first=cache.keySet().iterator().next();cacheBytes-=cache.remove(first).attachment().bytes().length;}return result;
    }
}
