package dev.discraft.forge.bridge;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.*;

/** Native-only origin header. Display text is literal; click identity stays separately server-bound. */
public record NativeAuthorHeader(Component component,ClickEvent authorClick) {
    public static NativeAuthorHeader create(String fallback,ActorRef actor){
        var component=Component.empty();
        boolean discord=actor!=null&&actor.kind()==ActorRef.Kind.DISCORD;
        component.append(Component.literal(discord?"[D] ":"<").withStyle(ChatFormatting.GRAY));
        if(actor==null){component.append(Component.literal(fallback).withStyle(ChatFormatting.AQUA));component.append(Component.literal("> ").withStyle(ChatFormatting.GRAY));return new NativeAuthorHeader(component,null);}
        var click=new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND,actor.label()+" ");
        var style=Style.EMPTY.withColor(discord?0xff8ea1e1:0xff72cf9c).withClickEvent(click).withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,Component.literal(actor.label()+"\n"+actor.id())));
        component.append(Component.literal(actor.display()).withStyle(style));
        component.append(Component.literal(discord?": ":"> ").withStyle(ChatFormatting.GRAY));
        return new NativeAuthorHeader(component,click);
    }
}
