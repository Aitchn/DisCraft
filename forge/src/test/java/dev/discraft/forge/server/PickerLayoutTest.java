package dev.discraft.forge.server;

import dev.discraft.forge.PickerLayout;
import dev.discraft.forge.PendingCardLayout.Rect;
import java.util.Random;

final class PickerLayoutTest {
    static void run(){
        int checks=0;var random=new Random(128);
        for(int i=0;i<1200;i++){
            int width=320+random.nextInt(1600),height=240+random.nextInt(900),x=random.nextInt(width),y=random.nextInt(height-12),iw=40+random.nextInt(width),pw=220+random.nextInt(381),ph=180+random.nextInt(271);
            var panel=PickerLayout.solve(width,height,x,y,iw,12,pw,ph);
            if(panel.x()<4||panel.y()<4||panel.x()+panel.width()>width-4||panel.y()+panel.height()>height-4||panel.width()>pw||panel.height()>ph)throw new AssertionError("Picker escaped logical viewport");
            if(panel.height()>=144&&panel.overlaps(new Rect(0,y-6,width,24)))throw new AssertionError("Picker intersects real input band");checks++;
        }
        var normal=PickerLayout.solve(640,450,4,438,632,12,350,260);var small=PickerLayout.solve(640,450,4,438,632,12,220,180);
        if(normal.width()!=350||normal.height()!=260||small.width()!=220||small.height()!=180)throw new AssertionError("Preferred dimensions not respected when available");
        if(PickerLayout.solve(320,240,4,110,312,12,600,450).height()>=144)throw new AssertionError("Insufficient space lacks safe closed fallback");
        System.out.println("PASS "+(checks+2)+" bounded picker geometry/preference checks");
    }
}
