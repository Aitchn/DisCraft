package dev.discraft.forge.server;

import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.*;

/** Empty templates and fake operator data only; never performs a Discord request. */
final class ServerConfigInitTest {
    private static final String TOKEN="fake-init-secret-not-real-credentials",CHANNEL="1234567890123456";
    private static final String CUSTOM="enabled=true\nchannel="+CHANNEL+"\nbotToken="+TOKEN+"\nallowedPlayers=*\nlocale=zh_tw\n";
    private static int checks;
    private static void check(boolean value,String label){if(!value)throw new AssertionError(label);checks++;System.out.println("PASS "+label);}
    private static void rejects(Path root)throws Exception{
        var notices=new ArrayList<String>();
        try{ServerConfig.initialize(root,notices::add);throw new AssertionError("Unsafe initialization accepted");}
        catch(IOException expected){check(expected.getCause()==null&&!expected.toString().contains(root.toString())&&!expected.toString().contains(TOKEN)&&notices.stream().anyMatch(s->s.contains("initialization failed"))&&notices.stream().noneMatch(s->s.contains(root.toString())||s.contains(TOKEN)),"Initialization failure is clear and redacts paths, credentials and nested causes");}
    }
    static void run()throws Exception{
        Path base=Files.createTempDirectory("discraft-init-test");
        try{
            var notices=new ArrayList<String>();Path first=base.resolve("first"),file=ServerConfig.initialize(first,notices::add);
            String template=Files.readString(file);var config=ServerConfig.load(file);
            check(file.equals(first.resolve("discraft/server.properties"))&&!config.enabled()&&config.token().isEmpty()&&config.channel().isEmpty()&&!config.allows(UUID.randomUUID()),"First dedicated initialization creates an empty-credential disabled deny-all template");
            check(template.contains("#")&&template.contains("notifications=")&&template.contains("itemDisplay=false")&&template.contains("resourcePacks=")&&template.contains("locale=en_us"),"Generated UTF-8 template documents all supported configuration fields");
            check(notices.stream().anyMatch(s->s.contains("created disabled")),"First initialization reports the disabled relative config path");
            ServerConfig.initialize(first,notices::add);check(Files.readString(file).equals(template),"Second initialization preserves the existing template byte-for-byte");
            Files.writeString(file,CUSTOM);ServerConfig.initialize(first,notices::add);
            check(Files.readString(file).equals(CUSTOM)&&ServerConfig.load(file).token().equals(TOKEN),"Custom operator configuration and fake token survive initialization unchanged");
            String previous=System.getProperty("discraft.discord.enabled");
            try{
                for(String flag:new String[]{null,"true","false"}){
                    if(flag==null)System.clearProperty("discraft.discord.enabled");else System.setProperty("discraft.discord.enabled",flag);
                    var enabled=ServerConfig.load(file);
                    check(enabled.canConnect(true,true)&&!enabled.canConnect(false,true)&&!enabled.canConnect(true,false)&&!config.canConnect(true,true),"Config alone controls connection eligibility for every obsolete JVM-flag state; authenticated dedicated boundary stays strict");
                    try(var ignored=DiscordBotAdapter.fromConfig(enabled)){check(true,"Enabled fake config constructs the official adapter without a JVM gate or network request");}
                }
            }finally{if(previous==null)System.clearProperty("discraft.discord.enabled");else System.setProperty("discraft.discord.enabled",previous);}
            Path legacy=base.resolve("legacy");Files.createDirectories(legacy);Path old=legacy.resolve("discraft-server.properties");Files.writeString(old,CUSTOM);
            check(ServerConfig.initialize(legacy,notices::add).equals(old)&&!Files.exists(legacy.resolve("discraft/server.properties"))&&Files.readString(old).equals(CUSTOM),"Existing legacy configuration prevents a shadowing new default and is never migrated or overwritten");
            Files.createDirectories(legacy.resolve("discraft"));Path fresh=legacy.resolve("discraft/server.properties");Files.writeString(fresh,"enabled=invalid\n");
            check(ServerConfig.initialize(legacy,notices::add).equals(fresh)&&Files.readString(fresh).equals("enabled=invalid\n"),"Invalid existing new config is not replaced or bypassed with legacy data");
            try{ServerConfig.load(fresh);throw new AssertionError("Invalid new config accepted");}catch(IOException expected){check(Files.readString(old).equals(CUSTOM),"Invalid preferred config fails closed while preserving the legacy token");}
            Files.writeString(fresh,"enabled=false\n");check(!ServerConfig.load(ServerConfig.initialize(legacy,notices::add)).enabled(),"Existing disabled new config remains authoritative over enabled legacy data");
            Path parallel=base.resolve("parallel");var logs=new ConcurrentLinkedQueue<String>();var pool=Executors.newFixedThreadPool(8);
            try{
                var futures=new ArrayList<Future<String>>();for(int i=0;i<24;i++)futures.add(pool.submit(()->Files.readString(ServerConfig.initialize(parallel,logs::add))));
                for(var future:futures)if(!future.get(30,TimeUnit.SECONDS).equals(template))throw new AssertionError("Partial or replaced concurrent template");
                check(true,"Twenty-four concurrent initializers observe only one complete template, never a partial write");
                try(var files=Files.list(parallel.resolve("discraft"))){check(files.count()==1,"Concurrent initialization leaves only the config, with no temporary files");}
            }finally{pool.shutdownNow();}
            Path symlink=base.resolve("symlink"),outside=base.resolve("outside");Files.createDirectories(symlink.resolve("discraft"));Files.createDirectories(outside);Path outsideFile=outside.resolve("server.properties");Files.writeString(outsideFile,CUSTOM);
            Path linked=symlink.resolve("discraft/server.properties");Files.createSymbolicLink(linked,outsideFile);rejects(symlink);check(Files.readString(outsideFile).equals(CUSTOM),"Config symlink cannot overwrite an outside file");Files.delete(linked);Files.delete(linked.getParent());Files.createSymbolicLink(linked.getParent(),outside);rejects(symlink);check(Files.readString(outsideFile).equals(CUSTOM),"Parent symlink cannot redirect template creation");
            Path confused=base.resolve("confused");Files.createDirectories(confused.resolve("discraft/server.properties"));rejects(confused);check(Files.isDirectory(confused.resolve("discraft/server.properties")),"A directory at the config filename remains untouched");
            Path parentFile=base.resolve("parent-file");Files.createDirectories(parentFile);Files.writeString(parentFile.resolve("discraft"),"fixture");rejects(parentFile);
            // A provider without hard links must fail closed rather than fall back to a replacing move.
            try(var fs=FileSystems.newFileSystem(URI.create("jar:"+base.resolve("no-links.zip").toUri()),Map.of("create","true"))){Path root=fs.getPath("/config");rejects(root);check(!Files.exists(root.resolve("discraft/server.properties")),"Unsupported atomic publish never leaves a partial or auto-enabled config");}
            if(base.getFileSystem().supportedFileAttributeViews().contains("posix")){
                Path denied=base.resolve("denied");Files.createDirectories(denied.resolve("discraft"));Files.setPosixFilePermissions(denied.resolve("discraft"),PosixFilePermissions.fromString("r-x------"));
                try{if(!Files.isWritable(denied.resolve("discraft"))){rejects(denied);check(!Files.exists(denied.resolve("discraft/server.properties")),"Write permission failure creates no config or credential file");}}
                finally{Files.setPosixFilePermissions(denied.resolve("discraft"),PosixFilePermissions.fromString("rwx------"));}
            }
        }finally{try(var paths=Files.walk(base)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
        System.out.println("PASS "+checks+" safe default initialization and config-only enablement checks (no network)");
    }
}
