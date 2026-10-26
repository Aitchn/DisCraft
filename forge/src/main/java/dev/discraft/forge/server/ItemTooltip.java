package dev.discraft.forge.server;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.*;
import net.minecraft.network.chat.contents.*;
import net.minecraft.locale.Language;
import net.minecraft.nbt.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.*;
import net.minecraft.world.item.*;
import net.minecraft.world.item.enchantment.*;
import net.minecraft.server.level.ServerPlayer;
import java.util.*;
import java.text.*;

/** Server-only semantic text. No original stack, arbitrary NBT or native hover DTO reaches the image renderer. */
record ItemTooltip(List<Line> lines) {
    private static final net.minecraft.network.chat.ComponentContents EMPTY=Component.empty().getContents();
    record Run(String text,int color,String font,boolean bold,boolean italic,boolean underline,boolean strike,boolean obfuscated){Run{Objects.requireNonNull(text);if(text.length()>8192||font==null||font.length()>256||!font.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")||color<0||color>0xffffff)throw new IllegalArgumentException("Invalid tooltip run");}}
    record Line(List<Run> runs){Line{runs=List.copyOf(runs);if(runs.size()>1024)throw new IllegalArgumentException("Tooltip run budget exceeded");}String plain(){return runs.stream().map(Run::text).reduce("",String::concat);}}
    ItemTooltip{lines=List.copyOf(lines);if(lines.size()>64||lines.stream().mapToInt(l->l.plain().length()).sum()>8192)throw new IllegalArgumentException("Item tooltip text budget exceeded");}
    static ItemTooltip nameOnly(String name,String font){return new ItemTooltip(List.of(new Line(List.of(new Run(name,0xffffff,font,false,false,false,false,false)))));}
    static ItemTooltip capture(ItemStack safe,ServerPlayer player,ComponentText localizer){
        var lines=new ArrayList<Component>();lines.add(safe.getHoverName());var tag=safe.getOrCreateTag();int hide=tag.contains("HideFlags",Tag.TAG_ANY_NUMERIC)?tag.getInt("HideFlags"):safe.getItem().getDefaultTooltipHideFlags(safe);
        if((hide&1)==0)ItemStack.appendEnchantmentNames(lines,safe.getEnchantmentTags());
        if(safe.getItem() instanceof EnchantedBookItem&&(hide&32)==0)ItemStack.appendEnchantmentNames(lines,EnchantedBookItem.getEnchantments(safe));
        var lore=tag.getCompound("display").getList("Lore",Tag.TAG_STRING);
        for(int i=0;i<lore.size();i++){var component=Component.Serializer.fromJson(lore.getString(i));if(component!=null)lines.add(Component.empty().withStyle(ChatFormatting.DARK_PURPLE,ChatFormatting.ITALIC).append(component));}
        if((hide&2)==0)for(var slot:EquipmentSlot.values()){
            var modifiers=safe.getAttributeModifiers(slot);if(modifiers.size()>64)throw new IllegalArgumentException("Item tooltip attribute budget exceeded");if(modifiers.isEmpty())continue;
            lines.add(Component.empty());lines.add(Component.translatable("item.modifiers."+slot.getName()).withStyle(ChatFormatting.GRAY));
            for(var entry:modifiers.entries()){
                var modifier=entry.getValue();double amount=modifier.getAmount();boolean base=false;
                // Vanilla special base totals apply to default item modifiers, not reconstructed NBT overrides.
                if(!tag.contains("AttributeModifiers",Tag.TAG_LIST)){
                    if(modifier.getId().equals(UUID.fromString("cb3f55d3-645c-4f38-a497-9c13a33db5cf"))){amount+=player.getAttributeBaseValue(Attributes.ATTACK_DAMAGE)+EnchantmentHelper.getDamageBonus(safe,MobType.UNDEFINED);base=true;}
                    else if(modifier.getId().equals(UUID.fromString("fa233e1c-4180-4865-b01b-bcce9785aca3"))){amount+=player.getAttributeBaseValue(Attributes.ATTACK_SPEED);base=true;}
                }
                if(!Double.isFinite(amount)||Math.abs(amount)>1000000)throw new IllegalArgumentException("Item tooltip attribute amount exceeded");
                double shown=modifier.getOperation()!=AttributeModifier.Operation.ADDITION?amount*100:entry.getKey().equals(Attributes.KNOCKBACK_RESISTANCE)?amount*10:amount;
                String value=new DecimalFormat("#.##",DecimalFormatSymbols.getInstance(Locale.ROOT)).format(base?shown:Math.abs(shown));String operation=Integer.toString(modifier.getOperation().toValue());var name=Component.translatable(entry.getKey().getDescriptionId());
                if(base)lines.add(Component.literal(" ").append(Component.translatable("attribute.modifier.equals."+operation,value,name)).withStyle(ChatFormatting.DARK_GREEN));
                else if(amount!=0)lines.add(Component.translatable("attribute.modifier."+(amount>0?"plus.":"take.")+operation,value,name).withStyle(amount>0?ChatFormatting.BLUE:ChatFormatting.RED));
            }
        }
        if((hide&4)==0&&tag.getBoolean("Unbreakable"))lines.add(Component.translatable("item.unbreakable").withStyle(ChatFormatting.BLUE));
        // Explicit profile: ordinary safe fields plus only advanced damaged durability, never ID/NBT counts.
        if(safe.isDamaged())lines.add(Component.translatable("item.durability",Math.max(0,safe.getMaxDamage()-safe.getDamageValue()),safe.getMaxDamage()));
        var output=new ArrayList<Line>();var budget=new ExpansionBudget();for(var line:lines)output.add(localizer.tooltipLine(line,budget));return new ItemTooltip(output);
    }
    /** One budget covers the entire tooltip, including all nested and repeated translation arguments. */
    static final class ExpansionBudget {
        static final int MAX_NODES=1024,MAX_CHARS=8192;
        int remainingInputs=512,generatedNodes,outputChars;
        private void node(){if(generatedNodes>=MAX_NODES)throw new IllegalArgumentException("Tooltip generated node budget exceeded");generatedNodes++;}
        private MutableComponent empty(){node();return Component.empty();}
        private Component literal(String text){if(text.length()>MAX_CHARS-outputChars)throw new IllegalArgumentException("Tooltip expanded text budget exceeded");node();outputChars+=text.length();return Component.literal(text);}
    }
    static Line localize(Component input,ResourcePackIndex resources,String locale){return localize(input,resources,locale,new ExpansionBudget());}
    static Line localize(Component input,ResourcePackIndex resources,String locale,ExpansionBudget budget){
        Component component=localized(input,resources,locale,budget,new IdentityHashMap<>(),0);var runs=new ArrayList<Run>();final int[] length={0};
        component.visit((style,text)->{length[0]+=text.length();if(length[0]>8192||runs.size()>1024)throw new IllegalArgumentException("Tooltip text budget exceeded");if(!text.isEmpty())runs.add(new Run(text,style.getColor()==null?0xffffff:style.getColor().getValue(),style.getFont().toString(),style.isBold(),style.isItalic(),style.isUnderlined(),style.isStrikethrough(),style.isObfuscated()));return Optional.<Object>empty();},Style.EMPTY);
        return new Line(runs);
    }
    private static Component localized(Component input,ResourcePackIndex resources,String locale,ExpansionBudget budget,IdentityHashMap<Component,Boolean> path,int depth){
        if(depth>32||--budget.remainingInputs<0||path.put(input,true)!=null)throw new IllegalArgumentException("Tooltip component budget exceeded");var out=budget.empty().setStyle(input.getStyle().withClickEvent(null).withHoverEvent(null).withInsertion(null));
        if(input.getContents() instanceof LiteralContents literal)out.append(budget.literal(literal.text()));
        else if(input.getContents() instanceof TranslatableContents tr){
            String fallback=Language.getInstance().getOrDefault(tr.getKey(),Objects.requireNonNullElse(tr.getFallback(),tr.getKey()));String template=resources.translation(locale,tr.getKey(),fallback);if(template.length()>8192)throw new IllegalArgumentException("Tooltip translation text budget exceeded");Object[] args=tr.getArgs();if(args.length>32)throw new IllegalArgumentException("Tooltip translation argument budget exceeded");int implicit=0;
            for(int at=0;at<template.length();){int start=at;if(template.charAt(at++)!='%'){while(at<template.length()&&template.charAt(at)!='%')at++;out.append(budget.literal(template.substring(start,at)));continue;}
                if(at<template.length()&&template.charAt(at)=='%'){out.append(budget.literal("%"));at++;continue;}int digits=at;while(at<template.length()&&Character.isDigit(template.charAt(at)))at++;int index=implicit;boolean numbered=at>digits;
                if(numbered){if(at>=template.length()||template.charAt(at++)!='$'){out.append(budget.literal(template.substring(start,at)));continue;}try{index=Integer.parseInt(template.substring(digits,at-1))-1;}catch(NumberFormatException invalid){index=-1;}}
                if(at<template.length()&&template.charAt(at)=='s'){at++;if(!numbered)implicit++;if(index>=0&&index<args.length){Object arg=args[index];out.append(arg instanceof Component c?localized(c,resources,locale,budget,path,depth+1):budget.literal(arg==null||arg instanceof String||arg instanceof Number||arg instanceof Boolean?String.valueOf(arg):"?"));}else out.append(budget.literal(template.substring(start,at)));}
                else out.append(budget.literal(template.substring(start,at)));
            }
        }else if(input.getContents()!=EMPTY)out.append(budget.literal("[unsupported tooltip component]"));
        for(var sibling:input.getSiblings())out.append(localized(sibling,resources,locale,budget,path,depth+1));path.remove(input);return out;
    }
}
