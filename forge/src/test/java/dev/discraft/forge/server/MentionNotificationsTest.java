package dev.discraft.forge.server;

import dev.discraft.forge.MentionNotifications;
import dev.discraft.forge.bridge.*;
import java.util.*;

final class MentionNotificationsTest {
    static void run(){
        UUID local=UUID.randomUUID();var target=new ActorRef(ActorRef.Kind.MINECRAFT,local.toString(),"Same");
        var other=new ActorRef(ActorRef.Kind.MINECRAFT,UUID.randomUUID().toString(),"Same");
        var discord=new ActorRef(ActorRef.Kind.DISCORD,"1234567890123456","Same");
        var guard=new MentionNotifications();int checks=0;
        for(var actor:List.of(target,other,discord)){
            var record=record(actor);boolean expected=actor==target;
            if(guard.accept(record,local,true)!=expected)throw new AssertionError("Stable Minecraft UUID only");checks++;
            if(guard.accept(record,local,true))throw new AssertionError("Duplicate delivery sounded");checks++;
        }
        var plain=new RelayRecord(UUID.randomUUID(),"Same","Same M@Same",List.of(),Map.of(),Map.of());
        if(guard.accept(plain,local,true))throw new AssertionError("Plain text matched");checks++;
        var muted=record(target);if(guard.accept(muted,local,false)||guard.accept(muted,local,true))throw new AssertionError("Muted delivery replayed after enable");checks++;
        if(guard.accept(record(target),null,true))throw new AssertionError("No local player");checks++;
        var once=record(target);if(!guard.accept(once,local,true)||guard.accept(once,local,true))throw new AssertionError("New/repeated delivery");checks++;
        guard.reset();if(!guard.accept(once,local,true))throw new AssertionError("New session reset");checks++;
        System.out.println("PASS "+checks+" structured UUID mention notification checks");
    }
    private static RelayRecord record(ActorRef actor){String t=actor.label();return new RelayRecord(UUID.randomUUID(),"Sender",t,List.of(),Map.of(),Map.of(),null,List.of(new Mention(0,t.length(),actor)));}
}
