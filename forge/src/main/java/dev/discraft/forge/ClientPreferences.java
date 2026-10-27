package dev.discraft.forge;

import net.minecraftforge.common.ForgeConfigSpec;

/** Side-safe declaration; Forge loads this CLIENT config only on physical clients. Never synchronized. */
public final class ClientPreferences {
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.IntValue PICKER_WIDTH,PICKER_HEIGHT;
    public static final ForgeConfigSpec.BooleanValue SCREENSHOT_CLIPBOARD, MENTION_SOUND;
    static {
        var builder=new ForgeConfigSpec.Builder();
        SCREENSHOT_CLIPBOARD=builder.comment("Copy physical F2 screenshots to the system image clipboard. Vanilla disk saving is always preserved. Default true.").define("screenshotClipboard",true);
        MENTION_SOUND=builder.comment("Play one local sound for a new structured mention targeting your Minecraft UUID.").define("mentionSound",true);
        PICKER_WIDTH=builder.comment("Preferred emoji/GIF/sticker panel width in logical GUI pixels; text size is unchanged.").defineInRange("pickerWidth",350,220,600);
        PICKER_HEIGHT=builder.comment("Preferred panel height; clipped to space above/below the real chat input.").defineInRange("pickerHeight",260,180,450);
        SPEC=builder.build();
    }
    private ClientPreferences(){}
}
