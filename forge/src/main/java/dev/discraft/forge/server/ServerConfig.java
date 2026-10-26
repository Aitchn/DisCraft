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
    private final String locale;
    private final List<String> packs;
    private final Set<String> notifications;
    private final boolean itemDisplay;
    private ServerConfig(boolean enabled,String channel,String token,Set<String> players,String locale,List<String> packs,Set<String> notifications,boolean itemDisplay){
        this.enabled=enabled;this.channel=channel;this.token=token;this.players=Set.copyOf(players);
        this.locale=locale;this.packs=List.copyOf(packs);
        this.notifications=Set.copyOf(notifications);
        this.itemDisplay=itemDisplay;
    }
    static ServerConfig disabled(){return new ServerConfig(false,"","",Set.of(),"en_us",List.of(),Set.of(),false);}
    static ServerConfig testEvents(){
        if(!Boolean.getBoolean("discraft.multiplayerSmoke")||!Boolean.getBoolean("discraft.serverEventsSmoke"))throw new IllegalStateException("TEST event configuration requires isolated smoke mode");
        return new ServerConfig(false,"","",Set.of("*"),"en_us",List.of(),Set.of("join","quit","death","advancement","lifecycle"),false);
    }
    static ServerConfig testNativeHover(){
        if(!Boolean.getBoolean("discraft.multiplayerSmoke")||!Boolean.getBoolean("discraft.nativeHoverSmoke"))throw new IllegalStateException("TEST native hover requires isolated smoke mode");
        return new ServerConfig(false,"","",Set.of("*"),"en_us",List.of(),Set.of("advancement"),true);
    }
    static ServerConfig load(Path file)throws IOException{
        try {
            if(!Files.exists(file,LinkOption.NOFOLLOW_LINKS))return disabled();
            if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))throw new IOException();
            byte[] bytes;
            try(var in=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)){bytes=in.readNBytes(MAX_BYTES+1);}
            if(bytes.length>MAX_BYTES)throw new IOException();
            var properties=new Properties();
            properties.load(new StringReader(new String(bytes,java.nio.charset.StandardCharsets.UTF_8)));
            if(!Set.of("enabled","channel","botToken","allowedPlayers","locale","resourcePacks","notifications","itemDisplay").containsAll(properties.stringPropertyNames()))throw new IOException();
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
            String locale=properties.getProperty("locale","en_us").trim();if(!locale.matches("[a-z]{2,3}_[a-z]{2,3}"))throw new IOException();
            var packs=new ArrayList<String>();String packList=properties.getProperty("resourcePacks","").trim();
            if(!packList.isEmpty())for(String entry:packList.split(",",-1)){String name=entry.trim();if(!name.matches("[a-zA-Z0-9_-][a-zA-Z0-9_.-]{0,95}\\.zip")||packs.contains(name))throw new IOException();packs.add(name);}
            if(packs.size()>ResourcePackIndex.MAX_PACKS)throw new IOException();
            var notifications=new HashSet<String>();String notices=properties.getProperty("notifications","").trim();
            if(!notices.isEmpty())for(String entry:notices.split(",",-1)){String kind=entry.trim();if(!Set.of("join","quit","death","advancement","lifecycle").contains(kind)||!notifications.add(kind))throw new IOException();}
            String item=properties.getProperty("itemDisplay","false").trim();if(!item.equals("true")&&!item.equals("false"))throw new IOException();
            return new ServerConfig(enabled,channel,token,players,locale,packs,notifications,item.equals("true"));
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
    String locale(){return locale;}
    List<String> resourcePacks(){return packs;}
    boolean notifies(String kind){return notifications.contains(kind);}
    boolean itemDisplay(){return itemDisplay;}
    @Override public String toString(){return "ServerConfig[redacted]";}
}
