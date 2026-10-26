package dev.discraft.forge.server;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import net.minecraft.network.chat.Component;

/** Local synthetic ZIPs only; no copyrighted game assets, network or DISPLAY required. */
final class ResourcePackTest {
    private static int checks;
    private static void check(boolean value,String label){if(!value)throw new AssertionError(label);checks++;System.out.println("PASS "+label);}
    private static void zip(Path file,Map<String,byte[]> entries)throws Exception{
        try(var out=new ZipOutputStream(Files.newOutputStream(file))){for(var entry:entries.entrySet()){out.putNextEntry(new ZipEntry(entry.getKey()));out.write(entry.getValue());out.closeEntry();}}
    }
    private static byte[] utf8(String text){return text.getBytes(StandardCharsets.UTF_8);}
    private static Map<String,byte[]> pack(){var entries=new LinkedHashMap<String,byte[]>();entries.put("pack.mcmeta",utf8("{\"pack\":{\"pack_format\":15,\"description\":\"fixture\"}}"));return entries;}
    private static void reject(Path root,Map<String,byte[]> entries,String label)throws Exception{
        zip(root.resolve("bad.zip"),entries);
        try{ResourcePackIndex.load(root,List.of("bad.zip"));throw new AssertionError(label);}catch(IOException expected){check(expected.getCause()==null&&!expected.toString().contains(root.toString()),label+" with sanitized diagnostics");}
    }
    static void run()throws Exception{
        InstalledLanguageTest.run();
        Path root=Files.createTempDirectory("discraft-resource-test");
        try{
            var low=pack();low.put("assets/test/lang/en_us.json",utf8("{\"test.death\":\"%1$s was slain by %2$s using %3$s\",\"english.only\":\"Fallback\",\"retained\":\"low key\",\"shared\":\"low\"}"));
            low.put("assets/test/lang/zh_tw.json",utf8("{\"test.death\":\"%1$s 被 %2$s 使用 %3$s 殺死\",\"shared\":\"低\"}"));
            low.put("assets/test/models/item/test.json",utf8("{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"test:item/test\"}}"));
            var high=pack();high.put("assets/test/lang/zh_tw.json",utf8("{\"shared\":\"高\"}"));high.put("assets/test/models/item/test.json",utf8("{\"parent\":\"test:item/replacement\"}"));
            zip(root.resolve("low.zip"),low);zip(root.resolve("high.zip"),high);
            var index=ResourcePackIndex.load(root,List.of("low.zip","high.zip"));
            check(index.translation("zh_tw","shared","missing").equals("高"),"Later pack overrides a language key");
            check(index.translation("zh_tw","retained","missing").equals("low key"),"Per-key language merge retains lower-priority keys and English fallback");
            check(index.translation("zh_tw","absent","fallback").equals("fallback"),"Missing translation preserves supplied fallback");
            check(new String(index.asset("assets/test/models/item/test.json"),StandardCharsets.UTF_8).contains("replacement"),"Non-language asset uses whole-file higher-priority override");
            byte[] asset=index.asset("assets/test/models/item/test.json");asset[0]=0;check(index.asset("assets/test/models/item/test.json")[0]=='{',"Immutable resource snapshot does not expose retained byte arrays");
            check(!index.fingerprint().equals(ResourcePackIndex.load(root,List.of("high.zip","low.zip")).fingerprint()),"Cache fingerprint changes when pack priority changes");
            var text=new ComponentText(index,"zh_tw");
            var death=Component.translatable("test.death",Component.literal("Aitchn"),Component.translatable("entity.minecraft.zombie"),Component.literal("星光劍"));
            check(text.render(death).equals("Aitchn 被 Zombie 使用 星光劍 殺死"),"Nested death arguments preserve custom names and common-server entity fallback");
            check(text.render(Component.translatable("missing.key",Component.literal("unused")).append(Component.literal(" 尾巴"))).equals("missing.key 尾巴"),"Missing translation key and sibling component remain visible");
            check(text.render(Component.translatableWithFallback("missing.fallback","Name: %s",Component.literal("自訂名稱"))).equals("Name: 自訂名稱"),"Explicit component fallback still receives recursively localized arguments");
            check(ComponentText.format("%2$s / %s / %1$s / %%",List.of("甲","乙")).equals("乙 / 甲 / 甲 / %"),"Numbered and sequential Minecraft placeholders and escaped percent");
            check(ComponentText.format("%9$s %d %999999999999999999999$s",List.of("x")).equals("%9$s %d %999999999999999999999$s"),"Malformed or absent arguments stay visible without formatting exceptions");
            var deep=Component.literal("start");for(int i=0;i<34;i++)deep=Component.translatable("test.death",deep,"x","y");
            try{text.render(deep);throw new AssertionError();}catch(IllegalArgumentException expected){check(true,"Deep component nesting rejected before recursive expansion");}
            var cyclic=Component.literal("loop");cyclic.append(cyclic);try{text.render(cyclic);throw new AssertionError();}catch(IllegalArgumentException expected){check(true,"Cyclic component graph rejected without game-thread recursion overflow");}
            for(String path:List.of("../escape","/absolute","assets/test/../escape","assets\\escape","C:/escape")){var bad=pack();bad.put(path,utf8("x"));reject(root,bad,"ZIP traversal and absolute path rejected");}
            reject(root,Map.of("assets/test/lang/en_us.json",utf8("{}")),"Missing pack metadata rejected");
            var bad=pack();bad.put("pack.mcmeta",utf8("{\"pack\":{\"pack_format\":46}}"));reject(root,bad,"Unsupported resource version rejected");
            bad=pack();bad.put("assets/test/lang/en_us.json",utf8("{\"x\":5}"));reject(root,bad,"Non-string language entry rejected");
            bad=pack();bad.put("assets/test/models/item/deep.json",utf8("{\"x\":".repeat(33)+"{}"+"}".repeat(33)));reject(root,bad,"Nested resource JSON bounded before parsing");
            bad=pack();bad.put("ignored.bin",new byte[ResourcePackIndex.MAX_ENTRY+1]);reject(root,bad,"Ignored ZIP entry still consumes decompression bounds");
            bad=pack();bad.put("payload-directory/",new byte[ResourcePackIndex.MAX_ENTRY+1]);reject(root,bad,"Directory ZIP payload cannot bypass per-entry expanded-byte limit");
            check(Files.size(root.resolve("bad.zip"))<64*1024,"Over-limit directory payload fixture has a small compressed representation");
            bad=pack();for(int i=0;i<ResourcePackIndex.MAX_FILES;i++)bad.put("ignored/"+i,new byte[0]);reject(root,bad,"ZIP entry count is bounded even for empty ignored files");
            bad=pack();byte[] chunk=new byte[ResourcePackIndex.MAX_ENTRY];for(int i=0;i<33;i++)bad.put("ignored/"+i,chunk);reject(root,bad,"Expanded-byte budget applies across all ignored entries");
            bad=pack();for(int i=0;i<33;i++)bad.put("payload-directory-"+i+"/",chunk);reject(root,bad,"Directory payloads share the complete import expanded-byte limit");
            bad=pack();bad.put("assets/test/textures/item/bad.png",new byte[]{1,2,3});reject(root,bad,"Malformed PNG rejected during import");
            bad=pack();var image=new java.awt.image.BufferedImage(4097,1,java.awt.image.BufferedImage.TYPE_INT_ARGB);var encoded=new ByteArrayOutputStream();javax.imageio.ImageIO.write(image,"png",encoded);bad.put("assets/test/textures/item/wide.png",encoded.toByteArray());reject(root,bad,"Oversized texture rejected from metadata before pixel allocation");
            Files.createSymbolicLink(root.resolve("linked.zip"),root.resolve("low.zip"));
            try{ResourcePackIndex.load(root,List.of("linked.zip"));throw new AssertionError();}catch(IOException expected){check(true,"Symbolic-link pack source rejected");}
            try{ResourcePackIndex.load(root,List.of("../low.zip"));throw new AssertionError();}catch(IOException expected){check(true,"Config pack filename cannot escape import root");}
            Files.createSymbolicLink(root.resolve("linked-root"),root);try{ResourcePackIndex.load(root.resolve("linked-root"),List.of("low.zip"));throw new AssertionError();}catch(IOException expected){check(true,"Symbolic-link import root rejected");}
        }finally{try(var paths=Files.list(root)){for(Path path:paths.toList())Files.delete(path);}Files.delete(root);}
        System.out.println("PASS "+checks+" headless resource/localization checks (local fixtures only)");
    }
}
