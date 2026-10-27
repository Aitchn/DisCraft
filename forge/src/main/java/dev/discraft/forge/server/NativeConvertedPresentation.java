package dev.discraft.forge.server;

import net.minecraft.network.chat.*;
import net.minecraft.network.chat.contents.*;

/** A preserved, actually visible component identity, never a string/author-name association. */
final class NativeConvertedPresentation {
    private NativeConvertedPresentation(){}
    static String shape(Component view,Component origin){
        String snapshot=NativeChatProjection.shape(view);if(count(view,origin,new int[]{512},0)!=1)throw new IllegalArgumentException("Unsupported converted presentation");return snapshot;
    }
    private static int count(Component node,Component origin,int[] budget,int depth){
        if(depth>32||--budget[0]<0)throw new IllegalArgumentException("Converted presentation bounds");
        if(node==origin)return 1;
        int found=0;var contents=node.getContents();
        if(contents instanceof TranslatableContents tr){
            // Only the pinned vanilla chat decoration has a known visible argument contract.
            if(!tr.getKey().equals("chat.type.text")||tr.getFallback()!=null||tr.getArgs().length!=2)throw new IllegalArgumentException("Unknown converted translation");
            for(Object arg:tr.getArgs())if(arg instanceof Component child)found+=count(child,origin,budget,depth+1);else if(!(arg instanceof String))throw new IllegalArgumentException("Unknown decoration argument");
        }else if(!(contents instanceof LiteralContents)&&contents!=Component.empty().getContents())throw new IllegalArgumentException("Unknown converted content");
        for(var child:node.getSiblings())found+=count(child,origin,budget,depth+1);return found;
    }
}
