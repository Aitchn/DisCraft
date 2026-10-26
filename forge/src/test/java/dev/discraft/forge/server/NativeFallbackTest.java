package dev.discraft.forge.server;

import dev.discraft.core.*;
import dev.discraft.forge.bridge.*;
import net.minecraft.network.chat.*;
import java.util.*;

final class NativeFallbackTest {
    private static void check(boolean value,String label){if(!value)throw new AssertionError(label);System.out.println("PASS "+label);}
    static void run()throws Exception{
        var plain=Component.literal("private-original");String originalShape=NativeChatProjection.shape(plain);
        check(originalShape.equals(NativeChatProjection.shape(plain.copy().withStyle(net.minecraft.ChatFormatting.GOLD,net.minecraft.ChatFormatting.BOLD))),"Native projection permits style-only changes without changing visible content");
        check(!originalShape.equals(NativeChatProjection.shape(Component.literal("[redacted]"))),"Native projection rejects a moderator replacement instead of forwarding original text");
        try{NativeChatProjection.shape(Component.literal("private-original").withStyle(net.minecraft.ChatFormatting.OBFUSCATED));throw new AssertionError("Missing obfuscation guard");}catch(IllegalArgumentException expected){check(true,"Obfuscated text cannot disclose its hidden literal through Discord");}
        var cycle=Component.empty();cycle.append(cycle);try{NativeChatProjection.shape(cycle);throw new AssertionError("Missing cycle guard");}catch(IllegalArgumentException expected){check(true,"Native presentation comparison rejects cycles before serialization");}
        String body="{\"clickEvent\":{\"action\":\"run_command\",\"value\":\"/op x\"}} :Fixture:";
        var actor=new ActorRef(ActorRef.Kind.DISCORD,"2234567890123456","[Alias] <@everyone>");
        var record=new RelayRecord(UUID.randomUUID(),"ignored",body,List.of(Attachment.fromResource("landscape.png")),Map.of("Fixture",new Content.CustomEmoji("1111111111111111","Fixture",false,"fixture.png")),Map.of(),actor,List.of());
        var component=NativeFallbackChat.compose(record);boolean[] safe={true};component.visit((style,text)->{if(style.getClickEvent()!=null)safe[0]=false;return Optional.<Object>empty();},Style.EMPTY);
        check(component.getString().startsWith("[D] "+actor.display()+": {\"clickEvent\"")&&safe[0],"No-client author/body remain literal native components without executable actions");
        check(component.getString().endsWith(":Fixture: [media requires DisCraft client]")&&!component.getString().contains("1111111111111111")&&!component.getString().contains("landscape.png"),"No-client custom emoji/media downgrade hides wire IDs, file names and raw media metadata");
        var empty=new NativeItemData("minecraft:air",0,"{}");var source=Component.literal("one %item ").withStyle(net.minecraft.ChatFormatting.GOLD).append(Component.literal("two %item"));
        var replaced=NativeItemComponents.replaceMarkers(source,empty,"[Empty hand]");
        check(!NativeChatProjection.shape(replaced).isEmpty(),"Native item replacement supports bounded empty wrapper components");
        check(replaced.getString().equals("one [Empty hand] two [Empty hand]")&&source.getString().contains("%item")&&replaced.getStyle().equals(source.getStyle()),"Native item marker replacement preserves source/style and bounded inline empty-hand fallback");
        try{NativeItemComponents.replaceMarkers(Component.literal("%item".repeat(9)),empty,"empty");throw new AssertionError("Missing marker cap");}catch(IllegalArgumentException expected){check(true,"Native no-client item marker count remains bounded");}
        try{NativeItemComponents.replaceMarkers(Component.literal("x".repeat(8193)),empty,"empty");throw new AssertionError("Missing text cap");}catch(IllegalArgumentException expected){check(true,"Native no-client item input text remains bounded");}
    }
}
