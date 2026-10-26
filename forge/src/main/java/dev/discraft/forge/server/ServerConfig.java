package dev.discraft.forge.server;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Server-local configuration only. Never serialized, logged, or synchronized to clients. */
final class ServerConfig {
    private static final int MAX_BYTES=8192;
    private final boolean enabled;
    private final String channel,token;
    private final Set<String> players;
    private ServerConfig(boolean enabled,String channel,String token,Set<String> players){
        this.enabled=enabled;this.channel=channel;this.token=token;this.players=Set.copyOf(players);
    }
    static ServerConfig disabled(){return new ServerConfig(false,"","",Set.of());}
    static ServerConfig load(Path file)throws IOException{
        try {
            if(!Files.exists(file,LinkOption.NOFOLLOW_LINKS))return disabled();
            if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))throw new IOException();
            byte[] bytes;
            try(var in=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)){bytes=in.readNBytes(MAX_BYTES+1);}
            if(bytes.length>MAX_BYTES)throw new IOException();
            var properties=new Properties();
            properties.load(new StringReader(new String(bytes,java.nio.charset.StandardCharsets.UTF_8)));
            if(!Set.of("enabled","channel","botToken","allowedPlayers").containsAll(properties.stringPropertyNames()))throw new IOException();
            String flag=properties.getProperty("enabled","false").trim();
            if(!flag.equals("true")&&!flag.equals("false"))throw new IOException();
            boolean enabled=flag.equals("true");
            String channel=properties.getProperty("channel","").trim(),token=properties.getProperty("botToken","");
            var players=new HashSet<String>();
            String list=properties.getProperty("allowedPlayers","").trim();
            if(!list.isEmpty())for(String entry:list.split(",",-1)){
                String value=entry.trim();
                if(!value.equals("*")&&!UUID.fromString(value).toString().equals(value))throw new IOException();
                players.add(value);
            }
            if(enabled)validateCredentials(channel,token);
            return new ServerConfig(enabled,channel,token,players);
        }catch(Exception rejected){
            // Properties/UUID/path exceptions can quote input; never preserve their message or cause.
            throw new IOException("Invalid or unreadable DisCraft server configuration");
        }
    }
    static void validateCredentials(String channel,String token){
        if(channel==null||!channel.matches("[0-9]{15,22}")||token==null||token.length()<20||token.length()>200||token.chars().anyMatch(c->Character.isWhitespace(c)||Character.isISOControl(c)))throw new IllegalArgumentException("Invalid server Discord configuration");
    }
    boolean enabled(){return enabled;}
    boolean allows(UUID player){return players.contains("*")||players.contains(player.toString());}
    String channel(){return channel;}
    String token(){return token;}
    @Override public String toString(){return "ServerConfig[redacted]";}
}
