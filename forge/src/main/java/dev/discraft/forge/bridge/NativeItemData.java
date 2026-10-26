package dev.discraft.forge.bridge;

import com.google.gson.*;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.*;
import net.minecraft.network.chat.contents.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;
import java.util.*;

/** Allowlisted native SHOW_ITEM payload. Never retains an original ItemStack or arbitrary NBT. */
public record NativeItemData(String itemId,int count,String tag) {
    public static final int MAX_TAG=8192;
    private static final Set<String> TOP=Set.of("display","Damage","CustomModelData","Enchantments","StoredEnchantments","AttributeModifiers","Unbreakable","HideFlags","Potion");
    public NativeItemData {
        id(itemId);if(count<0||count>9999||tag.length()>MAX_TAG)throw new IllegalArgumentException("Invalid native item bounds");
        try{validateTag(parse(tag));}catch(Exception invalid){throw new IllegalArgumentException("Invalid native item data");}
    }
    public static NativeItemData capture(ItemStack stack,String fallback){
        if(stack.isEmpty())return new NativeItemData("minecraft:air",0,"{}");
        CompoundTag out=new CompoundTag(),display=new CompoundTag();Component name;
        try{name=sanitize(stack.getHoverName(),new IdentityHashMap<>(),new int[]{64},0);}catch(Exception oversized){name=Component.literal(fallback);}
        var style=Style.EMPTY.withColor(stack.getRarity().color).withItalic(stack.hasCustomHoverName());
        name=Component.empty().setStyle(style).append(name);String nameJson=Component.Serializer.toJson(name);if(nameJson.length()>2048)nameJson=Component.Serializer.toJson(Component.literal(fallback).withStyle(style));display.putString("Name",nameJson);
        CompoundTag source=stack.getTag();if(source!=null){
            var originalDisplay=source.getCompound("display");if(originalDisplay.contains("color",Tag.TAG_ANY_NUMERIC))display.putInt("color",originalDisplay.getInt("color")&0xffffff);
            var lore=originalDisplay.getList("Lore",Tag.TAG_STRING);var cleanLore=new ListTag();for(int i=0;i<Math.min(8,lore.size());i++)try{
                String json=lore.getString(i);json(json,512);var line=sanitize(Component.Serializer.fromJson(json),new IdentityHashMap<>(),new int[]{32},0);String encoded=Component.Serializer.toJson(line);if(encoded.length()<=512)cleanLore.add(StringTag.valueOf(encoded));
            }catch(Exception ignored){}if(!cleanLore.isEmpty())display.put("Lore",cleanLore);
            for(String key:List.of("Damage","CustomModelData","HideFlags"))if(source.contains(key,Tag.TAG_ANY_NUMERIC)){int value=source.getInt(key);if(key.equals("Damage"))value=Math.max(0,Math.min(1000000,value));if(key.equals("HideFlags"))value&=255;out.putInt(key,value);}
            if(source.contains("Unbreakable"))out.putBoolean("Unbreakable",source.getBoolean("Unbreakable"));
            if(source.contains("Potion",Tag.TAG_STRING))try{id(source.getString("Potion"));out.putString("Potion",source.getString("Potion"));}catch(Exception ignored){}
            for(String key:List.of("Enchantments","StoredEnchantments")){var entries=source.getList(key,Tag.TAG_COMPOUND);var safe=new ListTag();for(int i=0;i<Math.min(8,entries.size());i++)try{var entry=entries.getCompound(i);id(entry.getString("id"));var copy=new CompoundTag();copy.putString("id",entry.getString("id"));copy.putShort("lvl",(short)Math.max(0,Math.min(255,entry.getShort("lvl"))));safe.add(copy);}catch(Exception ignored){}if(!safe.isEmpty())out.put(key,safe);}
            var attributes=source.getList("AttributeModifiers",Tag.TAG_COMPOUND);var safeAttributes=new ListTag();for(int i=0;i<Math.min(8,attributes.size());i++)try{
                var entry=attributes.getCompound(i);id(entry.getString("AttributeName"));double amount=entry.getDouble("Amount");int operation=entry.getInt("Operation");if(!Double.isFinite(amount)||Math.abs(amount)>1000000||operation<0||operation>2)continue;
                var copy=new CompoundTag();copy.putString("AttributeName",entry.getString("AttributeName"));copy.putString("Name","DisCraft tooltip");copy.putDouble("Amount",amount);copy.putInt("Operation",operation);copy.putUUID("UUID",entry.hasUUID("UUID")?entry.getUUID("UUID"):new UUID(0,i+1));String slot=entry.getString("Slot");if(!slot.isEmpty()){if(!Set.of("mainhand","offhand","head","chest","legs","feet").contains(slot))continue;copy.putString("Slot",slot);}safeAttributes.add(copy);
            }catch(Exception ignored){}if(!safeAttributes.isEmpty())out.put("AttributeModifiers",safeAttributes);
        }
        out.put("display",display);
        // Truncate optional detail before publishing the immutable snapshot, never the original stack.
        if(out.toString().length()>MAX_TAG)display.remove("Lore");if(out.toString().length()>MAX_TAG)out.remove("AttributeModifiers");
        return new NativeItemData(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),Math.min(9999,stack.getCount()),out.toString());
    }
    public CompoundTag safeTag(){try{return parse(tag);}catch(Exception impossible){throw new IllegalStateException("Validated item tag unreadable");}}
    private static void id(String value){if(value==null||value.length()>256||!value.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))throw new IllegalArgumentException("Invalid item identifier");}
    private static CompoundTag parse(String text)throws Exception{nesting(text,16);return TagParser.parseTag(text);}
    private static void nesting(String text,int limit){int depth=0;char quote=0;boolean escape=false;for(char c:text.toCharArray()){if(quote!=0){if(escape)escape=false;else if(c=='\\')escape=true;else if(c==quote)quote=0;}else if(c=='\''||c=='"')quote=c;else if(c=='{'||c=='['){if(++depth>limit)throw new IllegalArgumentException("Native item depth exceeded");}else if(c=='}'||c==']')depth--;}}
    private static void validateTag(CompoundTag tag){
        if(!TOP.containsAll(tag.getAllKeys()))throw new IllegalArgumentException();
        for(String key:tag.getAllKeys()){int type=switch(key){case "display"->Tag.TAG_COMPOUND;case "Damage","CustomModelData","HideFlags"->Tag.TAG_INT;case "Unbreakable"->Tag.TAG_BYTE;case "Potion"->Tag.TAG_STRING;default->Tag.TAG_LIST;};if(!tag.contains(key,type))throw new IllegalArgumentException();if(type==Tag.TAG_LIST){var list=(ListTag)tag.get(key);if(!list.isEmpty()&&list.getElementType()!=Tag.TAG_COMPOUND)throw new IllegalArgumentException();}}
        if(tag.contains("display")){var display=tag.getCompound("display");if(!Set.of("Name","Lore","color").containsAll(display.getAllKeys()))throw new IllegalArgumentException();if(display.contains("Name")){if(!display.contains("Name",Tag.TAG_STRING))throw new IllegalArgumentException();json(display.getString("Name"),2048);}if(display.contains("color")&&(!display.contains("color",Tag.TAG_INT)||display.getInt("color")<0||display.getInt("color")>0xffffff))throw new IllegalArgumentException();if(display.contains("Lore")){if(!display.contains("Lore",Tag.TAG_LIST))throw new IllegalArgumentException();var list=(ListTag)display.get("Lore");if(!list.isEmpty()&&list.getElementType()!=Tag.TAG_STRING)throw new IllegalArgumentException();}var lore=display.getList("Lore",Tag.TAG_STRING);if(lore.size()>8)throw new IllegalArgumentException();for(int i=0;i<lore.size();i++)json(lore.getString(i),512);}
        for(String key:List.of("Enchantments","StoredEnchantments")){var entries=tag.getList(key,Tag.TAG_COMPOUND);if(entries.size()>8)throw new IllegalArgumentException();for(int i=0;i<entries.size();i++){var e=entries.getCompound(i);if(!Set.of("id","lvl").containsAll(e.getAllKeys())||!e.contains("id",Tag.TAG_STRING)||!e.contains("lvl",Tag.TAG_SHORT))throw new IllegalArgumentException();id(e.getString("id"));if(e.getInt("lvl")<0||e.getInt("lvl")>255)throw new IllegalArgumentException();}}
        var attributes=tag.getList("AttributeModifiers",Tag.TAG_COMPOUND);if(attributes.size()>8)throw new IllegalArgumentException();for(int i=0;i<attributes.size();i++){var e=attributes.getCompound(i);if(!Set.of("AttributeName","Name","Amount","Operation","UUID","Slot").containsAll(e.getAllKeys())||!e.contains("AttributeName",Tag.TAG_STRING)||!e.contains("Name",Tag.TAG_STRING)||!e.contains("Amount",Tag.TAG_DOUBLE)||!e.contains("Operation",Tag.TAG_INT)||!e.getString("Name").equals("DisCraft tooltip")||!e.hasUUID("UUID")||!Double.isFinite(e.getDouble("Amount"))||Math.abs(e.getDouble("Amount"))>1000000||e.getInt("Operation")<0||e.getInt("Operation")>2)throw new IllegalArgumentException();id(e.getString("AttributeName"));if(e.contains("Slot")&&(!e.contains("Slot",Tag.TAG_STRING)||!Set.of("mainhand","offhand","head","chest","legs","feet").contains(e.getString("Slot"))))throw new IllegalArgumentException();}
        if(tag.contains("Potion"))id(tag.getString("Potion"));if(tag.getInt("Damage")<0||tag.getInt("Damage")>1000000||tag.getInt("HideFlags")<0||tag.getInt("HideFlags")>255)throw new IllegalArgumentException();
    }
    private static void json(String text,int max){if(text.length()>max)throw new IllegalArgumentException();nesting(text,16);validateJson(JsonParser.parseString(text),0,new int[]{64});}
    private static void validateJson(JsonElement value,int depth,int[] budget){
        if(depth>8||--budget[0]<0)throw new IllegalArgumentException();if(value.isJsonPrimitive()){if(!value.getAsJsonPrimitive().isString()||value.getAsString().length()>512)throw new IllegalArgumentException();return;}
        if(!value.isJsonObject())throw new IllegalArgumentException();var object=value.getAsJsonObject();if(!Set.of("text","translate","fallback","with","extra","color","font","bold","italic","underlined","strikethrough","obfuscated").containsAll(object.keySet())||!object.has("text")&&!object.has("translate"))throw new IllegalArgumentException();
        for(var entry:object.entrySet()){String key=entry.getKey();var data=entry.getValue();if(key.equals("with")||key.equals("extra")){if(data.getAsJsonArray().size()>16)throw new IllegalArgumentException();for(var child:data.getAsJsonArray())validateJson(child,depth+1,budget);}else if(Set.of("bold","italic","underlined","strikethrough","obfuscated").contains(key)){if(!data.isJsonPrimitive()||!data.getAsJsonPrimitive().isBoolean())throw new IllegalArgumentException();}else{if(!data.isJsonPrimitive()||!data.getAsJsonPrimitive().isString()||data.getAsString().length()>512)throw new IllegalArgumentException();if(key.equals("font"))id(data.getAsString());if(key.equals("color")&&TextColor.parseColor(data.getAsString())==null)throw new IllegalArgumentException();}}
    }
    private static Component sanitize(Component input,IdentityHashMap<Component,Boolean> path,int[] budget,int depth){
        if(input==null||depth>8||--budget[0]<0||path.put(input,true)!=null)throw new IllegalArgumentException();MutableComponent result;
        if(input.getContents() instanceof LiteralContents literal){if(literal.text().length()>512)throw new IllegalArgumentException();result=Component.literal(literal.text());}
        else if(input.getContents() instanceof TranslatableContents tr){if(tr.getKey().length()>256||tr.getArgs().length>8)throw new IllegalArgumentException();Object[] args=new Object[tr.getArgs().length];for(int i=0;i<args.length;i++){Object a=tr.getArgs()[i];if(a instanceof Component c)args[i]=sanitize(c,path,budget,depth+1);else{String value=a==null||a instanceof String||a instanceof Number||a instanceof Boolean?String.valueOf(a):"?";if(value.length()>512)throw new IllegalArgumentException();args[i]=Component.literal(value);}}String fallback=tr.getFallback();if(fallback!=null&&fallback.length()>512)throw new IllegalArgumentException();result=Component.translatableWithFallback(tr.getKey(),fallback,args);}
        else result=Component.empty();
        var s=input.getStyle();id(s.getFont().toString());result.setStyle(s.withClickEvent(null).withHoverEvent(null).withInsertion(null));
        for(var sibling:input.getSiblings())result.append(sanitize(sibling,path,budget,depth+1));path.remove(input);return result;
    }
}
