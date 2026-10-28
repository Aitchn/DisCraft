package dev.discraft.forge;

import net.minecraftforge.fml.common.Mod;

@Mod(DisCraft.ID)
public final class DisCraft {
    public static final String ID = "discraft";
    public DisCraft() { net.minecraftforge.fml.ModLoadingContext.get().registerConfig(net.minecraftforge.fml.config.ModConfig.Type.CLIENT,ClientPreferences.SPEC,"discraft/client.toml"); dev.discraft.forge.bridge.BridgeNetwork.init(); dev.discraft.forge.bridge.TooltipChannel.init(); com.mojang.logging.LogUtils.getLogger().info("DisCraft loaded; bridge state is reported at logical server initialization"); }
}
