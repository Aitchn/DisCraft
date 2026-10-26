package dev.discraft.forge.server;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.*;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import java.util.Objects;
import dev.discraft.forge.bridge.NativeItemData;

/** Immutable appearance plus a bounded native-only tooltip whitelist. No original stack, container or book body survives. */
record ItemSnapshot(String id,String name,String font,int count,int customModelData,double damage,boolean damaged,
                    boolean leftHanded,int tint,boolean enchanted,boolean empty,NativeItemData nativeItem,ItemTooltip tooltip) {
    ItemSnapshot(String id,String name,String font,int count,int customModelData,double damage,boolean damaged,boolean leftHanded,int tint,boolean enchanted,boolean empty){this(id,name,font,count,customModelData,damage,damaged,leftHanded,tint,enchanted,empty,null,ItemTooltip.nameOnly(name,font));}
    ItemSnapshot {
        Objects.requireNonNull(id);Objects.requireNonNull(name);Objects.requireNonNull(font);Objects.requireNonNull(tooltip);
        dev.discraft.core.Limits.text(name,128,"item name");
        if(!id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")||id.length()>256||font.length()>256||name.codePointCount(0,name.length())>128||count<0||count>9999||!Double.isFinite(damage)||damage<0||damage>1)throw new IllegalArgumentException("Invalid item appearance");
    }
    static ItemSnapshot capture(ServerPlayer player,ComponentText text){
        if(!player.server.isSameThread())throw new IllegalStateException("Item capture requires server game thread");
        // Copy the authoritative main hand on the game thread, then retain only the whitelist below.
        ItemStack stack=player.getMainHandItem().copy();
        if(stack.isEmpty())return new ItemSnapshot("minecraft:air",text.render(Component.translatableWithFallback("discraft.item.empty","Empty hand")),"minecraft:default",0,0,0,false,false,-1,false,true);
        String id=BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();Component name=stack.getHoverName();String label=text.render(name);
        if(label.codePointCount(0,label.length())>128)label=label.substring(0,label.offsetByCodePoints(0,127))+"…";
        int model=stack.hasTag()&&stack.getTag().contains("CustomModelData",Tag.TAG_ANY_NUMERIC)?stack.getTag().getInt("CustomModelData"):0;
        int tint=stack.getItem() instanceof DyeableLeatherItem leather?leather.getColor(stack):stack.getItem() instanceof PotionItem||stack.getItem() instanceof TippedArrowItem?PotionUtils.getColor(stack):-1;
        var nativeData=NativeItemData.capture(stack,label);var safe=new ItemStack(stack.getItem(),stack.getCount());safe.setTag(nativeData.safeTag());
        var tooltip=ItemTooltip.capture(safe,player,text);
        return new ItemSnapshot(id,label,name.getStyle().getFont().toString(),Math.min(9999,stack.getCount()),model,stack.isDamageableItem()?Math.max(0,Math.min(1,(double)stack.getDamageValue()/Math.max(1,stack.getMaxDamage()))):0,stack.isDamaged(),player.getMainArm()==HumanoidArm.LEFT,tint,stack.hasFoil(),false,nativeData,tooltip);
    }
    String label(){return empty?"["+name+"]":"["+name+" ×"+count+"]";}
}
