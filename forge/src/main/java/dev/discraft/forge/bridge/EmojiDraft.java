package dev.discraft.forge.bridge;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Chat-input emoji that behave like Discord's: the picker (or the player) writes a short {@code :name:} shortcode, a
 * correctly formed token is recognised as an emoji as soon as it is complete, and what is actually sent is Discord's own
 * {@code <:name:id>} / {@code <a:name:id>} markup. Identity always comes from the server-owned guild catalog, never
 * from the text: an unknown, ambiguous, disabled or role-restricted name simply stays literal text.
 */
public final class EmojiDraft {
    private EmojiDraft(){}
    private static final Pattern RAW=Pattern.compile("<(a?):([A-Za-z0-9_]{1,32}):([0-9]{15,22})>"),SHORT=Pattern.compile(":([A-Za-z0-9_]{1,32}):");
    /** A recognised emoji token; {@code shortcode} is false for Discord's raw {@code <:name:id>} form. */
    public record Span(int start,int end,GuildEmojiCatalog.Entry entry,boolean shortcode) {}
    public record Expansion(String text,List<Mention> mentions,int emoji,int distinct) {}

    /** What the picker inserts: the shortcode when exactly one usable emoji has that name, otherwise the unambiguous markup. */
    public static String insertion(GuildEmojiCatalog catalog,GuildEmojiCatalog.Entry entry){
        return catalog.fixture()||unique(catalog,entry.name())!=null&&unique(catalog,entry.name()).id().equals(entry.id())?":"+entry.name()+":":entry.markup();
    }
    private static GuildEmojiCatalog.Entry unique(GuildEmojiCatalog catalog,String name){
        GuildEmojiCatalog.Entry found=null;
        for(var entry:catalog.entries())if(entry.usable()&&entry.name().equalsIgnoreCase(name)){if(found!=null)return null;found=entry;}
        return found;
    }
    private static boolean overlaps(int start,int end,List<Mention> mentions){for(var mention:mentions)if(start<mention.end()&&end>mention.start())return true;return false;}

    /** Recognised emoji in draft order. Tokens overlapping a selected mention are never emoji. */
    public static List<Span> spans(String text,GuildEmojiCatalog catalog,List<Mention> mentions){
        if(text.isEmpty()||!catalog.ready())return List.of();
        var result=new ArrayList<Span>();var matcher=RAW.matcher(text);var reserved=new ArrayList<int[]>(); // every raw-looking token, valid or not, owns its inner :name:
        while(matcher.find()){reserved.add(new int[]{matcher.start(),matcher.end()});
            var entry=catalog.find(matcher.group(3));
            if(entry!=null&&entry.usable()&&!overlaps(matcher.start(),matcher.end(),mentions))result.add(new Span(matcher.start(),matcher.end(),entry,false));
        }
        var short_=SHORT.matcher(text);int from=0;
        while(from<text.length()&&short_.find(from)){
            int start=short_.start(),end=short_.end();var entry=unique(catalog,short_.group(1));
            boolean taken=entry==null||overlaps(start,end,mentions);
            for(var range:reserved)if(start<range[1]&&end>range[0]){taken=true;break;}
            if(taken){from=start+1;continue;}
            result.add(new Span(start,end,entry,true));from=end;
        }
        result.sort(Comparator.comparingInt(Span::start));return List.copyOf(result);
    }
    public static boolean contains(String text,GuildEmojiCatalog catalog,List<Mention> mentions){return !spans(text,catalog,mentions).isEmpty();}

    /** Wire text: every shortcode becomes real Discord markup; selected mention spans keep exact positions. */
    public static Expansion expand(String text,List<Mention> mentions,GuildEmojiCatalog catalog){
        // A fixture catalog is a clearly labelled test guild whose server adapter resolves :name: itself.
        if(catalog.fixture())return new Expansion(text,mentions,0,0);
        var spans=spans(text,catalog,mentions);var out=new StringBuilder();var ids=new HashSet<String>();int at=0,count=0;
        var moved=new ArrayList<Mention>();var shifts=new ArrayList<int[]>(); // {position in original text, cumulative delta after it}
        int delta=0;
        for(var span:spans){
            if(!span.shortcode())continue;
            out.append(text,at,span.start());String markup=span.entry().markup();out.append(markup);at=span.end();
            delta+=markup.length()-(span.end()-span.start());shifts.add(new int[]{span.end(),delta});count++;ids.add(span.entry().id());
        }
        out.append(text.substring(at));
        for(var span:spans)if(!span.shortcode())ids.add(span.entry().id());
        for(var mention:mentions){int shift=0;for(var s:shifts)if(s[0]<=mention.start())shift=s[1];moved.add(new Mention(mention.start()+shift,mention.end()+shift,mention.target()));}
        return new Expansion(out.toString(),List.copyOf(moved),count,ids.size());
    }
}
