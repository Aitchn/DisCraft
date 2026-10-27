package dev.discraft.forge.server;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import dev.discraft.forge.DisCraftPaths;

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
    static ServerConfig testOptional(){
        if(!Boolean.getBoolean("discraft.multiplayerSmoke")||!Boolean.getBoolean("discraft.optionalClientSmoke"))throw new IllegalStateException("TEST optional client requires isolated smoke mode");
        return new ServerConfig(false,"","",Set.of("*"),"en_us",List.of(),Set.of("join","quit","death","advancement"),true);
    }
    /** Publish only a complete empty-credential template; never replace existing operator data. */
    static Path initialize(Path directory,Consumer<String> notice)throws IOException{
        Path temporary=null;
        try{
            Path selected=DisCraftPaths.serverConfig(directory,notice);
            if(present(selected)){regular(selected);return selected;}
            Files.createDirectories(selected.getParent());
            // Recheck after directory creation: another initializer or a legacy file may now exist.
            selected=DisCraftPaths.serverConfig(directory,notice);
            if(present(selected)){regular(selected);return selected;}
            byte[] template;
            try(var in=ServerConfig.class.getResourceAsStream("/defaults/discraft/server.properties.example")){
                if(in==null)throw new IOException();template=in.readNBytes(MAX_BYTES+1);
            }
            if(template.length>MAX_BYTES)throw new IOException();
            var defaults=new Properties();defaults.load(new StringReader(new String(template,java.nio.charset.StandardCharsets.UTF_8)));
            if(!"true".equals(defaults.getProperty("enabled"))||!"".equals(defaults.getProperty("botToken"))||!"".equals(defaults.getProperty("channel")))throw new IOException();
            var attributes=selected.getFileSystem().supportedFileAttributeViews().contains("posix")?new java.nio.file.attribute.FileAttribute<?>[]{java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"))}:new java.nio.file.attribute.FileAttribute<?>[0];
            temporary=Files.createTempFile(selected.getParent(),".server-template-",".tmp",attributes);
            try(var out=Files.newOutputStream(temporary,StandardOpenOption.WRITE,StandardOpenOption.TRUNCATE_EXISTING,LinkOption.NOFOLLOW_LINKS)){out.write(template);}
            Path current=DisCraftPaths.serverConfig(directory,notice);
            if(present(current)){regular(current);return current;}
            // A hard-link publish is atomic and fails if the target exists. ATOMIC_MOVE may replace it.
            try{Files.createLink(current,temporary);notice.accept("DisCraft: created config/discraft/server.properties with features enabled and empty credentials. Fill botToken and channel locally, then restart; no connection until both are valid.");}
            catch(FileAlreadyExistsException concurrent){current=DisCraftPaths.serverConfig(directory,notice);regular(current);}
            return current;
        }catch(Exception rejected){
            notice.accept("DisCraft: server config initialization failed; bridge disabled. Check config directory permissions and filesystem support, or install the empty example manually. Existing files were not overwritten.");
            throw new IOException("Unable to initialize DisCraft server configuration");
        }finally{if(temporary!=null)try{Files.deleteIfExists(temporary);}catch(IOException ignored){notice.accept("DisCraft: could not remove an empty-credential config template temporary file; check the DisCraft config directory.");}}
    }
    private static boolean present(Path file)throws IOException{
        try{Files.readAttributes(file,java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);return true;}catch(NoSuchFileException missing){return false;}
    }
    private static void regular(Path file)throws IOException{if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))throw new IOException();}
    boolean canConnect(boolean dedicated,boolean authenticated){return dedicated&&authenticated&&enabled;}
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
            String channel=properties.getProperty("channel","").trim(),token=properties.getProperty("botToken","").trim();
            var players=new HashSet<String>();
            String list=properties.getProperty("allowedPlayers","").trim();
            if(!list.isEmpty())for(String entry:list.split(",",-1)){
                String value=entry.trim();
                if(!value.equals("*")&&!UUID.fromString(value).toString().equals(value))throw new IOException();
                players.add(value);
            }
            if(enabled){if(channel.isBlank()||token.isBlank())throw new MissingCredentials();validateCredentials(channel,token);}
            String locale=properties.getProperty("locale","en_us").trim();if(!locale.matches("[a-z]{2,3}_[a-z]{2,3}"))throw new IOException();
            var packs=new ArrayList<String>();String packList=properties.getProperty("resourcePacks","").trim();
            if(!packList.isEmpty())for(String entry:packList.split(",",-1)){String name=entry.trim();if(!name.matches("[a-zA-Z0-9_-][a-zA-Z0-9_.-]{0,95}\\.zip")||packs.contains(name))throw new IOException();packs.add(name);}
            if(packs.size()>ResourcePackIndex.MAX_PACKS)throw new IOException();
            var notifications=new HashSet<String>();String notices=properties.getProperty("notifications","").trim();
            if(!notices.isEmpty())for(String entry:notices.split(",",-1)){String kind=entry.trim();if(!Set.of("join","quit","death","advancement","lifecycle").contains(kind)||!notifications.add(kind))throw new IOException();}
            String item=properties.getProperty("itemDisplay","false").trim();if(!item.equals("true")&&!item.equals("false"))throw new IOException();
            return new ServerConfig(enabled,channel,token,players,locale,packs,notifications,item.equals("true"));
        }catch(MissingCredentials missing){throw missing;}catch(Exception rejected){
            // Properties/UUID/path exceptions can quote input; never preserve their message or cause.
            throw new IOException("Invalid or unreadable DisCraft server configuration");
        }
    }
    static final class MissingCredentials extends IOException { MissingCredentials(){super("DisCraft botToken/channel are missing; fill server config locally and restart; no network connection attempted");} }
    static void validateCredentials(String channel,String token){
        if(channel==null||!channel.matches("[0-9]{15,22}")||token==null||token.length()<20||token.length()>200||token.chars().anyMatch(c->Character.isWhitespace(c)||Character.isISOControl(c)))throw new IllegalArgumentException("Invalid server Discord configuration");
    }
    enum PlayerAccess { DENY_ALL, EXPLICIT_AUTHENTICATED_LIST, ALL_AUTHENTICATED }
    PlayerAccess playerAccess(){return players.isEmpty()?PlayerAccess.DENY_ALL:players.contains("*")?PlayerAccess.ALL_AUTHENTICATED:PlayerAccess.EXPLICIT_AUTHENTICATED_LIST;}
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
