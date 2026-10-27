package dev.discraft.forge.smoke;

import dev.discraft.forge.*;
import dev.discraft.forge.bridge.*;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.sound.PlaySoundEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import java.util.*;
import java.util.function.Consumer;

/** Authored loopback fixture records enter the actual bounded decoder; observes the sound engine event. */
@Mod.EventBusSubscriber(modid=DisCraft.ID,value=Dist.CLIENT)
public final class MentionSoundAcceptance {
    private static int sounds,phase,ticks,base;private static RelayRecord current;
    @SubscribeEvent public static void sound(PlaySoundEvent event){if(Boolean.getBoolean("discraft.multiplayerSmoke")&&event.getName().equals("block.note_block.pling"))sounds++;}
    private static RelayRecord record(ActorRef target){String text=target==null?"plain Alice M@Alice":target.label();return new RelayRecord(UUID.randomUUID(),"LOCAL notification fixture",text,List.of(),Map.of(),Map.of(),null,target==null?List.of():List.of(new Mention(0,text.length(),target)));}
    private static void download(RelayRecord r)throws Exception{byte[] b=r.encode();for(int i=0;i<b.length;i+=BridgeNetwork.CHUNK)ClientBridge.download(new BridgeNetwork.Download(r.id(),b.length,i,Arrays.copyOfRange(b,i,Math.min(b.length,i+BridgeNetwork.CHUNK))));}
    private static void next(int n){phase=n;ticks=0;}
    private static void check(boolean ok,String name,Consumer<String> pass){if(!ok)throw new AssertionError(name);pass.accept(name);}
    static boolean run(Consumer<String> pass)throws Exception{
        var mc=Minecraft.getInstance();var local=new ActorRef(ActorRef.Kind.MINECRAFT,mc.player.getUUID().toString(),mc.player.getGameProfile().getName());
        if(phase==0){base=sounds;ClientPreferences.MENTION_SOUND.set(true);ClientBridge.notice(new BridgeNetwork.Notice(BridgeNetwork.HISTORY_END,true,"History end"));current=record(local);download(current);next(1);return false;}
        if(++ticks<8||ClientBridge.snapshot().stream().noneMatch(r->r.id().equals(current.id())))return false;
        switch(phase){
        case 1->{check(sounds==base+1,"Actual decoded structured local UUID mention reaches sound engine once",pass);download(current);next(2);}
        case 2->{check(sounds==base+1,"Duplicate packet delivery does not repeat mention sound",pass);ClientBridge.notice(new BridgeNetwork.Notice(BridgeNetwork.HISTORY_BEGIN,true,"History begin"));current=record(local);download(current);ClientBridge.notice(new BridgeNetwork.Notice(BridgeNetwork.HISTORY_END,true,"History end"));next(3);}
        case 3->{check(sounds==base+1,"Historical assembly stays silent even if replay end precedes asynchronous decode",pass);ClientPreferences.MENTION_SOUND.set(false);current=record(local);download(current);next(4);}
        case 4->{check(sounds==base+1,"Disabled preference mutes actual decoder delivery",pass);ClientPreferences.MENTION_SOUND.set(true);download(current);next(5);}
        case 5->{check(sounds==base+1,"Enabling preference does not re-sound an already received message",pass);current=record(new ActorRef(ActorRef.Kind.MINECRAFT,UUID.randomUUID().toString(),local.display()));download(current);next(6);}
        case 6->{check(sounds==base+1,"Same display name with another UUID stays silent",pass);current=record(null);download(current);next(7);}
        case 7->{check(sounds==base+1,"Plain-name text never produces mention sound",pass);return true;}
        }return false;
    }
}
