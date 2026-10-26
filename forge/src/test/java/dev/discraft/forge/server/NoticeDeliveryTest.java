package dev.discraft.forge.server;

import dev.discraft.forge.bridge.RelayRecord;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

final class NoticeDeliveryTest {
    private static int checks;
    private static void check(boolean value,String label){if(!value)throw new AssertionError(label);checks++;System.out.println("PASS "+label);}
    private static ThreadPoolExecutor worker(){return new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(1),r->{var t=new Thread(r,"notice-test");t.setDaemon(true);return t;});}
    static void run()throws Exception{
        var record=NoticeDelivery.record("Aitchn 被 星光劍 殺死 @everyone <@1234567890123456> **custom** [link](https://example.invalid)");
        check(record.author().equals("Server")&&record.mentions().isEmpty()&&record.attachments().isEmpty(),"System notice has explicit server identity and no ping or attachment bindings");
        check(record.text().contains("@\u200beveryone")&&!record.text().contains("<@123")&&record.text().contains("\\*\\*custom\\*\\*")&&record.text().contains("星光劍"),"Generated names preserve Unicode while neutralizing mention-like and Markdown syntax");
        check(NoticeDelivery.record("😀".repeat(3000)).text().codePointCount(0,NoticeDelivery.record("😀".repeat(3000)).text().length())<=1800,"Generated notice truncation preserves Unicode and message budget");
        var calls=new AtomicInteger();var adapter=new DiscordAdapter(){public void send(RelayRecord message){calls.incrementAndGet();throw new IllegalStateException("fixture uncertain result");}public List<RelayRecord> poll(){return List.of();}};
        var queue=worker();NoticeDelivery.submit(queue,NoticeDelivery.task(adapter,record));NoticeDelivery.drain(queue,null,TimeUnit.SECONDS.toNanos(1));
        check(calls.get()==1,"Uncertain automatic notice is attempted once without a retry");
        var order=Collections.synchronizedList(new ArrayList<String>());queue=worker();NoticeDelivery.submit(queue,()->order.add("pending"));NoticeDelivery.drain(queue,()->order.add("shutdown"),TimeUnit.SECONDS.toNanos(1));
        check(order.equals(List.of("pending","shutdown"))&&queue.isTerminated(),"Normal shutdown drains pending notice before final notice");
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);queue=worker();NoticeDelivery.submit(queue,()->{entered.countDown();try{release.await();}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}});check(entered.await(1,TimeUnit.SECONDS),"Fixture holds active worker before testing full queue");
        NoticeDelivery.submit(queue,()->order.add("queued"));check(!NoticeDelivery.submit(queue,()->order.add("overflow")),"Full notice queue rejects additional work instead of growing");
        long start=System.nanoTime();NoticeDelivery.drain(queue,()->order.add("too-late"),TimeUnit.MILLISECONDS.toNanos(75));release.countDown();
        check(System.nanoTime()-start<TimeUnit.SECONDS.toNanos(1)&&!order.contains("too-late"),"Full queue and final notice share one total shutdown deadline");
        check(!NoticeDelivery.submit(queue,()->order.add("after-stop")),"Stopped worker refuses new notices");
        System.out.println("PASS "+checks+" notice delivery checks (local fixtures only)");
    }
}
