package dev.discraft.forge.server;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Immutable bounded language data; no resource paths, native client classes or executable content. */
final class LanguageIndex {
    static final int MAX_KEY=512,MAX_VALUE=8192,MAX_PER_LOCALE=250000,MAX_KEYS=750000,MAX_LOCALES=64;
    static final long MAX_UNITS=32L*1024*1024;
    private final Map<String,Map<String,String>> tables;
    private LanguageIndex(Map<String,Map<String,String>> source){var out=new HashMap<String,Map<String,String>>();source.forEach((locale,table)->out.put(locale,Map.copyOf(table)));tables=Map.copyOf(out);}
    static LanguageIndex empty(){return new LanguageIndex(Map.of());}
    static final class Builder {
        private final Map<String,Map<String,String>> tables=new HashMap<>();private long units;private int keys;
        void put(String locale,String key,String value)throws IOException{
            if(!locale.matches("[a-z0-9_]{1,32}")||key.length()>MAX_KEY||value.length()>MAX_VALUE)throw new IOException("Language field budget exceeded");
            value=normalizeNumbers(value);
            var table=tables.get(locale);if(table==null){if(tables.size()>=MAX_LOCALES)throw new IOException("Language locale budget exceeded");table=new HashMap<>();tables.put(locale,table);}
            String previous=table.get(key);if(previous==null&&(table.size()>=MAX_PER_LOCALE||keys>=MAX_KEYS))throw new IOException("Language key budget exceeded");
            long next=units+(previous==null?key.length()+value.length():value.length()-previous.length());if(next>MAX_UNITS)throw new IOException("Language text budget exceeded");
            table.put(key,value);units=next;if(previous==null)keys++;
        }
        void json(String locale,JsonObject object)throws IOException{for(var member:object.entrySet()){if(!member.getValue().isJsonPrimitive()||!member.getValue().getAsJsonPrimitive().isString())throw new IOException("Language values must be strings");put(locale,member.getKey(),member.getValue().getAsString());}}
        void add(LanguageIndex index)throws IOException{for(var locale:new TreeMap<>(index.tables).entrySet())for(var entry:new TreeMap<>(locale.getValue()).entrySet())put(locale.getKey(),entry.getKey(),entry.getValue());}
        LanguageIndex build(){return new LanguageIndex(tables);}
    }
    /** Minecraft numeric printf placeholders become component string parameters; linear scan only. */
    private static boolean digit(char c){return c>='0'&&c<='9';}
    private static String normalizeNumbers(String value){
        if(value.indexOf('%')<0)return value;
        var out=new StringBuilder();for(int at=0;at<value.length();){int begin=at;if(value.charAt(at++)!='%'){out.append(value.charAt(begin));continue;}int digits=at;while(at<value.length()&&digit(value.charAt(at)))at++;boolean indexed=at>digits&&at<value.length()&&value.charAt(at)=='$';String index=indexed?value.substring(digits,++at):"";if(!indexed)at=digits;while(at<value.length()&&(digit(value.charAt(at))||value.charAt(at)=='.'))at++;if(at<value.length()&&(value.charAt(at)=='d'||value.charAt(at)=='f')){out.append('%').append(index).append('s');at++;}else{out.append('%');at=begin+1;}}return out.toString();
    }
    String translation(String locale,String key,String fallback){String english=tables.getOrDefault("en_us",Map.of()).getOrDefault(key,fallback);return tables.getOrDefault(locale,Map.of()).getOrDefault(key,english);}
    Set<String> englishKeys(){return tables.getOrDefault("en_us",Map.of()).keySet();}
    int keys(String locale){return tables.getOrDefault(locale,Map.of()).size();}
    String fingerprint(){try{var digest=MessageDigest.getInstance("SHA-256");for(var locale:new TreeMap<>(tables).entrySet())for(var entry:new TreeMap<>(locale.getValue()).entrySet())for(String text:List.of(locale.getKey(),entry.getKey(),entry.getValue())){digest.update(text.getBytes(StandardCharsets.UTF_8));digest.update((byte)0);}return HexFormat.of().formatHex(digest.digest());}catch(Exception impossible){throw new IllegalStateException("Language fingerprint unavailable");}}
}
