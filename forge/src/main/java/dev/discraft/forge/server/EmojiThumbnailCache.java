package dev.discraft.forge.server;

import dev.discraft.core.Attachment;
import java.util.*;
import java.util.function.LongSupplier;

/** Worker-confined positive/negative LRU; decoded pixels and encoded bytes are independently bounded. */
final class EmojiThumbnailCache {
    static final int MAX_ENTRIES=64,MAX_IMAGES=64,MAX_BYTES=2*1024*1024,MAX_PIXELS=2*1024*1024,FILE_BYTES=256*1024,SINGLE_PIXELS=256*1024;
    interface Loader{Attachment load()throws Exception;}
    private record Value(Optional<Attachment> image,long expires){}
    private final LinkedHashMap<String,Value> values=new LinkedHashMap<>(16,.75f,true);
    private final LongSupplier clock;
    EmojiThumbnailCache(){this(System::nanoTime);}EmojiThumbnailCache(LongSupplier clock){this.clock=clock;}
    static long pixels(Attachment image){return (long)image.media().width()*image.media().height()*image.media().frames().size();}
    Optional<Attachment> get(String key,Loader loader){
        long now=clock.getAsLong();var prior=values.get(key);if(prior!=null&&now<prior.expires())return prior.image();
        Optional<Attachment> image;
        try{var loaded=loader.load();if(loaded.bytes().length>FILE_BYTES||pixels(loaded)>SINGLE_PIXELS)throw new IllegalArgumentException();image=Optional.of(loaded);}catch(Exception rejected){image=Optional.empty();}
        values.put(key,new Value(image,clock.getAsLong()+(image.isPresent()?600_000_000_000L:60_000_000_000L)));
        trim();return image;
    }
    private void trim(){while(values.size()>MAX_ENTRIES||images()>MAX_IMAGES||bytes()>MAX_BYTES||pixels()>MAX_PIXELS)values.remove(values.keySet().iterator().next());}
    int images(){return (int)values.values().stream().filter(v->v.image().isPresent()).count();}
    long bytes(){return values.values().stream().flatMap(v->v.image().stream()).mapToLong(a->a.bytes().length).sum();}
    long pixels(){return values.values().stream().flatMap(v->v.image().stream()).mapToLong(EmojiThumbnailCache::pixels).sum();}
    int size(){return values.size();}
    void retain(Set<String> keys){values.keySet().retainAll(keys);}
    void clear(){values.clear();}
}
