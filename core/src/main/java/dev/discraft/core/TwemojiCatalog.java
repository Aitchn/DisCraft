package dev.discraft.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Pinned local artwork and explicit Unicode-sequence aliases. Never normalizes transmitted text. */
public final class TwemojiCatalog {
    public static final String VERSION="17.0.3",INDEX_SHA256="75689f795e2a0b752d827989ef6bb1faa5ccdf2d5007dfbf363721c8089f54ff";
    public static final int CACHE_ENTRIES=96,PIXELS=72*72;
    public record Entry(String value,String asset,String name,int category,boolean picker,String sha256){}
    public record Part(String text,Entry emoji){}
    private static final class Node{final Map<Integer,Node> children=new HashMap<>();Entry entry;}
    private static final Node ROOT=new Node();
    private static final List<Entry> ENTRIES;
    private static final Map<String,Entry> EXACT=new HashMap<>(),ASSETS=new HashMap<>();
    private static final LinkedHashMap<String,Attachment> CACHE=new LinkedHashMap<>(96,.75f,true);
    private static final Set<String> FAILED=new HashSet<>();
    static{
        try(var input=TwemojiCatalog.class.getResourceAsStream("/twemoji/index.tsv")){
            if(input==null)throw new IOException("Missing emoji index");byte[] bytes=input.readNBytes(2*1024*1024+1);if(bytes.length>2*1024*1024||!sha(bytes).equals(INDEX_SHA256))throw new IOException("Emoji index validation failed");
            var entries=new ArrayList<Entry>();for(String line:new String(bytes,StandardCharsets.UTF_8).split("\n")){
                String[] columns=line.split("\t",6);if(columns.length!=6||EXACT.size()>=8192||!columns[1].matches("[0-9a-f]+(?:-[0-9a-f]+){0,31}")||columns[1].length()>256||!columns[4].matches("[0-9a-f]{64}")||columns[5].length()>256)throw new IOException("Emoji index bounds");
                var value=new StringBuilder();String[] points=columns[0].split("-");if(points.length>32)throw new IOException("Emoji sequence bounds");for(String point:points){int cp=Integer.parseInt(point,16);if(!Character.isValidCodePoint(cp)||cp>=0xd800&&cp<=0xdfff)throw new IOException("Invalid emoji point");value.appendCodePoint(cp);}
                int category=Integer.parseInt(columns[2]);if(category<0||category>4)throw new IOException("Emoji category bounds");var entry=new Entry(value.toString(),columns[1],columns[5],category,columns[3].equals("1"),columns[4]);
                if(EXACT.put(entry.value(),entry)!=null)throw new IOException("Duplicate emoji sequence");ASSETS.putIfAbsent(entry.asset(),entry);Node node=ROOT;for(int cp:entry.value().codePoints().toArray())node=node.children.computeIfAbsent(cp,k->new Node());node.entry=entry;if(entry.picker())entries.add(entry);
            }ENTRIES=List.copyOf(entries);
        }catch(Exception invalid){throw new ExceptionInInitializerError(invalid);}
    }
    private TwemojiCatalog(){}
    public static List<Entry> entries(){return ENTRIES;}
    public static Entry exact(String value){return EXACT.get(value);}
    /** Emoji grapheme boundaries prevent partial images inside unknown ZWJ/modifier/VS clusters. */
    private static int clusterEnd(String text,int start){
        int cp=text.codePointAt(start),at=start+Character.charCount(cp);
        if(cp>=0x1f1e6&&cp<=0x1f1ff&&at<text.length()){int next=text.codePointAt(at);if(next>=0x1f1e6&&next<=0x1f1ff)at+=Character.charCount(next);}
        while(at<text.length()){
            int next=text.codePointAt(at);
            if(next==0xfe0e||next==0xfe0f||next==0x20e3||next>=0x1f3fb&&next<=0x1f3ff||next>=0xe0020&&next<=0xe007f){at+=Character.charCount(next);continue;}
            if(next==0x200d){at++;if(at<text.length())at+=Character.charCount(text.codePointAt(at));continue;}break;
        }return at;
    }
    public static List<Part> parts(String text){
        Limits.text(text,Limits.TEXT_CODEPOINTS,"emoji text");var result=new ArrayList<Part>();int plain=0;
        for(int at=0;at<text.length();){int end=clusterEnd(text,at),cursor=at,last=-1;Entry found=null;Node node=ROOT;
            for(int points=0;cursor<end&&points<32;points++){int cp=text.codePointAt(cursor);node=node.children.get(cp);if(node==null)break;cursor+=Character.charCount(cp);if(node.entry!=null){found=node.entry;last=cursor;}}
            if(found!=null&&last==end){if(plain<at)result.add(new Part(text.substring(plain,at),null));result.add(new Part(text.substring(at,end),found));plain=end;}at=end;
        }if(plain<text.length())result.add(new Part(text.substring(plain),null));return List.copyOf(result);
    }
    public static String sha(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception impossible){throw new IllegalStateException(impossible);}}
    public static synchronized Attachment asset(Entry entry){
        var canonical=ASSETS.get(entry.asset());if(canonical==null||!canonical.sha256().equals(entry.sha256())||FAILED.contains(entry.asset()))return null;
        var cached=CACHE.get(entry.asset());if(cached!=null)return cached;
        try(var input=TwemojiCatalog.class.getResourceAsStream("/twemoji/"+entry.asset()+".png")){
            if(input==null)throw new IOException("Missing emoji asset");byte[] bytes=input.readNBytes(65537);if(bytes.length>65536||!sha(bytes).equals(entry.sha256()))throw new IOException("Emoji asset validation failed");var image=Attachment.of("twemoji.png",bytes);if(image.media().width()!=72||image.media().height()!=72||image.media().frames().size()!=1)throw new IOException("Emoji pixel bounds");
            CACHE.put(entry.asset(),image);while(CACHE.size()>CACHE_ENTRIES)CACHE.remove(CACHE.keySet().iterator().next());return image;
        }catch(Exception rejected){FAILED.add(entry.asset());return null;}
    }
    public static synchronized int cachedAssets(){return CACHE.size();}
    public static synchronized int cachedPixels(){return CACHE.size()*PIXELS;}
    public static synchronized void clearAssets(){CACHE.clear();}
}
