package dev.discraft.forge.server;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import javax.imageio.ImageIO;
import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.util.concurrent.atomic.AtomicReference;
import dev.discraft.forge.bridge.RelayRecord;

/** Original synthetic textures/models/font sheets; neither game assets nor third-party renderer code. */
final class ItemFixtures {
    static JsonObject sendToLocalDiscord(RelayRecord record)throws Exception{return sendToLocalDiscord(record,null);}
    static JsonObject sendToLocalDiscord(RelayRecord record,Map<String,Long> timing)throws Exception{
        var post=new AtomicReference<byte[]>();var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);String channel="1234567890123456";
        server.createContext("/",exchange->{try{
            String path=exchange.getRequestURI().getPath(),response;
            if(!"Bot discraft-local-test-token".equals(exchange.getRequestHeaders().getFirst("Authorization")))throw new IOException("Missing fixture authorization");
            if(exchange.getRequestMethod().equals("POST")){byte[] body=exchange.getRequestBody().readNBytes(512*1024+1);if(body.length>512*1024||!post.compareAndSet(null,body))throw new IOException("Invalid or repeated fixture send");response="{\"id\":\"4234567890123456\"}";}
            else if(path.endsWith("users/@me"))response="{\"id\":\"2234567890123456\",\"bot\":true}";
            else if(path.endsWith("channels/"+channel))response="{\"id\":\""+channel+"\",\"type\":0,\"guild_id\":\"3234567890123456\"}";
            else if(path.contains("/members/"))response="{\"roles\":[]}";
            else if(path.endsWith("/emojis"))response="[]";else throw new IOException("Unexpected fixture route");
            byte[] bytes=json(response);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
        }finally{exchange.close();}});server.start();
        try(var adapter=new DiscordBotAdapter(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/api/v10/"),channel,"discraft-local-test-token",true)){
            long started=System.nanoTime();var prepared=adapter.prepare(record);if(timing!=null)timing.put("prepare_ns",System.nanoTime()-started);started=System.nanoTime();String receipt=adapter.sendWithReceipt(prepared);if(timing!=null)timing.put("upload_ns",System.nanoTime()-started);if(!receipt.equals("4234567890123456"))throw new AssertionError("Wrong fixture receipt");
            if(timing!=null){post.set(null);var warm=new RelayRecord(UUID.randomUUID(),record.author(),record.text(),record.attachments(),record.catalog(),record.emoji(),record.actor(),record.mentions(),record.nativeItems());started=System.nanoTime();var warmPrepared=adapter.prepare(warm);timing.put("warm_prepare_ns",System.nanoTime()-started);started=System.nanoTime();adapter.sendWithReceipt(warmPrepared);timing.put("warm_upload_ns",System.nanoTime()-started);}
            String body=new String(Objects.requireNonNull(post.get()),StandardCharsets.UTF_8);int start=body.indexOf("\r\n\r\n")+4,end=body.indexOf("\r\n--",start);String payload=body.startsWith("--")?body.substring(start,end):body;
            var parsed=JsonParser.parseString(payload).getAsJsonObject();if(!record.attachments().isEmpty()&&!body.contains("filename=\"item.png\""))throw new AssertionError("Missing rendered PNG multipart");return parsed;
        }finally{server.stop(0);}
    }
    private static byte[] json(String value){return value.getBytes(StandardCharsets.UTF_8);}
    private static byte[] png(BufferedImage image)throws Exception{var out=new ByteArrayOutputStream();ImageIO.write(image,"png",out);return out.toByteArray();}
    private static BufferedImage texture(int color){var image=new BufferedImage(16,16,BufferedImage.TYPE_INT_ARGB);for(int y=2;y<15;y++)for(int x=6;x<10;x++)image.setRGB(x,y,color);for(int y=10;y<12;y++)for(int x=3;x<13;x++)image.setRGB(x,y,color);return image;}
    private static String generated(String texture){return "{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"test:item/"+texture+"\"}}";}
    static Path create(Path directory)throws Exception{
        Files.createDirectories(directory);var entries=new LinkedHashMap<String,byte[]>();
        entries.put("pack.mcmeta",json("{\"pack\":{\"pack_format\":15,\"description\":\"Original DisCraft local item fixtures\"}}"));
        entries.put("assets/minecraft/lang/zh_tw.json",json("{\"item.minecraft.diamond_sword\":\"測試鑽石劍\"}"));
        entries.put("assets/minecraft/models/item/diamond_sword.json",json("{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"test:item/red\"},\"overrides\":[{\"predicate\":{\"custom_model_data\":1},\"model\":\"test:item/variant1\"},{\"predicate\":{\"custom_model_data\":2},\"model\":\"test:item/variant2\"}]}"));
        entries.put("assets/test/models/item/variant1.json",json(generated("green")));entries.put("assets/test/models/item/variant2.json",json(generated("blue")));
        entries.put("assets/test/models/item/inherited.json",json("{\"parent\":\"test:item/variant1\",\"textures\":{\"layer0\":\"#base\",\"base\":\"test:item/red\"}}"));
        entries.put("assets/test/models/item/layers.json",json("{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"test:item/red\",\"layer1\":\"test:item/blue\"}}"));
        entries.put("assets/minecraft/models/item/stone.json",json("{\"parent\":\"minecraft:block/cube_all\",\"textures\":{\"all\":\"test:block/cube\"}}"));
        entries.put("assets/test/models/item/animated.json",json(generated("animated")));
        for(int rotation:new int[]{0,90})entries.put("assets/test/models/item/uv"+rotation+".json",json("{\"gui_light\":\"front\",\"textures\":{\"face\":\"test:item/quadrants\"},\"elements\":[{\"from\":[0,0,8],\"to\":[16,16,8],\"faces\":{\"south\":{\"texture\":\"#face\",\"uv\":[0,0,16,16],\"rotation\":"+rotation+"}}}]}"));
        entries.put("assets/test/models/item/loader.json",json("{\"loader\":\"test:custom\"}"));
        entries.put("assets/test/models/item/cycle.json",json("{\"parent\":\"test:item/cycle\"}"));
        entries.put("assets/test/models/item/missing.json",json(generated("absent")));
        entries.put("assets/test/models/item/texture_cycle.json",json("{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"#a\",\"a\":\"#layer0\"}}"));
        entries.put("assets/test/models/item/predicate.json",json("{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"test:item/red\"},\"overrides\":[{\"predicate\":{\"test:custom_callback\":1},\"model\":\"test:item/variant1\"}]}"));
        entries.put("assets/test/textures/item/red.png",png(texture(0xffff2020)));entries.put("assets/test/textures/item/green.png",png(texture(0xff20ff20)));entries.put("assets/test/textures/item/blue.png",png(texture(0xff2020ff)));
        var quadrants=new BufferedImage(16,16,BufferedImage.TYPE_INT_ARGB);for(int y=0;y<16;y++)for(int x=0;x<16;x++)quadrants.setRGB(x,y,y<8?(x<8?0xffff2020:0xff20ff20):(x<8?0xff2020ff:0xffffff20));entries.put("assets/test/textures/item/quadrants.png",png(quadrants));
        var cube=new BufferedImage(16,16,BufferedImage.TYPE_INT_ARGB);for(int y=0;y<16;y++)for(int x=0;x<16;x++)cube.setRGB(x,y,0xffedcc30);entries.put("assets/test/textures/block/cube.png",png(cube));
        var animated=new BufferedImage(16,32,BufferedImage.TYPE_INT_ARGB);var g=animated.createGraphics();g.drawImage(texture(0xffff2020),0,0,null);g.drawImage(texture(0xff20ff20),0,16,null);g.dispose();entries.put("assets/test/textures/item/animated.png",png(animated));entries.put("assets/test/textures/item/animated.png.mcmeta",json("{\"animation\":{\"frames\":[1,0]}}"));
        var horizontal=new BufferedImage(32,16,BufferedImage.TYPE_INT_ARGB);g=horizontal.createGraphics();g.drawImage(texture(0xffff2020),0,0,null);g.drawImage(texture(0xff20ff20),16,0,null);g.dispose();entries.put("assets/test/textures/item/horizontal.png",png(horizontal));entries.put("assets/test/textures/item/horizontal.png.mcmeta",json("{\"animation\":{\"frames\":[1,0]}}"));
        for(String axis:new String[]{"width","height","both"}){entries.put("assets/test/textures/item/axis_"+axis+".png",png(quadrants));entries.put("assets/test/textures/item/axis_"+axis+".png.mcmeta",json("{\"animation\":{"+(axis.equals("both")?"\"width\":8,\"height\":4":"\""+axis+"\":8")+",\"frames\":[1]}}"));}
        var sheet=new BufferedImage(16,8,BufferedImage.TYPE_INT_ARGB);for(int y=0;y<7;y++)for(int x=0;x<16;x++)if(x%8==1||y==2||y==5)sheet.setRGB(x,y,0xffff8000);entries.put("assets/test/textures/font/fixture.png",png(sheet));
        entries.put("assets/test/font/custom.json",json("{\"providers\":[{\"type\":\"reference\",\"id\":\"test:glyphs\"},{\"type\":\"space\",\"advances\":{\" \":4}}]}"));
        entries.put("assets/test/font/glyphs.json",json("{\"providers\":[{\"type\":\"bitmap\",\"file\":\"test:font/fixture.png\",\"ascent\":7,\"height\":8,\"chars\":[\"星光\"]}]}"));
        entries.put("assets/test/font/cycle.json",json("{\"providers\":[{\"type\":\"reference\",\"id\":\"test:cycle\"}]}"));
        entries.put("assets/test/font/unsupported.json",json("{\"providers\":[{\"type\":\"test:callback\"}]}"));
        Path file=directory.resolve("fixture.zip");try(var zip=new ZipOutputStream(Files.newOutputStream(file))){for(var entry:entries.entrySet()){zip.putNextEntry(new ZipEntry(entry.getKey()));zip.write(entry.getValue());zip.closeEntry();}}return file;
    }
}
