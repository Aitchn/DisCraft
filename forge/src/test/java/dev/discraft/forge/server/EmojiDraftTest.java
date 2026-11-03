package dev.discraft.forge.server;

import dev.discraft.forge.bridge.*;
import java.util.*;

/** Chat-input emoji shortcodes: recognition, ambiguity, mention spans and the exact Discord wire markup. */
final class EmojiDraftTest {
    private static int checks;
    private static void check(boolean value,String label){if(!value)throw new AssertionError(label);checks++;System.out.println("PASS "+label);}
    static void run(){
        var smile=new GuildEmojiCatalog.Entry("1111111111111111","smile",false,true,true,false);
        var dance=new GuildEmojiCatalog.Entry("2222222222222222","dance",true,true,true,false);
        var twinA=new GuildEmojiCatalog.Entry("3333333333333333","twin",false,true,true,false);var twinB=new GuildEmojiCatalog.Entry("4444444444444444","Twin",false,true,true,false);
        var off=new GuildEmojiCatalog.Entry("5555555555555555","off",false,false,true,false);var locked=new GuildEmojiCatalog.Entry("6666666666666666","locked",false,true,false,true);
        var once=new GuildEmojiCatalog.Entry("7777777777777777","solo",false,true,true,false);var oldTwin=new GuildEmojiCatalog.Entry("8888888888888888","solo",false,false,true,false);
        var catalog=new GuildEmojiCatalog(1,"Discord guild emoji",false,true,true,List.of(smile,dance,twinA,twinB,off,locked,once,oldTwin));
        check(EmojiDraft.insertion(catalog,smile).equals(":smile:")&&EmojiDraft.insertion(catalog,dance).equals(":dance:"),"Picker inserts a short :name: for an unambiguous emoji");
        check(EmojiDraft.insertion(catalog,twinA).equals("<:twin:3333333333333333>")&&EmojiDraft.insertion(catalog,twinB).equals("<:Twin:4444444444444444>"),"Picker inserts the exact markup when two usable emoji share a name");
        check(EmojiDraft.insertion(catalog,once).equals(":solo:"),"A disabled same-name emoji does not make a usable one ambiguous");
        var spans=EmojiDraft.spans("hi :smile: and <a:dance:2222222222222222> x",catalog,List.of());
        check(spans.size()==2&&spans.get(0).shortcode()&&spans.get(0).entry().equals(smile)&&!spans.get(1).shortcode()&&spans.get(1).entry().equals(dance),"Complete shortcodes and raw Discord markup are both recognised as emoji");
        check(EmojiDraft.spans(":smile",catalog,List.of()).isEmpty()&&EmojiDraft.spans("smile:",catalog,List.of()).isEmpty()&&EmojiDraft.spans(":sm ile:",catalog,List.of()).isEmpty(),"Incomplete or malformed shortcodes stay literal text");
        check(EmojiDraft.spans(":unknown: :off: :locked: :twin:",catalog,List.of()).isEmpty(),"Unknown, disabled, restricted and ambiguous names are never converted");
        check(EmojiDraft.spans(":SMILE:",catalog,List.of()).size()==1,"Shortcode lookup is case-insensitive like Discord");
        check(EmojiDraft.spans("a:smile:b:dance:",catalog,List.of()).size()==2,"Adjacent colons still recognise each complete token");
        check(EmojiDraft.spans(":x:smile:",catalog,List.of()).size()==1&&EmojiDraft.spans(":x:smile:",catalog,List.of()).get(0).start()==2,"A failed token does not swallow the following valid one");
        check(EmojiDraft.spans("<:smile:9999999999999999>",catalog,List.of()).isEmpty(),"Raw markup with an unknown ID is not an emoji");
        check(EmojiDraft.spans(":smile:",GuildEmojiCatalog.empty(1),List.of()).isEmpty(),"No emoji are recognised while the catalog is unavailable");
        var expanded=EmojiDraft.expand("hi :smile: <a:dance:2222222222222222> :dance: :nope:",List.of(),catalog);
        check(expanded.text().equals("hi <:smile:1111111111111111> <a:dance:2222222222222222> <a:dance:2222222222222222> :nope:")&&expanded.emoji()==2&&expanded.distinct()==2,"Send converts shortcodes to Discord markup, keeps existing markup and unknown text");
        var actor=new ActorRef(ActorRef.Kind.DISCORD,"9234567890123456","Zed");String label=actor.label();
        String draft=":smile: "+label+" :dance: "+label;var mentions=List.of(new Mention(8,8+label.length(),actor),new Mention(draft.lastIndexOf(label),draft.length(),actor));
        var moved=EmojiDraft.expand(draft,mentions,catalog);boolean aligned=true;for(var mention:moved.mentions())aligned&=moved.text().substring(mention.start(),mention.end()).equals(label);
        check(aligned&&moved.mentions().size()==2&&moved.emoji()==2,"Mention spans stay exactly aligned after shortcodes lengthen the text");
        var inside=List.of(new Mention(0,label.length(),actor));check(EmojiDraft.expand(label+" :smile:",inside,catalog).text().equals(label+" <:smile:1111111111111111>")&&EmojiDraft.spans(label,catalog,inside).isEmpty(),"A selected mention is never reinterpreted as an emoji");
        var fixture=new GuildEmojiCatalog(1,"TEST fixture",true,true,true,List.of(smile));
        check(EmojiDraft.expand(":smile:",List.of(),fixture).text().equals(":smile:")&&EmojiDraft.insertion(fixture,smile).equals(":smile:"),"The labelled test fixture keeps plain shortcodes for its server adapter");
        System.out.println("PASS: "+checks+" emoji input checks");
    }
}
