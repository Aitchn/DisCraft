package dev.discraft.forge.server;

import dev.discraft.core.*;
import dev.discraft.forge.bridge.*;
import java.util.*;

/** Explicit deterministic test peer, exercised through real game packets, never labelled Discord. */
final class TestDiscordAdapter implements DiscordAdapter {
    private final Deque<RelayRecord> incoming=new ArrayDeque<>();
    private Attachment oversizedPreview;
    public TestDiscordAdapter()throws Exception{
        var emoji=new HashMap<String,Attachment>();for(var c:MockTransport.EMOJI.values())emoji.put(c.mediaKey(),Attachment.fromResource(c.mediaKey()));
        incoming.add(new RelayRecord(UUID.randomUUID(),"TEST peer (not Discord)","Server peer 世界 👋 :discraft: :dance:",List.of(Attachment.fromResource("dance.gif")),MockTransport.EMOJI,emoji,new ActorRef(ActorRef.Kind.DISCORD,"7777777777777777","測試成員"),List.of()));
    }
    @Override public GuildEmojiCatalog catalog(){return new GuildEmojiCatalog(1,"TEST fixture (not Discord)",true,true,true,List.of(
        new GuildEmojiCatalog.Entry("1111111111111111","discraft",false,true,true,false),
        new GuildEmojiCatalog.Entry("2222222222222222","dance",true,true,true,false),
        new GuildEmojiCatalog.Entry("3333333333333333","unavailable_fixture",false,false,true,false),
        new GuildEmojiCatalog.Entry("4444444444444444","oversized_preview_fixture",false,true,true,false)));}
    @Override public Map<String,Attachment> thumbnails(List<String> ids){
        var result=new HashMap<String,Attachment>();for(String id:ids)try{if(id.equals("1111111111111111"))result.put(id,Attachment.fromResource("emoji.png"));else if(id.equals("2222222222222222"))result.put(id,Attachment.fromResource("dance.gif"));
            else if(id.equals("4444444444444444")){
                // Deliberately malformed upstream fixture, exactly one pixel above the client limit.
                // Normal production thumbnails pass through EmojiThumbnailCache's stricter bounds.
                if(oversizedPreview==null){var bytes=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(481,545,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",bytes);oversizedPreview=Attachment.of("oversized-preview-fixture.png",bytes.toByteArray());}
                result.put(id,oversizedPreview);
            }}catch(Exception ignored){}return Map.copyOf(result);
    }
    // Explicit upstream-error fixture, reachable only through the smoke-gated TEST mode.
    public void send(RelayRecord m){if(m.text().equals("fixture:reject-send"))throw new IllegalStateException("TEST adapter rejection fixture");incoming.add(new RelayRecord(UUID.randomUUID(),"TEST peer (not Discord)","Peer received: "+m.text(),m.attachments(),m.catalog(),m.emoji(),new ActorRef(ActorRef.Kind.DISCORD,"7777777777777777","測試成員"),m.mentions().stream().map(x->new Mention(x.start()+15,x.end()+15,x.target())).toList()));}
    public List<RelayRecord> poll(){var result=List.copyOf(incoming);incoming.clear();return result;}
}
