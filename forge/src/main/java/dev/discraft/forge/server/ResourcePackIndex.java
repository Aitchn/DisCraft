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
    private final LanguageIndex languages;
    private final Map<String,byte[]> vanilla;private final InstalledModAssets installed;
    private final String fingerprint;
    private ResourcePackIndex(Map<String,byte[]> assets,LanguageIndex languages,String fingerprint){
        this(assets,languages,fingerprint,Map.of(),null);
    }
    private ResourcePackIndex(Map<String,byte[]> assets,LanguageIndex languages,String fingerprint,Map<String,byte[]> vanilla,InstalledModAssets installed){
        this.assets=Map.copyOf(assets);this.languages=languages;this.fingerprint=fingerprint;this.vanilla=Map.copyOf(vanilla);this.installed=installed;
    }
    static ResourcePackIndex empty(){return new ResourcePackIndex(Map.of(),LanguageIndex.empty(),"empty");}
    /** Listed later means higher priority. Every file, including ignored data, consumes the ZIP budget. */
    static ResourcePackIndex load(Path root,List<String> names)throws IOException{
        try{
            if(names.size()>MAX_PACKS)throw new IOException();
            if(names.isEmpty())return empty();
            if(!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS))throw new IOException();
            // Refuse symbolic links in the entire source path, not just the final ZIP filename.
            Path absolute=root.toAbsolutePath().normalize();
            for(Path p=absolute;p!=null;p=p.getParent())if(Files.isSymbolicLink(p))throw new IOException();
            var assets=new LinkedHashMap<String,byte[]>();var languages=new LanguageIndex.Builder();
            var digest=MessageDigest.getInstance("SHA-256");long total=0,stored=0;int files=0;
            for(String name:names){
                if(!name.matches("[a-zA-Z0-9_-][a-zA-Z0-9_.-]{0,95}\\.zip"))throw new IOException();
                Path file=absolute.resolve(name);if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>MAX_STORED)throw new IOException();
                boolean metadata=false;var seen=new HashSet<String>();var languagePaths=new TreeSet<String>();
                try(var stream=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS);var zip=new ZipInputStream(stream,StandardCharsets.UTF_8)){
                    for(ZipEntry entry;(entry=zip.getNextEntry())!=null;){
                        if(++files>MAX_FILES)throw new IOException();String path=entry.getName();
                        if(!safePath(path)||!seen.add(path))throw new IOException();
                        // Directory entries can carry compressed payload too: account for every byte.
                        boolean keep=path.equals("pack.mcmeta")||path.matches("assets/[a-z0-9_.-]+/(lang/[a-z0-9_]+\\.json|models/[a-z0-9_./-]+\\.json|textures/[a-z0-9_./-]+\\.png(?:\\.mcmeta)?|font/[a-z0-9_./-]+\\.json)");
                        var bytes=keep?new ByteArrayOutputStream():null;long size=0;byte[] buffer=new byte[8192];
                        for(int n;(n=zip.read(buffer))!=-1;){size+=n;total+=n;if(size>MAX_ENTRY||total>MAX_EXPANDED)throw new IOException();if(keep)bytes.write(buffer,0,n);}
                        if(!keep)continue;
                        byte[] value=bytes.toByteArray();digest.update(path.getBytes(StandardCharsets.UTF_8));digest.update((byte)0);digest.update(value);digest.update((byte)0);
                        if(path.equals("pack.mcmeta")){
                            var pack=json(value).getAsJsonObject("pack");var format=pack.get("pack_format");
                            if(!format.isJsonPrimitive()||!format.getAsJsonPrimitive().isNumber()||!format.getAsString().equals("15"))throw new IOException();metadata=true;continue;
                        }
                        if(path.endsWith(".json")||path.endsWith(".mcmeta")){
                            var object=json(value);
                            if(path.contains("/lang/"))languagePaths.add(path);
                        }else validateImage(value);
                        var previous=assets.put(path,value);stored+=value.length-(previous==null?0:previous.length);if(stored>MAX_STORED)throw new IOException();
                    }
                }
                if(!metadata)throw new IOException();
                for(String path:languagePaths){String locale=path.substring(path.lastIndexOf('/')+1,path.length()-5);languages.json(locale,json(assets.get(path)));}
            }
            return new ResourcePackIndex(assets,languages.build(),HexFormat.of().formatHex(digest.digest()));
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
    static void validateImage(byte[] bytes)throws IOException{
        try(var input=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))){
            var readers=ImageIO.getImageReaders(input);if(!readers.hasNext())throw new IOException();var reader=readers.next();
            try{reader.setInput(input,true,true);int w=reader.getWidth(0),h=reader.getHeight(0);if(!reader.getFormatName().equalsIgnoreCase("png")||w<1||h<1||w>4096||h>4096||(long)w*h>4*1024*1024)throw new IOException();}finally{reader.dispose();}
        }
    }
    String translation(String locale,String key,String fallback){
        return languages.translation(locale,key,fallback);
    }
    ResourcePackIndex withBase(LanguageIndex base)throws IOException{
        var merged=new LanguageIndex.Builder();merged.add(base);merged.add(languages);
        return new ResourcePackIndex(assets,merged.build(),base.fingerprint()+":"+fingerprint,vanilla,installed);
    }
    int languageKeys(String locale){return languages.keys(locale);}

    ResourcePackIndex withVanilla(ResourcePackIndex lower,InstalledModAssets modAssets)throws IOException{
        var merged=new LanguageIndex.Builder();merged.add(lower.languages);
        // Forge already merges installed en_us. Overlay only vanilla English keys, avoiding another mod scan.
        for(String key:lower.languages.englishKeys()){String nativeValue=net.minecraft.locale.Language.getInstance().getOrDefault(key,null);if(nativeValue!=null)merged.put("en_us",key,nativeValue);}
        merged.add(languages);return new ResourcePackIndex(assets,merged.build(),lower.fingerprint+":"+fingerprint,lower.assets,modAssets);
    }
    byte[] asset(String path){byte[] bytes=assets.get(path);if(bytes!=null)return bytes.clone();if(installed!=null){var mod=installed.asset(path);if(mod!=null)return mod;}bytes=vanilla.get(path);return bytes==null?null:bytes.clone();}
    static ResourcePackIndex vanilla(Path jar,String locale,byte[] localeBytes,String fingerprint)throws IOException{
        var assets=new LinkedHashMap<String,byte[]>();var languages=new LanguageIndex.Builder();var names=new HashSet<String>();int files=0;long expanded=0,stored=0;
        try(var in=Files.newInputStream(jar,LinkOption.NOFOLLOW_LINKS);var zip=new ZipInputStream(in,StandardCharsets.UTF_8)){
            for(ZipEntry entry;(entry=zip.getNextEntry())!=null;){String path=entry.getName();if(++files>30000||!safePath(path)||!names.add(path))throw new IOException("Vanilla ZIP entries rejected");
                boolean keep=path.matches("assets/minecraft/(lang/[a-z0-9_]+\\.json|models/[a-z0-9_./-]+\\.json|textures/[a-z0-9_./-]+\\.png(?:\\.mcmeta)?|font/[a-z0-9_./-]+\\.json)");var output=keep?new ByteArrayOutputStream():null;int size=0;byte[] buffer=new byte[8192];
                for(int n;(n=zip.read(buffer))!=-1;){size+=n;expanded+=n;if(size>MAX_ENTRY||expanded>64L*1024*1024)throw new IOException("Vanilla ZIP expansion limit");if(keep)output.write(buffer,0,n);}
                if(!keep)continue;byte[] value=output.toByteArray();stored+=value.length;if(stored>16L*1024*1024)throw new IOException("Vanilla resource memory limit");
                if(path.endsWith(".png"))validateImage(value);else{var object=json(value);if(path.contains("/lang/"))languages.json(path.substring(path.lastIndexOf('/')+1,path.length()-5),object);}assets.put(path,value);
            }
        }catch(Exception bad){throw new IOException("Invalid or oversized verified vanilla resources");}
        if(localeBytes!=null)languages.json(locale,json(localeBytes));if(assets.isEmpty())throw new IOException("Vanilla resource content missing");return new ResourcePackIndex(assets,languages.build(),fingerprint);
    }
    static String resourcePath(String id,String directory,String suffix)throws IOException{
        if(id.length()>256)throw new IOException("Invalid resource reference");String[] parts=id.split(":",-1);String namespace=parts.length==1?"minecraft":parts[0],path=parts.length==1?parts[0]:parts.length==2?parts[1]:"";
        if(!namespace.matches("[a-z0-9_.-]+")||!path.matches("[a-z0-9_./-]+")||path.startsWith("/")||path.contains("//")||!safePath(path))throw new IOException("Invalid resource reference");
        return "assets/"+namespace+"/"+directory+"/"+path+suffix;
    }
    String fingerprint(){return fingerprint;}
    int assetCount(){return assets.size();}
    @Override public String toString(){return "ResourcePackIndex["+assets.size()+" assets]";}
}
