package dev.discraft.forge.server;

import dev.discraft.core.*;
import dev.discraft.forge.bridge.RelayRecord;
import java.util.*;

/** Explicit deterministic test peer, exercised through real game packets, never labelled Discord. */
final class TestDiscordAdapter implements DiscordAdapter {
    private final Deque<RelayRecord> incoming=new ArrayDeque<>();
    public TestDiscordAdapter()throws Exception{
        var emoji=new HashMap<String,Attachment>();for(var c:MockTransport.EMOJI.values())emoji.put(c.mediaKey(),Attachment.fromResource(c.mediaKey()));
        incoming.add(new RelayRecord(UUID.randomUUID(),"TEST peer (not Discord)","Server peer 世界 👋 :discraft: :dance:",List.of(Attachment.fromResource("dance.gif")),MockTransport.EMOJI,emoji));
    }
    // Explicit upstream-error fixture, reachable only through the smoke-gated TEST mode.
    public void send(RelayRecord m){if(m.text().equals("fixture:reject-send"))throw new IllegalStateException("TEST adapter rejection fixture");incoming.add(new RelayRecord(UUID.randomUUID(),"TEST peer (not Discord)","Peer received: "+m.text(),m.attachments(),m.catalog(),m.emoji()));}
    public List<RelayRecord> poll(){var result=List.copyOf(incoming);incoming.clear();return result;}
}
