package dev.discraft.forge.bridge;

import dev.discraft.forge.DisCraft;
import dev.discraft.forge.server.ServerBridge;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.*;
import net.minecraftforge.network.simple.SimpleChannel;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Client to server: the tooltip lines (Component JSON) the sender's client renders for its main hand, tied to one
 * draft. A separate optional channel, so old clients and old servers simply never use it. The server treats the
 * lines as untrusted text and re-validates them against its own capture.
 */
public record TooltipReport(UUID draft,String itemId,int count,List<String> lines) {
    public static final int MAX_LINES=64,MAX_LINE=4096,MAX_CHARS=12000,MAX_BYTES=30000;
    private static final String VERSION="1";
    private static final ResourceLocation NAME=new ResourceLocation(DisCraft.ID,"tooltip");
    public static final SimpleChannel CHANNEL=NetworkRegistry.newSimpleChannel(NAME,()->VERSION,NetworkRegistry.acceptMissingOr(VERSION),NetworkRegistry.acceptMissingOr(VERSION));
    private static final AtomicInteger QUEUED=new AtomicInteger();
    public TooltipReport{
        Objects.requireNonNull(draft);Objects.requireNonNull(itemId);lines=List.copyOf(lines);
        if(itemId.length()>256||count<0||lines.size()>MAX_LINES||lines.stream().anyMatch(l->l.length()>MAX_LINE)||lines.stream().mapToInt(String::length).sum()>MAX_CHARS)throw new IllegalArgumentException("Tooltip report bounds");
    }
    public static void init(){CHANNEL.registerMessage(0,TooltipReport.class,TooltipReport::write,TooltipReport::read,TooltipReport::handle,Optional.of(NetworkDirection.PLAY_TO_SERVER));}
    private void write(FriendlyByteBuf b){b.writeUUID(draft);b.writeUtf(itemId,256);b.writeVarInt(count);b.writeVarInt(lines.size());for(String line:lines)b.writeUtf(line,MAX_LINE);}
    private static TooltipReport read(FriendlyByteBuf b){
        UUID draft=b.readUUID();String id=b.readUtf(256);int count=b.readVarInt();int n=b.readVarInt();if(n<0||n>MAX_LINES)throw new IllegalArgumentException("Tooltip report bounds");
        var lines=new ArrayList<String>();for(int i=0;i<n;i++)lines.add(b.readUtf(MAX_LINE));return new TooltipReport(draft,id,count,lines);
    }
    /** Serverbound custom payloads are capped near 32 KiB; an oversized report is not sent and the server falls back. */
    public boolean fits(){var buffer=new FriendlyByteBuf(Unpooled.buffer());try{write(buffer);return buffer.readableBytes()<=MAX_BYTES;}finally{buffer.release();}}
    private static void handle(TooltipReport report,Supplier<NetworkEvent.Context> supplied){
        var context=supplied.get();var player=context.getSender();context.setPacketHandled(true);
        if(player==null||QUEUED.incrementAndGet()>64){QUEUED.decrementAndGet();return;}
        context.enqueueWork(()->{try{ServerBridge.tooltipReport(player,report);}finally{QUEUED.decrementAndGet();}});
    }
    public static boolean supported(net.minecraft.network.Connection connection){
        if(connection==null||connection.channel()==null)return false;var data=NetworkHooks.getConnectionData(connection);return data!=null&&VERSION.equals(data.getChannels().get(NAME));
    }
}
