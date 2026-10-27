package dev.discraft.forge.server;

import dev.discraft.forge.DisCraft;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.*;
import net.minecraftforge.fml.common.Mod;

/** Test-only enablement of the existing authoritative item route; never shipped or used with real Discord. */
@Mod.EventBusSubscriber(modid=DisCraft.ID)
public final class ChatPlusServerAcceptance {
    @SubscribeEvent(priority=EventPriority.LOWEST) public static void start(ServerStartedEvent event)throws Exception{
        if(!Boolean.getBoolean("discraft.chatPlusSmoke")||!Boolean.getBoolean("discraft.multiplayerSmoke"))return;
        if(!ServerBridge.mode().equals("test"))throw new AssertionError("Chat compatibility fixture requires the local TEST adapter");
        var config=ServerBridge.class.getDeclaredField("config");config.setAccessible(true);var constructor=ServerConfig.class.getDeclaredConstructor(boolean.class,String.class,String.class,java.util.Set.class,String.class,java.util.List.class,java.util.Set.class,boolean.class);constructor.setAccessible(true);config.set(null,constructor.newInstance(false,"","",java.util.Set.of("*"),"en_us",java.util.List.of(),java.util.Set.of(),true));
        // Hold only this scenario's final mock echo so history-capacity races are deterministic.
        if(!Boolean.getBoolean("discraft.pendingCardSmoke")){var field=ServerBridge.class.getDeclaredField("adapter");field.setAccessible(true);var delegate=(DiscordAdapter)field.get(null);if(!(delegate instanceof TestDiscordAdapter))throw new AssertionError("Delayed echo fixture must use TEST transport");field.set(null,new DelayedEcho(delegate));}
        var renderer=ServerBridge.class.getDeclaredField("itemRenderer");renderer.setAccessible(true);renderer.set(null,new HeadlessItemRenderer(ResourcePackIndex.empty()));
    }
    private static final class DelayedEcho implements DiscordAdapter {
        private final DiscordAdapter delegate;private final java.util.List<dev.discraft.forge.bridge.RelayRecord> held=new java.util.ArrayList<>();private long release;
        DelayedEcho(DiscordAdapter delegate){this.delegate=delegate;}
        public dev.discraft.forge.bridge.RelayRecord prepare(dev.discraft.forge.bridge.RelayRecord record)throws Exception{return delegate.prepare(record);}
        public dev.discraft.forge.bridge.GuildEmojiCatalog catalog(){return delegate.catalog();}
        public java.util.Map<String,dev.discraft.core.Attachment> thumbnails(java.util.List<String> ids){return delegate.thumbnails(ids);}
        public void send(dev.discraft.forge.bridge.RelayRecord record)throws Exception{delegate.send(record);if(record.text().startsWith("CP attachment"))release=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(4);}
        public java.util.List<dev.discraft.forge.bridge.RelayRecord> poll()throws Exception{var result=new java.util.ArrayList<dev.discraft.forge.bridge.RelayRecord>();for(var record:delegate.poll()){if(record.text().startsWith("Peer received: CP attachment")&&System.nanoTime()<release){if(held.size()>=4)throw new AssertionError("Delayed fixture bound");held.add(record);}else result.add(record);}if(System.nanoTime()>=release){result.addAll(held);held.clear();}return java.util.List.copyOf(result);}
        public void close(){held.clear();delegate.close();}
    }
}
