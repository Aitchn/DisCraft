package dev.discraft.forge.mixin;

import dev.discraft.forge.server.VanillaChatRelay;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.ForgeHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Marks the pinned Forge event-bus dispatch as complete; never changes its return or cancellation. */
@Mixin(value=ForgeHooks.class,remap=false)
abstract class ForgeChatCompletionMixin {
    @Inject(method="onServerChatSubmittedEvent",at=@At("RETURN"),require=0,remap=false)
    private static void discraft$complete(ServerPlayer player,String plain,Component decorated,CallbackInfoReturnable<Component> ci){VanillaChatRelay.nativeDispatchComplete(player);}
}
