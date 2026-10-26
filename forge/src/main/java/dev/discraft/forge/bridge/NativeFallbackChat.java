package dev.discraft.forge.bridge;

import dev.discraft.core.*;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.*;

/** Vanilla packet content only: no custom channel, raw media, URLs or component JSON from user text. */
public final class NativeFallbackChat {
    private NativeFallbackChat(){}
    public static Component compose(RelayRecord record){
        var result=Component.empty();var actor=record.actor();
        if(actor!=null&&actor.kind()==ActorRef.Kind.DISCORD){result.append(Component.literal("[D] ").withStyle(ChatFormatting.GRAY));result.append(Component.literal(actor.display()+": ").withStyle(Style.EMPTY.withColor(0x8ea1e1).withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,Component.literal(actor.label()+"\n"+actor.id())))));}
        else result.append(Component.literal("<"+(actor==null?record.author():actor.display())+"> ").withStyle(ChatFormatting.GRAY));
        int at=0;for(var item:record.nativeItems()){text(result,record,record.text().substring(at,item.start()));result.append(NativeItemComponents.name(item.item(),record.text().substring(item.start(),item.end())));at=item.end();}text(result,record,record.text().substring(at));
        for(var image:record.attachments())result.append(Component.literal(image.name().startsWith("SPOILER_")?" [spoiler media requires DisCraft client]":" [media requires DisCraft client]").withStyle(ChatFormatting.GRAY));return result;
    }
    private static void text(MutableComponent output,RelayRecord record,String text){for(var part:Content.parse(text,record.catalog())){if(part instanceof Content.Text literal)output.append(Component.literal(literal.value()));else if(part instanceof Content.CustomEmoji emoji)output.append(Component.literal(":"+emoji.name()+":"));}}
}
