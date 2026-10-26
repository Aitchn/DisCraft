package dev.discraft.forge.smoke;

import dev.discraft.core.*;
import dev.discraft.forge.*;
import dev.discraft.forge.bridge.*;
import net.minecraft.client.*;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

/** Real client metadata/thumbnail packets, native picker actions and stale-selection regressions. */
final class GuildPickerAcceptance {
    private static final Minecraft MC=Minecraft.getInstance();
    private static Consumer<String> pass;private static GuildEmojiCatalog original;private static Object staticItem;private static int phase,ticks;private static Set<UUID> before;
    static void start(Consumer<String> callback)throws Exception{pass=callback;phase=0;ticks=0;MC.setScreen(new ChatScreen(""));set("category",5);set("guildFilter",0);((EditBox)get("search")).setValue("");NativeInteractions.openPicker();}
    private static void check(boolean value,String label){if(!value)throw new AssertionError(label);pass.accept(label);}
    private static void set(String name,Object value)throws Exception{var field=NativeInteractions.class.getDeclaredField(name);field.setAccessible(true);field.set(null,value);}
    private static Object get(String name)throws Exception{var field=NativeInteractions.class.getDeclaredField(name);field.setAccessible(true);return field.get(null);}
    @SuppressWarnings("unchecked") private static List<Object> items()throws Exception{var method=NativeInteractions.class.getDeclaredMethod("filtered");method.setAccessible(true);return (List<Object>)method.invoke(null);}
    private static String id(Object item)throws Exception{var method=item.getClass().getDeclaredMethod("id");method.setAccessible(true);return (String)method.invoke(item);}
    private static void pick(Object item)throws Exception{var method=NativeInteractions.class.getDeclaredMethod("pick",item.getClass());method.setAccessible(true);method.invoke(null,item);}
    private static EditBox input(){return MC.screen.children().stream().filter(w->w instanceof EditBox).map(w->(EditBox)w).findFirst().orElseThrow();}
    static boolean tick()throws Exception{
        if(phase==0){
            original=ClientGuildEmoji.snapshot();if(!original.ready())return false;
            check(original.fixture()&&original.entries().size()==3,"Dedicated server synchronizes a clearly labelled fixture guild catalog independently of chat history");
            phase=1;return false;
        }
        if(phase==1){
            if(ClientGuildEmoji.preview("1111111111111111")==null||ClientGuildEmoji.preview("2222222222222222")==null)return false;
            check(!ClientGuildEmoji.preview("1111111111111111").media().animated()&&ClientGuildEmoji.preview("2222222222222222").media().animated(),"Lazy static and animated thumbnails traverse real server/client packets and decode");
            if(++ticks<8)return false;try(var image=Screenshot.takeScreenshot(MC.getMainRenderTarget())){image.writeToFile(Path.of("../../build/evidence/multiplayer/Guild-picker-fixture.png"));}
            var list=items();staticItem=list.stream().filter(item->{try{return id(item).equals("1111111111111111");}catch(Exception e){return false;}}).findFirst().orElseThrow();
            var disabled=list.stream().filter(item->{try{return id(item).equals("3333333333333333");}catch(Exception e){return false;}}).findFirst().orElseThrow();String draft=input().getValue();pick(disabled);
            check(input().getValue().equals(draft),"Native picker refuses an unavailable fixture emoji without changing the draft");
            var renamed=new ArrayList<>(original.entries());renamed.set(0,new GuildEmojiCatalog.Entry("1111111111111111","renamed",false,true,true,false));
            ClientGuildEmoji.catalog(new GuildEmojiCatalog(original.version()+1,original.label(),true,true,true,renamed).encode());
            check(ClientGuildEmoji.preview("1111111111111111")!=null,"Catalog rename preserves the thumbnail by stable ID");pick(staticItem);
            check(input().getValue().equals(":renamed:"),"Stale picker hit resolves its stable ID to the latest renamed entry");input().setValue("");
            ClientGuildEmoji.catalog(new GuildEmojiCatalog(original.version()+2,original.label(),true,true,true,original.entries().subList(1,3)).encode());pick(staticItem);
            check(input().getValue().isEmpty()&&ClientGuildEmoji.preview("1111111111111111")==null,"Deleted catalog entry cannot reuse a stale hit or cached thumbnail");
            ClientGuildEmoji.catalog(GuildEmojiCatalog.empty(original.version()+3).encode());pick(staticItem);check(input().getValue().isEmpty(),"Permission-loss catalog invalidation disables stale selection");
            ClientGuildEmoji.reset();check(!ClientGuildEmoji.snapshot().ready()&&ClientGuildEmoji.preview("2222222222222222")==null,"Disconnect/reset clears guild metadata, previews and pending requests");
            ClientGuildEmoji.catalog(original.encode());set("guildFilter",2);NativeInteractions.openPicker();
            var animated=items();check(animated.size()==1&&id(animated.get(0)).equals("2222222222222222"),"Animated guild category filters the synchronized catalog by metadata");
            ((EditBox)get("search")).setValue("dance");check(items().size()==1,"Native search finds the actual catalog emoji name");before=ClientBridge.snapshot().stream().map(Message::id).collect(java.util.stream.Collectors.toSet());pick(items().get(0));
            check(input().getValue().equals(":dance:"),"Native catalog selection inserts a usable fixture emoji into the real composer");
            MC.keyboardHandler.keyPress(MC.getWindow().getWindow(),257,0,1,0);MC.keyboardHandler.keyPress(MC.getWindow().getWindow(),257,0,0,0);phase=2;return false;
        }
        var delivered=ClientBridge.snapshot().stream().filter(m->!before.contains(m.id())&&m.author().equals("Alice")&&Content.plain(m.content()).equals(":dance:")).toList();if(delivered.isEmpty())return false;
        check(delivered.size()==1&&delivered.get(0).content().stream().anyMatch(c->c instanceof Content.CustomEmoji e&&e.animated()),"Picker-selected fixture emoji sends once through authoritative game packets and retains animation");
        set("guildFilter",0);set("category",0);MC.setScreen(new ChatScreen(""));return true;
    }
}
