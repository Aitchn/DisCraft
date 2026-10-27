package dev.discraft.forge.server;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.*;

/** Same total capacity and one adapter thread. Foreground FIFO precedes queued background work.
 * In-flight I/O is never preempted; one background job is served after at most eight waiting foreground jobs. */
final class BridgeWorkQueue extends AbstractQueue<Runnable> implements BlockingQueue<Runnable> {
    private record Background(Runnable task) implements Runnable {public void run(){task.run();}}
    static Runnable background(Runnable task){return new Background(Objects.requireNonNull(task));}
    private final int capacity;private final ArrayDeque<Runnable> foreground=new ArrayDeque<>(),background=new ArrayDeque<>();
    private final ReentrantLock lock=new ReentrantLock();private final Condition nonempty=lock.newCondition(),space=lock.newCondition();private int burst;
    BridgeWorkQueue(int capacity){if(capacity<1)throw new IllegalArgumentException("Queue capacity");this.capacity=capacity;}
    private int count(){return foreground.size()+background.size();}
    private void addLocked(Runnable task){(task instanceof Background?background:foreground).addLast(task);nonempty.signal();}
    private Runnable next(){return foreground.isEmpty()?background.peekFirst():!background.isEmpty()&&burst>=8?background.peekFirst():foreground.peekFirst();}
    private Runnable removeNext(){Runnable value;if(foreground.isEmpty()||!background.isEmpty()&&burst>=8){value=background.removeFirst();burst=0;}else{value=foreground.removeFirst();burst=background.isEmpty()?0:burst+1;}space.signalAll();return value;}
    @Override public boolean offer(Runnable task){Objects.requireNonNull(task);lock.lock();try{if(count()==capacity)return false;addLocked(task);return true;}finally{lock.unlock();}}
    @Override public void put(Runnable task)throws InterruptedException{Objects.requireNonNull(task);lock.lockInterruptibly();try{while(count()==capacity)space.await();addLocked(task);}finally{lock.unlock();}}
    @Override public boolean offer(Runnable task,long timeout,TimeUnit unit)throws InterruptedException{Objects.requireNonNull(task);long left=unit.toNanos(timeout);lock.lockInterruptibly();try{while(count()==capacity){if(left<=0)return false;left=space.awaitNanos(left);}addLocked(task);return true;}finally{lock.unlock();}}
    @Override public Runnable poll(){lock.lock();try{return count()==0?null:removeNext();}finally{lock.unlock();}}
    @Override public Runnable take()throws InterruptedException{lock.lockInterruptibly();try{while(count()==0)nonempty.await();return removeNext();}finally{lock.unlock();}}
    @Override public Runnable poll(long timeout,TimeUnit unit)throws InterruptedException{long left=unit.toNanos(timeout);lock.lockInterruptibly();try{while(count()==0){if(left<=0)return null;left=nonempty.awaitNanos(left);}return removeNext();}finally{lock.unlock();}}
    @Override public Runnable peek(){lock.lock();try{return next();}finally{lock.unlock();}}
    @Override public int size(){lock.lock();try{return count();}finally{lock.unlock();}}
    @Override public int remainingCapacity(){return capacity-size();}
    @Override public boolean remove(Object value){lock.lock();try{boolean removed=foreground.removeFirstOccurrence(value)||background.removeFirstOccurrence(value);if(removed)space.signalAll();return removed;}finally{lock.unlock();}}
    @Override public boolean contains(Object value){lock.lock();try{return foreground.contains(value)||background.contains(value);}finally{lock.unlock();}}
    @Override public void clear(){lock.lock();try{foreground.clear();background.clear();burst=0;space.signalAll();}finally{lock.unlock();}}
    @Override public int drainTo(Collection<? super Runnable> target){return drainTo(target,Integer.MAX_VALUE);}
    @Override public int drainTo(Collection<? super Runnable> target,int maximum){Objects.requireNonNull(target);if(target==this)throw new IllegalArgumentException("Cannot drain queue to itself");lock.lock();try{int n=0;while(n<maximum&&count()>0){var value=next();target.add(value);removeNext();n++;}return n;}finally{lock.unlock();}}
    @Override public Iterator<Runnable> iterator(){
        var copy=new ArrayList<Runnable>();lock.lock();try{copy.addAll(foreground);copy.addAll(background);}finally{lock.unlock();}
        var iterator=copy.iterator();return new Iterator<>(){Runnable last;boolean removable;public boolean hasNext(){return iterator.hasNext();}public Runnable next(){last=iterator.next();removable=true;return last;}public void remove(){if(!removable)throw new IllegalStateException();BridgeWorkQueue.this.remove(last);removable=false;}};
    }
}
