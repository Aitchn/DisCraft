package dev.discraft.forge.server;

import net.minecraftforge.fml.ModList;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Forge common-side installed-file API only. Reads selected locale once; native Forge already owns English. */
final class InstalledModLanguages {
    record Source(String order,Path assets){}
    static LanguageIndex load(String locale)throws IOException{
        if(locale.equals("en_us"))return LanguageIndex.empty();
        try{
            var sources=new ArrayList<Source>();
            for(var info:ModList.get().getModFiles()){
                String order=info.getMods().stream().map(m->m.getModId()).sorted().reduce((a,b)->a+","+b).orElseThrow();
                sources.add(new Source(order,info.getFile().findResource("assets")));
            }
            return loadSources(locale,sources);
        }catch(Exception rejected){throw new IOException("Installed mod language index unavailable");}
    }
    /** Same reader is exercised with synthetic installed-file roots; no directory/JAR discovery outside ModList. */
    static LanguageIndex loadSources(String locale,List<Source> sources)throws IOException{
        try{
            if(!locale.matches("[a-z]{2,3}_[a-z]{2,3}")||sources.size()>4096)throw new IOException();if(locale.equals("en_us"))return LanguageIndex.empty();
            var sorted=new ArrayList<>(sources);sorted.sort(Comparator.comparing(Source::order));var orders=new HashSet<String>();var builder=new LanguageIndex.Builder();long total=0;int namespaces=0,files=0;
            for(var source:sorted){
                if(!orders.add(source.order()))throw new IOException();Path root=source.assets();for(Path parent=root;parent!=null;parent=parent.getParent())if(Files.isSymbolicLink(parent))throw new IOException();if(!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS))continue;
                // Only direct namespace children and exactly lang/<configured locale>.json are visited.
                var paths=new ArrayList<Path>();try(var children=Files.newDirectoryStream(root)){for(Path child:children){if(++namespaces>20000)throw new IOException();String namespace=child.getFileName().toString();if(!namespace.matches("[a-z0-9_.-]+"))continue;if(Files.isSymbolicLink(child))throw new IOException();if(Files.isDirectory(child,LinkOption.NOFOLLOW_LINKS))paths.add(child);}}
                paths.sort(Comparator.comparing(p->p.getFileName().toString()));
                for(Path namespace:paths){Path directory=namespace.resolve("lang"),file=directory.resolve(locale+".json");if(Files.isSymbolicLink(directory)||Files.isSymbolicLink(file))throw new IOException();if(!Files.exists(file,LinkOption.NOFOLLOW_LINKS))continue;if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||++files>20000)throw new IOException();
                    String scheme=file.getFileSystem().provider().getScheme();if(!Set.of("file","union","jar").contains(scheme))throw new IOException();
                    // SecureJar union FS supports READ only; it rejects a NOFOLLOW open option. All nodes
                    // above were checked without following links; physical development files also use an atomic no-follow open.
                    byte[] bytes;try(var in=scheme.equals("file")?Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS):Files.newInputStream(file,StandardOpenOption.READ)){bytes=in.readNBytes(1024*1024+1);}total+=bytes.length;if(bytes.length>1024*1024||total>64L*1024*1024)throw new IOException();builder.json(locale,ResourcePackIndex.json(bytes));
                }
            }
            return builder.build();
        }catch(Exception rejected){throw new IOException("Invalid or oversized installed mod language data");}
    }
}
