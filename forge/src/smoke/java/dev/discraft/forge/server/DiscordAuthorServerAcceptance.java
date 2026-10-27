package dev.discraft.forge.server;

import dev.discraft.forge.DisCraft;
import net.minecraftforge.event.server.*;
import net.minecraftforge.eventbus.api.*;
import net.minecraftforge.fml.common.Mod;
import java.nio.file.*;

/** Actual production adapter + server network, backed only by an isolated local REST peer. */
@Mod.EventBusSubscriber(modid=DisCraft.ID)
public final class DiscordAuthorServerAcceptance {
    private static DiscordAuthorFixtures peer;
    private static boolean enabled(){return Boolean.getBoolean("discraft.multiplayerSmoke")&&Boolean.getBoolean("discraft.discordAuthorSmoke");}
    @SubscribeEvent(priority=EventPriority.LOWEST) public static void start(ServerStartedEvent event)throws Exception{
        if(!enabled())return;peer=new DiscordAuthorFixtures();var field=ServerBridge.class.getDeclaredField("adapter");field.setAccessible(true);((DiscordAdapter)field.get(null)).close();field.set(null,peer.adapter());
    }
    @SubscribeEvent(priority=EventPriority.LOWEST) public static void stop(ServerStoppedEvent event)throws Exception{
        if(!enabled())return;boolean valid=peer!=null&&peer.posts.get()==0&&peer.memberRequests.get()==4;Path out=Path.of(System.getProperty("discraft.discordAuthorOutput"));Files.createDirectories(out);Files.writeString(out.resolve("server-result.txt"),valid?"PASS actual adapter/server ingress: four member-name lookups, zero POSTs; isolated loopback REST only\n":"FAIL Discord author peer counts\n");if(peer!=null)peer.close();
    }
}
