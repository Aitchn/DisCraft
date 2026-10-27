package dev.discraft.core;

import java.util.*;

/** Shared UTF-16 marker spans. Percent starts a token; an ASCII word suffix prevents expansion. */
public final class ItemMarkers {
    public record Span(int start,int end){public int length(){return end-start;}}
    private ItemMarkers(){}
    private static boolean word(char c){return c>='a'&&c<='z'||c>='A'&&c<='Z'||c>='0'&&c<='9'||c=='_';}
    public static List<Span> spans(String text){
        Limits.text(text,Limits.TEXT_CODEPOINTS,"item marker text");var spans=new ArrayList<Span>();
        for(int at=0;at<text.length();){int size=text.startsWith("%item",at)?5:text.startsWith("%i",at)?2:0;
            if(size>0&&(at+size==text.length()||!word(text.charAt(at+size)))){spans.add(new Span(at,at+size));at+=size;}else at++;
        }return List.copyOf(spans);
    }
    public static boolean contains(String text){try{return !spans(text).isEmpty();}catch(IllegalArgumentException invalid){return false;}}
}
