package dev.discraft.forge;

import java.util.*;

/** Bounded right-edge placement. Never changes a chat/input rectangle to make room. */
public final class PendingCardLayout {
    public record Rect(int x,int y,int width,int height){
        public boolean contains(double px,double py){return px>=x&&py>=y&&px<(long)x+width&&py<(long)y+height;}
        public boolean overlaps(Rect other){return width>0&&height>0&&other.width>0&&other.height>0&&x<(long)other.x+other.width&&other.x<(long)x+width&&y<(long)other.y+other.height&&other.y<(long)y+height;}
    }
    public enum Mode { FULL, COMPACT, HIDDEN }
    public record Placement(Mode mode,Rect bounds){
        public Rect spoiler(){return new Rect(bounds.x()+bounds.width()-44,bounds.y()+4,18,18);}
        public Rect remove(){return new Rect(bounds.x()+bounds.width()-22,bounds.y()+4,18,18);}
        public Rect image(){return new Rect(bounds.x()+4,bounds.y()+26,Math.max(0,bounds.width()-8),Math.max(0,bounds.height()-32));}
    }
    private PendingCardLayout(){}
    public static Placement solve(int width,int height,int inputY,List<Rect> obstacles){
        var hidden=new Placement(Mode.HIDDEN,new Rect(0,0,0,0));
        if(width<24||height<24||obstacles.size()>12)return hidden;
        var blocked=new ArrayList<Rect>();for(var r:obstacles){long x=Math.max(0,r.x()),y=Math.max(0,r.y()),right=Math.min((long)width,(long)r.x()+Math.max(0,r.width())),bottom=Math.min((long)height,(long)r.y()+Math.max(0,r.height()));if(right>x&&bottom>y)blocked.add(new Rect((int)x,(int)y,(int)(right-x),(int)(bottom-y)));}
        for(int[] size:List.of(new int[]{152,104},new int[]{128,88},new int[]{112,72},new int[]{112,26})){
            int w=size[0],h=size[1],x=width-4-w;if(x<width/2||h>height-8)continue;
            var ys=new LinkedHashSet<Integer>();ys.add(inputY-8-h);ys.add(4);ys.add(height-4-h);
            for(var r:blocked){ys.add(r.y()-4-h);long after=(long)r.y()+r.height()+4;if(after<=Integer.MAX_VALUE)ys.add((int)after);}
            for(int y:ys){if(y<4||(long)y+h>height-4)continue;var rect=new Rect(x,y,w,h);if(blocked.stream().noneMatch(rect::overlaps))return new Placement(h==26?Mode.COMPACT:Mode.FULL,rect);}
        }return hidden;
    }
}
