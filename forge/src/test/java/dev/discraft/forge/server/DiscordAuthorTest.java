package dev.discraft.forge.server;

import dev.discraft.forge.bridge.*;
import net.minecraft.network.chat.*;
import java.util.*;

final class DiscordAuthorTest {
    private static int checks;
    private static void check(boolean value,String text){if(!value)throw new AssertionError(text);checks++;System.out.println("PASS "+text);}
    @SuppressWarnings("unchecked") private static void expire(DiscordBotAdapter adapter)throws Exception{
        var f=DiscordBotAdapter.class.getDeclaredField("memberNames");f.setAccessible(true);var cache=(Map<String,Object>)f.get(adapter);for(var entry:cache.entrySet()){Object old=entry.getValue();var type=old.getClass();var constructor=type.getDeclaredConstructor(String.class,String.class,String.class,long.class);constructor.setAccessible(true);var nick=type.getDeclaredMethod("nick");var global=type.getDeclaredMethod("global");var username=type.getDeclaredMethod("username");nick.setAccessible(true);global.setAccessible(true);username.setAccessible(true);entry.setValue(constructor.newInstance(nick.invoke(old),global.invoke(old),username.invoke(old),Long.MIN_VALUE));}
    }
    static void run()throws Exception{
        for(String display:List.of("Aitchn","小雨","[x] \"name\" <@all>")){
            var actor=new ActorRef(ActorRef.Kind.MINECRAFT,"00000000-0000-0000-0000-000000000001",display);var header=NativeAuthorHeader.create("ignored",actor);
            check(header.component().getString().equals("<"+display+"> ")&&header.authorClick().getValue().equals("M@"+display+" "),"Minecraft enhanced header shows player display only while retaining M@ insertion: "+display);
            boolean[] name={false},punctuation={true},bodySafe={false};String body="plain 😀 %i {\"clickEvent\":\"run_command\"}";
            var message=Component.empty().append(header.component()).append(Component.literal(body));message.visit((style,value)->{if(value.equals(display))name[0]=style.getClickEvent()==header.authorClick()&&style.getHoverEvent().getValue(HoverEvent.Action.SHOW_TEXT).getString().contains(actor.id());else if(value.equals("<")||value.equals("> "))punctuation[0]&=style.getClickEvent()==null&&style.getHoverEvent()==null;else if(value.equals(body))bodySafe[0]=style.getClickEvent()==null&&style.getHoverEvent()==null;return Optional.<Object>empty();},Style.EMPTY);
            check(name[0]&&punctuation[0]&&bodySafe[0],"Only player name owns the stable click/hover; punctuation and emoji/item-like literal body stay inert");
            var fallback=NativeFallbackChat.compose(new RelayRecord(UUID.randomUUID(),"ignored",body,List.of(),Map.of(),Map.of(),actor,List.of()));
            check(fallback.getString().equals(message.getString()),"Enhanced and absent-client headers share the exact player chat appearance");
        }
        check(NativeAuthorHeader.create("fallback",null).component().getString().equals("<fallback> "),"Missing actor uses a literal player-style fallback without fabricated identity");
        try(var peer=new DiscordAuthorFixtures();var adapter=peer.adapter()){
            check(adapter.poll().isEmpty(),"Display-name lookup still skips old channel history");var records=adapter.poll();check(records.size()==4,"Production polling converts four distinct server-bound Discord authors");
            for(int i=0;i<4;i++){var record=records.get(i);check(record.actor().display().equals(DiscordAuthorFixtures.EXPECTED.get(i))&&record.actor().id().equals(DiscordAuthorFixtures.USERS.get(i)),"Guild nickname/global/username/special-character display with stable ID: "+i);var header=NativeAuthorHeader.create(record.author(),record.actor());check(header.component().getString().equals("[D] "+record.actor().display()+": ")&&header.authorClick().getAction()==ClickEvent.Action.SUGGEST_COMMAND&&header.authorClick().getValue().equals(record.actor().label()+" "),"Exact native [D] display retains D@ mention insertion: "+i);}
            check(peer.memberRequests.get()==4,"One bounded lookup per distinct incoming author");peer.nickname(DiscordAuthorFixtures.USERS.get(0),"Renamed Guild User");peer.advance();check(adapter.poll().get(0).actor().display().equals("HoverTester")&&peer.memberRequests.get()==4,"Member display cache avoids repeated fetch before TTL");expire(adapter);peer.advance();check(adapter.poll().get(0).actor().display().equals("Renamed Guild User")&&peer.memberRequests.get()==8,"Expired member display refreshes a guild rename");
            var actor=new ActorRef(ActorRef.Kind.DISCORD,DiscordAuthorFixtures.USERS.get(3),"[x] \"name\" <@all>");String body="{\"clickEvent\":{\"action\":\"run_command\",\"value\":\"/op x\"}}";var header=NativeAuthorHeader.create("ignored",actor);var component=Component.empty().append(header.component()).append(Component.literal(body));
            final boolean[] bodySafe={false};component.visit((style,text)->{if(text.equals(body))bodySafe[0]=style.getClickEvent()==null&&style.getHoverEvent()==null;return Optional.<Object>empty();},Style.EMPTY);check(component.getString().equals("[D] "+actor.display()+": "+body)&&bodySafe[0],"Display names and message JSON-like text remain literal without body click/hover injection");check(peer.posts.get()==0,"Display/formatter verification sends no Discord messages, even to its loopback fixture");
        }
        try(var peer=new DiscordAuthorFixtures();var adapter=peer.adapter()){peer.mismatched();adapter.poll();var records=adapter.poll();check(records.get(0).actor().display().equals("Global Alias")&&!records.get(0).actor().display().equals("HoverTester"),"Mismatched guild member identity falls back without reattributing the user");peer.advance();adapter.poll();check(peer.memberRequests.get()==4,"Failed member lookups are negatively cached");}
        try(var peer=new DiscordAuthorFixtures();var adapter=peer.adapter()){peer.slow();adapter.poll();long started=System.nanoTime();var records=adapter.poll();check(System.nanoTime()-started<3_000_000_000L&&records.size()==4&&peer.memberRequests.get()==1,"Slow guild-member reads share a two-second page budget and retain safe user fallbacks");}
        System.out.println("PASS "+checks+" Discord member display/native header checks (loopback only)");
    }
}
