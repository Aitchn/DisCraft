package dev.discraft.forge.server;

import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import dev.discraft.core.*;
import dev.discraft.forge.bridge.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.*;

/** Discord to game content: readable mentions, timestamps, stickers and graceful fallbacks. Loopback fixture only. */
final class DiscordContentTest {
    private static int checks;
    private static void check(boolean value,String label){if(!value)throw new AssertionError(label);checks++;System.out.println("PASS "+label);}
    private static final String CHANNEL="1234567890123456",GUILD="8888888888888888",ALICE="4234567890123456",BOB="4234567890123457",GHOST="4234567890123499";
    private static final String ROLE="5234567890123456",MISSING_ROLE="5234567890123499",ROOM="6234567890123456",MISSING_ROOM="6234567890123499";
    private static final String PNG="7000000000000001",APNG="7000000000000002",LOTTIE="7000000000000003",BROKEN="7000000000000004",GIFSTICKER="7000000000000005";
    private static JsonObject user(String id,String username,String global){var u=new JsonObject();u.addProperty("id",id);u.addProperty("username",username);if(global!=null)u.addProperty("global_name",global);return u;}
    private static RelayRecord convert(DiscordBotAdapter adapter,JsonObject message)throws Exception{
        var method=DiscordBotAdapter.class.getDeclaredMethod("convert",JsonObject.class);method.setAccessible(true);return (RelayRecord)method.invoke(adapter,message);
    }
    private static JsonObject message(String text){var m=new JsonObject();m.addProperty("id","8234567890123456");m.addProperty("content",text);m.add("author",user("3234567890123456","Human",null));return m;}
    static void run()throws Exception{
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var roleRequests=new AtomicInteger();var channelRequests=new AtomicInteger();var directoryStatus=new AtomicInteger(200);var otherRequests=new AtomicInteger();
        server.createContext("/",exchange->{
            try{
                String path=exchange.getRequestURI().getPath();byte[] response;int status=200;
                if(path.startsWith("/api/v10/sticker/")){
                    String id=path.substring(path.lastIndexOf('/')+1);
                    if(id.equals(PNG))response=Attachment.fromResource("emoji.png").bytes();else if(id.equals(APNG))response=Attachment.fromResource("sticker-apng.png").bytes();else if(id.equals(GIFSTICKER))response=Attachment.fromResource("dance.gif").bytes();else{status=404;response=new byte[]{0};}
                }
                else if(path.endsWith("/users/@me"))response=("{\"id\":\"9999999999999999\",\"bot\":true}").getBytes(StandardCharsets.UTF_8);
                else if(path.endsWith("/roles")){roleRequests.incrementAndGet();if(directoryStatus.get()!=200){status=directoryStatus.get();response="{\"message\":\"Missing Access\"}".getBytes(StandardCharsets.UTF_8);}else response=("[{\"id\":\"5234567890123458\",\"name\":\""+"R".repeat(64)+"\"},{\"id\":\""+ROLE+"\",\"name\":\"Moderators\"},{\"id\":\"5234567890123457\",\"name\":\"伺服器管理員\"}]").getBytes(StandardCharsets.UTF_8);}
                else if(path.endsWith("/channels")&&path.contains("/guilds/")){channelRequests.incrementAndGet();if(directoryStatus.get()!=200){status=directoryStatus.get();response="{\"message\":\"Missing Access\"}".getBytes(StandardCharsets.UTF_8);}else response=("[{\"id\":\""+ROOM+"\",\"name\":\"general-chat\"}]").getBytes(StandardCharsets.UTF_8);}
                else if(path.contains("/members/")){
                    String id=path.substring(path.lastIndexOf('/')+1);
                    if(id.equals(ALICE))response=("{\"nick\":\"愛麗絲\",\"roles\":[],\"user\":{\"id\":\""+ALICE+"\",\"username\":\"alice\"}}").getBytes(StandardCharsets.UTF_8);
                    else if(id.equals(BOB)||id.equals("3234567890123456"))response=("{\"roles\":[],\"user\":{\"id\":\""+id+"\",\"username\":\"bob_user\",\"global_name\":\"Bob\"}}").getBytes(StandardCharsets.UTF_8);
                    else{status=404;response="{\"message\":\"Unknown Member\"}".getBytes(StandardCharsets.UTF_8);}
                }
                else if(path.endsWith("/messages")){otherRequests.incrementAndGet();response=exchange.getRequestURI().getQuery().contains("limit=1")?"[{\"id\":\"1\"}]".getBytes(StandardCharsets.UTF_8):"[]".getBytes(StandardCharsets.UTF_8);}
                else response=("{\"id\":\""+CHANNEL+"\",\"type\":0,\"guild_id\":\""+GUILD+"\"}").getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status,response.length);exchange.getResponseBody().write(response);
            }catch(Exception failure){throw new RuntimeException(failure);}finally{exchange.close();}
        });server.start();
        try(var adapter=new DiscordBotAdapter(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/api/v10/"),CHANNEL,"discraft-local-test-token",true)){
            adapter.poll();adapter.poll(); // the first poll verifies the bot/channel (guild id); the second grants this poll's member/directory lookup budget.
            // Users: the message's own mention data, then the guild nickname, then a fixed placeholder.
            var mentioned=message("hi <@"+ALICE+"> and <@!"+BOB+"> and <@"+GHOST+">");var users=new JsonArray();users.add(user(ALICE,"alice","Alice Global"));users.add(user(BOB,"bob_user",null));mentioned.add("mentions",users);
            var record=convert(adapter,mentioned);
            check(record.text().equals("hi @愛麗絲 and @Bob and @unknown-user"),"User mentions resolve to nickname, display name, or a fixed placeholder: "+record.text());
            check(!record.text().contains("<@")&&record.mentions().isEmpty(),"No raw mention markup reaches the game and no impersonating actor span is created");
            var gatewayNick=message("<@"+GHOST+">");var ghost=user(GHOST,"ghost_user","Ghost");var member=new JsonObject();member.addProperty("nick","幽靈");ghost.add("member",member);var gm=new JsonArray();gm.add(ghost);gatewayNick.add("mentions",gm);
            check(convert(adapter,gatewayNick).text().equals("@幽靈"),"Gateway-provided member nickname is used without a REST call");
            var plain=message("<@"+GHOST+">");var pg=new JsonArray();pg.add(user(GHOST,"ghost_user","Ghost"));plain.add("mentions",pg);
            check(convert(adapter,plain).text().equals("@Ghost"),"Message user data is the fallback when the member lookup fails");
            // Roles and channels share one cached listing per kind.
            record=convert(adapter,message("<@&"+ROLE+"> <@&5234567890123457> <@&"+MISSING_ROLE+"> <#"+ROOM+"> <#"+MISSING_ROOM+">"));
            check(record.text().equals("@Moderators @伺服器管理員 @unknown-role #general-chat #unknown-channel"),"Role and channel mentions resolve to readable names: "+record.text());
            check(roleRequests.get()==1&&channelRequests.get()==1,"Role and channel listings are fetched once per message burst, not once per mention");
            convert(adapter,message("<@&"+ROLE+"> <#"+ROOM+">"));check(roleRequests.get()==1&&channelRequests.get()==1,"Resolved role and channel names are cached across messages");
            // Other markup.
            check(convert(adapter,message("@everyone @here </help:1234567890123456> </a b c:1234567890123456> <id:browse>")).text().equals("@everyone @here /help /a b c #瀏覽頻道"),"@everyone/@here stay readable; slash commands and guild navigation become names");
            check(convert(adapter,message("<t:0:F>|<t:86400:t>|<t:86400:d>|<t:86400:D>|<t:90061>|<t:3661:T>")).text().equals("1970年1月1日 星期四 00:00 UTC|00:00 UTC|1970/01/02|1970年1月2日|1970年1月2日 01:01 UTC|01:01:01 UTC"),"Discord timestamps render every style in UTC");
            check(DiscordMarkup.relative(1000,1000+7200).equals("2 小時前")&&DiscordMarkup.relative(1000+90,1000).equals("1 分鐘後")&&DiscordMarkup.relative(0,86_400L*400).equals("1 年前")&&DiscordMarkup.relative(5,5).equals("0 秒後"),"Relative timestamps use bounded units and direction");
            check(convert(adapter,message("<t:9999999999999:R>")).text().equals("[無效時間]")&&convert(adapter,message("<t:123456789012345678>")).text().equals("<t:123456789012345678>"),"Out-of-range timestamps degrade safely");
            // Emoji and mentions coexist; replacement text is never re-parsed as markup.
            var mixed=convert(adapter,message("<@"+ALICE+"> <:smile:1111111111111111> <@&"+ROLE+">"));
            check(mixed.text().startsWith("@愛麗絲 ")&&mixed.text().endsWith(" @Moderators")&&mixed.catalog().size()<=1,"Mentions and custom emoji in one message both convert");
            // Stickers.
            var sticker=message("");var items=new JsonArray();var item=new JsonObject();item.addProperty("id",PNG);item.addProperty("name","Wave");item.addProperty("format_type",1);items.add(item);sticker.add("sticker_items",items);
            record=convert(adapter,sticker);
            check(record.attachments().size()==1&&record.attachments().get(0).name().equals("Wave.png")&&record.text().isBlank(),"PNG sticker becomes a decoded attachment instead of the unavailable placeholder");
            item.addProperty("id",APNG);item.addProperty("format_type",2);record=convert(adapter,sticker);
            check(record.attachments().get(0).media().animated()&&record.attachments().get(0).media().frames().size()==3,"APNG sticker keeps its animation");
            item.addProperty("id",GIFSTICKER);item.addProperty("format_type",4);record=convert(adapter,sticker);
            check(record.attachments().get(0).name().equals("Wave.gif")&&record.attachments().get(0).media().animated(),"GIF sticker decodes as an animated GIF");
            item.addProperty("id",LOTTIE);item.addProperty("format_type",3);record=convert(adapter,sticker);
            check(record.attachments().isEmpty()&&record.text().equals("[貼圖：Wave]"),"Lottie sticker degrades to a readable label");
            item.addProperty("id",BROKEN);item.addProperty("format_type",1);record=convert(adapter,sticker);
            check(record.attachments().isEmpty()&&record.text().equals("[貼圖：Wave]"),"A sticker that cannot be fetched degrades to a readable label");
            item.addProperty("id","not-an-id");record=convert(adapter,sticker);check(record.attachments().isEmpty()&&record.text().equals("[貼圖：Wave]"),"Malformed sticker identity is never fetched");
            var withText=message("nice");item.addProperty("id",PNG);withText.add("sticker_items",items);record=convert(adapter,withText);
            check(record.text().equals("nice")&&record.attachments().size()==1,"Sticker accompanies message text");
            // Oversized attachments are reported by name rather than silently dropped.
            var big=message("");var files=new JsonArray();var file=new JsonObject();file.addProperty("size",RelayRecord.FILE_BYTES+1);file.addProperty("filename","huge.png");file.addProperty("url","http://127.0.0.1:1/huge.png");files.add(file);big.add("attachments",files);
            record=convert(adapter,big);check(record.attachments().isEmpty()&&record.text().equals("[附件：huge.png]"),"An attachment over the relay limit is named instead of 'unavailable'");
            var emojiName=message("hello");var ef=new JsonArray();var efile=new JsonObject();efile.addProperty("size",RelayRecord.FILE_BYTES+1);efile.addProperty("filename","a".repeat(39)+"😀.png");efile.addProperty("url","http://127.0.0.1:1/x.png");ef.add(efile);emojiName.add("attachments",ef);
            check(convert(adapter,emojiName).text().startsWith("hello [附件："),"Fallback filenames are truncated on code point boundaries");
            check(convert(adapter,message("")).text().equals("[Discord content/media unavailable]"),"Truly empty content keeps the generic placeholder");
            var oversize=message("x".repeat(1900)+"<@&5234567890123458>".repeat(5));var expanded=convert(adapter,oversize).text();check(expanded.codePointCount(0,expanded.length())<=Limits.TEXT_CODEPOINTS&&expanded.endsWith("…"),"Expanded names can never push a message over the text limit");
            // Directory failures are non-fatal and never disable the bridge.
            var other=message("<@&"+MISSING_ROLE+">");
            try(var second=new DiscordBotAdapter(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/api/v10/"),CHANNEL,"discraft-local-test-token",true)){
                second.poll();second.poll();directoryStatus.set(403);
                check(convert(second,message("<@&"+ROLE+"> <#"+ROOM+">")).text().equals("@unknown-role #unknown-channel"),"Forbidden directory listings fall back to placeholders");
                check(second.poll().isEmpty(),"A forbidden directory listing does not make the adapter terminal");
                int before=roleRequests.get();convert(second,message("<@&"+ROLE+">"));check(roleRequests.get()==before,"A failed first directory load honors the retry interval");
            }
            // Outbound text must not be rewritten: raw markup typed in game stays exactly as typed.
            var out=adapter.prepare(RelayRecord.draft(UUID.randomUUID(),"literal <@&"+ROLE+"> <t:0>",null));check(out.text().equals("literal <@&"+ROLE+"> <t:0>"),"Minecraft to Discord text is never rewritten by the readable-mention pass");
        }finally{server.stop(0);}
        System.out.println("PASS: "+checks+" Discord content checks");
    }
}
