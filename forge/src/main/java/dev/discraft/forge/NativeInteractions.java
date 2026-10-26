package dev.discraft.forge;

import dev.discraft.core.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.*;
import net.minecraftforge.eventbus.api.*;
import net.minecraftforge.fml.common.Mod;
import java.util.*;

/** Native-chat popup/card prototype. No remote catalog or automatic send. */
@Mod.EventBusSubscriber(modid=DisCraft.ID,value=Dist.CLIENT)
public final class NativeInteractions {
    private static final Minecraft MC=Minecraft.getInstance();
    private static final ResourceLocation SMILEY=new ResourceLocation(DisCraft.ID,"textures/gui/smiley.png");
    private static final TextureCache textures=new TextureCache();
    private static final long started=System.nanoTime();
    private static Attachment demoGif;
    private static boolean open;private static int tab=2,category,scroll;private static String query="";
    private static EditBox search,input;private static ChatScreen screen;
    private record Item(String glyph,String name,String source,String value,Attachment media,boolean custom,boolean file) {}
    private record Hit(int x,int y,int w,int h,Item item) {}
    private static final List<Hit> hits=new ArrayList<>();
    private static final String[][] UNICODE={
        {"😀","😃","😄","😁","😆","😅","😂","🤣","🙂","🙃","😉","😊","😇","😍","🥰","😘","😋","😛","😜","🤪","😎","🤓","🤔","😐","😑","😶","😏","😒","🙄","😬","😌","😔","😪","😴","😷","🤒","🤕","🤢","🤮","🥺","😢","😭","😤","😡","🤯","😳","😱","😨"},
        {"🐶","🐱","🐭","🐹","🐰","🦊","🐻","🐼","🐨","🐯","🦁","🐮","🐷","🐸","🐵","🐔"},
        {"🍎","🍐","🍊","🍋","🍌","🍉","🍇","🍓","🍒","🍑","🥑","🍔","🍕","🍣","🍰","☕"},
        {"⚽","🏀","🎮","🎲","🎵","🎸","🎨","🏆","🚗","🚲","✈","🚀","🏠","⛰","🌞","⭐"},
        {"❤","💛","💚","💙","💜","✅","❌","❗","❓","✨","🔥","💯","👋","👍","👎","👏"}
    };
    static void attach(ChatScreen owner,ScreenEvent.Init.Post event,EditBox nativeInput){
        screen=owner;input=nativeInput;input.setWidth(Math.max(40,owner.width-30));
        event.addListener(new Smiley(owner.width-23,owner.height-17));
        search=new EditBox(MC.font,px()+10,py()+35,pw()-20,18,Component.literal("搜尋表情"));search.setHint(Component.literal("搜尋"));search.setMaxLength(80);search.setValue(query);search.setResponder(s->{query=s;scroll=0;});search.visible=open;search.active=open;event.addListener(search);
    }
    static void reset(){open=false;query="";hits.clear();textures.close();screen=null;search=null;}
    static void closePicker(){open=false;if(search!=null){search.visible=false;search.active=false;}if(screen!=null&&MC.screen==screen&&input!=null){screen.setFocused(input);input.setFocused(true);}}
    public static void openPicker(){if(MC.screen instanceof ChatScreen){open=true;if(search!=null){search.visible=true;search.active=true;screen.setFocused(search);search.setFocused(true);}}}
    private static int px(){return Math.max(4,screen.width-pw()-5);}private static int pw(){return Math.min(350,screen.width-8);}private static int ph(){return Math.min(260,screen.height-28);}private static int py(){return screen.height-22-ph();}
    private static boolean inside(double x,double y,int a,int b,int w,int h){return x>=a&&x<a+w&&y>=b&&y<b+h;}
    private static List<Item> items(){
        var list=new ArrayList<Item>();
        if(tab==1)return list;
        if(tab==0){try{if(demoGif==null)demoGif=Attachment.fromResource("dance.gif");list.add(new Item("","dance.gif","本機示範 GIF（非遠端搜尋）","",demoGif,false,true));}catch(Exception ignored){}return list;}
        if(category==5){
            for(var e:ClientBridge.catalog().entrySet()){var c=e.getValue();var a=ClientBridge.emoji(c.mediaKey());if(a==null)continue;String value=c.id().matches("[0-9]{15,22}")?"<"+(c.animated()?"a":"")+":"+c.name()+":"+c.id()+">":":"+e.getKey()+":";list.add(new Item("",":"+c.name()+":",ClientBridge.status().contains("TEST")?"伺服器示範（非 Discord）":"可用伺服器表情",value,a,true,false));}
        }else for(String glyph:UNICODE[Math.min(category,UNICODE.length-1)])list.add(new Item(glyph,":"+Character.getName(glyph.codePointAt(0)).toLowerCase(Locale.ROOT).replace(' ','_')+":","Unicode · 本機",glyph,null,false,false));
        return list;
    }
    private static List<Item> filtered(){String q=query.toLowerCase(Locale.ROOT);return items().stream().filter(i->q.isBlank()||(i.name()+i.source()+i.glyph()).toLowerCase(Locale.ROOT).contains(q)).toList();}
    private static void pick(Item item){if(item.file()){NativeComposer.stage(()->item.media());}else NativeComposer.insertEmoji(item.value(),item.custom());closePicker();screen.setFocused(input);input.setFocused(true);}
    @SubscribeEvent(priority=EventPriority.HIGH) public static void mouse(ScreenEvent.MouseButtonPressed.Pre event){
        if(!(event.getScreen() instanceof ChatScreen)||event.getButton()!=0)return;double x=event.getMouseX(),y=event.getMouseY();
        if(open){
            if(inside(x,y,px(),py(),pw(),ph())){
                event.setCanceled(true);
                if(inside(x,y,px()+10,py()+35,pw()-20,20)){search.mouseClicked(x,y,0);screen.setFocused(search);return;}
                if(y<py()+29){int t=(int)((x-px()-10)/64);if(t>=0&&t<3){tab=t;scroll=0;query="";search.setValue("");}return;}
                if(x<px()+34&&y>=py()+64&&y<py()+ph()-28){category=Math.max(0,Math.min(5,(int)((y-py()-64)/28)));scroll=0;return;}
                for(var h:hits)if(inside(x,y,h.x(),h.y(),h.w(),h.h())){pick(h.item());return;}return;
            }
            if(!inside(x,y,screen.width-23,screen.height-17,18,16))closePicker();
        }
        if(cardClick(x,y)){event.setCanceled(true);return;}
        var actor=NativeChat.actorAt(x,y);if(actor!=null){NativeComposer.insertMention(actor);event.setCanceled(true);return;}
        if(NativeChat.revealAt(x,y))event.setCanceled(true);
    }
    @SubscribeEvent public static void scroll(ScreenEvent.MouseScrolled.Pre event){if(open&&MC.screen==screen&&inside(event.getMouseX(),event.getMouseY(),px(),py(),pw(),ph())){scroll=Math.max(0,scroll+(event.getScrollDelta()<0?1:-1));event.setCanceled(true);}}
    @SubscribeEvent(priority=EventPriority.HIGH) public static void keys(ScreenEvent.KeyPressed.Pre event){if(!open||!(event.getScreen() instanceof ChatScreen))return;if(event.getKeyCode()==256){closePicker();screen.setFocused(input);event.setCanceled(true);}else if(event.getKeyCode()==257){var all=filtered();if(!all.isEmpty())pick(all.get(0));event.setCanceled(true);}}
    private static boolean cardClick(double x,double y){
        if(NativeComposer.selected()==null&&!NativeComposer.validating())return false;int cy=screen.height-126;
        if(inside(x,y,134,cy+4,18,18)){NativeComposer.removeStaged();return true;}
        if(inside(x,y,112,cy+4,18,18)){NativeComposer.toggleSpoiler();return true;}
        if(inside(x,y,8,cy+26,144,72)&&NativeComposer.isSpoiler()){NativeComposer.togglePreview();return true;}return false;
    }
    @SubscribeEvent(priority=EventPriority.HIGH) public static void shiftChat(CustomizeGuiOverlayEvent.Chat event){if(MC.screen instanceof ChatScreen&&(NativeComposer.selected()!=null||NativeComposer.validating()))event.setPosY(event.getPosY()-(int)(112*MC.gui.getChat().getScale()));}
    @SubscribeEvent public static void render(ScreenEvent.Render.Post event){
        if(!(event.getScreen() instanceof ChatScreen owner))return;screen=owner;var g=event.getGuiGraphics();textures.begin();
        try{
            card(g,event.getMouseX(),event.getMouseY());if(!open)return;
            int x=px(),y=py(),w=pw(),h=ph();g.fill(x,y,x+w,y+h,0xff2b2d31);g.fill(x,y,x+w,y+30,0xff232428);
            String[] tabs={"GIF","貼圖","表情符號"};for(int i=0;i<3;i++){if(i==tab)g.fill(x+8+i*64,y+5,x+66+i*64,y+26,0xff404249);g.drawString(MC.font,tabs[i],x+16+i*64,y+11,0xffeeeeee,false);}
            search.setX(x+10);search.setY(y+35);search.setWidth(w-20);search.visible=true;search.active=true;search.render(g,event.getMouseX(),event.getMouseY(),event.getPartialTick());
            g.fill(x,y+61,x+32,y+h-27,0xff232428);String[] cats={"☺","♞","♨","♫","❤","D"};for(int i=0;i<6;i++){if(i==category)g.fill(x+3,y+64+i*28,x+29,y+89+i*28,0xff4e5058);g.drawString(MC.font,cats[i],x+11,y+72+i*28,0xffb5bac1,false);}
            g.fill(x,y+h-27,x+w,y+h,0xff1e1f22);hits.clear();var all=filtered();Item hover=null;
            if(tab==1){g.drawString(MC.font,"尚未連接貼圖來源",x+48,y+85,0xffb5bac1,false);}else{
                int cell=tab==0?80:28,columns=Math.max(1,(w-42)/cell),rows=Math.max(1,(h-96)/cell),maxScroll=Math.max(0,(all.size()+columns-1)/columns-rows);scroll=Math.min(scroll,maxScroll);
                for(int row=0;row<rows;row++)for(int col=0;col<columns;col++){
                    int index=(row+scroll)*columns+col;if(index>=all.size())continue;var item=all.get(index);int ix=x+38+col*cell,iy=y+65+row*cell;var hit=new Hit(ix,iy,cell-2,cell-2,item);hits.add(hit);
                    if(inside(event.getMouseX(),event.getMouseY(),ix,iy,cell-2,cell-2)){g.fill(ix,iy,ix+cell-2,iy+cell-2,0xff4e5058);hover=item;}
                    if(item.media()!=null)draw(g,item.media(),ix+3,iy+3,cell-8,cell-8);else{g.pose().pushPose();g.pose().translate(ix+3,iy+4,0);g.pose().scale(2,2,1);g.drawString(MC.font,item.glyph(),0,0,0xffffcf55,false);g.pose().popPose();}
                }
            }
            if(hover!=null)g.drawString(MC.font,MC.font.plainSubstrByWidth(hover.name()+" · "+hover.source(),w-16),x+8,y+h-18,0xffdbdee1,false);else g.drawString(MC.font,tab==0?"本機示範 · 無遠端 GIF 搜尋":tab==1?"貼圖來源不可用":"選擇表情 · 本機／可用伺服器來源",x+8,y+h-18,0xffb5bac1,false);
        }finally{textures.end();}
    }
    private static void card(GuiGraphics g,int mx,int my){
        var a=NativeComposer.selected();if(a==null&&!NativeComposer.validating())return;int y=screen.height-126;g.fill(4,y,156,y+104,0xff2b2d31);g.fill(8,y+26,152,y+98,0xff1e1f22);
        if(a==null)g.drawString(MC.font,"正在驗證圖片…",16,y+55,0xffb5bac1,false);
        else if(NativeComposer.previewVisible())draw(g,a,8,y+26,144,72);else{g.drawCenteredString(MC.font,"暴雷內容",80,y+51,0xffeeeeee);g.drawCenteredString(MC.font,"點擊檢視",80,y+65,0xffb5bac1);}
        g.drawString(MC.font,a==null?"圖片":MC.font.plainSubstrByWidth(a.name(),96),10,y+10,0xffeeeeee,false);
        g.fill(112,y+4,130,y+22,NativeComposer.isSpoiler()?0xff5865f2:0xff404249);g.drawString(MC.font,"◉",117,y+9,0xffeeeeee,false);g.fill(134,y+4,152,y+22,0xff404249);g.drawString(MC.font,"×",140,y+9,0xffff6666,false);
        if(inside(mx,my,112,y+4,18,18))g.renderTooltip(MC.font,Component.literal("暴雷內容"),mx,my);else if(inside(mx,my,134,y+4,18,18))g.renderTooltip(MC.font,Component.literal("移除待發圖片"),mx,my);
    }
    private static void draw(GuiGraphics g,Attachment a,int x,int y,int w,int h){double scale=Math.min(w/(double)a.media().width(),h/(double)a.media().height());int dw=(int)(a.media().width()*scale),dh=(int)(a.media().height()*scale);var t=textures.frame(a.digest(),a.media(),(System.nanoTime()-started)/1_000_000);g.blit(t,x+(w-dw)/2,y+(h-dh)/2,dw,dh,0,0,a.media().width(),a.media().height(),a.media().width(),a.media().height());}
    private static final class Smiley extends AbstractButton {
        Smiley(int x,int y){super(x,y,18,16,Component.literal("表情、GIF 與貼圖"));setTooltip(Tooltip.create(getMessage()));}
        protected void updateWidgetNarration(net.minecraft.client.gui.narration.NarrationElementOutput output){output.add(net.minecraft.client.gui.narration.NarratedElementType.TITLE,getMessage());}
        public void onPress(){if(open)closePicker();else openPicker();}
        protected void renderWidget(GuiGraphics g,int mx,int my,float partial){g.blit(SMILEY,getX(),getY(),18,16,7,7,21,21,35,33);}
    }
}
