package dev.discraft.forge.mixin;

import dev.discraft.forge.server.VanillaChatRelay;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Observation only, before compatible mods replace player packets. No arguments or cancellation are changed. */
@Mixin(value=ServerGamePacketListenerImpl.class,priority=500,remap=false)
abstract class ServerChatOriginMixin {
    // Pinned 1.20.1 official + SRG selectors; no external mod code or optional-mod dependency.
    @Inject(method={"send(Lnet/minecraft/network/protocol/Packet;)V","m_9829_(Lnet/minecraft/network/protocol/Packet;)V"},at=@At("HEAD"),require=0,remap=false)
    private void discraft$observe(Packet<?> packet,CallbackInfo ci){VanillaChatRelay.beforeNativeSend(((ServerGamePacketListenerImpl)(Object)this).player,packet);}
    @Inject(method={"send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;)V","m_243119_(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;)V"},at=@At("HEAD"),require=0,remap=false)
    private void discraft$observeWithListener(Packet<?> packet,PacketSendListener listener,CallbackInfo ci){VanillaChatRelay.beforeNativeSend(((ServerGamePacketListenerImpl)(Object)this).player,packet);}
}
