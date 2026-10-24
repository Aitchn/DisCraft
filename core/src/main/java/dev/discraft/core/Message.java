package dev.discraft.core;

import java.util.*;

public record Message(UUID id, UUID correlationId, Origin origin, String author,
                      List<Content> content, List<Attachment> attachments) {
    public enum Origin { CLIENT, RELAY }
    public Message {
        Objects.requireNonNull(id); Objects.requireNonNull(correlationId); Objects.requireNonNull(origin);
        Limits.text(author, 80, "author");
        content = List.copyOf(content); attachments = List.copyOf(attachments);
        Limits.text(Content.plain(content), Limits.TEXT_CODEPOINTS, "message");
        if (attachments.size() > Limits.ATTACHMENTS) throw new IllegalArgumentException("At most four attachments");
        long pixels = attachments.stream().mapToLong(a -> (long)a.media().width() * a.media().height() * a.media().frames().size()).sum();
        if (pixels > Limits.MEDIA_TOTAL_PIXELS) throw new IllegalArgumentException("Message pixel budget exceeded");
        if (Content.plain(content).isBlank() && attachments.isEmpty()) throw new IllegalArgumentException("Empty message");
    }
}
