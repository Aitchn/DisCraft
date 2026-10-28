package dev.discraft.forge.server;

import dev.discraft.forge.bridge.*;
import net.minecraft.nbt.*;
import java.util.*;

final class NativeItemDataTest {
    private static int checks;
    private static void check(boolean value,String label){if(!value)throw new AssertionError(label);checks++;System.out.println("PASS "+label);}
    private static void rejects(String tag,String label){try{new NativeItemData("minecraft:diamond_sword",1,tag);throw new AssertionError(label);}catch(IllegalArgumentException expected){check(true,label);}}
    static void run()throws Exception{
        var display=new CompoundTag();display.putString("Name","{\"text\":\"星光劍\",\"color\":\"gold\",\"italic\":true}");var lore=new ListTag();lore.add(StringTag.valueOf("{\"text\":\"有界物品說明\",\"color\":\"gray\"}"));display.put("Lore",lore);var tag=new CompoundTag();tag.put("display",display);tag.putInt("Damage",4);
        var item=new NativeItemData("minecraft:diamond_sword",3,tag.toString());String text="show [星光劍 ×3]";var original=new RelayRecord(UUID.randomUUID(),"Aitchn",text,List.of(),Map.of(),Map.of(),null,List.of(),List.of(new ItemInline(5,text.length(),item)));var decoded=RelayRecord.decode(original.encode());
        check(decoded.nativeItems().equals(original.nativeItems())&&decoded.attachments().isEmpty(),"Wire preserves bounded native item hover metadata without a default image");
        var emptyOverride=new NativeItemData("minecraft:diamond_sword",1,"{AttributeModifiers:[]}");var emptyRecord=new RelayRecord(UUID.randomUUID(),"Aitchn","item",List.of(),Map.of(),Map.of(),null,List.of(),List.of(new ItemInline(0,4,emptyOverride)));var roundtrip=RelayRecord.decode(emptyRecord.encode()).nativeItems().get(0).item().safeTag();
        check(roundtrip.contains("AttributeModifiers",Tag.TAG_LIST)&&roundtrip.getList("AttributeModifiers",Tag.TAG_COMPOUND).isEmpty(),"Wire retains the distinction between an explicit empty attribute override and an absent key");
        var mutated=item.safeTag();mutated.putString("pages","fixture-private");check(!item.safeTag().contains("pages"),"Safe item tag accessor does not expose retained mutable NBT");
        rejects("{pages:[\"fixture-private\"]}","Book body cannot enter native item payload");rejects("{BlockEntityTag:{Items:[]}}","Container data cannot enter native item payload");
        rejects("{display:{Name:'{\"text\":\"name\",\"clickEvent\":{\"action\":\"run_command\",\"value\":\"/x\"}}'}}","Name click actions cannot reach native item tooltip");
        rejects("{display:{Name:'{\"text\":\"name\",\"hoverEvent\":{\"action\":\"show_text\",\"contents\":\"fixture-private\"}}'}}","Nested arbitrary hover cannot reach native item tooltip");
        rejects("{display:{Name:'{\"selector\":\"@a\"}'}}","Dynamic selector components rejected instead of evaluated");rejects("{Enchantments:[\"fixture-private\"]}","Wrong list element type cannot smuggle arbitrary NBT");rejects("{display:{Lore:[{secret:\"fixture-private\"}]}}","Lore requires bounded safe component strings");
        rejects("{Enchantments:[{id:'minecraft:sharpness',lvl:'fixture-private'}]}","Enchantment levels require canonical numeric tags");
        rejects("{AttributeModifiers:[{AttributeName:'minecraft:generic.attack_damage',Name:'DisCraft tooltip',Amount:'fixture-private',Operation:0,UUID:[I;1,2,3,4]}]}","Attribute amounts cannot smuggle arbitrary strings behind numeric defaults");
        rejects("{x:".repeat(20)+"0"+"}".repeat(20),"Native NBT depth rejected before parsing");rejects("{display:{Name:'"+"x".repeat(2049)+"'}}","Native name JSON byte budget enforced");
        var modded=new NativeItemData("minecraft:leather_chestplate",1,"{display:{Name:'{\"text\":\"Chestplate\"}'},mmorpg:{gear:'rarity=legendary',sockets:[{gem:'ruby'}]}}");var modRoundtrip=RelayRecord.decode(new RelayRecord(UUID.randomUUID(),"Aitchn","item",List.of(),Map.of(),Map.of(),null,List.of(),List.of(new ItemInline(0,4,modded))).encode()).nativeItems().get(0).item().safeTag();
        check(modRoundtrip.getCompound("mmorpg").getString("gear").equals("rarity=legendary"),"Opt-in mod item data survives the wire for the receiving client's own tooltip code");
        rejects("{mmorpg:{backpack:{Items:[{id:'minecraft:diamond'}]}}}","Nested container contents cannot ride inside mod data");rejects("{mod:[{Inventory:[]}]}","Inventory keys inside mod lists are rejected");
        rejects("{"+"k".repeat(129)+":1}","Mod data key length is bounded");rejects("{mod:'"+"x".repeat(NativeItemData.MAX_TAG)+"'}","Mod data cannot exceed the native tag budget");
        try{new RelayRecord(UUID.randomUUID(),"Aitchn","x",List.of(),Map.of(),Map.of(),null,List.of(),List.of(new ItemInline(0,2,item)));throw new AssertionError();}catch(IllegalArgumentException expected){check(true,"Inline ranges cannot exceed original text");}
        var actor=new ActorRef(ActorRef.Kind.DISCORD,"2234567890123456","Same");try{new RelayRecord(UUID.randomUUID(),"Aitchn",actor.label(),List.of(),Map.of(),Map.of(),null,List.of(new Mention(0,actor.label().length(),actor)),List.of(new ItemInline(0,actor.label().length(),item)));throw new AssertionError();}catch(IllegalArgumentException expected){check(true,"Inline items cannot overlap selected mention bindings");}
        try{new RelayRecord(UUID.randomUUID(),"Aitchn","😀",List.of(),Map.of(),Map.of(),null,List.of(),List.of(new ItemInline(1,2,item)));throw new AssertionError();}catch(IllegalArgumentException expected){check(true,"Native item range cannot split a Unicode surrogate pair");}
        System.out.println("PASS "+checks+" native item wire/safety checks (fake fixtures only)");
    }
}
