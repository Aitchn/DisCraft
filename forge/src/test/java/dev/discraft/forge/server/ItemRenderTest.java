package dev.discraft.forge.server;

import dev.discraft.core.*;
import dev.discraft.forge.bridge.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;

final class ItemRenderTest {
    private static int checks;
    private static void check(boolean value,String label){if(!value)throw new AssertionError(label);checks++;System.out.println("PASS "+label);}
    private static ItemSnapshot item(String id,int model,String font){return new ItemSnapshot(id,"星光劍",font,3,model,0,false,false,-1,false,false);}
    private static int pixel(HeadlessItemRenderer.Result result)throws Exception{return ImageIO.read(new ByteArrayInputStream(result.attachment().bytes())).getRGB(144,92);}
    static void run()throws Exception{
        Path root=Files.createTempDirectory("discraft-item-test");
        try{
            ItemFixtures.create(root);var index=ResourcePackIndex.load(root,List.of("fixture.zip"));var renderer=new HeadlessItemRenderer(index);
            var base=renderer.render(item("minecraft:diamond_sword",0,"minecraft:default"));
            check(base.status().equals("Standard model")&&pixel(base)==0xffff2020,"Generated item model draws its original fixture texture");
            check(pixel(renderer.render(item("minecraft:diamond_sword",1,"minecraft:default")))==0xff20ff20,"CustomModelData threshold selects first override");
            check(pixel(renderer.render(item("minecraft:diamond_sword",2,"minecraft:default")))==0xff2020ff&&pixel(renderer.render(item("minecraft:diamond_sword",3,"minecraft:default")))==0xff2020ff,"Last matching legacy override wins at and above second threshold");
            check(pixel(renderer.render(item("test:inherited",0,"minecraft:default")))==0xffff2020,"Parent inheritance and texture variable chains resolve child override");
            check(pixel(renderer.render(item("test:layers",0,"minecraft:default")))==0xff2020ff,"Generated layers composite in their declared order");
            check(pixel(renderer.render(item("test:animated",0,"minecraft:default")))==0xff20ff20,"Animation uses the first explicitly selected frame rather than an arbitrary sheet crop");
            var resourceImages=new ResourceImages(index);var horizontal=resourceImages.image("test:item/horizontal");var nativeHorizontal=new net.minecraft.client.resources.metadata.animation.AnimationMetadataSection(List.of(),-1,-1,1,false).calculateFrameSize(32,16);
            check(horizontal.getWidth()==nativeHorizontal.width()&&horizontal.getHeight()==nativeHorizontal.height()&&horizontal.getRGB(8,8)==0xff20ff20,"Horizontal animation default frame size matches pinned Minecraft 1.20.1 minimum-axis semantics and selected frame");
            for(String axis:List.of("width","height","both")){int width=axis.equals("height")?-1:8,height=axis.equals("width")?-1:axis.equals("both")?4:8;var nativeSize=new net.minecraft.client.resources.metadata.animation.AnimationMetadataSection(List.of(),width,height,1,false).calculateFrameSize(16,16);var selectedImage=resourceImages.image("test:item/axis_"+axis);
                int expected=axis.equals("width")?0xffffff20:axis.equals("height")?0xff2020ff:0xff20ff20;
                check(selectedImage.getWidth()==nativeSize.width()&&selectedImage.getHeight()==nativeSize.height()&&selectedImage.getRGB(4,selectedImage.getHeight()-1)==expected,"Animation explicit "+axis+" sizing and selected-frame pixels match pinned Minecraft 1.20.1 rather than forcing square frames");}
            var cube=renderer.render(item("minecraft:stone",0,"minecraft:default"));var image=ImageIO.read(new ByteArrayInputStream(cube.attachment().bytes()));var colors=new HashSet<Integer>();for(int y=10;y<170;y++)for(int x=65;x<220;x++){int rgb=image.getRGB(x,y);if((rgb&255)<80&&((rgb>>8)&255)>80&&((rgb>>16)&255)>80)colors.add(rgb);}
            check(cube.status().equals("Standard model")&&colors.size()>=3,"Element cube renders three differently lit visible faces, not a flattened first texture");
            var uv0=ImageIO.read(new ByteArrayInputStream(renderer.render(item("test:uv0",0,"minecraft:default")).attachment().bytes()));var uv90=ImageIO.read(new ByteArrayInputStream(renderer.render(item("test:uv90",0,"minecraft:default")).attachment().bytes()));
            check(uv0.getRGB(105,53)==0xffff2020&&uv90.getRGB(105,53)==0xff2020ff,"Explicit face UV and 90-degree UV rotation preserve distinguishable corner colors");
            for(String id:List.of("test:loader","test:cycle","test:missing","test:texture_cycle","test:predicate"))check(renderer.render(item(id,0,"minecraft:default")).status().equals("Model unavailable / unsupported"),"Missing or unsupported model uses a labelled fallback: "+id);
            var custom=renderer.render(item("minecraft:diamond_sword",1,"test:custom"));image=ImageIO.read(new ByteArrayInputStream(custom.attachment().bytes()));boolean orange=false;for(int y=180;y<203;y++)for(int x=12;x<70;x++)orange|=image.getRGB(x,y)==0xffff8000;
            Path evidence=Path.of("../build/evidence/items");Files.createDirectories(evidence);Files.write(evidence.resolve("bitmap-font-card.png"),custom.attachment().bytes());
            check(orange&&!custom.fontFallback(),"Imported bitmap glyphs render through reference and space providers");
            check(renderer.render(item("minecraft:diamond_sword",0,"test:cycle")).fontFallback()&&renderer.render(item("minecraft:diamond_sword",0,"test:unsupported")).fontFallback(),"Cyclic and unsupported font providers report fallback without hanging");
            var tinted=new ItemSnapshot("minecraft:diamond_sword","Tint","minecraft:default",1,0,0,false,false,0x00ffff,false,false);check((pixel(renderer.render(tinted))&0xff0000)==0,"Whitelisted item tint modulates generated base layer");
            var target=new ActorRef(ActorRef.Kind.DISCORD,"2234567890123456","Same");String content="%item "+target.label()+" %item";int at=content.indexOf(target.label());
            var record=new RelayRecord(UUID.randomUUID(),"Aitchn",content,List.of(),Map.of(),Map.of(),null,List.of(new Mention(at,at+target.label().length(),target)));
            var expanded=ItemExpansion.expand(record,item("minecraft:diamond_sword",0,"minecraft:default"),renderer);
            check(expanded.game().text().equals("[星光劍 ×3] "+target.label()+" [星光劍 ×3]")&&expanded.game().attachments().isEmpty()&&expanded.game().nativeItems().size()==2&&expanded.discord().attachments().size()==1,"Repeated item markers use native inline metadata while sharing one Discord-only image");
            check(expanded.game().mentions().get(0).target().equals(target)&&expanded.discord().mentions().get(0).target().equals(target)&&expanded.discord().text().substring(expanded.discord().mentions().get(0).start(),expanded.discord().mentions().get(0).end()).equals(target.label()),"Expansion remaps native and Discord mention offsets without changing the selected ID");
            check(expanded.discord().text().contains("\\[星光劍 ×3\\]")&&!expanded.game().text().contains("\\["),"Only Discord generated literals receive Markdown escaping");
            var payload=ItemFixtures.sendToLocalDiscord(expanded.discord());check(payload.get("content").getAsString().startsWith("<Aitchn> \\[星光劍 ×3\\]")&&payload.getAsJsonObject("allowed_mentions").getAsJsonArray("users").size()==1,"Expanded text and PNG pass through actual adapter multipart to loopback Discord fixture");
            var hostile=new ItemSnapshot("minecraft:diamond_sword","**name** @everyone :dance:","minecraft:default",1,0,0,false,false,-1,false,false);var literal=ItemExpansion.expand(RelayRecord.draft(UUID.randomUUID(),"%item",null),hostile,renderer).discord();
            check(literal.text().contains("@\u200beveryone")&&literal.text().contains(":\u200bdance:\u200b")&&literal.mentions().isEmpty(),"Item name cannot create a mention, Markdown injection or catalog-alias emoji");
            try{ItemExpansion.expand(RelayRecord.draft(UUID.randomUUID(),"%item",Attachment.fromResource("emoji.png")),tinted,renderer);throw new AssertionError();}catch(IllegalArgumentException expected){check(true,"Uploaded attachment and item image conflict is rejected before sending");}
            var empty=new ItemSnapshot("minecraft:air","Empty hand","minecraft:default",0,0,0,false,false,-1,false,true);var emptyResult=ItemExpansion.expand(RelayRecord.draft(UUID.randomUUID(),"%item",null),empty,renderer);check(emptyResult.game().text().equals("[Empty hand]")&&emptyResult.game().attachments().isEmpty(),"Empty hand expands safely without inventing an item image");
            try{ItemExpansion.expand(RelayRecord.draft(UUID.randomUUID(),"%item ".repeat(9),null),tinted,renderer);throw new AssertionError();}catch(IllegalArgumentException expected){check(true,"More than eight item markers rejected");}
            check(ItemExpansion.requested(RelayRecord.draft(UUID.randomUUID(),"hello %item",null).encode()),"Bounded draft header detects marker without game-thread media decoding");
            var namedMarker=new ActorRef(ActorRef.Kind.DISCORD,"3234567890123456","%item");String mentionLabel=namedMarker.label();var selected=new RelayRecord(UUID.randomUUID(),"Aitchn",mentionLabel,List.of(),Map.of(),Map.of(),null,List.of(new Mention(0,mentionLabel.length(),namedMarker)));
            check(ItemExpansion.expand(selected,tinted,renderer).game()==selected,"Item-like text inside a selected actor label stays literal and creates no image");
            String alias="e1234567890123456";var definition=new Content.CustomEmoji("1234567890123456","fixture",false,"emoji.png");var collisionItem=new ItemSnapshot("minecraft:diamond_sword",":"+alias+":","minecraft:default",1,0,0,false,false,-1,false,false);
            var emojiRecord=new RelayRecord(UUID.randomUUID(),"Aitchn","%item :"+alias+":",List.of(),Map.of(alias,definition),Map.of("emoji.png",Attachment.fromResource("emoji.png")));
            var collision=ItemExpansion.expand(emojiRecord,collisionItem,renderer).game();
            check(collision.text().contains("[:"+alias+": ×1]")&&!collision.catalog().containsKey(alias)&&collision.message().content().stream().filter(c->c instanceof Content.CustomEmoji).count()==1,"Native item-name emoji-alias collision is kept literal while the original selected emoji still renders");
            for(int i=0;i<40;i++)renderer.render(new ItemSnapshot("minecraft:diamond_sword","Cache "+i,"minecraft:default",1,0,0,false,false,-1,false,false));
            check(renderer.cachedItems()<=32&&renderer.cachedBytes()<=4L*1024*1024&&renderer.cachedTexturePixels()<=4L*1024*1024,"Rendered-card and decoded-texture caches remain bounded");
        }finally{try(var paths=Files.list(root)){for(Path p:paths.toList())Files.delete(p);}Files.delete(root);}
        System.out.println("PASS "+checks+" headless item model/font/expansion checks (original local fixtures only)");
    }
}
