package dev.discraft.forge.server;

import dev.discraft.forge.bridge.TooltipReport;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import java.util.*;

/**
 * Chooses the Discord tooltip source. The server whitelist is always the floor; a richer source (the sender's
 * own client report, or the item's native server-side tooltip code) is used only when it extends that floor.
 * See docs/mod-tooltip-compatibility.md.
 */
final class TooltipSources {
    private TooltipSources(){}
    static final long NATIVE_NANOS=50_000_000L;
    // Game-thread only. An item whose native tooltip code throws or is slow is not called again this run.
    private static final Set<String> nativeDisabled=new LinkedHashSet<>();
    static ItemTooltip select(ItemTooltip base,ItemTooltip client,ItemTooltip nativeLines,ServerConfig.ClientTooltips mode,boolean durability){
        // Durability is the whitelist's last, advanced-only line; NORMAL client/native tooltips omit it, so it is matched
        // separately and re-appended from the server value.
        var core=durability&&base.lines().size()>1?new ItemTooltip(base.lines().subList(0,base.lines().size()-1)):base;
        var extra=core==base?null:base.lines().get(base.lines().size()-1);
        if(client!=null&&(mode==ServerConfig.ClientTooltips.LENIENT?sameTitle(core,client):extendsBase(core,client)))return with(client,extra);
        if(nativeLines!=null&&extendsBase(core,nativeLines))return with(nativeLines,extra);
        return base;
    }
    private static ItemTooltip with(ItemTooltip tooltip,ItemTooltip.Line extra){
        if(extra==null||tooltip.lines().stream().anyMatch(l->l.plain().equals(extra.plain())))return tooltip;
        var lines=new ArrayList<>(tooltip.lines());lines.add(extra);try{return new ItemTooltip(lines);}catch(IllegalArgumentException full){return tooltip;}
    }
    static boolean sameTitle(ItemTooltip base,ItemTooltip candidate){return !base.lines().isEmpty()&&!candidate.lines().isEmpty()&&candidate.lines().get(0).plain().equals(base.lines().get(0).plain());}
    /** Every server-computed line must appear, in order and unchanged; a candidate may only add lines around them. */
    static boolean extendsBase(ItemTooltip base,ItemTooltip candidate){
        if(!sameTitle(base,candidate))return false;int at=0;
        for(var line:candidate.lines())if(at<base.lines().size()&&line.plain().equals(base.lines().get(at).plain()))at++;
        return at==base.lines().size();
    }
    /** Client-reported lines are parsed as plain styled text only; non-literal/translatable contents are never resolved. */
    static ItemTooltip client(TooltipReport report,String id,int count,ComponentText text){
        if(report==null||!report.itemId().equals(id)||report.count()!=count)return null;
        try{
            var budget=new ItemTooltip.ExpansionBudget();var out=new ArrayList<ItemTooltip.Line>();
            for(String json:report.lines()){DiscordGateway.validateJson(json);Component line=Component.Serializer.fromJson(json);if(line==null)return null;out.add(text.tooltipLine(line,budget));}
            return out.isEmpty()?null:new ItemTooltip(out);
        }catch(RuntimeException invalid){return null;}
    }
    /** Runs the item's own tooltip code (appendHoverText, common ItemTooltipEvent listeners) on the sanitized copy. */
    static ItemTooltip nativeLines(ItemStack safe,ServerPlayer player,ComponentText text,String id){
        if(nativeDisabled.contains(id))return null;long started=System.nanoTime();
        try{
            var lines=safe.copy().getTooltipLines(player,TooltipFlag.Default.NORMAL);if(lines.size()>64)return null;
            var budget=new ItemTooltip.ExpansionBudget();var out=new ArrayList<ItemTooltip.Line>();for(var line:lines)out.add(text.tooltipLine(line,budget));
            return new ItemTooltip(out);
        }catch(IllegalArgumentException overBudget){return null;}
        catch(VirtualMachineError fatal){throw fatal;}
        catch(Throwable failure){disable(id,"failed");return null;}
        finally{if(System.nanoTime()-started>NATIVE_NANOS)disable(id,"slow");}
    }
    private static void disable(String id,String why){
        if(nativeDisabled.size()<4096&&nativeDisabled.add(id))com.mojang.logging.LogUtils.getLogger().warn("DisCraft item display: native tooltip for {} {}; using the server whitelist for it until restart",id,why);
    }
}
