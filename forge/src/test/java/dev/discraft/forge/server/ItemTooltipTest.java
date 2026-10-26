package dev.discraft.forge.server;

import java.util.*;
import java.io.ByteArrayInputStream;
import javax.imageio.ImageIO;

final class ItemTooltipTest {
    static void run(ResourcePackIndex resources)throws Exception{
        FontInkCacheTest.run();
        TooltipExpansionBudgetTest.run();
        int checks=0;var renderer=new ItemTooltipRenderer(resources);var white=ItemTooltip.nameOnly("星","test:custom");var image=renderer.render(white);var decoded=ImageIO.read(new ByteArrayInputStream(image.attachment().bytes()));boolean orange=false;for(int y=5;y<40;y++)for(int x=5;x<40;x++)orange|=decoded.getRGB(x,y)==0xffff8000;
        if(!orange||image.fontFallback())throw new AssertionError("Imported bitmap glyph not rendered without fallback");checks++;
        var colored=new ItemTooltip(List.of(new ItemTooltip.Line(List.of(new ItemTooltip.Run("星",0x00ffff,"test:custom",false,false,false,false,false)))));var tint=renderer.render(colored);decoded=ImageIO.read(new ByteArrayInputStream(tint.attachment().bytes()));boolean modulated=false;for(int y=5;y<40;y++)for(int x=5;x<40;x++)modulated|=decoded.getRGB(x,y)==0xff008000;
        if(!modulated||Arrays.equals(image.attachment().bytes(),tint.attachment().bytes()))throw new AssertionError("Bitmap style RGB not modulated or cache key omitted color");checks++;
        var fallback=renderer.render(ItemTooltip.nameOnly("Hi","minecraft:default"));if(!fallback.fontFallback()||fallback.width()!=64||fallback.height()!=48)throw new AssertionError("Font fallback metadata changed or diagnostic footer still affects PNG size");checks++;
        var missing=renderer.render(ItemTooltip.nameOnly(new String(Character.toChars(0x10ffff)),"minecraft:default"));if(!missing.missingGlyphs().contains("U+10FFFF")||missing.height()!=72)throw new AssertionError("Actual missing-glyph notice disappeared together with the font-source footer");checks++;
        var longer=renderer.render(ItemTooltip.nameOnly("1234567890".repeat(70),"minecraft:default"));if(longer.width()>768||longer.height()<=image.height())throw new AssertionError("Measured wrapping absent");checks++;
        var mutable=new ArrayList<ItemTooltip.Run>();mutable.add(white.lines().get(0).runs().get(0));var line=new ItemTooltip.Line(mutable);mutable.clear();if(!line.plain().equals("星"))throw new AssertionError("Mutable semantic data retained");checks++;
        if(renderer.render(white)!=image)throw new AssertionError("Tooltip cache miss for identical immutable content");checks++;
        for(int i=0;i<40;i++)renderer.render(ItemTooltip.nameOnly("Cache "+i,"minecraft:default"));if(renderer.cachedItems()>32||renderer.cachedBytes()>4L*1024*1024)throw new AssertionError("Tooltip cache exceeded bounds");checks++;
        System.out.println("PASS "+checks+" adaptive tooltip bitmap/style/wrap/cache checks");
    }
}
