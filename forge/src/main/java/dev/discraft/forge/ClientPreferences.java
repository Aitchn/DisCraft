package dev.discraft.forge;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.config.*;
import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import java.util.*;

/** Side-safe declaration; Forge loads this CLIENT config only on physical clients. Never synchronized. */
public final class ClientPreferences {
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.IntValue PICKER_WIDTH,PICKER_HEIGHT;
    public static final ForgeConfigSpec.BooleanValue SCREENSHOT_CLIPBOARD, MENTION_SOUND;
    static {
        var builder=new ForgeConfigSpec.Builder();
        SCREENSHOT_CLIPBOARD=builder.comment("Copy physical F2 screenshots to the system image clipboard. Vanilla disk saving is always preserved. Default true.").define("screenshotClipboard",true);
        MENTION_SOUND=builder.comment("Play one local sound for a new structured mention targeting your Minecraft UUID.").define("mentionSound",true);
        PICKER_WIDTH=builder.comment("Preferred emoji/sticker panel width in logical GUI pixels; text size is unchanged.").defineInRange("pickerWidth",350,220,600);
        PICKER_HEIGHT=builder.comment("Preferred panel height; clipped to space above/below the real chat input.").defineInRange("pickerHeight",260,180,450);
        SPEC=builder.build();
    }
    record Selection(boolean screenshot,boolean mention,int width,int height){
        Selection{if(width<220||width>600||height<180||height>450)throw new IllegalArgumentException("Client preference bounds");}
        Map<String,Object> values(){var out=new LinkedHashMap<String,Object>();out.put("screenshotClipboard",screenshot);out.put("mentionSound",mention);out.put("pickerWidth",width);out.put("pickerHeight",height);return out;}
    }
    public static void save(boolean screenshot,boolean mention,int width,int height){
        var selection=new Selection(screenshot,mention,width,height);
        if(!SPEC.isLoaded())throw new IllegalStateException("Client config unavailable");
        var config=ConfigTracker.INSTANCE.configSets().getOrDefault(ModConfig.Type.CLIENT,Set.of()).stream().filter(c->c.getSpec()==SPEC).findFirst().orElseThrow(()->new IllegalStateException("Client config unavailable"));
        if(!(config.getConfigData() instanceof CommentedFileConfig data))throw new IllegalStateException("Client file config unavailable");
        persist(data,selection,SPEC::afterReload);
    }
    /** Pinned NightConfig autosave ObservedMap.putAll publishes all four fields, then invokes one save.
     * Individual ConfigValue.set calls each autosave and expose partial files to Forge's reload watcher. */
    static void persist(CommentedConfig data,Selection selection,Runnable invalidate){
        synchronized(data){
            var table=data.valueMap();var wanted=selection.values();var previous=new LinkedHashMap<String,Object>();
            for(String key:wanted.keySet()){if(!table.containsKey(key))throw new IllegalStateException("Client config unavailable");previous.put(key,table.get(key));}
            try{table.putAll(wanted);}
            catch(RuntimeException failure){try{table.putAll(previous);}catch(RuntimeException rollback){failure.addSuppressed(rollback);}throw failure;}
            finally{invalidate.run();}
        }
    }
    private ClientPreferences(){}
}
