package dev.discraft.forge;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = DisCraft.ID, value = Dist.CLIENT)
public final class ClientEvents {
    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            while (ClientRegistration.OPEN.consumeClick()) {
                var minecraft = Minecraft.getInstance();
                if (!(minecraft.screen instanceof DisCraftScreen)) minecraft.setScreen(new DisCraftScreen(minecraft.screen));
            }
        }
    }
    @SubscribeEvent public static void titleButton(ScreenEvent.Init.Post event) {
        if (event.getScreen() instanceof TitleScreen screen) {
            event.addListener(Button.builder(Component.literal("DisCraft mock"), button ->
                    Minecraft.getInstance().setScreen(new DisCraftScreen(screen)))
                    .bounds(screen.width - 126, 6, 120, 20).build());
        }
    }
}
