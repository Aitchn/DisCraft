package dev.discraft.forge.smoke;

import dev.discraft.core.*;
import dev.discraft.forge.*;
import dev.discraft.forge.bridge.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.*;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Real dedicated server + two real clients. Test adapter is server-owned and explicitly labelled. */
@Mod.EventBusSubscriber(modid=DisCraft.ID,value=Dist.CLIENT)
public final class MultiplayerAcceptance {
    private static final Minecraft MC=Minecraft.getInstance();
    private static final String ROLE=System.getProperty("discraft.bridgeRole","Alice");
    private static final Path OUT=Path.of("../../build/evidence/multiplayer").toAbsolutePath().normalize();
    private static final List<String> checks=new ArrayList<>();
    private static final Set<Integer> frames=new HashSet<>();
    private static final UUID REPLAY=UUID.randomUUID();
    private static CompletableFuture<String> request;
    private static final List<CompletableFuture<String>> flooded=new ArrayList<>();
    private static int stage,ticks,historyBeforeClose,nativeTicks,nativeFirstFrame;
    private static DisCraftScreen previous;
    private static final long deadline=System.nanoTime()+240_000_000_000L;
    private static boolean done;
    private static long lastDiagnostic;
    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event){
        if(Boolean.getBoolean("discraft.optionalClientSmoke")||Boolean.getBoolean("discraft.discordAuthorSmoke")||Boolean.getBoolean("discraft.uiTextSmoke")||Boolean.getBoolean("discraft.vanillaItemsSmoke")||Boolean.getBoolean("discraft.nativeHoverSmoke")||!Boolean.getBoolean("discraft.multiplayerSmoke")||done||event.phase!=TickEvent.Phase.END)return;
        try{
            if(System.nanoTime()-lastDiagnostic>5_000_000_000L){
                lastDiagnostic=System.nanoTime();
                System.out.println("MULTIPLAYER "+ROLE+" stage="+stage+" nativePhase="+NativeChatRegression.phase()+NativeUxAcceptance.state()+SpoilerPrivacyAcceptance.state()+" screen="+(MC.screen==null?"none":MC.screen.getClass().getSimpleName())+" player="+(MC.player!=null)+" overlay="+(MC.getOverlay()!=null)+" bridge="+ClientBridge.status()+" available="+ClientBridge.available()+" messages="+ClientBridge.snapshot().size());
            }
            if(System.nanoTime()>deadline)throw new AssertionError("Timeout "+ROLE+" stage="+stage+" status="+ClientBridge.status());
            if(++ticks%2!=0)return;
            if(stage==0){
                Files.createDirectories(OUT);
                if(MC.screen!=null&&MC.screen.getClass().getSimpleName().equals("AccessibilityOnboardingScreen")){MC.screen.children().stream().filter(w->w instanceof Button b&&b.getMessage().getString().equals("Continue")).map(w->(Button)w).findFirst().ifPresent(Button::onPress);return;}
                if(!(MC.screen instanceof TitleScreen)||MC.getOverlay()!=null)return;
                MC.options.guiScale().set(1);MC.options.renderDistance().set(2);MC.resizeDisplay();connect();stage=1;return;
            }
            if(stage==1||stage==12){
                // Login packets can enable the bridge before vanilla finishes its terrain screen.
                // Opening our screen then lets vanilla replace it, stranding the stage-2 gate.
                if(MC.player==null||MC.level==null||!ClientBridge.available()||MC.screen!=null||MC.getOverlay()!=null)return;
                check(true,(stage==1?"Initial":"Reconnect")+" vanilla join presentation completed before opening bridge");
                MC.setScreen(new DisCraftScreen(null));stage=stage==1?2:13;return;
            }
            if(stage==11){if(MC.getConnection()!=null)return;check(ClientBridge.snapshot().isEmpty(),"Disconnect clears old server history");connect();stage=12;return;}
            if(stage>=20){nativeChat();return;}
            if(!(MC.screen instanceof DisCraftScreen screen)||(Boolean)field(screen,"busy"))return;
            var messages=ClientBridge.snapshot();
            if(ROLE.equals("Bob")){bob(screen,messages);return;}
            switch(stage){
                case 2 -> {
                    if(messages.isEmpty())return;

                    var first=messages.get(0);if(first.attachments().isEmpty())return;
                    Object entry=((Map<?,?>)field(field(screen,"textures"),"entries")).get(first.attachments().get(0).digest());if(entry==null)return;
                    var get=entry.getClass().getDeclaredMethod("frame");get.setAccessible(true);frames.add((Integer)get.invoke(entry));
                    if(frames.size()==1)capture("Alice-inbound-frame0.png");if(frames.size()<2)return;
                    capture("Alice-inbound-frame1.png");
                    check(ClientBridge.status().contains("SERVER TEST"),"Server explicitly identifies test transport");
                    check(messages.stream().anyMatch(m->m.author().equals("TEST peer (not Discord)")),"Dedicated-server inbound peer traversed actual packets");
                    check(true,"Actual network GIF rendered multiple Minecraft texture frames "+frames);
                    check(ClientBridge.emoji("emoji.png")!=null&&ClientBridge.emoji("dance.gif")!=null,"Static and animated custom emoji arrived over server packets");
                    check(((Map<?,?>)field(field(screen,"textures"),"entries")).containsKey(ClientBridge.emoji("emoji.png").digest()),"Network static custom emoji uploaded to Minecraft texture cache");
                    edit(screen,"text").setValue("alice-one 世界 :discraft: :dance:");click("Demo GIF");stage=3;
                }
                case 3 -> {click("Send");click("Send");edit(screen,"text").setValue("Draft while awaiting network ACK");stage=4;}
                case 4 -> {
                    if(count(messages,"alice-one")<1)return;
                    check(count(messages,"alice-one")==1,"Repeated Send produced one authoritative player message");
                    check(edit(screen,"text").getValue().equals("Draft while awaiting network ACK"),"Network ACK preserves newly typed draft");
                    check(messages.stream().anyMatch(m->m.author().equals("Alice")&&Content.plain(m.content()).startsWith("alice-one")&&m.attachments().get(0).media().animated()),"Server derives sender identity and validates uploaded GIF");
                    Files.writeString(OUT.resolve("Alice-sent"),"ready");stage=5;
                }
                case 5 -> {
                    if(messages.stream().noneMatch(m->m.author().equals("Bob")&&Content.plain(m.content()).startsWith("bob-two")))return;
                    check(messages.stream().anyMatch(m->m.author().equals("Bob")&&Content.plain(m.content()).equals("bob-two 世界 👋")),"Second client PNG and Unicode arrived via dedicated server");capture("Alice-two-clients.png");
                    request=raw(new RelayRecord(UUID.randomUUID(),"Administrator","forged-identity",List.of(),Map.of(),Map.of()));stage=6;
                }
                case 6 -> {
                    if(!request.isDone())return;check(request.isCompletedExceptionally(),"Server rejected client-supplied author spoof");
                    check(messages.stream().noneMatch(m->m.author().equals("Administrator")),"Spoofed identity never entered transcript");
                    request=raw(RelayRecord.draft(REPLAY,"nonce-once",null));stage=7;
                }
                case 7 -> {
                    if(!request.isDone()||count(messages,"nonce-once")<1)return;check(!request.isCompletedExceptionally(),"Valid raw draft accepted through real packets");
                    check(messages.stream().filter(m->m.author().equals("Alice")&&Content.plain(m.content()).equals("nonce-once")).allMatch(m->!m.id().equals(REPLAY)),"Server generates canonical event IDs");
                    request=raw(RelayRecord.draft(REPLAY,"nonce-replayed",null));stage=8;
                }
                case 8 -> {
                    if(!request.isDone())return;check(request.isCompletedExceptionally()&&count(messages,"nonce-replayed")==0,"Replay rejected before transport delivery");
                    UUID bad=UUID.randomUUID();request=watch(bad);BridgeNetwork.CHANNEL.sendToServer(new BridgeNetwork.Upload(bad,16,1,new byte[]{1}));stage=15;
                }
                case 15 -> {
                    if(!request.isDone())return;check(request.isCompletedExceptionally(),"Server rejects out-of-order upload chunks");
                    UUID bad=UUID.randomUUID();request=watch(bad);BridgeNetwork.CHANNEL.sendToServer(new BridgeNetwork.Upload(bad,RelayRecord.MAX_BYTES+1,0,new byte[]{1}));stage=16;
                }
                case 16 -> {
                    if(!request.isDone())return;check(request.isCompletedExceptionally(),"Server rejects oversized upload header before allocation");
                    for(int i=0;i<12;i++)flooded.add(raw(new RelayRecord(UUID.randomUUID(),"Imposter","invalid-rate-probe",List.of(),Map.of(),Map.of())));stage=17;
                }
                case 17 -> {
                    if(flooded.stream().anyMatch(f->!f.isDone()))return;
                    check(flooded.stream().anyMatch(f->{try{f.join();return false;}catch(Exception denied){return denied.toString().contains("rate limited");}}),"Server rate limits repeated real-network requests");
                    historyBeforeClose=messages.size();previous=screen;screen.onClose();MC.setScreen(new DisCraftScreen(null));stage=9;
                }
                case 9 -> {
                    check(screen!=previous&&messages.size()>=historyBeforeClose,"Close/reopen retains server transcript without local mock replacement");
                    MC.level.disconnect();MC.clearLevel();MC.setScreen(new TitleScreen());stage=11;
                }
                case 13 -> {
                    if(count(messages,"nonce-once")<1)return;check(count(messages,"nonce-once")==1,"Reconnect resynchronizes authoritative history once");
                    request=raw(RelayRecord.draft(REPLAY,"reconnect-replay",null));stage=14;
                }
                case 14 -> {
                    if(!request.isDone())return;check(request.isCompletedExceptionally()&&count(messages,"reconnect-replay")==0,"Server replay window survives client reconnect");
                    capture("Alice-reconnected.png");screen.onClose();MC.options.guiScale().set(2);MC.resizeDisplay();NativeComposer.openBridge();MC.gui.getChat().scrollChat(8);stage=20;
                }
            }
        }catch(Throwable failure){NativeChatRegression.cleanup();NativeUxAcceptance.cleanup();done=true;try{Files.createDirectories(OUT);capture(ROLE+"-failure.png");Files.writeString(OUT.resolve(ROLE+"-result.txt"),String.join("\n",checks)+"\nFAIL stage "+stage+": "+failure+"\n");}catch(Exception ignored){}failure.printStackTrace();MC.stop();}
    }
    private static void bob(DisCraftScreen screen,List<Message> messages)throws Exception{
        switch(stage){
            case 2 -> {
                if(messages.stream().noneMatch(m->m.author().equals("Alice")&&Content.plain(m.content()).startsWith("alice-one")))return;
                check(true,"Bob received Alice server-authoritative message");
                check(messages.stream().filter(m->m.author().equals("Alice")).anyMatch(m->!m.attachments().isEmpty()&&m.attachments().get(0).media().animated()),"Bob decoded GIF received over actual game networking");
                var uploads=MC.gameDirectory.toPath().resolve("discraft-uploads");try(var in=Attachment.class.getResourceAsStream("/fixtures/landscape.png")){Files.copy(in,uploads.resolve("landscape.png"),StandardCopyOption.REPLACE_EXISTING);}
                edit(screen,"path").setValue("landscape.png");edit(screen,"text").setValue("bob-two 世界 👋");click("Attach file");stage=3;
            }
            case 3 -> {click("Send");stage=4;}
            case 4 -> {
                if(messages.stream().noneMatch(m->m.author().equals("TEST peer (not Discord)")&&Content.plain(m.content()).startsWith("Peer received: bob-two")))return;
                check(true,"Bob received distinct server-side peer reply to PNG upload");capture("Bob-roundtrip.png");stage=5;
            }
            case 5 -> {if(Files.exists(OUT.resolve("Alice-done")))finish();}
        }
    }
    private static void nativeChat()throws Exception{
        if(stage==20){
            if(++nativeTicks<30)return;
            check(MC.screen.getClass()==ChatScreen.class,"Native vanilla ChatScreen presents authoritative bridge messages");
            check(NativeChat.renderedTiles>0,"Native chat area renders inline emoji/media cells");nativeFirstFrame=nativeFrame();check(nativeFirstFrame>=0,"Actual native chat GIF texture is visible while scrolled");capture("Native-chat-GIF-emoji-frame0.png");stage=21;nativeTicks=0;return;
        }
        if(stage==21){
            if(++nativeTicks<4||nativeFrame()==nativeFirstFrame)return;check(true,"Native chat GIF uploaded a different real animation frame");capture("Native-chat-GIF-emoji-frame1.png");
            var uploads=MC.gameDirectory.toPath().resolve("discraft-uploads");Files.createDirectories(uploads);
            try(var in=Attachment.class.getResourceAsStream("/fixtures/landscape.png")){Files.copy(in,uploads.resolve("landscape.png"),StandardCopyOption.REPLACE_EXISTING);}
            var attach=NativeComposer.class.getDeclaredMethod("attach",String.class);attach.setAccessible(true);attach.invoke(null,"landscape.png");stage=22;return;
        }
        if(stage==22){
            var selected=NativeComposer.class.getDeclaredField("attachment");selected.setAccessible(true);if(selected.get(null)==null)return;
            MC.gui.getChat().resetChatScroll();var input=MC.screen.children().stream().filter(w->w instanceof EditBox).map(w->(EditBox)w).findFirst().orElseThrow();input.setValue("native T 世界 :discraft: :dance:");MC.screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER,0,0);stage=23;return;
        }
        if(stage==23){
            if(ClientBridge.snapshot().stream().noneMatch(m->m.author().equals("Alice")&&Content.plain(m.content()).startsWith("native T")&&!m.attachments().isEmpty()))return;
            check(true,"Vanilla ChatScreen Enter sends PNG and emoji through authoritative server packets");MC.setScreen(new ChatScreen(""));stage=24;nativeTicks=0;return;
        }
        if(stage==24){
            if(++nativeTicks<30)return;capture("Native-chat-PNG-send.png");
            MC.screen.children().stream().filter(w->w instanceof EditBox).map(w->(EditBox)w).findFirst().orElseThrow().setValue("vanilla-only smoke");MC.screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER,0,0);stage=25;return;
        }
        if(stage==25){
            if(!nativeText("vanilla-only smoke"))return;
            check(ClientBridge.snapshot().stream().noneMatch(m->Content.plain(m.content()).contains("vanilla-only smoke")),"Ordinary native chat still uses vanilla server chat without bridge duplication");
            NativeComposer.openBridge();MC.screen.children().stream().filter(w->w instanceof EditBox).map(w->(EditBox)w).findFirst().orElseThrow().setValue("/list");MC.screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER,0,0);stage=26;return;
        }
        if(stage==26){if(!nativeText("players online"))return;check(true,"Slash command remains a real vanilla server command while bridge target is selected");NativeChatRegression.start(label->check(true,label));stage=40;}
        if(stage==40&&NativeChatRegression.tick()){NativeComposer.openBridge();var attach=NativeComposer.class.getDeclaredMethod("attach",String.class);attach.setAccessible(true);attach.invoke(null,"landscape.png");stage=41;nativeTicks=0;}
        if(stage==41){var f=NativeComposer.class.getDeclaredField("attachment");f.setAccessible(true);if(f.get(null)==null)return;NativeInteractions.openPicker();stage=42;nativeTicks=0;}
        if(stage==42){if(++nativeTicks<12)return;capture("Native-Discord-picker-card.png");var close=NativeInteractions.class.getDeclaredMethod("closePicker");close.setAccessible(true);close.invoke(null);var toggle=NativeComposer.class.getDeclaredMethod("toggleSpoiler");toggle.setAccessible(true);toggle.invoke(null);stage=43;nativeTicks=0;}
        if(stage==43){if(++nativeTicks<6)return;capture("Native-spoiler-preview.png");var input=MC.screen.children().stream().filter(w->w instanceof EditBox).map(w->(EditBox)w).findFirst().orElseThrow();input.setValue("spoiler-native demo");MC.keyboardHandler.keyPress(MC.getWindow().getWindow(),257,0,1,0);MC.keyboardHandler.keyPress(MC.getWindow().getWindow(),257,0,0,0);stage=44;}
        if(stage==44){if(ClientBridge.snapshot().stream().noneMatch(m->m.author().equals("Alice")&&Content.plain(m.content()).equals("spoiler-native demo")&&!m.attachments().isEmpty()&&m.attachments().get(0).name().startsWith("SPOILER_")))return;check(true,"Native spoiler flag survives authoritative attachment transport");MC.setScreen(new ChatScreen(""));MC.gui.getChat().resetChatScroll();stage=45;nativeTicks=0;}
        if(stage==45){if(++nativeTicks<12)return;capture("Native-incoming-spoiler-hidden.png");NativeUxAcceptance.start(label->check(true,label));stage=50;}
        if(stage==50&&NativeUxAcceptance.tick()){GuildPickerAcceptance.start(label->check(true,label));stage=55;}
        if(stage==55&&GuildPickerAcceptance.tick()){SpoilerPrivacyAcceptance.start(label->check(true,label));stage=60;}
        if(stage==60&&SpoilerPrivacyAcceptance.tick())finish();
    }
    private static int nativeFrame()throws Exception{
        var f=NativeChat.class.getDeclaredField("textures");f.setAccessible(true);var entries=(Map<?,?>)field(f.get(null),"entries");var e=entries.get(ClientBridge.emoji("dance.gif").digest());if(e==null)return -1;var get=e.getClass().getDeclaredMethod("frame");get.setAccessible(true);return (Integer)get.invoke(e);
    }
    @SuppressWarnings("unchecked") private static boolean nativeText(String value)throws Exception{
        var f=net.minecraftforge.fml.util.ObfuscationReflectionHelper.findField(net.minecraft.client.gui.components.ChatComponent.class,"f_93760_");return ((List<net.minecraft.client.GuiMessage>)f.get(MC.gui.getChat())).stream().anyMatch(m->m.content().getString().contains(value));
    }
    private static int count(List<Message> messages,String start){return (int)messages.stream().filter(m->m.author().equals("Alice")&&Content.plain(m.content()).startsWith(start)).count();}
    @SuppressWarnings("unchecked") static CompletableFuture<String> raw(RelayRecord record)throws Exception{
        byte[] b=record.encode();var future=watch(record.id());
        for(int at=0;at<b.length;at+=BridgeNetwork.CHUNK)BridgeNetwork.CHANNEL.sendToServer(new BridgeNetwork.Upload(record.id(),b.length,at,Arrays.copyOfRange(b,at,Math.min(b.length,at+BridgeNetwork.CHUNK))));return future;
    }
    @SuppressWarnings("unchecked") private static CompletableFuture<String> watch(UUID id)throws Exception {
        var future=new CompletableFuture<String>();var field=ClientBridge.class.getDeclaredField("pending");field.setAccessible(true);((Map<UUID,CompletableFuture<String>>)field.get(null)).put(id,future);return future;
    }
    private static void connect(){String address="127.0.0.1:"+System.getProperty("discraft.bridgePort","25579");ConnectScreen.startConnecting(new TitleScreen(),MC,ServerAddress.parseString(address),new ServerData("DisCraft loopback acceptance",address,false),false);}
    private static Object field(Object o,String n)throws Exception{var f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(o);}
    private static EditBox edit(Object screen,String n)throws Exception{return (EditBox)field(screen,n);}
    private static void click(String name){MC.screen.children().stream().filter(w->w instanceof Button b&&b.getMessage().getString().equals(name)).map(w->(Button)w).findFirst().orElseThrow().onPress();}
    private static void capture(String name)throws Exception{try(var image=Screenshot.takeScreenshot(MC.getMainRenderTarget())){image.writeToFile(OUT.resolve(name));}}
    private static void check(boolean value,String label){if(!value)throw new AssertionError(label);checks.add("PASS "+label);System.out.println("MULTIPLAYER "+ROLE+" PASS "+label);}
    private static void finish()throws Exception{Files.writeString(OUT.resolve(ROLE+"-result.txt"),String.join("\n",checks)+"\nPASS "+checks.size()+" checks\n");Files.writeString(OUT.resolve(ROLE+"-done"),"done");done=true;MC.stop();}
}
