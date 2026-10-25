package dev.discraft.core;

import java.io.IOException;
import java.util.*;
import java.util.function.LongSupplier;

/** Bounded local policy and replay/echo suppression, independent of any game loader. */
public final class ChatSession implements AutoCloseable {
    public record Permissions(boolean sendText, boolean sendMedia, boolean receive) {
        public static Permissions preview() { return new Permissions(true, true, true); }
    }
    private final Transport transport;
    private final Permissions permissions;
    private final Map<String, Content.CustomEmoji> catalog;
    private final LongSupplier clock;
    private final Deque<Message> history = new ArrayDeque<>();
    private final LinkedHashSet<UUID> seen = new LinkedHashSet<>(), outbound = new LinkedHashSet<>();
    private double tokens = 5;
    private long updated;
    private boolean closed;
    public ChatSession(Transport transport, Permissions permissions, Map<String, Content.CustomEmoji> catalog, LongSupplier clock) {
        this.transport = Objects.requireNonNull(transport); this.permissions = permissions;
        this.catalog = Map.copyOf(catalog); this.clock = clock; updated = clock.getAsLong();
    }
    public synchronized Message send(String text, List<Attachment> attachments) throws IOException {
        ensureOpen();
        if ((!text.isBlank() && !permissions.sendText()) || (!attachments.isEmpty() && !permissions.sendMedia()))
            throw new IOException("Channel permission denied");
        UUID id = UUID.randomUUID();
        Message message = new Message(id, id, Message.Origin.CLIENT, "You (local)", Content.parse(text, catalog), attachments);
        consume();
        // Remember before send: a synchronous callback/replay cannot produce a duplicate.
        remember(outbound, id);
        transport.send(message);
        remember(seen, id); add(message);
        return message;
    }
    public synchronized int poll() throws IOException {
        ensureOpen();
        int accepted = 0;
        for (Message message : transport.poll()) {
            if (!permissions.receive() || message.origin() != Message.Origin.RELAY || seen.contains(message.id())
                    || outbound.contains(message.correlationId())) continue;
            remember(seen, message.id()); add(message); accepted++;
        }
        return accepted;
    }
    private void consume() throws IOException {
        long now = clock.getAsLong();
        tokens = Math.min(5, tokens + Math.max(0, now - updated) / 1000.0); updated = now;
        if (tokens < 1) throw new IOException("Rate limited: five-message burst, then one per second");
        tokens--;
    }
    private static void remember(LinkedHashSet<UUID> set, UUID id) {
        set.add(id);
        if (set.size() > 2048) set.remove(set.iterator().next());
    }
    private void add(Message message) {
        history.add(message);
        // Bound decoded memory retained by the transcript, including animated frames.
        while (history.size() > Limits.HISTORY || retainedPixels() > 2 * Limits.MEDIA_TOTAL_PIXELS) history.removeFirst();
    }
    private long retainedPixels() {
        return history.stream().flatMap(m -> m.attachments().stream())
                .mapToLong(a -> (long)a.media().width() * a.media().height() * a.media().frames().size()).sum();
    }
    public synchronized List<Message> snapshot() { return List.copyOf(history); }
    public synchronized boolean hasSeen(UUID id) { return seen.contains(id); }
    private void ensureOpen() throws IOException { if (closed) throw new IOException("Session closed"); }
    @Override public synchronized void close() { closed = true; transport.close(); history.clear(); seen.clear(); outbound.clear(); }
}
