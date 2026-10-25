package dev.discraft.forge.bridge;

import dev.discraft.core.Limits;
import java.util.UUID;

/** Stable identity supplied by the server, never derived from a display-name string. */
public record ActorRef(Kind kind,String id,String display) {
    public enum Kind { MINECRAFT, DISCORD }
    public ActorRef {
        if(kind==null||id==null)throw new IllegalArgumentException("Missing actor identity");
        if(kind==Kind.MINECRAFT&&!UUID.fromString(id).toString().equals(id)||kind==Kind.DISCORD&&!id.matches("[0-9]{15,22}"))throw new IllegalArgumentException("Invalid actor identity");
        Limits.text(display,64,"actor display");if(display.isBlank()||display.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Invalid actor display");
    }
    public String key(){return kind+":"+id;}
    public String label(){return (kind==Kind.DISCORD?"D@":"M@")+display;}
}
