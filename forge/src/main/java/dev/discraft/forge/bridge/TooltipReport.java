package dev.discraft.forge.bridge;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import java.util.*;

/**
 * Client to server: the tooltip lines (Component JSON) the sender's client renders for its main hand, tied to one
 * draft and sent over {@link TooltipChannel}. The server treats the lines as untrusted text and re-validates them
 * against its own capture.
 */
public record TooltipReport(UUID draft,String itemId,int count,List<String> lines) {
    public static final int MAX_LINES=64,MAX_LINE=4096,MAX_CHARS=12000,MAX_BYTES=30000;
    public TooltipReport{
        Objects.requireNonNull(draft);Objects.requireNonNull(itemId);lines=List.copyOf(lines);
        if(itemId.length()>256||count<0||lines.size()>MAX_LINES||lines.stream().anyMatch(l->l.length()>MAX_LINE)||lines.stream().mapToInt(String::length).sum()>MAX_CHARS)throw new IllegalArgumentException("Tooltip report bounds");
    }
    void write(FriendlyByteBuf b){b.writeUUID(draft);b.writeUtf(itemId,256);b.writeVarInt(count);b.writeVarInt(lines.size());for(String line:lines)b.writeUtf(line,MAX_LINE);}
    static TooltipReport read(FriendlyByteBuf b){
        UUID draft=b.readUUID();String id=b.readUtf(256);int count=b.readVarInt();int n=b.readVarInt();if(n<0||n>MAX_LINES)throw new IllegalArgumentException("Tooltip report bounds");
        var lines=new ArrayList<String>();for(int i=0;i<n;i++)lines.add(b.readUtf(MAX_LINE));return new TooltipReport(draft,id,count,lines);
    }
    /** Serverbound custom payloads are capped near 32 KiB; an oversized report is not sent and the server falls back. */
    public boolean fits(){var buffer=new FriendlyByteBuf(Unpooled.buffer());try{write(buffer);return buffer.readableBytes()<=MAX_BYTES;}finally{buffer.release();}}
}
