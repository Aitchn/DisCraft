package dev.discraft.core;

import java.util.*;
final class ItemMarkersTest {
    static void run(){
        for(String text:List.of("%inventory","%itemized","%i_","%i9","%ITEM","plain"))if(ItemMarkers.contains(text))throw new AssertionError("Unexpected marker prefix: "+text);
        String text="😀前%i後 %item/%i %inventory";var tokens=ItemMarkers.spans(text);if(tokens.size()!=3||tokens.get(0).start()!=3||tokens.get(0).length()!=2||tokens.get(1).length()!=5||tokens.get(2).length()!=2)throw new AssertionError("UTF-16/longest-match marker spans");
        if(!ItemMarkers.contains("我的%i！")||ItemMarkers.spans("%i %item ".repeat(4)).size()!=8)throw new AssertionError("Mixed marker count/CJK adjacency");
        if(ItemMarkers.contains("\ud83d%i"))throw new AssertionError("Invalid Unicode routing hint");try{ItemMarkers.spans("\ud83d%i");throw new AssertionError("Strict parser accepted invalid Unicode");}catch(IllegalArgumentException expected){}
        System.out.println("PASS 10 common item alias/longest-match/word-boundary/UTF16 checks");
    }
}
