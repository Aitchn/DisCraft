package dev.discraft.forge.server;

import dev.discraft.forge.bridge.TooltipReport;
import net.minecraft.network.chat.Component;
import java.util.*;

/** Source selection and client-report parsing; pure data, no game instance. */
final class TooltipSourcesTest {
    private static int checks;
    private static void check(boolean value,String label){if(!value)throw new AssertionError(label);checks++;System.out.println("PASS "+label);}
    private static ItemTooltip.Line line(String text){return new ItemTooltip.Line(List.of(new ItemTooltip.Run(text,0xffffff,"minecraft:default",false,false,false,false,false)));}
    private static ItemTooltip tooltip(String... lines){return new ItemTooltip(Arrays.stream(lines).map(TooltipSourcesTest::line).toList());}
    private static List<String> plain(ItemTooltip tooltip){return tooltip.lines().stream().map(ItemTooltip.Line::plain).toList();}
    private static String json(Component component){return Component.Serializer.toJson(component);}
    static void run(){
        var strict=ServerConfig.ClientTooltips.STRICT;var lenient=ServerConfig.ClientTooltips.LENIENT;
        var base=tooltip("Shell Horn","Sharpness V","","When in Main Hand:"," 7 Attack Damage");
        var richer=tooltip("Shell Horn","Summons Captain Cornelia.","Sharpness V","","When in Main Hand:"," 7 Attack Damage","Aquamirae");
        check(TooltipSources.extendsBase(base,richer)&&TooltipSources.select(base,richer,null,strict,false)==richer,"A source that only adds lines around the server lines is used");
        var altered=tooltip("Shell Horn","Sharpness X","","When in Main Hand:"," 7 Attack Damage","Aquamirae");
        check(!TooltipSources.extendsBase(base,altered)&&TooltipSources.select(base,altered,null,strict,false)==base,"A changed server line rejects a strict client report");
        var dropped=tooltip("Shell Horn","Summons Captain Cornelia.","Aquamirae");
        check(TooltipSources.select(base,dropped,null,strict,false)==base,"A removed server line rejects a strict client report");
        check(TooltipSources.select(base,dropped,null,lenient,false)==dropped,"Lenient mode accepts a report that drops vanilla lines");
        var renamed=tooltip("Reinforced Leather Chestplate of the Snake","✔ Level 12","+8 Armor","Prefix: Reinforced");
        check(TooltipSources.select(base,renamed,null,lenient,false)==renamed,"Lenient mode accepts a mod that renames the item and rewrites its lines (Mine and Slash gear)");
        check(TooltipSources.select(base,renamed,null,strict,false)==base,"Strict mode still rejects a renamed item");
        check(TooltipSources.select(base,altered,richer,strict,false)==richer,"Native capture is the fallback when the client report is rejected");
        check(TooltipSources.select(base,null,altered,strict,false)==base,"Native capture must also extend the whitelist");
        var damaged=tooltip("Shell Horn","Sharpness V","Durability: 10 / 20");
        var clientNormal=tooltip("Shell Horn","Summons Captain Cornelia.","Sharpness V");
        check(plain(TooltipSources.select(damaged,clientNormal,null,strict,true)).equals(List.of("Shell Horn","Summons Captain Cornelia.","Sharpness V","Durability: 10 / 20")),"Advanced-only durability is matched separately and re-appended from the server value");
        check(plain(TooltipSources.select(damaged,tooltip("Shell Horn","Sharpness V","Durability: 999 / 999"),null,strict,true)).contains("Durability: 10 / 20"),"The server durability value is always shown even if a client claims another");

        var text=new ComponentText(ResourcePackIndex.empty(),"en_us");
        var legacy=ItemTooltip.localize(Component.literal("§5Summons §lCaptain§r plain"),ResourcePackIndex.empty(),"en_us");
        check(legacy.plain().equals("Summons Captain plain")&&legacy.runs().get(0).color()==0xaa00aa&&legacy.runs().get(1).bold()&&legacy.runs().get(2).color()==0xffffff,"Legacy section-sign codes style text like the client font renderer instead of printing the codes");
        var report=new TooltipReport(UUID.randomUUID(),"aquamirae:shell_horn",1,List.of(json(Component.literal("Shell Horn")),json(Component.literal("Summons").withStyle(s->s.withClickEvent(new net.minecraft.network.chat.ClickEvent(net.minecraft.network.chat.ClickEvent.Action.RUN_COMMAND,"/op x")))),json(Component.nbt("Inventory",false,Optional.empty(),new net.minecraft.network.chat.contents.EntityDataSource("@s")))));
        var parsed=TooltipSources.client(report,"aquamirae:shell_horn",1,text);
        check(parsed!=null&&plain(parsed).equals(List.of("Shell Horn","Summons","[unsupported tooltip component]")),"Client lines keep text and style only; click events and data-reading components are never resolved");
        check(TooltipSources.client(report,"minecraft:stick",1,text)==null&&TooltipSources.client(report,"aquamirae:shell_horn",2,text)==null,"A report for a different item or count is ignored");
        var deep=new TooltipReport(UUID.randomUUID(),"aquamirae:shell_horn",1,List.of("[".repeat(40)+"\"x\""+"]".repeat(40)));
        check(TooltipSources.client(deep,"aquamirae:shell_horn",1,text)==null,"Deeply nested client JSON is rejected before parsing");
        try{new TooltipReport(UUID.randomUUID(),"x:y",1,Collections.nCopies(TooltipReport.MAX_LINES+1,"\"x\""));throw new AssertionError("Oversized report accepted");}catch(IllegalArgumentException expected){check(true,"Client tooltip reports are bounded");}
        System.out.println("PASS "+checks+" tooltip source checks");
    }
}
