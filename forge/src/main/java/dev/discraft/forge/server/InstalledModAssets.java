package dev.discraft.forge.server;

import net.minecraftforge.fml.ModList;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Only loaded Forge file roots, namespace-indexed once; lazy inert resource bytes, never callbacks/classes. */
final class InstalledModAssets {
    private final Map<String,List<Path>> namespaces;
    private final Map<String,byte[]> cache=new LinkedHashMap<>(128,.75f,true);private long bytes;
    private final Set<String> rejected=new LinkedHashSet<>();
    private InstalledModAssets(Map<String,List<Path>> namespaces){this.namespaces=Map.copyOf(namespaces);}
    static InstalledModAssets load()throws IOException{
        var sources=new ArrayList<InstalledModLanguages.Source>();
        for(var info:ModList.get().getModFiles())sources.add(new InstalledModLanguages.Source(info.getMods().stream().map(m->m.getModId()).sorted().reduce((a,b)->a+","+b).orElseThrow(),info.getFile().findResource("assets")));
        return sources(sources);
    }
    static InstalledModAssets sources(List<InstalledModLanguages.Source> sources)throws IOException{
        try{if(sources.size()>4096)throw new IOException();var sorted=new ArrayList<>(sources);sorted.sort(Comparator.comparing(InstalledModLanguages.Source::order));var out=new HashMap<String,List<Path>>();var orders=new HashSet<String>();int count=0;
            for(var source:sorted){if(!orders.add(source.order()))throw new IOException();Path root=source.assets();for(Path p=root;p!=null;p=p.getParent())if(Files.isSymbolicLink(p))throw new IOException();if(!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS))continue;
                try(var entries=Files.newDirectoryStream(root)){for(Path ns:entries){if(++count>20000)throw new IOException();if(Files.isSymbolicLink(ns))throw new IOException();if(ns.getFileName().toString().matches("[a-z0-9_.-]+")&&Files.isDirectory(ns,LinkOption.NOFOLLOW_LINKS))out.computeIfAbsent(ns.getFileName().toString(),k->new ArrayList<>()).add(ns);}}
            }out.replaceAll((k,v)->List.copyOf(v));return new InstalledModAssets(out);
        }catch(Exception invalid){throw new IOException("Installed resource roots unavailable");}
    }
    synchronized byte[] asset(String path){
        if(rejected.contains(path))throw new IllegalArgumentException("Installed resource rejected");var hit=cache.get(path);if(hit!=null)return hit.length==0?null:hit.clone();byte[] value=new byte[0];
        try{if(path.length()>256||!path.matches("assets/[a-z0-9_.-]+/(models/[a-z0-9_./-]+\\.json|textures/[a-z0-9_./-]+\\.png(?:\\.mcmeta)?|font/[a-z0-9_./-]+\\.json)")||path.contains("..")||path.contains("//"))throw new IOException();String[] parts=path.split("/",3);var roots=namespaces.getOrDefault(parts[1],List.of());
            for(int i=roots.size()-1;i>=0;i--){Path root=roots.get(i),file=root.resolve(parts[2]);for(Path p=file;p!=null&&p.startsWith(root);p=p.getParent())if(Files.isSymbolicLink(p))throw new IOException();if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))continue;String scheme=file.getFileSystem().provider().getScheme();if(!Set.of("file","jar","union").contains(scheme))throw new IOException();
                try(var in=scheme.equals("file")?Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS):Files.newInputStream(file,StandardOpenOption.READ)){value=in.readNBytes(ResourcePackIndex.MAX_ENTRY+1);}if(value.length>ResourcePackIndex.MAX_ENTRY)throw new IOException();if(path.endsWith(".png"))ResourcePackIndex.validateImage(value);else ResourcePackIndex.json(value);break;
            }
        }catch(Exception invalid){rejected.add(path);while(rejected.size()>128)rejected.remove(rejected.iterator().next());throw new IllegalArgumentException("Installed resource rejected");}
        cache.put(path,value);bytes+=value.length;while(cache.size()>128||bytes>16L*1024*1024){var key=cache.keySet().iterator().next();bytes-=cache.remove(key).length;}return value.length==0?null:value.clone();
    }
}
