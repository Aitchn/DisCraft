package dev.discraft.core;

import java.util.*;
public final class TwemojiTest {
    private static int checks;
    private static void check(boolean value,String label){if(!value)throw new AssertionError(label);checks++;}
    static void run()throws Exception{
        for(String emoji:List.of("😀","👍🏽","👩🏽‍💻","👨‍👩‍👧‍👦","🇹🇼","1️⃣","1⃣","🏳️‍🌈","❤️","🫩")){var parts=TwemojiCatalog.parts("前"+emoji+"後");check(parts.size()==3&&parts.get(1).emoji()!=null&&parts.get(1).text().equals(emoji),"Whole emoji sequence: "+emoji);check(parts.stream().map(TwemojiCatalog.Part::text).reduce("",String::concat).equals("前"+emoji+"後"),"Unchanged Unicode roundtrip");}
        for(String text:List.of("❤","❤\ufe0e","😀\ufe0e","😀\u200dA","👍🏽\u200dA","#","123","文字")){check(TwemojiCatalog.parts(text).stream().noneMatch(p->p.emoji()!=null),"Text/VS15/unknown cluster fallback remains literal: "+text);}
        check(TwemojiCatalog.parts("😀😀").size()==2&&TwemojiCatalog.parts("👩🏽‍💻").size()==1,"Longest match keeps adjacent emoji separate and ZWJ/skin sequence whole");
        check(TwemojiCatalog.entries().size()==3953,"Pinned full picker inventory");
        var assets=new HashSet<String>();for(var entry:TwemojiCatalog.entries())if(assets.add(entry.asset()))try(var in=TwemojiTest.class.getResourceAsStream("/twemoji/"+entry.asset()+".png")){check(in!=null&&TwemojiCatalog.sha(in.readNBytes(65537)).equals(entry.sha256()),"All packaged artwork matches pinned checksums");check(TwemojiCatalog.asset(entry)!=null,"All packaged PNGs decode through the bounded production loader");}
        TwemojiCatalog.clearAssets();for(var entry:TwemojiCatalog.entries().subList(0,130)){var image=TwemojiCatalog.asset(entry);check(image!=null&&image.media().frames().get(0).image().getRaster().getParent()==null,"Independent PNG backing raster");}check(TwemojiCatalog.cachedAssets()==96&&TwemojiCatalog.cachedPixels()==497664,"Lazy decoded LRU has a real pixel/entry bound");
        System.out.println("PASS "+checks+" Twemoji sequence/checksum/cache checks (3953 original PNG assets; no network)");
    }
}
