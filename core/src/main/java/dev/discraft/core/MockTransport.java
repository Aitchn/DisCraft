package dev.discraft.core;

import java.io.IOException;
import java.util.*;

/** In-process deterministic fixture peer. Does not use HTTP, Discord, or credentials. */
public final class MockTransport implements Transport {
    public static final Map<String, Content.CustomEmoji> EMOJI = Map.of(
            "discraft", new Content.CustomEmoji("fixture-discraft", "discraft", false, "emoji.png"),
            "dance", new Content.CustomEmoji("fixture-dance", "dance", true, "dance.gif"));
    private final Deque<Message> pending = new ArrayDeque<>();
    private int sentCount;
    public MockTransport() throws IOException {
        pending.add(inbound("Fixture peer", "Hello 世界 👋 Ω :discraft:", List.of(Attachment.fromResource("landscape.png"))));
        pending.add(inbound("Fixture peer", "Animated GIF + custom emoji :dance:", List.of(Attachment.fromResource("dance.gif"))));
    }
    public static Message inbound(String author, String text, List<Attachment> media) {
        UUID id = UUID.randomUUID();
        return new Message(id, id, Message.Origin.RELAY, author, Content.parse(text, EMOJI), media);
    }
    @Override public synchronized void send(Message outbound) throws IOException {
        if (pending.size() > 120) throw new IOException("Mock queue full");
        sentCount++;
        // Simulate a relay echo, followed by a distinct peer reply. Echo must be discarded.
        pending.add(new Message(UUID.randomUUID(), outbound.correlationId(), Message.Origin.RELAY,
                "Echo (must not appear)", outbound.content(), outbound.attachments()));
        pending.add(inbound("Mock peer (not Discord)", "Mock received: " + Content.plain(outbound.content()).substring(0,
                Content.plain(outbound.content()).offsetByCodePoints(0, Math.min(1000,
                        Content.plain(outbound.content()).codePointCount(0, Content.plain(outbound.content()).length())))), outbound.attachments()));
    }
    @Override public synchronized List<Message> poll() { var result = List.copyOf(pending); pending.clear(); return result; }
    public synchronized int sentCount() { return sentCount; }
    @Override public synchronized void close() { pending.clear(); }
}
