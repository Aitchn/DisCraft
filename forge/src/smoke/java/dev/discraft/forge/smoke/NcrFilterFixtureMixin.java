package dev.discraft.forge.smoke;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket;
import net.minecraft.network.chat.FilterMask;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Test only: supplies a real partial filter before NCR converts the packet. Never shipped or auto-enabled. */
@Mixin(value=ServerGamePacketListenerImpl.class,priority=400,remap=false)
abstract class NcrFilterFixtureMixin {
    @ModifyVariable(method={"send(Lnet/minecraft/network/protocol/Packet;)V","m_9829_(Lnet/minecraft/network/protocol/Packet;)V"},at=@At("HEAD"),argsOnly=true,ordinal=0,require=0,remap=false)
    private Packet<?> discraft$partialFilter(Packet<?> value){
        if(Boolean.getBoolean("discraft.ncrCompatibility")&&value instanceof ClientboundPlayerChatPacket packet&&packet.body().content().equals("bare-filter-before-conversion")&&packet.filterMask().isEmpty()){
            var mask=new FilterMask(packet.body().content().length());mask.setFiltered(0);return new ClientboundPlayerChatPacket(packet.sender(),packet.index(),packet.signature(),packet.body(),packet.unsignedContent(),mask,packet.chatType());
        }return value;
    }
}
