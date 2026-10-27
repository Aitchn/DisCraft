package dev.discraft.forge.server;

import java.util.*;
import java.util.concurrent.*;

final class BridgeWorkQueueTest {
    static void run()throws Exception{
        var queue=new BridgeWorkQueue(3);Runnable b=BridgeWorkQueue.background(()->{}),one=()->{},two=()->{};
        if(!queue.offer(b)||!queue.offer(one)||!queue.offer(two)||queue.offer(()->{})||queue.remainingCapacity()!=0)throw new AssertionError("Queue capacity changed");
        if(queue.take()!=one||queue.take()!=two||queue.take()!=b||queue.poll()!=null)throw new AssertionError("Foreground or background FIFO changed");
        queue=new BridgeWorkQueue(12);queue.offer(b);var foreground=new ArrayList<Runnable>();for(int i=0;i<10;i++){Runnable r=()->{};foreground.add(r);queue.offer(r);}
        for(int i=0;i<8;i++)if(queue.poll()!=foreground.get(i))throw new AssertionError("Foreground FIFO not preserved");
        if(queue.poll()!=b||queue.poll()!=foreground.get(8)||queue.poll()!=foreground.get(9))throw new AssertionError("Background fairness bound changed");
        queue.offer(one);var it=queue.iterator();if(it.next()!=one)throw new AssertionError();it.remove();if(!queue.isEmpty())throw new AssertionError("Iterator remove did not affect actual queue");
        if(queue.poll(5,TimeUnit.MILLISECONDS)!=null)throw new AssertionError("Empty timed poll");
        var pool=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new BridgeWorkQueue(3));var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var order=Collections.synchronizedList(new ArrayList<String>());
        try{pool.execute(()->{entered.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}});if(!entered.await(1,TimeUnit.SECONDS))throw new AssertionError("Queue gate");pool.execute(BridgeWorkQueue.background(()->order.add("background")));pool.execute(()->order.add("first"));pool.execute(()->order.add("second"));
            try{pool.execute(()->{});throw new AssertionError("Missing rejection");}catch(RejectedExecutionException expected){}
            release.countDown();pool.shutdown();if(!pool.awaitTermination(2,TimeUnit.SECONDS)||!order.equals(List.of("first","second","background")))throw new AssertionError("Actual executor ordering/drain failed");
        }finally{release.countDown();pool.shutdownNow();}
        System.out.println("PASS bounded bridge queue capacity, foreground FIFO, background fairness, timed poll, iterator, rejection and shutdown checks");
    }
}
