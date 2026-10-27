package dev.discraft.forge.server;

import dev.discraft.forge.bridge.*;
import net.minecraft.network.chat.*;
import java.util.*;

final class NativeMentionDisplayTest {
    static void run(){
        var d=new ActorRef(ActorRef.Kind.DISCORD,"2234567890123456","暱稱 😀");var m=new ActorRef(ActorRef.Kind.MINECRAFT,UUID.randomUUID().toString(),"Player_ID");
        String text="plain D@Typed "+d.label()+" / "+m.label()+" tail";int ds=text.indexOf(d.label()),ms=text.indexOf(m.label());
        var record=new RelayRecord(UUID.randomUUID(),"Sender",text,List.of(),Map.of(),Map.of(),null,List.of(new Mention(ds,ds+d.label().length(),d),new Mention(ms,ms+m.label().length(),m)));
        var shown=NativeFallbackChat.compose(record);String expected="<Sender> plain D@Typed @暱稱 😀 / Player_ID tail";
        if(!shown.getString().equals(expected)||!record.text().equals(text))throw new AssertionError("Selected mention projection changed wire or plain typed text");
        var labels=new ArrayList<String>();shown.visit((style,value)->{if(style.getHoverEvent()!=null&&style.getHoverEvent().getAction()==HoverEvent.Action.SHOW_TEXT){if(style.getColor()==null||style.getColor().getValue()!=0x55ffff||style.getClickEvent()!=null)throw new AssertionError("Mention style leaked actions or lost aqua");labels.add(style.getHoverEvent().getValue(HoverEvent.Action.SHOW_TEXT).getString());}return Optional.empty();},Style.EMPTY);
        if(labels.size()!=2||!labels.get(0).contains(d.id())||!labels.get(1).contains(m.id()))throw new AssertionError("Stable target hover identity missing");
        var first=NativeMentions.name(d);var other=NativeMentions.name(new ActorRef(ActorRef.Kind.DISCORD,"3234567890123456",d.display()));if(first.equals(other)||!first.getString().equals(other.getString()))throw new AssertionError("Duplicate display names lost distinct IDs");
        var hostile=new ActorRef(ActorRef.Kind.DISCORD,"4234567890123456","{clickEvent:/op} @everyone");if(NativeMentions.name(hostile).getStyle().getClickEvent()!=null||!NativeMentions.name(hostile).getString().equals("@"+hostile.display()))throw new AssertionError("Display text executed an action");
        try{NativeMentions.parts(record,ds+1,text.length(),s->{},a->{});throw new AssertionError("Split mention accepted");}catch(IllegalArgumentException expectedFailure){}
        System.out.println("PASS structured D/M mention presentation, aqua, stable hover, duplicate names and safe literal tests");
    }
}
