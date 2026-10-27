package dev.discraft.forge.bridge;

import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.*;
import net.minecraft.network.chat.contents.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/** Common-side, bounded native components from an already validated item whitelist. */
public final class NativeItemComponents {
    private NativeItemComponents(){}
    public static Component name(NativeItemData item,String fallback){
        if(item.count()==0)return Component.literal(fallback).withStyle(Style.EMPTY.withColor(ChatFormatting.GRAY).withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,Component.translatableWithFallback("discraft.item.empty","Empty hand"))));
        var stack=new ItemStack(BuiltInRegistries.ITEM.get(new ResourceLocation(item.itemId())),item.count());stack.setTag(item.safeTag());if(stack.isEmpty())return Component.literal(fallback).withStyle(ChatFormatting.GRAY);
        var name=stack.getHoverName().copy();if(stack.getCount()>1)name.append(Component.literal(" ×"+stack.getCount()));return ComponentUtils.wrapInSquareBrackets(name).withStyle(Style.EMPTY.withColor(name.getStyle().getColor()!=null?name.getStyle().getColor():TextColor.fromLegacyFormat(stack.getRarity().color)).withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_ITEM,new HoverEvent.ItemStackInfo(stack))));
    }
    public static Component replaceMarkers(Component input,NativeItemData item,String fallback){return replace(input,item,fallback,new int[]{512,0,8192},0);}
    private static Component replace(Component input,NativeItemData item,String fallback,int[] budget,int depth){
        if(depth>32||--budget[0]<0)throw new IllegalArgumentException("Native chat component budget exceeded");MutableComponent result;
        if(input.getContents() instanceof LiteralContents literal){
            String text=literal.text();if((budget[2]-=text.length())<0)throw new IllegalArgumentException("Native chat text budget exceeded");result=Component.empty();int at=0;
            for(var token:dev.discraft.core.ItemMarkers.spans(text)){int next=token.start();if(++budget[1]>8)throw new IllegalArgumentException("Too many item markers");result.append(Component.literal(text.substring(at,next))).append(name(item,fallback));at=token.end();}result.append(Component.literal(text.substring(at)));
        }else if(input.getContents() instanceof TranslatableContents tr){Object[] args=tr.getArgs().clone();if(args.length>32)throw new IllegalArgumentException("Native chat argument budget exceeded");for(int i=0;i<args.length;i++)if(args[i] instanceof Component c)args[i]=replace(c,item,fallback,budget,depth+1);result=Component.translatableWithFallback(tr.getKey(),tr.getFallback(),args);}
        else result=MutableComponent.create(input.getContents());
        result.setStyle(input.getStyle());for(var sibling:input.getSiblings())result.append(replace(sibling,item,fallback,budget,depth+1));return result;
    }
}
