package dev.discraft.forge.server;

import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.*;
import java.util.*;

/** Dedicated-server component localization; never loads client I18n, fonts or renderers. */
final class ComponentText {
    private final ResourcePackIndex resources;
    private final String locale;
    ComponentText(ResourcePackIndex resources,String locale){this.resources=resources;this.locale=locale;}
    ItemTooltip.Line tooltipLine(Component component){return ItemTooltip.localize(component,resources,locale);}
    String render(Component component){
        var out=new StringBuilder();var budget=new int[]{512};append(component,out,new IdentityHashMap<>(),budget,0);return out.toString();
    }
    private void append(Component component,StringBuilder out,IdentityHashMap<Component,Boolean> path,int[] budget,int depth){
        if(depth>32||--budget[0]<0||path.put(component,Boolean.TRUE)!=null)throw new IllegalArgumentException("Component complexity exceeded");
        var contents=component.getContents();
        if(contents instanceof LiteralContents literal)add(out,literal.text());
        else if(contents instanceof TranslatableContents translated){
            String fallback=Language.getInstance().getOrDefault(translated.getKey(),Objects.requireNonNullElse(translated.getFallback(),translated.getKey()));
            String template=resources.translation(locale,translated.getKey(),fallback);Object[] args=translated.getArgs();
            var values=new ArrayList<String>();if(args.length>32)throw new IllegalArgumentException("Component argument budget exceeded");
            for(Object arg:args){if(arg instanceof Component nested){var value=new StringBuilder();append(nested,value,path,budget,depth+1);values.add(value.toString());}else if(arg==null||arg instanceof String||arg instanceof Number||arg instanceof Boolean)values.add(String.valueOf(arg));else values.add("?");}
            add(out,format(template,values));
        }else if(contents instanceof KeybindContents key)add(out,key.getName());
        else if(contents!=ComponentContentsEmpty.VALUE)add(out,"[unresolved component]");
        for(Component sibling:component.getSiblings())append(sibling,out,path,budget,depth+1);
        path.remove(component);
    }
    // ComponentContents.EMPTY is not stable across mappings; compare using the empty component's contents.
    private static final class ComponentContentsEmpty {static final net.minecraft.network.chat.ComponentContents VALUE=Component.empty().getContents();}
    private static void add(StringBuilder out,String value){if(value.length()>8192-out.length())throw new IllegalArgumentException("Component text budget exceeded");out.append(value);}
    static String format(String template,List<String> args){
        var out=new StringBuilder();int implicit=0;
        for(int at=0;at<template.length();){char c=template.charAt(at++);if(c!='%'){add(out,String.valueOf(c));continue;}
            int start=at-1;if(at<template.length()&&template.charAt(at)=='%'){add(out,"%");at++;continue;}
            int digits=at;while(at<template.length()&&Character.isDigit(template.charAt(at)))at++;
            int index=implicit;boolean numbered=at>digits;
            if(numbered){if(at>=template.length()||template.charAt(at++)!='$'){add(out,template.substring(start,at));continue;}try{index=Integer.parseInt(template.substring(digits,at-1))-1;}catch(NumberFormatException invalid){index=-1;}}
            if(at<template.length()&&template.charAt(at)=='s'){at++;if(!numbered)implicit++;add(out,index>=0&&index<args.size()?args.get(index):template.substring(start,at));}
            else add(out,template.substring(start,at));
        }
        return out.toString();
    }
}
