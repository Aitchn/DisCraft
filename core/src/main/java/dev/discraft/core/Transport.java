package dev.discraft.core;

import java.io.IOException;
import java.util.List;

/** Future loader adapters speak this contract; credentials never belong here.
 * Production implementation must authenticate a trusted relay, not Discord directly. */
public interface Transport extends AutoCloseable {
    void send(Message outbound) throws IOException;
    List<Message> poll() throws IOException;
    @Override default void close() {}
}
