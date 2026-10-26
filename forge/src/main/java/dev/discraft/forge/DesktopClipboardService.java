package dev.discraft.forge;

import java.io.*;
import java.nio.file.*;
import java.util.concurrent.*;

/** One bounded command pipe, no clipboard polling, no credential or clipboard logging. */
final class DesktopClipboardService {
    private static final ThreadPoolExecutor io=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(1),r->{var t=new Thread(r,"DisCraft desktop clipboard");t.setDaemon(true);return t;});
    private static Process process;private static DataInputStream input;private static DataOutputStream output;private static Path directory;
    static {Runtime.getRuntime().addShutdownHook(new Thread(DesktopClipboardService::stop,"DisCraft clipboard cleanup"));}
    static byte[] read()throws Exception{return request(1,null);}
    static void write(byte[] png)throws Exception{request(2,png);}
    private static byte[] request(int command,byte[] png)throws Exception{
        Future<byte[]> pending=io.submit(()->{
            start();output.writeByte(command);if(png!=null){output.writeInt(png.length);output.write(png);}output.flush();int count=input.readInt();if(count<0||count>262144)throw new IOException("Desktop clipboard unavailable");byte[] bytes=input.readNBytes(count);if(bytes.length!=count)throw new EOFException();return count==0?null:bytes;
        });
        try{return pending.get(3,TimeUnit.SECONDS);}catch(Exception unavailable){stop();pending.cancel(true);throw new IOException("Desktop clipboard unavailable",unavailable);}finally{io.purge();}
    }
    private static synchronized void start()throws Exception{
        if(process!=null&&process.isAlive())return;
        if(directory==null){directory=Files.createTempDirectory("discraft-desktop-");for(String name:new String[]{"ClipboardDesktop.class","ClipboardDesktop$Pixels.class"}){
            String resource="dev/discraft/forge/"+name;Path target=directory.resolve(resource);Files.createDirectories(target.getParent());try(var bytes=ClipboardDesktop.class.getClassLoader().getResourceAsStream(resource)){if(bytes==null)throw new IOException("Desktop service missing");Files.copy(bytes,target);}
        }}
        String executable=System.getProperty("os.name").startsWith("Windows")?"java.exe":"java";
        process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin",executable).toString(),"-Djava.awt.headless=false","-Xmx128m","-XX:ActiveProcessorCount=1","-cp",directory.toString(),ClipboardDesktop.class.getName()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        input=new DataInputStream(process.getInputStream());output=new DataOutputStream(process.getOutputStream());
    }
    private static synchronized void stop(){if(process!=null){process.destroyForcibly();process=null;}if(directory!=null){try(var files=Files.walk(directory)){for(Path p:files.sorted(java.util.Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}catch(IOException ignored){}directory=null;}}
}
