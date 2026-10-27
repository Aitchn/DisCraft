package dev.discraft.forge;

import dev.discraft.forge.bridge.*;
import java.util.*;

/** Delivery-time UUID matching only. Rendering, names, plain text and Discord IDs cannot trigger it. */
public final class MentionNotifications {
    private final Set<UUID> seen=new LinkedHashSet<>();
    public boolean accept(RelayRecord record,UUID localPlayer,boolean enabled){
        if(!seen.add(record.id()))return false;
        if(seen.size()>256)seen.remove(seen.iterator().next());
        return enabled&&localPlayer!=null&&record.mentions().stream().anyMatch(m->m.target().kind()==ActorRef.Kind.MINECRAFT&&m.target().id().equals(localPlayer.toString()));
    }
    public void reset(){seen.clear();}
}
