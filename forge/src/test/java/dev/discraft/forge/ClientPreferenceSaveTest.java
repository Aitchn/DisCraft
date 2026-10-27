package dev.discraft.forge;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.core.utils.ObservedMap;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Pinned NightConfig observed-map publication contract plus an actual synchronous autosave TOML file. */
public final class ClientPreferenceSaveTest {
    private static int checks;
    private static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);checks++;}
    private static LinkedHashMap<String,Object> defaults(){var out=new LinkedHashMap<String,Object>();out.put("screenshotClipboard",true);out.put("mentionSound",true);out.put("pickerWidth",350);out.put("pickerHeight",260);out.put("futurePreference","preserve");return out;}
    public static void run()throws Exception{
        final var raw=defaults();var writes=new ArrayList<Map<String,Object>>();
        var observed=new ObservedMap<String,Object>(raw,()->{writes.add(Map.copyOf(raw));if(writes.size()==3){var earlier=writes.get(1);raw.clear();raw.putAll(earlier);}});
        var legacy=CommentedConfig.wrap(observed,CommentedConfig.inMemory().configFormat());
        legacy.set("pickerWidth",410);legacy.set("pickerHeight",315);legacy.set("screenshotClipboard",false);legacy.set("mentionSound",false);
        check(writes.size()==4&&Boolean.TRUE.equals(raw.get("screenshotClipboard")),"Legacy per-field autosaves allow a watcher replay of a partially published configuration to lose an earlier preference");
        final var state=defaults();writes.clear();var invalidations=new AtomicInteger();
        var grouped=CommentedConfig.wrap(new ObservedMap<String,Object>(state,()->{writes.add(Map.copyOf(state));if(writes.size()==3){var earlier=writes.get(1);state.clear();state.putAll(earlier);}}),CommentedConfig.inMemory().configFormat());grouped.setComment("screenshotClipboard","existing comment");
        var wanted=new ClientPreferences.Selection(false,false,410,315);ClientPreferences.persist(grouped,wanted,invalidations::incrementAndGet);
        check(writes.size()==1&&writes.get(0).entrySet().containsAll(wanted.values().entrySet()),"Preference group is published once with all four requested values already present");
        check(invalidations.get()==1&&state.entrySet().containsAll(wanted.values().entrySet()),"Reload-cache invalidation observes the complete saved selection");
        check(state.get("futurePreference").equals("preserve")&&grouped.getComment("screenshotClipboard").equals("existing comment"),"Batch update preserves unrelated values and comments");
        var failedState=defaults();var failures=new AtomicInteger();var failed=CommentedConfig.wrap(new ObservedMap<String,Object>(failedState,()->{if(failures.incrementAndGet()==1)throw new IllegalStateException("Synthetic write failure");}),CommentedConfig.inMemory().configFormat());
        try{ClientPreferences.persist(failed,wanted,invalidations::incrementAndGet);throw new AssertionError("Failed write accepted");}catch(IllegalStateException expected){check(failures.get()==2&&failedState.equals(defaults()),"Failed group publication rolls back the complete previous group without partial per-key writes");}
        Path path=Files.createTempFile("discraft-client-save-",".toml");
        try(var file=CommentedFileConfig.builder(path).sync().autosave().build()){
            file.load();file.valueMap().putAll(defaults());file.setComment("screenshotClipboard","keep comment");ClientPreferences.persist(file,wanted,()->{});file.load();
            check(file.valueMap().entrySet().containsAll(wanted.values().entrySet())&&file.get("futurePreference").equals("preserve"),"Actual NightConfig autosave file round-trips all four preferences together");
            check(Files.readString(path).contains("screenshotClipboard = false")&&file.getComment("screenshotClipboard").equals("keep comment"),"Actual TOML preserves explicit false and comments");
        }finally{Files.deleteIfExists(path);}
        try{new ClientPreferences.Selection(false,false,219,315);throw new AssertionError("Invalid dimension accepted");}catch(IllegalArgumentException expected){check(true,"Preference publication retains size bounds");}
        System.out.println("PASS "+checks+" grouped client preference autosave/reload/rollback/TOML checks");
    }
}
