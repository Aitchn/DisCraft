package dev.discraft.forge.server;

import dev.discraft.forge.bridge.RelayRecord;
import dev.discraft.forge.bridge.GuildEmojiCatalog;
import dev.discraft.core.Attachment;
import java.util.Map;
import java.util.List;

/** Only the logical server owns an implementation; never serialized or sent to clients. */
public interface DiscordAdapter extends AutoCloseable {
    default RelayRecord prepare(RelayRecord message)throws Exception{return message;}
    default GuildEmojiCatalog catalog(){return GuildEmojiCatalog.empty(0);}
    default Map<String,Attachment> thumbnails(List<String> ids){return Map.of();}
    default void failed(BridgeDiagnostics.Operation operation){BridgeDiagnostics.failed(operation);}
    void send(RelayRecord message) throws Exception;
    /** Explicit server-generated event route; never infer notice identity from a player display name. */
    default void sendNotice(RelayRecord message)throws Exception{send(message);}
    List<RelayRecord> poll() throws Exception;
    @Override default void close(){}
}
