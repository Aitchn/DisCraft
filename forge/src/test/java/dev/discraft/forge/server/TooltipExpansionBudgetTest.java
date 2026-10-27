package dev.discraft.forge.server;

import com.google.gson.*;
import net.minecraft.network.chat.*;
import net.minecraft.ChatFormatting;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

final class TooltipExpansionBudgetTest {
    private static void rejected(Component input,ResourcePackIndex resources,ItemTooltip.ExpansionBudget budget,String reason)throws Exception{
        try{ItemTooltip.localize(input,resources,"en_us",budget);throw new AssertionError("Unbounded tooltip translation accepted");}catch(IllegalArgumentException expected){if(!expected.getMessage().contains(reason))throw new AssertionError("Wrong tooltip budget rejection");}
        if(budget.generatedNodes>1024||budget.outputChars>8192)throw new AssertionError("Budget was checked after generating excess output");
    }
    static void run()throws Exception{
        Path directory=Files.createTempDirectory("discraft-translation-budget-");try{
            var lang=new JsonObject();lang.addProperty("budget.outer","%1$s".repeat(500));lang.addProperty("budget.inner","%%".repeat(4096));lang.addProperty("budget.characters","Z".repeat(8192));
            try(var zip=new ZipOutputStream(Files.newOutputStream(directory.resolve("budget.zip")))){for(var entry:Map.of("pack.mcmeta","{\"pack\":{\"pack_format\":15,\"description\":\"Original early-stop fixtures\"}}","assets/minecraft/lang/en_us.json",lang.toString()).entrySet()){zip.putNextEntry(new ZipEntry(entry.getKey()));zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));zip.closeEntry();}}
            var resources=ResourcePackIndex.load(directory,List.of("budget.zip"));var explosion=Component.translatable("budget.outer",Component.translatable("budget.inner"));
            if(Component.Serializer.toJson(explosion).length()>512)throw new AssertionError("Regression component no longer fits the safe lore JSON bound");
            var nodes=new ItemTooltip.ExpansionBudget();rejected(explosion,resources,nodes,"generated node");if(nodes.generatedNodes!=1024||nodes.remainingInputs<500)throw new AssertionError("Repeated percent expansion did not stop inside the first nested argument");
            var characters=new ItemTooltip.ExpansionBudget();rejected(Component.translatableWithFallback("budget.missing","%1$s%1$s",Component.translatable("budget.characters")),resources,characters,"expanded text");if(characters.outputChars!=8192||characters.generatedNodes>5)throw new AssertionError("Literal translated argument did not stop before copying the second 8192-character value");
            var primitive=new ItemTooltip.ExpansionBudget();rejected(Component.translatableWithFallback("budget.primitive","%1$s%1$s","X".repeat(5000)),resources,primitive,"expanded text");if(primitive.outputChars!=5000)throw new AssertionError("Primitive argument path did not share the pre-append budget");
            var direct=new ItemTooltip.ExpansionBudget();rejected(Component.literal("X".repeat(8193)),resources,direct,"expanded text");if(direct.outputChars!=0||direct.generatedNodes!=1)throw new AssertionError("Direct literal allocated output before validation");
            var shared=new ItemTooltip.ExpansionBudget();ItemTooltip.localize(Component.literal("X".repeat(5000)),resources,"en_us",shared);rejected(Component.literal("Y".repeat(5000)),resources,shared,"expanded text");if(shared.outputChars!=5000)throw new AssertionError("Tooltip lines do not share the same budget");
            var normal=ItemTooltip.localize(Component.translatableWithFallback("budget.normal","%2$s / %1$s / %%",Component.literal("中文").withStyle(ChatFormatting.GOLD),Component.literal("English").withStyle(ChatFormatting.BLUE)).append(Component.literal(" sibling")),resources,"en_us");if(!normal.plain().equals("English / 中文 / % sibling")||normal.runs().stream().noneMatch(r->r.text().equals("中文")&&r.color()==ChatFormatting.GOLD.getColor()))throw new AssertionError("Normal nested placeholder style/sibling semantics changed");
            System.out.println("PASS 6 tooltip expansion early-stop checks: two-level multiplication, pre-append node/character budgets, literal/primitive/fallback paths, shared lines and preserved styles");
        }finally{try(var files=Files.list(directory)){for(var file:files.toList())Files.delete(file);}Files.delete(directory);}
    }
}
