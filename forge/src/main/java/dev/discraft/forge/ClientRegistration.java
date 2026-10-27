package dev.discraft.forge;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

@Mod.EventBusSubscriber(modid = DisCraft.ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ClientRegistration {
    public static final KeyMapping OPEN = new KeyMapping("key.discraft.open", InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_F8, "key.categories.discraft");
    @SubscribeEvent public static void setup(net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent event) {
        net.minecraftforge.fml.ModLoadingContext.get().registerExtensionPoint(net.minecraftforge.client.ConfigScreenHandler.ConfigScreenFactory.class,()->new net.minecraftforge.client.ConfigScreenHandler.ConfigScreenFactory(ClientSettingsScreen::new));
    }
    @SubscribeEvent public static void register(RegisterKeyMappingsEvent event) { event.register(OPEN); }
}
