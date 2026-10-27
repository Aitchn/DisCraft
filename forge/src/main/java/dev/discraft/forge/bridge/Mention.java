package dev.discraft.forge.bridge;

/** UTF-16 span of an explicitly selected actor, checked against canonical server identity and text. */
public record Mention(int start,int end,ActorRef target) {
    public Mention {if(start<0||end<=start||end>4000||target==null)throw new IllegalArgumentException("Invalid mention span");}
}
