package dev.discraft.forge.server;

import dev.discraft.forge.PendingCardLayout;
import dev.discraft.forge.PendingCardLayout.*;
import java.util.*;

final class PendingCardLayoutTest {
    private static void require(boolean ok){if(!ok)throw new AssertionError("Pending-card placement invariant");}
    static void run(){
        var normal=PendingCardLayout.solve(640,450,438,List.of(new Rect(0,210,340,220),new Rect(0,432,640,18)));require(normal.mode()==Mode.FULL&&normal.bounds().x()==484);
        var occupied=PendingCardLayout.solve(640,450,438,List.of(new Rect(350,160,290,280),new Rect(0,432,640,18)));require(occupied.mode()==Mode.FULL&&occupied.bounds().y()==4);
        var adaptive=PendingCardLayout.solve(640,450,438,List.of(new Rect(0,100,640,350)));require(adaptive.mode()==Mode.FULL&&adaptive.bounds().width()==128&&adaptive.bounds().height()==88);
        var compact=PendingCardLayout.solve(320,225,213,List.of(new Rect(0,34,320,191)));require(compact.mode()==Mode.COMPACT);
        require(PendingCardLayout.solve(320,225,213,List.of(new Rect(0,0,320,225))).mode()==Mode.HIDDEN);
        require(PendingCardLayout.solve(8,8,3,List.of()).mode()==Mode.HIDDEN);
        require(PendingCardLayout.solve(640,450,438,Collections.nCopies(13,new Rect(0,0,1,1))).mode()==Mode.HIDDEN);
        var random=new Random(6126);for(int sample=0;sample<2500;sample++){int width=160+random.nextInt(1200),height=100+random.nextInt(700),input=random.nextInt(height);var obstacles=new ArrayList<Rect>();obstacles.add(new Rect(0,input,width,16));for(int i=0;i<random.nextInt(10);i++)obstacles.add(new Rect(random.nextInt(width+100)-50,random.nextInt(height+100)-50,random.nextInt(width),random.nextInt(height)));var result=PendingCardLayout.solve(width,height,input,obstacles);if(result.mode()==Mode.HIDDEN)continue;var r=result.bounds();require(r.x()>=width/2&&r.y()>=4&&(long)r.x()+r.width()<=width-4&&(long)r.y()+r.height()<=height-4);require(obstacles.stream().noneMatch(r::overlaps));for(var button:List.of(result.spoiler(),result.remove()))require(button.x()>=r.x()&&button.y()>=r.y()&&button.x()+button.width()<=r.x()+r.width()&&button.y()+button.height()<=r.y()+r.height());require(!result.spoiler().overlaps(result.remove()));}
        System.out.println("PASS 2507 right-side pending-card full/compact/hidden/bounded-obstacle placement checks");
    }
}
