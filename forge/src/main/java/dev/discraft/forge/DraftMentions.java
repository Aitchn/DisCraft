package dev.discraft.forge;

import dev.discraft.forge.bridge.*;
import net.minecraft.client.gui.components.EditBox;
import java.util.*;

/** Native editing adapters provide actual ranges; unknown changes invalidate selections. */
final class DraftMentions {
    private static final MentionDraft draft=new MentionDraft();
    private record Edit(int start,int end) {}
    private static Edit pending;
    @SuppressWarnings("unchecked") static void install(EditBox input){
        pending=null;
        try{var field=net.minecraftforge.fml.util.ObfuscationReflectionHelper.findField(EditBox.class,"f_94089_");var previousResponder=(java.util.function.Consumer<String>)field.get(input);input.setResponder(text->{Edit edit=pending;pending=null;if(edit==null)draft.synchronize(text);else draft.edit(text,edit.start(),edit.end());if(previousResponder!=null)previousResponder.accept(text);});draft.synchronize(input.getValue());}
        catch(IllegalAccessException unavailable){throw new IllegalStateException("Native input responder unavailable",unavailable);}
    }
    static void endEdit(){pending=null;}
    static void beforeEdit(EditBox input){beforeEdit(input,0,false);}
    static void beforeEdit(EditBox input,int direction,boolean word){
        endEdit();draft.synchronize(input.getValue());
        try{int cursor=input.getCursorPosition(),highlight=net.minecraftforge.fml.util.ObfuscationReflectionHelper.findField(EditBox.class,"f_94102_").getInt(input);int a=Math.min(cursor,highlight),b=Math.max(cursor,highlight);
            if(a==b&&direction!=0){int other=word?input.getWordPosition(direction):input.getValue().offsetByCodePoints(cursor,direction<0&&cursor>0?-1:direction>0&&cursor<input.getValue().length()?1:0);a=Math.min(cursor,other);b=Math.max(cursor,other);}
            draft.invalidate(a,b);pending=new Edit(a,b);
        }catch(IllegalAccessException unavailable){throw new IllegalStateException("Native input selection unavailable",unavailable);}
    }
    static void clear(){pending=null;draft.clear();}
    static void insert(EditBox input,ActorRef actor){
        beforeEdit(input);int start=pending.start();String label=actor.label();
        try{input.insertText(label+" ");draft.select(start,actor);}finally{endEdit();}
    }
    static List<Mention> snapshot(String text){return draft.snapshot(text);}
}
