package dev.discraft.forge.server;

import com.google.gson.*;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

final class VanillaAssetsTest {
    private static int count;
    private static void check(boolean value,String label){if(!value)throw new AssertionError(label);count++;}
    interface Action{void run()throws Exception;}
    private static void reject(Action action,String label)throws Exception{try{action.run();throw new AssertionError(label);}catch(AssertionError wrong){throw wrong;}catch(Exception expected){count++;}}
    private static byte[] bytes(String s){return s.getBytes(StandardCharsets.UTF_8);}
    private static String hash(byte[] b)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(b));}
    private static byte[] zip(Map<String,byte[]> files)throws Exception{var out=new ByteArrayOutputStream();try(var zip=new ZipOutputStream(out)){for(var entry:files.entrySet()){zip.putNextEntry(new ZipEntry(entry.getKey()));zip.write(entry.getValue());zip.closeEntry();}}return out.toByteArray();}
    static void run()throws Exception{
        byte[] jar=zip(Map.of("assets/minecraft/lang/en_us.json",bytes("{\"item.test\":\"Vanilla English\",\"base.only\":\"Base\"}"),"assets/minecraft/models/item/example.json",bytes("{\"parent\":\"item/generated\"}")));
        byte[] language=bytes("{\"item.test\":\"原版名稱\",\"locale.only\":\"語系\"}");String lh=hash(language),jh=hash(jar);
        byte[] index=bytes("{\"objects\":{\"minecraft/lang/zh_tw.json\":{\"hash\":\""+lh+"\",\"size\":"+language.length+"}}}");String ih=hash(index);
        byte[] descriptor=bytes("{\"id\":\"1.20.1\",\"downloads\":{\"client\":{\"sha1\":\""+jh+"\",\"size\":"+jar.length+",\"url\":\"https://piston-data.mojang.com/test/client.jar\"}},\"assetIndex\":{\"sha1\":\""+ih+"\",\"size\":"+index.length+",\"url\":\"https://piston-meta.mojang.com/test/index.json\"}}");String dh=hash(descriptor);
        byte[] manifest=bytes("{\"versions\":[{\"id\":\"1.20.1\",\"sha1\":\""+dh+"\",\"url\":\"https://piston-meta.mojang.com/test/version.json\"}]}");
        Map<URI,byte[]> responses=Map.of(VanillaAssets.MANIFEST,manifest,URI.create("https://piston-meta.mojang.com/test/version.json"),descriptor,URI.create("https://piston-data.mojang.com/test/client.jar"),jar,URI.create("https://piston-meta.mojang.com/test/index.json"),index,URI.create("https://resources.download.minecraft.net/"+lh.substring(0,2)+"/"+lh),language);
        var seen=new ArrayList<URI>();VanillaAssets.Fetcher fetch=(uri,max)->{seen.add(uri);var value=responses.get(uri);if(value==null)throw new IOException();return value;};
        Path root=Files.createTempDirectory("discraft-vanilla-test-");var base=VanillaAssets.load(root,"zh_tw",fetch,jh);
        check(seen.size()==5,"Only manifest, exact descriptor, client, index and selected locale requested");
        check(base.translation("zh_tw","item.test","").equals("原版名稱"),"Selected vanilla locale loaded");check(base.translation("zh_tw","base.only","").equals("Base"),"Vanilla English fallback");
        check(base.asset("assets/minecraft/models/item/example.json")!=null,"Model paths preserved rather than guessed item texture");
        var offline=VanillaAssets.load(root,"zh_tw",(uri,max)->{throw new AssertionError("Verified cache must not access network");},jh);check(offline.fingerprint().equals(base.fingerprint()),"Offline cache reverified");
        var builder=new LanguageIndex.Builder();builder.put("zh_tw","item.test","模組名稱");builder.put("zh_tw","mod.only","模組");
        var merged=ResourcePackIndex.empty().withBase(builder.build()).withVanilla(base,InstalledModAssets.sources(List.of()));
        check(merged.translation("zh_tw","item.test","").equals("模組名稱")&&merged.translation("zh_tw","locale.only","").equals("語系"),"Mod selected language overlays vanilla per key");
        Path admin=Files.createTempDirectory("discraft-pack-test-");Files.write(admin.resolve("a.zip"),zip(Map.of("pack.mcmeta",bytes("{\"pack\":{\"pack_format\":15}}"),"assets/minecraft/lang/zh_tw.json",bytes("{\"item.test\":\"管理員\"}"))));
        merged=ResourcePackIndex.load(admin,List.of("a.zip")).withBase(builder.build()).withVanilla(base,InstalledModAssets.sources(List.of()));check(merged.translation("zh_tw","item.test","").equals("管理員")&&merged.translation("zh_tw","mod.only","").equals("模組"),"Administrator language overrides mod without losing remaining keys");
        reject(()->VanillaAssets.load(Files.createTempDirectory("discraft-pin-"),"zh_tw",fetch),"Production rejects unpinned client");
        Files.write(root.resolve(jh+".jar"),bytes("bad"));reject(()->VanillaAssets.load(root,"zh_tw",fetch,jh),"Corrupt cached resource fails closed");
        reject(()->VanillaAssets.load(Files.createTempDirectory("discraft-integrity-"),"zh_tw",(uri,max)->uri.equals(VanillaAssets.MANIFEST)?manifest:bytes("wrong"),jh),"Network hash mismatch rejected");
        for(String url:List.of("http://piston-meta.mojang.com/a","https://piston-meta.mojang.com.evil/a","https://user@piston-meta.mojang.com/a","https://piston-meta.mojang.com/a?secret=x","https://piston-meta.mojang.com:443/a","https://piston-meta.mojang.com/../a"))reject(()->VanillaAssets.official(url,"piston-meta.mojang.com"),"Unsafe official URL rejected");
        Path link=root.resolve("link");Files.createSymbolicLink(link,Files.createTempDirectory("discraft-link-target-"));reject(()->VanillaAssets.secureDirectory(link.resolve("cache")),"Symlink parent rejected");
        Path modA=Files.createTempDirectory("discraft-mod-A-"),modB=Files.createTempDirectory("discraft-mod-B-");String path="assets/minecraft/models/item/example.json";
        for(Path mod:List.of(modA,modB))Files.createDirectories(mod.resolve("minecraft/models/item"));Files.write(modA.resolve("minecraft/models/item/example.json"),bytes("{\"a\":1}"));Files.write(modB.resolve("minecraft/models/item/example.json"),bytes("{\"b\":2}"));
        var installed=InstalledModAssets.sources(List.of(new InstalledModLanguages.Source("z",modB),new InstalledModLanguages.Source("a",modA)));
        check(new String(installed.asset(path),StandardCharsets.UTF_8).contains("\"b\""),"Deterministic later mod asset priority");reject(()->installed.asset("assets/minecraft/models/../secret.json"),"Unsafe mod resource path rejected");
        var composed=ResourcePackIndex.empty().withVanilla(base,installed);check(new String(composed.asset(path),StandardCharsets.UTF_8).contains("\"b\""),"Installed mod model overlays vanilla");
        Files.write(modB.resolve("minecraft/models/item/example.json"),bytes("malformed"));var invalid=ResourcePackIndex.empty().withVanilla(base,InstalledModAssets.sources(List.of(new InstalledModLanguages.Source("z",modB))));reject(()->invalid.asset(path),"Malformed mod override cannot silently reveal lower vanilla model");
        Path bomb=Files.createTempFile("discraft-directory-payload-",".zip");Files.write(bomb,zip(Map.of("payload/",new byte[ResourcePackIndex.MAX_ENTRY+1])));reject(()->ResourcePackIndex.vanilla(bomb,"en_us",null,"fixture"),"Directory compressed payload consumes expansion budget");
        System.out.println("PASS "+count+" vanilla manifest/cache/priority/path/bounds checks");
    }
}
