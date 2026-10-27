package dev.discraft.core;

import java.util.*;
public final class TwemojiTest {
    private static int checks;
    private static void check(boolean value,String label){if(!value)throw new AssertionError(label);checks++;}
    static void run()throws Exception{
        for(String emoji:List.of("😀","👍🏽","👩🏽‍💻","👨‍👩‍👧‍👦","🇹🇼","1️⃣","1⃣","🏳️‍🌈","❤️","🫩")){var parts=TwemojiCatalog.parts("前"+emoji+"後");check(parts.size()==3&&parts.get(1).emoji()!=null&&parts.get(1).text().equals(emoji),"Whole emoji sequence: "+emoji);check(parts.stream().map(TwemojiCatalog.Part::text).reduce("",String::concat).equals("前"+emoji+"後"),"Unchanged Unicode roundtrip");}
        for(String text:List.of("😀\u0301","😀"+new String(Character.toChars(0x1e4ec)),"🇹🇼\u0301","\u0600😀","😀\u0903","❤","❤\ufe0e","😀\ufe0e","😀\u200dA","👍🏽\u200dA","#","123","文字")){check(TwemojiCatalog.parts(text).stream().noneMatch(p->p.emoji()!=null),"Text/VS15/unknown cluster fallback remains literal: "+text);}
        check(TwemojiCatalog.parts("😀😀").size()==2&&TwemojiCatalog.parts("👩🏽‍💻").size()==1,"Longest match keeps adjacent emoji separate and ZWJ/skin sequence whole");
        String extended="😀"+"\u0301".repeat(1999);check(TwemojiCatalog.parts(extended).size()==1&&TwemojiCatalog.parts(extended).get(0).text().equals(extended)&&TwemojiCatalog.parts(extended).get(0).emoji()==null,"Maximum bounded Extend chain stays one literal cluster");
        try{TwemojiCatalog.parts(extended+"\u0301");throw new AssertionError("Missing input bound");}catch(IllegalArgumentException expected){check(true,"Oversize Extend chain rejected before scanning");}
        check(TwemojiCatalog.entries().size()==3953,"Pinned full picker inventory");
        try(var input=TwemojiTest.class.getResourceAsStream("/twemoji/index.tsv")){for(String line:new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).split("\n")){String[] fields=line.split("\t",6);var sequence=new StringBuilder();for(String hex:fields[0].split("-"))sequence.appendCodePoint(Integer.parseInt(hex,16));var parsed=TwemojiCatalog.parts(sequence.toString());check(parsed.size()==1&&parsed.get(0).emoji()!=null&&parsed.get(0).emoji().asset().equals(fields[1])&&parsed.get(0).text().equals(sequence.toString()),"Every pinned qualified/alias sequence still matches as one unchanged emoji");}}
        var assets=new HashSet<String>();for(var entry:TwemojiCatalog.entries())if(assets.add(entry.asset()))try(var in=TwemojiTest.class.getResourceAsStream("/twemoji/"+entry.asset()+".png")){check(in!=null&&TwemojiCatalog.sha(in.readNBytes(65537)).equals(entry.sha256()),"All packaged artwork matches pinned checksums");check(TwemojiCatalog.asset(entry)!=null,"All packaged PNGs decode through the bounded production loader");}
        TwemojiCatalog.clearAssets();for(var entry:TwemojiCatalog.entries().subList(0,130)){var image=TwemojiCatalog.asset(entry);check(image!=null&&image.media().frames().get(0).image().getRaster().getParent()==null,"Independent PNG backing raster");}check(TwemojiCatalog.cachedAssets()==96&&TwemojiCatalog.cachedPixels()==497664,"Lazy decoded LRU has a real pixel/entry bound");
        System.out.println("PASS "+checks+" Twemoji sequence/checksum/cache checks (3953 original PNG assets; no network)");
    }
}
