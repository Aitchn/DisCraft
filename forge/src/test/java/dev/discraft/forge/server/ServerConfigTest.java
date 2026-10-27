package dev.discraft.forge.server;

import dev.discraft.forge.bridge.RelayRecord;
import dev.discraft.forge.DisCraftPaths;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Fake configuration only; no host credential/environment reads. */
final class ServerConfigTest {
    private static int checks;
    private static final String TOKEN="fake-config-secret-never-live",CHANNEL="1234567890123456";
    private static void check(boolean value,String label){if(!value)throw new AssertionError(label);checks++;System.out.println("PASS "+label);}
    private static void rejects(Path file,String content)throws Exception{
        Files.writeString(file,content);
        try{ServerConfig.load(file);throw new AssertionError("Invalid config accepted");}
        catch(IOException expected){
            var log=new StringWriter();expected.printStackTrace(new PrintWriter(log));
            check(expected.getCause()==null&&!log.toString().contains(TOKEN)&&!log.toString().contains(CHANNEL)&&!log.toString().contains(file.toString()),"Invalid config diagnostics redact values, paths and nested causes");
        }
    }
    private static void pathCompatibility()throws Exception{
        Path game=Files.createTempDirectory("discraft-path-test"),root=game.resolve("config"),fresh=root.resolve("discraft/server.properties"),legacy=root.resolve("discraft-server.properties");
        var warnings=new ArrayList<String>();
        try{
            check(DisCraftPaths.serverConfig(root,warnings::add).equals(fresh)&&!Files.exists(root),"Missing paths select unified location without creating or migrating data");
            Files.createDirectories(root);String old="enabled=true\nchannel="+CHANNEL+"\nbotToken="+TOKEN+"\nallowedPlayers=*\n";Files.writeString(legacy,old);
            check(ServerConfig.load(DisCraftPaths.serverConfig(root,warnings::add)).token().equals(TOKEN)&&!Files.exists(fresh),"Legacy credentials remain readable without copy or overwrite");
            check(warnings.size()==1&&warnings.get(0).contains("legacy")&&!warnings.get(0).contains(TOKEN)&&!warnings.get(0).contains(game.toString()),"Legacy warning identifies static relative paths without secrets or host paths");
            Files.createDirectories(fresh.getParent());Files.writeString(fresh,"enabled=false\n");
            check(!ServerConfig.load(DisCraftPaths.serverConfig(root,warnings::add)).enabled()&&Files.readString(legacy).equals(old),"Existing new disabled config wins and leaves legacy token untouched");
            Files.writeString(fresh,"enabled=invalid\n");try{ServerConfig.load(DisCraftPaths.serverConfig(root,warnings::add));throw new AssertionError("Invalid new config fell back");}catch(IOException expected){check(true,"Invalid new config fails closed instead of reading legacy credentials");}
            Path oldPacks=root.resolve("discraft-resourcepacks"),newPacks=root.resolve("discraft/resourcepacks");Files.createDirectories(oldPacks);Files.writeString(oldPacks.resolve("marker"),"old");
            check(DisCraftPaths.resourcePacks(root,warnings::add).equals(oldPacks),"Legacy resource directory fallback is explicit and independent of config selection");
            Files.createDirectories(newPacks);check(DisCraftPaths.resourcePacks(root,warnings::add).equals(newPacks)&&Files.exists(oldPacks.resolve("marker")),"Empty unified resource directory wins without merging old resources");
            Path oldUploads=game.resolve("discraft-uploads"),newUploads=root.resolve("discraft/uploads");Files.createDirectories(oldUploads);
            check(DisCraftPaths.uploads(root,game,warnings::add).equals(oldUploads),"Client legacy uploads remain usable without relocation");
            check(DisCraftPaths.uploadLabel(root,game,DisCraftPaths.uploads(root,game,warnings::add)).equals("discraft-uploads/"),"Legacy upload label follows selected directory without exposing host paths");
            Files.createDirectories(newUploads);check(DisCraftPaths.uploads(root,game,warnings::add).equals(newUploads),"Unified client uploads take priority");
            check(DisCraftPaths.uploadLabel(root,game,DisCraftPaths.uploads(root,game,warnings::add)).equals("config/discraft/uploads/"),"Unified upload label follows selected directory without exposing host paths");
            try{DisCraftPaths.uploadLabel(root,game,game.resolve("unrelated"));throw new AssertionError("Arbitrary path label accepted");}catch(IllegalArgumentException expected){check(!expected.getMessage().contains(game.toString()),"Unrecognized upload location cannot become a display path");}
            Files.delete(fresh);Files.createSymbolicLink(fresh,legacy);
            try{DisCraftPaths.serverConfig(root,warnings::add);throw new AssertionError("Preferred symlink accepted");}catch(IOException expected){check(true,"Preferred symlink cannot redirect credentials or trigger legacy fallback");}Files.delete(fresh);
            Files.delete(newUploads);Files.delete(newPacks);Files.delete(fresh.getParent());Files.createSymbolicLink(fresh.getParent(),root);
            try{DisCraftPaths.serverConfig(root,warnings::add);throw new AssertionError("Parent symlink accepted");}catch(IOException expected){check(true,"Unified parent symlink fails closed before any config read");}Files.delete(fresh.getParent());
            Files.createDirectory(legacy.resolveSibling("target-dir"));Files.delete(legacy);Files.createSymbolicLink(legacy,root.resolve("target-dir"));
            try{DisCraftPaths.serverConfig(root,warnings::add);throw new AssertionError("Legacy symlink accepted");}catch(IOException expected){check(true,"Legacy config symlink rejected without migration");}
        }finally{try(var entries=Files.walk(game)){for(Path p:entries.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
    }
    static void run()throws Exception{
        ServerConfigInitTest.run();
        pathCompatibility();
        Path directory=Files.createTempDirectory("discraft-config-test"),file=directory.resolve("server.properties");
        try{
            UUID player=UUID.randomUUID(),other=UUID.randomUUID();
            check(!ServerConfig.load(file).enabled(),"Missing server config stays disabled without fallback");
            Files.writeString(file,"enabled=false\nchannel=\nbotToken=\nallowedPlayers=\n");
            var blank=ServerConfig.load(file);check(!blank.enabled()&&!blank.allows(player),"Empty placeholders disable bridge and deny players");
            String valid="enabled=true\nchannel="+CHANNEL+"\nbotToken="+TOKEN+"\nallowedPlayers="+player+"\n";
            Files.writeString(file,valid);var config=ServerConfig.load(file);
            check(config.enabled()&&config.channel().equals(CHANNEL)&&config.token().equals(TOKEN),"Live credentials come from explicit server config");
            check(config.allows(player)&&!config.allows(other),"Server config keeps canonical player allowlist");
            check(config.toString().equals("ServerConfig[redacted]"),"Configuration string representation reveals no fields");
            try(var adapter=DiscordBotAdapter.fromConfig(config)){
                check(!adapter.toString().contains(TOKEN)&&!adapter.toString().contains(CHANNEL),"Adapter representation omits credentials and channel");
            }
            byte[] packet=RelayRecord.draft(UUID.randomUUID(),"ordinary draft",null).encode();
            String wire=new String(packet,StandardCharsets.ISO_8859_1);
            check(!wire.contains(TOKEN)&&!wire.contains(CHANNEL),"Packet serialization carries no server configuration");
            Files.writeString(file,valid.replace(player.toString(),"*"));check(ServerConfig.load(file).allows(other),"Explicit wildcard allows authenticated players");
            Files.writeString(file,valid+"locale=zh_tw\nresourcePacks=base.zip,custom.zip\n");var localized=ServerConfig.load(file);
            check(localized.locale().equals("zh_tw")&&localized.resourcePacks().equals(List.of("base.zip","custom.zip")),"Resource locale and ordered local pack names come only from server config");
            check(!config.notifies("lifecycle")&&!blank.notifies("join"),"Automatic notifications default off even with a live-enabled configuration");
            check(!config.itemDisplay(),"Item display defaults off on existing configurations");
            Files.writeString(file,valid+"itemDisplay=true\n");check(ServerConfig.load(file).itemDisplay(),"Item display requires a server-local config switch");rejects(file,valid+"itemDisplay=maybe\n");
            try{ServerConfig.testEvents();throw new AssertionError("Ungated fixture config accepted");}catch(IllegalStateException expected){check(true,"Event fixture config cannot activate outside isolated multiplayer smoke mode");}
            Files.writeString(file,valid+"notifications=join,quit,death,advancement,lifecycle\n");var notices=ServerConfig.load(file);
            check(notices.notifies("join")&&notices.notifies("quit")&&notices.notifies("death")&&notices.notifies("advancement")&&notices.notifies("lifecycle"),"All event categories require explicit server-config opt-in");
            rejects(file,valid+"notifications=all\n");rejects(file,valid+"notifications=join,join\n");
            rejects(file,valid+"resourcePacks=../private.zip\n");
            rejects(file,valid+"resourcePacks=https://example.invalid/pack.zip\n");
            rejects(file,valid+"resourcePacks=base.zip,base.zip\n");
            rejects(file,valid+"locale=not a locale\n");
            rejects(file,valid.replace(TOKEN,"short"));
            Files.writeString(file,valid.replace("botToken="+TOKEN,"botToken="+TOKEN+"  "));check(ServerConfig.load(file).token().equals(TOKEN),"Trailing whitespace pasted after the bot token is ignored");
            rejects(file,valid.replace(CHANNEL,"bad-channel"));
            rejects(file,valid.replace(player.toString(),TOKEN));
            rejects(file,valid+"unexpected="+TOKEN+"\n");
            rejects(file,valid+"bad=\\uZZZZ\n");
            rejects(file,valid.replace("enabled=true","enabled="+TOKEN));
            rejects(file,valid+"#"+"x".repeat(8192));
            Files.delete(file);Files.createSymbolicLink(file,directory.resolve("absent"));
            try{ServerConfig.load(file);throw new AssertionError("Symlink config accepted");}catch(IOException expected){check(expected.getCause()==null,"Symlink config rejected without input diagnostics");}
            try{DiscordBotAdapter.fromConfig(blank);throw new AssertionError("Disabled adapter accepted");}catch(IllegalArgumentException expected){check(true,"Disabled config cannot construct live adapter");}
        }finally{Files.deleteIfExists(file);Files.delete(directory);}
        System.out.println("PASS "+checks+" server configuration checks (fake fixtures only)");
    }
}
