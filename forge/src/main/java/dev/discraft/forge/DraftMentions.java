package dev.discraft.forge;

import dev.discraft.forge.bridge.*;
import net.minecraft.client.gui.components.EditBox;
import java.util.*;

/** Explicit selections survive edits outside their span; typed lookalike text never becomes a mention. */
final class DraftMentions {
    private static String previous="";
    private static final List<Mention> tokens=new ArrayList<>();
    @SuppressWarnings("unchecked") static void install(EditBox input){
        try{var field=net.minecraftforge.fml.util.ObfuscationReflectionHelper.findField(EditBox.class,"f_94089_");var previousResponder=(java.util.function.Consumer<String>)field.get(input);input.setResponder(text->{sync(text);if(previousResponder!=null)previousResponder.accept(text);});sync(input.getValue());}
        catch(IllegalAccessException unavailable){throw new IllegalStateException("Native input responder unavailable",unavailable);}
    }
    static void beforeEdit(EditBox input){
        try{int cursor=input.getCursorPosition(),highlight=net.minecraftforge.fml.util.ObfuscationReflectionHelper.findField(EditBox.class,"f_94102_").getInt(input);int a=Math.min(cursor,highlight),b=Math.max(cursor,highlight);tokens.removeIf(t->a<b?t.start()<b&&t.end()>a:a>t.start()&&a<t.end());}
        catch(IllegalAccessException unavailable){throw new IllegalStateException("Native input selection unavailable",unavailable);}
    }
    static void clear(){previous="";tokens.clear();}
    static void sync(String text){
        if(text.equals(previous))return;int left=0;while(left<text.length()&&left<previous.length()&&text.charAt(left)==previous.charAt(left))left++;
        int right=0;while(right<text.length()-left&&right<previous.length()-left&&text.charAt(text.length()-1-right)==previous.charAt(previous.length()-1-right))right++;
        int oldEnd=previous.length()-right,delta=text.length()-previous.length();var updated=new ArrayList<Mention>();
        for(var t:tokens){if(oldEnd<=t.start())updated.add(new Mention(t.start()+delta,t.end()+delta,t.target()));else if(left>=t.end())updated.add(t);}
        tokens.clear();tokens.addAll(updated);previous=text;
    }
    static void insert(EditBox input,ActorRef actor){
        sync(input.getValue());String label=actor.label();input.insertText(label+" ");sync(input.getValue());
        int start=input.getCursorPosition()-label.length()-1,end=start+label.length();
        if(tokens.size()<8&&start>=0&&end<=previous.length()&&previous.substring(start,end).equals(label)){
            tokens.removeIf(t->t.start()<end&&t.end()>start);tokens.add(new Mention(start,end,actor));tokens.sort(Comparator.comparingInt(Mention::start));
        }
    }
    static List<Mention> snapshot(String text){sync(text);return List.copyOf(tokens);}
}
