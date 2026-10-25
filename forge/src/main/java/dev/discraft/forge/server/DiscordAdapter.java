package dev.discraft.forge.server;

import dev.discraft.forge.bridge.RelayRecord;
import java.util.List;

/** Only the logical server owns an implementation; never serialized or sent to clients. */
public interface DiscordAdapter extends AutoCloseable {
    default RelayRecord prepare(RelayRecord message)throws Exception{return message;}
    void send(RelayRecord message) throws Exception;
    List<RelayRecord> poll() throws Exception;
    @Override default void close(){}
}
