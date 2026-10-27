package dev.discraft.forge.bridge;

import dev.discraft.core.Limits;
import java.io.*;
import java.util.*;

/** Server-owned metadata only: no URLs, credentials, member lists or role IDs cross this boundary. */
public record GuildEmojiCatalog(long version,String label,boolean fixture,boolean ready,boolean complete,List<Entry> entries) {
    public static final int MAX_ENTRIES=512,MAX_BYTES=48*1024,THUMB_BATCH=4;
    public static final int THUMB_IMAGES=64,THUMB_BYTES=2*1024*1024,THUMB_PIXELS=2*1024*1024,THUMB_SINGLE_PIXELS=256*1024;
    public static final long CLIENT_TTL_NANOS=120_000_000_000L;
    public record Entry(String id,String name,boolean animated,boolean available,boolean permitted,boolean restricted){
        public Entry{if(id==null||!id.matches("[0-9]{15,22}")||name==null||!name.matches("[A-Za-z0-9_]{1,32}"))throw new IllegalArgumentException("Invalid guild emoji metadata");}
        public boolean usable(){return available&&permitted;}
        public String markup(){return "<"+(animated?"a":"")+":"+name+":"+id+">";}
    }
    public GuildEmojiCatalog{
        if(version<0||entries.size()>MAX_ENTRIES)throw new IllegalArgumentException("Catalog bounds");
        Limits.text(label,80,"catalog label");entries=List.copyOf(entries);var ids=new HashSet<String>();
        for(var e:entries)if(!ids.add(e.id()))throw new IllegalArgumentException("Duplicate emoji identity");
        if(!ready&&!entries.isEmpty())throw new IllegalArgumentException("Unavailable catalog contains entries");
    }
    public static GuildEmojiCatalog empty(long version){return new GuildEmojiCatalog(version,"Guild emoji unavailable",false,false,true,List.of());}
    public Entry find(String id){return entries.stream().filter(e->e.id().equals(id)).findFirst().orElse(null);}
    public byte[] encode()throws IOException{
        var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);out.writeLong(version);out.writeUTF(label);out.writeBoolean(fixture);out.writeBoolean(ready);out.writeBoolean(complete);out.writeInt(entries.size());
        for(var e:entries){out.writeUTF(e.id());out.writeUTF(e.name());out.writeBoolean(e.animated());out.writeBoolean(e.available());out.writeBoolean(e.permitted());out.writeBoolean(e.restricted());}
        out.flush();if(bytes.size()>MAX_BYTES)throw new IOException("Catalog wire budget");return bytes.toByteArray();
    }
    public static GuildEmojiCatalog decode(byte[] bytes)throws IOException{
        if(bytes.length>MAX_BYTES)throw new IOException("Catalog wire budget");
        try(var in=new DataInputStream(new ByteArrayInputStream(bytes))){
            long version=in.readLong();String label=in.readUTF();boolean fixture=in.readBoolean(),ready=in.readBoolean(),complete=in.readBoolean();int count=in.readInt();if(count<0||count>MAX_ENTRIES)throw new IOException("Catalog entry budget");
            var entries=new ArrayList<Entry>();for(int i=0;i<count;i++)entries.add(new Entry(in.readUTF(),in.readUTF(),in.readBoolean(),in.readBoolean(),in.readBoolean(),in.readBoolean()));
            if(in.available()!=0)throw new IOException("Trailing catalog bytes");return new GuildEmojiCatalog(version,label,fixture,ready,complete,entries);
        }catch(RuntimeException rejected){throw new IOException("Invalid catalog metadata");}
    }
}
