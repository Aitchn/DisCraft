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
        var renderer=ServerBridge.class.getDeclaredField("itemRenderer");renderer.setAccessible(true);renderer.set(null,new HeadlessItemRenderer(ResourcePackIndex.empty()));
    }
}
