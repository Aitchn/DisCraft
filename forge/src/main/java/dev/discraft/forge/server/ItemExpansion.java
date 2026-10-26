package dev.discraft.forge.server;

import dev.discraft.core.*;
import dev.discraft.forge.bridge.*;
import java.io.*;
import java.util.*;

/** Expand server-owned item names after emoji preparation; selected mention identities remain unchanged. */
final class ItemExpansion {
    record Result(RelayRecord game,RelayRecord discord){}
    static boolean requested(byte[] draft)throws IOException{
        try(var in=new DataInputStream(new ByteArrayInputStream(draft))){
            in.readLong();in.readLong();if(!in.readUTF().equals("draft"))throw new IOException("Invalid draft header");
            String text=in.readUTF();Limits.text(text,1800,"bridge text");return text.contains("%item");
        }
    }
    static Result expand(RelayRecord record,ItemSnapshot item,HeadlessItemRenderer renderer)throws Exception{
        if(item==null||positions(record).isEmpty())return new Result(record,record);
        if(!record.attachments().isEmpty())throw new IllegalArgumentException("Item display cannot be combined with an uploaded attachment");
        if(!record.nativeItems().isEmpty())throw new IllegalArgumentException("Item display already expanded");
        List<Attachment> attachments=item.empty()?List.of():List.of(renderer.render(item).attachment());
        record=avoidCatalogCollision(record,item.label());
        var nativeItem=item.nativeItem()!=null?item.nativeItem():new NativeItemData(item.id(),item.count(),"{}");
        return new Result(replace(record,item.label(),List.of(),nativeItem),replace(record,literal(item.label()),attachments,null));
    }
    /** Keep native generated names literal without adding visible escapes or changing the wire format. */
    private static RelayRecord avoidCatalogCollision(RelayRecord record,String label){
        var changes=new LinkedHashMap<String,String>();var catalog=new LinkedHashMap<>(record.catalog());int candidate=0;
        for(String alias:record.catalog().keySet())if(label.contains(":"+alias+":")){
            String replacement;do{if(candidate>1024)throw new IllegalArgumentException("Item alias budget exceeded");replacement="itemLiteral"+candidate++;}while(label.contains(replacement)||record.text().contains(replacement)||catalog.containsKey(replacement));
            catalog.put(replacement,catalog.remove(alias));changes.put(":"+alias+":",":"+replacement+":");
        }
        if(changes.isEmpty())return record;
        var text=new StringBuilder();var mentions=new ArrayList<Mention>();int at=0;
        for(var mention:record.mentions()){text.append(aliases(record.text().substring(at,mention.start()),changes));int start=text.length();text.append(record.text(),mention.start(),mention.end());mentions.add(new Mention(start,text.length(),mention.target()));at=mention.end();}
        text.append(aliases(record.text().substring(at),changes));
        return new RelayRecord(record.id(),record.author(),text.toString(),record.attachments(),catalog,record.emoji(),record.actor(),mentions);
    }
    private static String aliases(String text,Map<String,String> changes){for(var change:changes.entrySet())text=text.replace(change.getKey(),change.getValue());return text;}
    static List<Integer> positions(RelayRecord record){
        var positions=new ArrayList<Integer>();for(int at=0;(at=record.text().indexOf("%item",at))>=0;at+=5){
            final int start=at;if(record.mentions().stream().noneMatch(m->start<m.end()&&start+5>m.start()))positions.add(at);
            if(positions.size()>8)throw new IllegalArgumentException("Too many item markers");
        }return positions;
    }
    private static RelayRecord replace(RelayRecord record,String label,List<Attachment> attachments,NativeItemData nativeItem){
        var items=new ArrayList<ItemInline>();var positions=positions(record);var text=new StringBuilder();int at=0;for(int position:positions){text.append(record.text(),at,position);int start=text.length();text.append(label);if(nativeItem!=null)items.add(new ItemInline(start,text.length(),nativeItem));at=position+5;}text.append(record.text(),at,record.text().length());
        Limits.text(text.toString(),1800,"expanded item text");var mentions=new ArrayList<Mention>();
        for(var mention:record.mentions()){int shift=0;for(int position:positions)if(position<mention.start())shift+=label.length()-5;mentions.add(new Mention(mention.start()+shift,mention.end()+shift,mention.target()));}
        return new RelayRecord(record.id(),record.author(),text.toString(),attachments,record.catalog(),record.emoji(),record.actor(),mentions,items);
    }
    static String literal(String text){
        var out=new StringBuilder();for(int c:text.codePoints().toArray()){
            if(c=='@')out.append("@\u200b");else if(c==':')out.append(":\u200b");else if(!Character.isISOControl(c)){if("\\`*_{}[]()<>#+-.!|~".indexOf(c)>=0)out.append('\\');out.appendCodePoint(c);}
        }return out.toString();
    }
}
