package dev.discraft.forge.bridge;

import dev.discraft.core.*;
import java.io.*;
import java.util.*;

/** Binary protocol: no token, URLs, client-supplied player identity, or Java serialization. */
public record RelayRecord(UUID id, String author, String text, List<Attachment> attachments,
                          Map<String, Content.CustomEmoji> catalog, Map<String, Attachment> emoji, ActorRef actor,List<Mention> mentions) {
    public RelayRecord(UUID id,String author,String text,List<Attachment> attachments,Map<String,Content.CustomEmoji> catalog,Map<String,Attachment> emoji){this(id,author,text,attachments,catalog,emoji,null,List.of());}
    public static final int MAX_BYTES = 512 * 1024, FILE_BYTES = 256 * 1024;
    public RelayRecord {
        Limits.text(author,80,"author"); Limits.text(text,Limits.TEXT_CODEPOINTS,"text");
        mentions=List.copyOf(mentions);if(mentions.size()>8)throw new IllegalArgumentException("Mention count exceeded");int end=0;for(var mention:mentions){if(mention.start()<end||mention.end()>text.length()||!text.substring(mention.start(),mention.end()).equals(mention.target().label()))throw new IllegalArgumentException("Mention/text mismatch");end=mention.end();}
        attachments=List.copyOf(attachments); catalog=Map.copyOf(catalog); emoji=Map.copyOf(emoji);
        if(attachments.size()>1 || catalog.size()>8 || emoji.size()>8) throw new IllegalArgumentException("Bridge media count exceeded");
        new Message(id,id,Message.Origin.RELAY,author,Content.parse(text,catalog),attachments);
        long pixels=attachments.stream().mapToLong(RelayRecord::pixels).sum()+emoji.values().stream().mapToLong(RelayRecord::pixels).sum();
        if(pixels>Limits.MEDIA_TOTAL_PIXELS) throw new IllegalArgumentException("Bridge pixel budget exceeded");
    }
    private static long pixels(Attachment a){return (long)a.media().width()*a.media().height()*a.media().frames().size();}
    public Message message(){return new Message(id,id,Message.Origin.RELAY,author,Content.parse(text,catalog),attachments);}
    public byte[] encode() throws IOException {
        var bytes=new ByteArrayOutputStream(); var out=new DataOutputStream(bytes);
        out.writeLong(id.getMostSignificantBits());out.writeLong(id.getLeastSignificantBits());out.writeUTF(author);out.writeUTF(text);
        out.writeInt(attachments.size());for(var a:attachments)writeMedia(out,a);
        out.writeInt(catalog.size());for(var e:catalog.entrySet()){
            var c=e.getValue();out.writeUTF(e.getKey());out.writeUTF(c.id());out.writeUTF(c.name());out.writeBoolean(c.animated());out.writeUTF(c.mediaKey());
            writeMedia(out,Objects.requireNonNull(emoji.get(c.mediaKey())));
        }
        out.writeBoolean(actor!=null);if(actor!=null)writeActor(out,actor);out.writeInt(mentions.size());for(var mention:mentions){out.writeInt(mention.start());out.writeInt(mention.end());writeActor(out,mention.target());}
        out.flush();if(bytes.size()>MAX_BYTES)throw new IOException("Bridge encoded budget exceeded");return bytes.toByteArray();
    }
    public static RelayRecord decode(byte[] bytes) throws IOException {
        if(bytes.length>MAX_BYTES)throw new IOException("Bridge encoded budget exceeded");
        try(var in=new DataInputStream(new ByteArrayInputStream(bytes))){
            var id=new UUID(in.readLong(),in.readLong());var author=in.readUTF();var text=in.readUTF();
            long pixels=0;
            int n=count(in,1);var files=new ArrayList<Attachment>();for(int i=0;i<n;i++){var a=readMedia(in);pixels+=pixels(a);if(pixels>Limits.MEDIA_TOTAL_PIXELS)throw new IOException("Message pixel budget exceeded");files.add(a);}
            int e=count(in,8);var catalog=new HashMap<String,Content.CustomEmoji>();var emoji=new HashMap<String,Attachment>();
            for(int i=0;i<e;i++){String alias=in.readUTF();var c=new Content.CustomEmoji(in.readUTF(),in.readUTF(),in.readBoolean(),in.readUTF());var a=readMedia(in);pixels+=pixels(a);if(pixels>Limits.MEDIA_TOTAL_PIXELS)throw new IOException("Message pixel budget exceeded");catalog.put(alias,c);emoji.put(c.mediaKey(),a);}
            ActorRef actor=in.readBoolean()?readActor(in):null;var mentions=new ArrayList<Mention>();int mn=count(in,8);for(int i=0;i<mn;i++)mentions.add(new Mention(in.readInt(),in.readInt(),readActor(in)));
            if(in.available()!=0)throw new IOException("Trailing bridge bytes");
            return new RelayRecord(id,author,text,files,catalog,emoji,actor,mentions);
        }catch(RuntimeException failure){throw new IOException("Invalid bridge message",failure);}
    }
    private static void writeActor(DataOutputStream out,ActorRef actor)throws IOException{out.writeByte(actor.kind().ordinal());out.writeUTF(actor.id());out.writeUTF(actor.display());}
    private static ActorRef readActor(DataInputStream in)throws IOException{int kind=in.readUnsignedByte();if(kind>=ActorRef.Kind.values().length)throw new IOException("Invalid actor kind");return new ActorRef(ActorRef.Kind.values()[kind],in.readUTF(),in.readUTF());}
    private static int count(DataInputStream in,int max)throws IOException{int n=in.readInt();if(n<0||n>max)throw new IOException("Invalid count");return n;}
    private static void writeMedia(DataOutputStream out,Attachment a)throws IOException{byte[] b=a.bytes();if(b.length>FILE_BYTES)throw new IOException("Bridge file limit 256 KiB");out.writeUTF(a.name());out.writeInt(b.length);out.write(b);}
    private static Attachment readMedia(DataInputStream in)throws IOException{String n=in.readUTF();int size=count(in,FILE_BYTES);byte[] b=in.readNBytes(size);if(b.length!=size)throw new EOFException();return Attachment.of(n,b);}
    public static RelayRecord draft(UUID id,String text,Attachment file){return new RelayRecord(id,"draft",text,file==null?List.of():List.of(file),Map.of(),Map.of());}
}
