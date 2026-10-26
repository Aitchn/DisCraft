package dev.discraft.forge.bridge;

import java.util.*;

/** Mention identity follows an explicit replacement range, never a guessed text diff. */
public final class MentionDraft {
    private String text="";
    private final List<Mention> tokens=new ArrayList<>();
    public void clear(){text="";tokens.clear();}
    public void synchronize(String value){if(!text.equals(value)){tokens.clear();text=value;}}
    public void invalidate(int start,int end){tokens.removeIf(t->start<end?t.start()<end&&t.end()>start:start>t.start()&&start<t.end());}
    public void edit(String next,int start,int end){
        int inserted=next.length()-text.length()+end-start;
        if(start<0||end<start||end>text.length()||inserted<0||start+inserted>next.length()||!next.substring(0,start).equals(text.substring(0,start))||!next.substring(start+inserted).equals(text.substring(end))){tokens.clear();text=next;return;}
        int delta=next.length()-text.length();var updated=new ArrayList<Mention>();
        for(var t:tokens){if(t.end()<=start)updated.add(t);else if(t.start()>=end)updated.add(new Mention(t.start()+delta,t.end()+delta,t.target()));}
        tokens.clear();tokens.addAll(updated);text=next;
    }
    public void select(int start,ActorRef actor){
        int end=start+actor.label().length();if(start<0||end>text.length()||!text.substring(start,end).equals(actor.label()))return;
        tokens.removeIf(t->t.start()<end&&t.end()>start);if(tokens.size()<8){tokens.add(new Mention(start,end,actor));tokens.sort(Comparator.comparingInt(Mention::start));}
    }
    /** Deterministic 1.20.1 trim/normalizeSpace/256-char transform, verified against the actual event. */
    public static List<Mention> normalized(String raw,List<Mention> selected,String actual){
        String full=org.apache.commons.lang3.StringUtils.normalizeSpace(raw.trim());String expected=full.substring(0,Math.min(256,full.length()));if(!expected.equals(actual))return List.of();
        var result=new ArrayList<Mention>();for(var token:selected){
            if(token.end()>raw.length())continue;
            int start=org.apache.commons.lang3.StringUtils.normalizeSpace(raw.substring(0,token.start())+"\ue000").length()-1;
            int end=org.apache.commons.lang3.StringUtils.normalizeSpace(raw.substring(0,token.end())+"\ue000").length()-1;
            if(start>=0&&end>start&&end<=actual.length()&&actual.substring(start,end).equals(token.target().label()))result.add(new Mention(start,end,token.target()));
        }return List.copyOf(result);
    }
    public List<Mention> snapshot(String value){synchronize(value);return List.copyOf(tokens);}
}
