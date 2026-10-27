package dev.discraft.forge;

/** Logical GUI pixels only: resize the panel, never its font, native input or history. */
public final class PickerLayout {
    private PickerLayout(){}
    public static PendingCardLayout.Rect solve(int width,int height,int inputX,int inputY,int inputWidth,int inputHeight,int preferredWidth,int preferredHeight){
        if(width<1||height<1||preferredWidth<220||preferredWidth>600||preferredHeight<180||preferredHeight>450)throw new IllegalArgumentException("Picker layout bounds");
        if(width<8||height<8)return new PendingCardLayout.Rect(0,0,0,0);
        int w=Math.max(0,Math.min(preferredWidth,width-8));
        int iy=Math.max(0,Math.min(height,inputY)),ih=Math.max(0,Math.min(height,inputHeight));
        int above=Math.max(0,iy-14),below=(int)Math.max(0,(long)height-iy-ih-12);
        int h=Math.min(preferredHeight,Math.max(above,below));
        int x=(int)Math.max(4,Math.min((long)width-w-4,(long)inputX+inputWidth-w-5));
        int y=above>=h?iy-10-h:(int)Math.min(Integer.MAX_VALUE,(long)iy+ih+8);
        return new PendingCardLayout.Rect(x,Math.max(4,Math.min(Math.max(4,height-h-4),y)),w,h);
    }
}
