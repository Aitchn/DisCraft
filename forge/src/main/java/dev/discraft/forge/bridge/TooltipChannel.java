package dev.discraft.forge.bridge;

import dev.discraft.forge.DisCraft;
import dev.discraft.forge.server.ServerBridge;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.*;
import net.minecraftforge.network.simple.SimpleChannel;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/** Optional channel for {@link TooltipReport}; old clients and old servers lack it and simply never use it. */
public final class TooltipChannel {
    private TooltipChannel(){}
    private static final String VERSION="1";
    private static final ResourceLocation NAME=new ResourceLocation(DisCraft.ID,"tooltip");
    public static final SimpleChannel CHANNEL=NetworkRegistry.newSimpleChannel(NAME,()->VERSION,NetworkRegistry.acceptMissingOr(VERSION),NetworkRegistry.acceptMissingOr(VERSION));
    private static final AtomicInteger QUEUED=new AtomicInteger();
    public static void init(){CHANNEL.registerMessage(0,TooltipReport.class,TooltipReport::write,TooltipReport::read,TooltipChannel::handle,Optional.of(NetworkDirection.PLAY_TO_SERVER));}
    private static void handle(TooltipReport report,Supplier<NetworkEvent.Context> supplied){
        var context=supplied.get();var player=context.getSender();context.setPacketHandled(true);
        if(player==null||QUEUED.incrementAndGet()>64){QUEUED.decrementAndGet();return;}
        context.enqueueWork(()->{try{ServerBridge.tooltipReport(player,report);}finally{QUEUED.decrementAndGet();}});
    }
    public static boolean supported(net.minecraft.network.Connection connection){
        if(connection==null||connection.channel()==null)return false;var data=NetworkHooks.getConnectionData(connection);return data!=null&&VERSION.equals(data.getChannels().get(NAME));
    }
}
