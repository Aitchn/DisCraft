package dev.discraft.forge.server;

import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import dev.discraft.core.*;
import dev.discraft.forge.bridge.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.*;

/** Catalog/preview contracts use synthetic metadata and loopback images only. */
final class GuildEmojiTest {
    private static int checks;
    private static void check(boolean value,String label){if(!value)throw new AssertionError(label);checks++;System.out.println("PASS "+label);}
    private interface Work{void run()throws Exception;}
    private static void reject(Work work,String label)throws Exception{try{work.run();throw new AssertionError(label);}catch(AssertionError failed){throw failed;}catch(Exception expected){check(true,label);}}
    private static JsonObject entry(String id,String name,boolean animated,boolean available,String... roles){
        var e=new JsonObject();e.addProperty("id",id);e.addProperty("name",name);e.addProperty("animated",animated);e.addProperty("available",available);var r=new JsonArray();for(String role:roles)r.add(role);e.add("roles",r);return e;
    }
    private static void refresh(DiscordBotAdapter adapter)throws Exception{var field=DiscordBotAdapter.class.getDeclaredField("nextCatalog");field.setAccessible(true);field.setLong(adapter,0);}
    static void run()throws Exception{
        String first="1111111111111111",animated="2222222222222222",disabled="3333333333333333",restricted="4444444444444444";
        var definition=new GuildEmojiCatalog.Entry(first,"same",false,true,true,false);
        var original=new GuildEmojiCatalog(1,"TEST fixture",true,true,true,List.of(definition,new GuildEmojiCatalog.Entry(animated,"same",true,true,true,false)));
        check(GuildEmojiCatalog.decode(original.encode()).equals(original),"Guild catalog codec preserves stable IDs, duplicate names, animation and fixture label");
        reject(()->new GuildEmojiCatalog(1,"test",false,true,true,List.of(definition,definition)),"Catalog rejects duplicate IDs while allowing duplicate names");
        reject(()->GuildEmojiCatalog.decode(new byte[GuildEmojiCatalog.MAX_BYTES+1]),"Catalog rejects oversized wire data before allocation");
        reject(()->GuildEmojiCatalog.decode(Arrays.copyOf(original.encode(),12)),"Catalog rejects truncated metadata");
        byte[] extra=Arrays.copyOf(original.encode(),original.encode().length+1);reject(()->GuildEmojiCatalog.decode(extra),"Catalog rejects trailing data");
        reject(()->new GuildEmojiCatalog.Entry(first,"../bad",false,true,true,false),"Catalog names cannot introduce paths or markup");
        reject(()->new GuildEmojiCatalog(0,"test",false,false,true,List.of(definition)),"Unavailable catalog cannot retain selectable entries");
        var clock=new AtomicLong();var cache=new EmojiThumbnailCache(clock::get);var loads=new AtomicInteger();var image=Attachment.fromResource("emoji.png");
        for(int i=0;i<20;i++)cache.get("positive",()->{loads.incrementAndGet();return image;});check(loads.get()==1,"Thumbnail positive cache avoids repeated fetches across requests");
        for(int i=0;i<20;i++)cache.get("negative",()->{loads.incrementAndGet();throw new java.io.IOException();});check(loads.get()==2,"Thumbnail failure cache avoids repeated failed fetches");
        clock.set(60_000_000_001L);cache.get("negative",()->{loads.incrementAndGet();return image;});check(loads.get()==3,"Negative thumbnail TTL permits a bounded later retry");
        for(int i=0;i<100;i++){final int n=i;cache.get("bad"+n,()->{throw new java.io.IOException();});}check(cache.size()<=64,"Thumbnail negative metadata cache is bounded");
        for(int i=0;i<70;i++)cache.get("image"+i,()->image);
        check(cache.images()<=64&&cache.bytes()<=EmojiThumbnailCache.MAX_BYTES&&cache.pixels()<=EmojiThumbnailCache.MAX_PIXELS,"Thumbnail LRU independently caps images, encoded bytes and decoded pixels");
        cache.retain(Set.of());check(cache.size()==0,"Catalog invalidation removes cached thumbnails and negative entries");
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var metadata=new JsonArray();metadata.add(entry(first,"static",false,true));metadata.add(entry(animated,"animated",true,true));metadata.add(entry(disabled,"disabled",false,false));metadata.add(entry(restricted,"restricted",false,true,"5555555555555555"));
        var rows=new AtomicReference<JsonArray>(metadata);var botRoles=new AtomicReference<List<String>>(List.of());var requests=new AtomicInteger();var downloads=new AtomicInteger();var metadataStatus=new AtomicInteger(200);var noMediaAuth=new AtomicBoolean(true);
        server.createContext("/",exchange->{try{
            requests.incrementAndGet();String path=exchange.getRequestURI().getPath();int status=200;byte[] response;
            if(path.contains("/emoji/")){
                downloads.incrementAndGet();if(exchange.getRequestHeaders().containsKey("Authorization"))noMediaAuth.set(false);
                String id=path.substring(path.lastIndexOf('/')+1);boolean moving=false;for(var value:rows.get()){var e=value.getAsJsonObject();if(e.get("id").getAsString().equals(id))moving=e.get("animated").getAsBoolean();}
                response=Attachment.fromResource(moving?"dance.gif":"emoji.png").bytes();
            }else if(path.endsWith("/users/@me"))response="{\"id\":\"9999999999999999\",\"bot\":true}".getBytes(StandardCharsets.UTF_8);
            else if(path.contains("/members/")){var object=new JsonObject();var r=new JsonArray();botRoles.get().forEach(r::add);object.add("roles",r);response=object.toString().getBytes(StandardCharsets.UTF_8);}
            else if(path.endsWith("/emojis")){status=metadataStatus.getAndSet(200);response=(status==429?"{\"retry_after\":2}":rows.get().toString()).getBytes(StandardCharsets.UTF_8);}
            else response="{\"id\":\"1234567890123456\",\"type\":0,\"guild_id\":\"8888888888888888\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status,response.length);exchange.getResponseBody().write(response);
        }catch(Exception ignored){}finally{exchange.close();}});server.start();
        URI api=URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/api/v10/");
        try{
            try(var adapter=new DiscordBotAdapter(api,"1234567890123456","discraft-local-test-token",true)){
                var catalog=adapter.catalog();check(catalog.ready()&&catalog.entries().size()==4,"Official guild catalog resolves guild from configured channel and reads bot role policy");
                int before=requests.get();check(adapter.catalog().equals(catalog)&&requests.get()==before,"Catalog refresh TTL suppresses repeated REST reads");
                check(!catalog.find(disabled).usable()&&!catalog.find(restricted).usable()&&catalog.find(restricted).restricted(),"Unavailable and role-restricted emojis remain visible but not sendable");
                var previews=adapter.thumbnails(List.of(first,animated,disabled,restricted));check(previews.size()==2&&previews.get(animated).media().animated(),"Lazy preview batch fetches only usable static/animated emoji");
                int downloaded=downloads.get();adapter.thumbnails(List.of(first,animated));check(downloads.get()==downloaded&&noMediaAuth.get(),"Cached previews do not refetch and never receive Bot authorization");
                check(adapter.thumbnails(List.of(first,first,first,first,first)).isEmpty(),"Thumbnail request batch is capped before fetching");
                rows.get().get(0).getAsJsonObject().addProperty("name","renamed");refresh(adapter);var renamed=adapter.catalog();
                check(renamed.version()>catalog.version()&&renamed.find(first).name().equals("renamed"),"Rename increments metadata version while retaining stable emoji ID");
                adapter.thumbnails(List.of(first));check(downloads.get()==downloaded,"Rename reuses the ID-keyed thumbnail without another CDN fetch");
                var prepared=adapter.prepare(RelayRecord.draft(UUID.randomUUID(),"<:old_name:"+first+">",null));
                check(prepared.catalog().values().iterator().next().name().equals("renamed"),"Outbound stale display name resolves to current metadata by stable ID");
                reject(()->adapter.prepare(RelayRecord.draft(UUID.randomUUID(),"<:disabled:"+disabled+">",null)),"Unavailable emoji is rejected before a Discord send");
                reject(()->adapter.prepare(RelayRecord.draft(UUID.randomUUID(),"<:restricted:"+restricted+">",null)),"Missing emoji role permission is rejected before a Discord send");
                botRoles.set(List.of("5555555555555555"));refresh(adapter);check(adapter.catalog().find(restricted).usable(),"Updated bot roles refresh emoji usability");
                rows.get().remove(0);refresh(adapter);var deleted=adapter.catalog();before=downloads.get();
                check(deleted.find(first)==null&&adapter.thumbnails(List.of(first)).isEmpty()&&downloads.get()==before,"Deleted emoji loses metadata and thumbnail access");
                reject(()->adapter.prepare(RelayRecord.draft(UUID.randomUUID(),"<:renamed:"+first+">",null)),"Deleted emoji draft cannot silently select a different same-name ID");
                var many=new JsonArray();for(int i=0;i<520;i++)many.add(entry(String.valueOf(6000000000000000L+i),"item"+i,false,true));rows.set(many);refresh(adapter);var capped=adapter.catalog();
                check(capped.ready()&&!capped.complete()&&capped.entries().size()==GuildEmojiCatalog.MAX_ENTRIES,"Unpaginated upstream catalog is capped and explicitly marked incomplete");
                metadataStatus.set(403);refresh(adapter);check(!adapter.catalog().ready()&&adapter.thumbnails(List.of(animated)).isEmpty(),"Permission loss clears catalog and cached preview access immediately");
                before=requests.get();adapter.catalog();check(requests.get()==before,"Terminal permission loss never retries catalog requests");
            }
            try(var adapter=new DiscordBotAdapter(api,"1234567890123456","discraft-local-test-token",true)){
                metadataStatus.set(429);check(!adapter.catalog().ready(),"Guild metadata 429 fails closed");int before=requests.get();adapter.catalog();check(requests.get()==before,"Guild metadata rate limit suppresses early refresh attempts");
            }
        }finally{server.stop(0);}
        System.out.println("PASS "+checks+" guild catalog/thumbnail checks (synthetic metadata, loopback only)");
    }
}
