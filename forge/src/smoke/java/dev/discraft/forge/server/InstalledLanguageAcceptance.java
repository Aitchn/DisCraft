package dev.discraft.forge.server;

import com.google.gson.*;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Real Forge ModList, no DISPLAY/client classes; optional user-owned language pack stays outside artifacts. */
final class InstalledLanguageAcceptance {
    static void run(Path output)throws Exception{
        var checks=new ArrayList<String>();var nativeLanguage=Language.getInstance();String english=nativeLanguage.getOrDefault("key.discraft.open","missing");
        if(!english.equals("Open DisCraft mock chat"))throw new AssertionError("Native Forge English did not include the installed DisCraft mod: "+english);checks.add("PASS native Forge already supplies installed DisCraft en_us");
        var tw=InstalledModLanguages.load("zh_tw");var cn=InstalledModLanguages.load("zh_cn");if(!tw.translation("zh_tw","fml.menu.mods","missing").equals("模組")||!cn.translation("zh_cn","fml.menu.mods","missing").equals("Mod"))throw new AssertionError("Actual installed Forge CN/TW resources were not resolved");checks.add("PASS installed Forge common-side resource API resolves actual TW/CN tables without client classes");
        Path root=output.resolve("language-fixtures");Files.createDirectories(root);try(var zip=new ZipOutputStream(Files.newOutputStream(root.resolve("override.zip")))){for(var entry:Map.of("pack.mcmeta","{\"pack\":{\"pack_format\":15,\"description\":\"Original installed Forge overlay fixture\"}}","assets/forge/lang/zh_tw.json","{\"fml.menu.mods\":\"操作員模組\"}").entrySet()){zip.putNextEntry(new ZipEntry(entry.getKey()));zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));zip.closeEntry();}}
        var merged=ResourcePackIndex.load(root,List.of("override.zip")).withBase(tw);var text=new ComponentText(merged,"zh_tw");if(!text.render(Component.translatable("fml.menu.mods")).equals("操作員模組")||!text.render(Component.translatable("fml.menu.mods.title")).equals("模組")||!text.render(Component.translatable("key.discraft.open")).equals(english))throw new AssertionError("Actual installed base/overlay/native English chain failed");checks.add("PASS actual Forge locale key override, retained same-locale key and native mod English fallback");
        if(Language.getInstance()!=nativeLanguage||!nativeLanguage.getOrDefault("key.discraft.open","missing").equals(english))throw new AssertionError("Native language mutated");checks.add("PASS bridge locale index leaves native server/player Language untouched");
        String supplied=System.getProperty("discraft.languagePackEvidence");if(supplied!=null){Path file=Path.of(supplied);var pack=ResourcePackIndex.load(file.getParent(),List.of(file.getFileName().toString()));var importedTw=pack.withBase(tw);var importedCn=pack.withBase(cn);
            if(pack.languageKeys("zh_cn")!=177286||pack.languageKeys("zh_tw")!=179022||pack.languageKeys("en_us")!=43)throw new AssertionError("Supplied pack counts mismatch");checks.add("PASS complete user pack validated by actual bounded Java importer: CN=177286 TW=179022 EN=43");
            if(!importedCn.translation("zh_cn","block.abnormals_delight.aspen_cabinet","missing").equals("白杨木厨柜")||!importedTw.translation("zh_tw","block.abnormals_delight.aspen_cabinet","missing").equals("白楊木廚櫃"))throw new AssertionError("Supplied CN/TW examples mismatch");checks.add("PASS supplied CN/TW samples remain separate; translation does not claim those mods are installed");
            var report=new JsonObject();report.addProperty("cn_keys",pack.languageKeys("zh_cn"));report.addProperty("tw_keys",pack.languageKeys("zh_tw"));report.addProperty("english_keys",pack.languageKeys("en_us"));report.addProperty("actual_installed_base","Forge 47.4.0 + DisCraft; no CTE2 mod installation");report.addProperty("cn_sample",importedCn.translation("zh_cn","block.abnormals_delight.aspen_cabinet","missing"));report.addProperty("tw_sample",importedTw.translation("zh_tw","block.abnormals_delight.aspen_cabinet","missing"));Files.writeString(output.resolve("language-import-metadata.json"),report.toString());
        }
        checks.add("PASS "+checks.size()+" actual installed-language checks; headless and no Discord transport");Files.write(output.resolve("language-result.txt"),checks);System.out.println(checks.get(checks.size()-1));
    }
}
