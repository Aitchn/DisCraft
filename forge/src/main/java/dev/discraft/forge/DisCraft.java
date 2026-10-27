package dev.discraft.forge;

import net.minecraftforge.fml.common.Mod;

@Mod(DisCraft.ID)
public final class DisCraft {
    public static final String ID = "discraft";
    public DisCraft() { dev.discraft.forge.bridge.BridgeNetwork.init(); com.mojang.logging.LogUtils.getLogger().info("DisCraft loaded; bridge state is reported at logical server initialization"); }
}
