package dev.discraft.forge.server;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;
import javax.imageio.ImageIO;

/** Immutable, server-only resource data. ZIPs are read, never extracted or executed. */
final class ResourcePackIndex {
    static final int MAX_PACKS=8,MAX_FILES=20000,MAX_ENTRY=4*1024*1024,MAX_STORED=64*1024*1024;
    static final long MAX_EXPANDED=128L*1024*1024;
    private final Map<String,byte[]> assets;
    private final Map<String,Map<String,String>> languages;
    private final String fingerprint;
    private ResourcePackIndex(Map<String,byte[]> assets,Map<String,Map<String,String>> languages,String fingerprint){
        this.assets=Map.copyOf(assets);var copy=new HashMap<String,Map<String,String>>();languages.forEach((k,v)->copy.put(k,Map.copyOf(v)));this.languages=Map.copyOf(copy);this.fingerprint=fingerprint;
    }
    static ResourcePackIndex empty(){return new ResourcePackIndex(Map.of(),Map.of(),"empty");}
    /** Listed later means higher priority. Every file, including ignored data, consumes the ZIP budget. */
    static ResourcePackIndex load(Path root,List<String> names)throws IOException{
        try{
            if(names.size()>MAX_PACKS)throw new IOException();
            if(names.isEmpty())return empty();
            if(!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS))throw new IOException();
            // Refuse symbolic links in the entire source path, not just the final ZIP filename.
            Path absolute=root.toAbsolutePath().normalize();
            for(Path p=absolute;p!=null;p=p.getParent())if(Files.isSymbolicLink(p))throw new IOException();
            var assets=new LinkedHashMap<String,byte[]>();var languages=new HashMap<String,Map<String,String>>();
            var digest=MessageDigest.getInstance("SHA-256");long total=0,stored=0;int files=0;
            for(String name:names){
                if(!name.matches("[a-zA-Z0-9_-][a-zA-Z0-9_.-]{0,95}\\.zip"))throw new IOException();
                Path file=absolute.resolve(name);if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>MAX_STORED)throw new IOException();
                boolean metadata=false;var seen=new HashSet<String>();
                try(var stream=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS);var zip=new ZipInputStream(stream,StandardCharsets.UTF_8)){
                    for(ZipEntry entry;(entry=zip.getNextEntry())!=null;){
                        if(++files>MAX_FILES)throw new IOException();String path=entry.getName();
                        if(!safePath(path)||!seen.add(path))throw new IOException();
                        // Directory entries can carry compressed payload too: account for every byte.
                        boolean keep=path.equals("pack.mcmeta")||path.matches("assets/[a-z0-9_.-]+/(lang/[a-z0-9_]+\\.json|models/[a-z0-9_./-]+\\.json|textures/[a-z0-9_./-]+\\.png|font/[a-z0-9_./-]+\\.json)");
                        var bytes=keep?new ByteArrayOutputStream():null;long size=0;byte[] buffer=new byte[8192];
                        for(int n;(n=zip.read(buffer))!=-1;){size+=n;total+=n;if(size>MAX_ENTRY||total>MAX_EXPANDED)throw new IOException();if(keep)bytes.write(buffer,0,n);}
                        if(!keep)continue;
                        byte[] value=bytes.toByteArray();digest.update(path.getBytes(StandardCharsets.UTF_8));digest.update((byte)0);digest.update(value);digest.update((byte)0);
                        if(path.equals("pack.mcmeta")){
                            var pack=json(value).getAsJsonObject("pack");var format=pack.get("pack_format");
                            if(!format.isJsonPrimitive()||!format.getAsJsonPrimitive().isNumber()||!format.getAsString().equals("15"))throw new IOException();metadata=true;continue;
                        }
                        if(path.endsWith(".json")){
                            var object=json(value);
                            if(path.contains("/lang/")){
                                String locale=path.substring(path.lastIndexOf('/')+1,path.length()-5);
                                var language=languages.computeIfAbsent(locale,k->new HashMap<>());
                                for(var member:object.entrySet()){
                                    if(member.getKey().length()>256||!member.getValue().isJsonPrimitive()||!member.getValue().getAsJsonPrimitive().isString())throw new IOException();
                                    String text=member.getValue().getAsString();if(text.length()>8192||language.size()>=100000&&!language.containsKey(member.getKey()))throw new IOException();language.put(member.getKey(),text);
                                }
                            }
                        }else validateImage(value);
                        var previous=assets.put(path,value);stored+=value.length-(previous==null?0:previous.length);if(stored>MAX_STORED)throw new IOException();
                    }
                }
                if(!metadata)throw new IOException();
            }
            return new ResourcePackIndex(assets,languages,HexFormat.of().formatHex(digest.digest()));
        }catch(Exception rejected){throw new IOException("Invalid or unreadable DisCraft resource pack");}
    }
    private static boolean safePath(String path){
        if(path.length()>256||path.startsWith("/")||path.indexOf('\\')>=0||path.indexOf(':')>=0||path.chars().anyMatch(Character::isISOControl))return false;
        for(String part:path.split("/",-1))if(part.equals(".")||part.equals(".."))return false;
        return true;
    }
    /** Bound nesting before Gson's recursive parser; reject non-object documents. */
    static JsonObject json(byte[] bytes)throws IOException{
        if(bytes.length>1024*1024)throw new IOException("Resource JSON budget exceeded");
        String source=StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        int depth=0;boolean string=false,escape=false;
        for(int i=0;i<source.length();i++){char c=source.charAt(i);if(string){if(escape)escape=false;else if(c=='\\')escape=true;else if(c=='"')string=false;}else if(c=='"')string=true;else if(c=='{'||c=='['){if(++depth>32)throw new IOException("Resource JSON depth exceeded");}else if(c=='}'||c==']')depth--;}
        return JsonParser.parseString(source).getAsJsonObject();
    }
    private static void validateImage(byte[] bytes)throws IOException{
        try(var input=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))){
            var readers=ImageIO.getImageReaders(input);if(!readers.hasNext())throw new IOException();var reader=readers.next();
            try{reader.setInput(input,true,true);int w=reader.getWidth(0),h=reader.getHeight(0);if(!reader.getFormatName().equalsIgnoreCase("png")||w<1||h<1||w>4096||h>4096||(long)w*h>4*1024*1024)throw new IOException();}finally{reader.dispose();}
        }
    }
    String translation(String locale,String key,String fallback){
        String english=languages.getOrDefault("en_us",Map.of()).getOrDefault(key,fallback);
        return languages.getOrDefault(locale,Map.of()).getOrDefault(key,english);
    }
    byte[] asset(String path){byte[] bytes=assets.get(path);return bytes==null?null:bytes.clone();}
    String fingerprint(){return fingerprint;}
    int assetCount(){return assets.size();}
    @Override public String toString(){return "ResourcePackIndex["+assets.size()+" assets]";}
}
