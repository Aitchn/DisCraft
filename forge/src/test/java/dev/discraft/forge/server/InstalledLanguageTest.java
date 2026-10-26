package dev.discraft.forge.server;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.util.zip.*;
import net.minecraft.network.chat.Component;

final class InstalledLanguageTest {
    private static int checks;
    private static void check(boolean value,String label){if(!value)throw new AssertionError(label);checks++;System.out.println("PASS "+label);}
    private static void lang(Path assets,String namespace,String locale,String json)throws Exception{Path target=assets.resolve(namespace+"/lang/"+locale+".json");Files.createDirectories(target.getParent());Files.writeString(target,json);}
    private static void pack(Path target,LinkedHashMap<String,String> files)throws Exception{try(var zip=new ZipOutputStream(Files.newOutputStream(target))){zip.putNextEntry(new ZipEntry("pack.mcmeta"));zip.write("{\"pack\":{\"pack_format\":15,\"description\":\"Original language overlay fixtures\"}}".getBytes(StandardCharsets.UTF_8));zip.closeEntry();for(var e:files.entrySet()){zip.putNextEntry(new ZipEntry(e.getKey()));zip.write(e.getValue().getBytes(StandardCharsets.UTF_8));zip.closeEntry();}}}
    static void run()throws Exception{
        Path root=Files.createTempDirectory("discraft-installed-language-");try{
            Path alpha=root.resolve("alpha/assets"),zeta=root.resolve("zeta/assets");lang(alpha,"a","zh_tw","{\"same\":\"alpha\",\"retain\":\"保留\",\"only.tw\":\"繁體\",\"formatted\":\"%1$d / %2$.2f\"}");lang(zeta,"z","zh_tw","{\"same\":\"zeta\"}");lang(alpha,"a","zh_cn","{\"retain\":\"保留简体\"}");
            var sources=List.of(new InstalledModLanguages.Source("zeta",zeta),new InstalledModLanguages.Source("alpha",alpha));var base=InstalledModLanguages.loadSources("zh_tw",sources);var reverse=InstalledModLanguages.loadSources("zh_tw",List.of(sources.get(1),sources.get(0)));
            check(base.translation("zh_tw","same","missing").equals("zeta")&&base.fingerprint().equals(reverse.fingerprint()),"Installed mods resolve duplicate keys by stable mod-ID order, independent of discovery order");
            check(InstalledModLanguages.loadSources("zh_cn",sources).translation("zh_cn","retain","missing").equals("保留简体"),"Selected CN locale loads its own installed-mod table");
            check(InstalledModLanguages.loadSources("en_us",sources).keys("en_us")==0,"English is reused from native Forge rather than rescanning installed assets");
            var files=new LinkedHashMap<String,String>();files.put("assets/z/lang/zh_tw.json","{\"same\":\"namespace-z\"}");files.put("assets/a/lang/zh_tw.json","{\"same\":\"namespace-a\"}");files.put("assets/a/lang/en_us.json","{\"retain\":\"pack English must not replace mod TW\",\"english.only\":\"pack English\"}");pack(root.resolve("low.zip"),files);
            var low=ResourcePackIndex.load(root,List.of("low.zip")).withBase(base);check(low.translation("zh_tw","same","missing").equals("namespace-z"),"One pack resolves cross-namespace duplicates lexically, not by ZIP entry order");check(low.translation("zh_tw","retain","missing").equals("保留"),"Mod selected-locale key survives a pack that only overrides English");check(low.translation("zh_tw","english.only","native English").equals("pack English"),"Missing selected-locale key falls through pack English");check(low.translation("zh_cn","only.tw","explicit").equals("explicit"),"CN does not silently reuse TW");
            var high=new LinkedHashMap<String,String>();high.put("assets/a/lang/zh_tw.json","{\"same\":\"操作員最後覆蓋\"}");pack(root.resolve("high.zip"),high);var merged=ResourcePackIndex.load(root,List.of("low.zip","high.zip")).withBase(base);check(merged.translation("zh_tw","same","missing").equals("操作員最後覆蓋")&&merged.translation("zh_tw","retain","missing").equals("保留"),"Later local pack overrides per key and retains uncovered installed-mod translations");
            var text=new ComponentText(merged,"zh_tw");check(text.render(Component.translatable("formatted",4,2.5)).equals("4 / 2.5"),"Mod numeric printf forms normalize like Minecraft component language loading");check(text.render(Component.literal("Literal stays literal")).equals("Literal stays literal"),"Mod literal text is never pretended to be a translation key");check(text.render(Component.translatableWithFallback("no.key","Explicit %s","fallback")).equals("Explicit fallback"),"Missing native/pack key retains explicit component fallback");
            lang(alpha,"a","zh_tw","{\"bad\":1}");try{InstalledModLanguages.loadSources("zh_tw",sources);throw new AssertionError();}catch(IOException expected){check(!expected.toString().contains(root.toString()),"Malformed installed JSON fails closed with sanitized diagnostics");}
            lang(alpha,"a","zh_tw"," ".repeat(1024*1024+1));try{InstalledModLanguages.loadSources("zh_tw",sources);throw new AssertionError();}catch(IOException expected){check(true,"Installed language JSON retains the one-MiB read bound");}
            Path link=alpha.resolve("a/lang/zh_tw.json"),other=root.resolve("other.json");Files.writeString(other,"{}");Files.delete(link);Files.createSymbolicLink(link,other);try{InstalledModLanguages.loadSources("zh_tw",sources);throw new AssertionError();}catch(IOException expected){check(true,"Installed development resources reject symbolic-link language files");}
            var budget=new LanguageIndex.Builder();budget.put("zh_tw","K".repeat(512),"v");try{budget.put("zh_tw","K".repeat(513),"v");throw new AssertionError();}catch(IOException expected){check(true,"Documented 512-character key bound admits the supplied pack without becoming unbounded");}
            var aggregate=new LanguageIndex.Builder();String repeated="X".repeat(8192);int accepted=0;try{for(int i=0;i<5000;i++){aggregate.put(i%2==0?"zh_cn":"zh_tw","unit"+i,repeated);accepted++;}throw new AssertionError();}catch(IOException expected){check(accepted<4096,"Decoded text has a shared 32-Mi UTF-16-unit cap across locales");}
            for(int i=0;i<LanguageIndex.MAX_PER_LOCALE-1;i++)budget.put("zh_tw","k"+i,"v");try{budget.put("zh_tw","overflow","v");throw new AssertionError();}catch(IOException expected){check(true,"Installed and external layers share the explicit per-locale key bound");}
        }finally{try(var paths=Files.walk(root)){for(var p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}
        System.out.println("PASS "+checks+" installed-language/base-overlay bounds checks (synthetic roots; no mod installation)");
    }
}
