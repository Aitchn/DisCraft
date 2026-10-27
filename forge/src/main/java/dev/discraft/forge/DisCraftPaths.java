package dev.discraft.forge;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Side-safe path selection only: never creates, moves, copies or rewrites operator data. */
public final class DisCraftPaths {
    private static final Set<String> WARNED=ConcurrentHashMap.newKeySet();
    private DisCraftPaths(){}
    public static void warn(String message){if(WARNED.add(message))com.mojang.logging.LogUtils.getLogger().warn(message);}
    public static Path serverConfig(Path config,Consumer<String> warning)throws IOException{
        return choose(config.resolve("discraft/server.properties"),config.resolve("discraft-server.properties"),"config/discraft/server.properties","config/discraft-server.properties",warning);
    }
    public static Path resourcePacks(Path config,Consumer<String> warning)throws IOException{
        return choose(config.resolve("discraft/resourcepacks"),config.resolve("discraft-resourcepacks"),"config/discraft/resourcepacks/","config/discraft-resourcepacks/",warning);
    }
    public static Path uploads(Path config,Path game,Consumer<String> warning)throws IOException{
        return choose(config.resolve("discraft/uploads"),game.resolve("discraft-uploads"),"config/discraft/uploads/","discraft-uploads/",warning);
    }
    /** Display only a known relative label, never an operator's absolute filesystem path. */
    public static String uploadLabel(Path config,Path game,Path selected){
        Path actual=selected.toAbsolutePath().normalize();
        if(actual.equals(config.resolve("discraft/uploads").toAbsolutePath().normalize()))return "config/discraft/uploads/";
        if(actual.equals(game.resolve("discraft-uploads").toAbsolutePath().normalize()))return "discraft-uploads/";
        throw new IllegalArgumentException("Unknown DisCraft upload location");
    }
    private static boolean exists(Path path)throws IOException{
        try{Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);return true;}
        catch(NoSuchFileException missing){return false;}
    }
    private static void parents(Path path)throws IOException{
        for(Path p=path.toAbsolutePath().normalize().getParent();p!=null;p=p.getParent()){
            if(exists(p)&&!Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS))throw new IOException("Invalid DisCraft configuration directory");
        }
    }
    private static Path choose(Path preferred,Path legacy,String currentName,String oldName,Consumer<String> warning)throws IOException{
        parents(preferred);
        if(exists(preferred)){
            if(Files.isSymbolicLink(preferred))throw new IOException("Invalid DisCraft configuration path");
            if(exists(legacy))warning.accept("DisCraft: legacy "+oldName+" is ignored because "+currentName+" exists; no files were changed.");
            return preferred;
        }
        parents(legacy);
        if(exists(legacy)){
            if(Files.isSymbolicLink(legacy))throw new IOException("Invalid DisCraft legacy path");
            warning.accept("DisCraft: reading legacy "+oldName+"; manually migrate to "+currentName+". No files were changed.");
            return legacy;
        }
        return preferred;
    }
}
