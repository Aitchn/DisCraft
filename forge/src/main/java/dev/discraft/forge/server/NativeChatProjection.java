package dev.discraft.forge.server;

import net.minecraft.network.chat.*;
import net.minecraft.network.chat.contents.*;
import java.util.*;

/** Bounded content-only snapshot. Unknown/dynamic or obfuscated presentations fail closed. */
final class NativeChatProjection {
    private NativeChatProjection(){}
    static String shape(Component component){var output=new StringBuilder();append(component,output,new IdentityHashMap<>(),new int[]{512},0);return output.toString();}
    private static void add(StringBuilder output,String value){if(value.length()>16384-output.length())throw new IllegalArgumentException("Native presentation budget exceeded");output.append(value);}
    private static void value(StringBuilder output,String value){add(output,Integer.toString(value.length()));add(output,":");add(output,value);}
    private static void append(Component component,StringBuilder output,IdentityHashMap<Component,Boolean> path,int[] nodes,int depth){
        if(depth>32||--nodes[0]<0||path.put(component,Boolean.TRUE)!=null||component.getStyle().isObfuscated())throw new IllegalArgumentException("Unsupported native presentation");
        var contents=component.getContents();
        if(contents instanceof LiteralContents literal){add(output,"L");value(output,literal.text());}
        else if(contents instanceof TranslatableContents tr){add(output,"T");value(output,tr.getKey());add(output,tr.getFallback()==null?"N":"F");if(tr.getFallback()!=null)value(output,tr.getFallback());Object[] args=tr.getArgs();if(args.length>32)throw new IllegalArgumentException("Native argument budget exceeded");add(output,args.length+"[");for(Object arg:args){if(arg instanceof Component nested){add(output,"C");append(nested,output,path,nodes,depth+1);}else if(arg==null||arg instanceof String||arg instanceof Number||arg instanceof Boolean){add(output,"V");value(output,String.valueOf(arg));}else throw new IllegalArgumentException("Unsupported native argument");}add(output,"]");}
        else if(contents==Component.empty().getContents())add(output,"E");
        else throw new IllegalArgumentException("Unsupported native content");
        add(output,"[");for(var sibling:component.getSiblings())append(sibling,output,path,nodes,depth+1);add(output,"]");path.remove(component);
    }
}
