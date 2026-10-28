package dev.discraft.forge;

import dev.discraft.forge.bridge.TooltipReport;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.TooltipFlag;
import java.util.*;

/** Reports the tooltip this client actually renders for its main hand, including mod and client tooltip events. */
final class ClientTooltipReport {
    private ClientTooltipReport(){}
    static void send(UUID draft,net.minecraft.network.Connection connection){
        try{
            var mc=Minecraft.getInstance();if(mc.player==null||!TooltipReport.supported(connection))return;
            var stack=mc.player.getMainHandItem();if(stack.isEmpty())return;
            var lines=new ArrayList<String>();int chars=0;
            for(var line:stack.getTooltipLines(mc.player,TooltipFlag.Default.NORMAL)){
                String json=Component.Serializer.toJson(line);chars+=json.length();
                if(lines.size()>=TooltipReport.MAX_LINES||json.length()>TooltipReport.MAX_LINE||chars>TooltipReport.MAX_CHARS)return;lines.add(json);
            }
            var report=new TooltipReport(draft,BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),stack.getCount(),lines);
            if(report.fits())TooltipReport.CHANNEL.sendToServer(report);
        }catch(VirtualMachineError fatal){throw fatal;}
        catch(Throwable failure){/* A faulty client tooltip hook only costs the enrichment; the server uses its own capture. */}
    }
}
