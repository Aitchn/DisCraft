package dev.discraft.forge.server;

import dev.discraft.forge.bridge.RelayRecord;
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
    static void run()throws Exception{
        Path directory=Files.createTempDirectory("discraft-config-test"),file=directory.resolve("discraft-server.properties");
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
