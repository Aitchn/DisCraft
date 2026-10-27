package dev.discraft.forge.bridge;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.*;
import java.util.function.Consumer;

/** Presentation of already validated spans only; wire/input text and stable identities stay unchanged. */
public final class NativeMentions {
    private NativeMentions(){}
    public static Component name(ActorRef actor){
        return Component.literal((actor.kind()==ActorRef.Kind.DISCORD?"@":"")+actor.display()).withStyle(Style.EMPTY.withColor(ChatFormatting.AQUA).withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,Component.literal(actor.label()+"\n"+actor.id()))));
    }
    public static void parts(RelayRecord record,int start,int end,Consumer<String> literal,Consumer<ActorRef> selected){
        if(start<0||end<start||end>record.text().length())throw new IllegalArgumentException("Mention presentation range");
        int at=start;for(var mention:record.mentions()){
            if(mention.end()<=start||mention.start()>=end)continue;
            if(mention.start()<start||mention.end()>end)throw new IllegalArgumentException("Mention presentation split");
            if(at<mention.start())literal.accept(record.text().substring(at,mention.start()));selected.accept(mention.target());at=mention.end();
        }if(at<end)literal.accept(record.text().substring(at,end));
    }
}
