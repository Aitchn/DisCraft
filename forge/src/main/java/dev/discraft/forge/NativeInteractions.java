package dev.discraft.forge;

import dev.discraft.core.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.*;
import net.minecraftforge.eventbus.api.*;
import net.minecraftforge.fml.common.Mod;
import java.util.*;

/** Native-chat popup/card prototype. Server-owned bounded guild catalog; no automatic send. */
@Mod.EventBusSubscriber(modid=DisCraft.ID,value=Dist.CLIENT)
public final class NativeInteractions {
    private static final Minecraft MC=Minecraft.getInstance();
    private static final TextureCache textures=new TextureCache();
    private static final long started=System.nanoTime();
    private static Attachment demoGif;
    private static boolean open;private static int tab=2,category,scroll,guildFilter;private static String query="";
    private static EditBox search,input;private static ChatScreen screen;
    private static Smiley smiley;private static int inputWidth,appliedInputWidth;
    private record Item(String glyph,String name,String source,String value,Attachment media,boolean custom,boolean file,String id,boolean enabled) {}
    private record Hit(int x,int y,int w,int h,Item item) {}
    private static final List<Hit> hits=new ArrayList<>();
    private static String builtinKey="";private static List<Item> builtinItems=List.of();
    static void attach(ChatScreen owner,ScreenEvent.Init.Post event,EditBox nativeInput){
        screen=owner;input=nativeInput;hits.clear();inputWidth=input.getWidth();appliedInputWidth=inputWidth;
        smiley=new Smiley(0,0);syncInput();event.addListener(smiley);
        search=new EditBox(UiText.font(),px()+10,py()+35,pw()-20,20,Component.literal("搜尋表情"));search.setHint(Component.literal("搜尋"));search.setMaxLength(80);search.setValue(query);search.setResponder(s->{query=s;scroll=0;});search.visible=open;search.active=open;event.addListener(search);
    }
    static void reset(){ClientGuildEmoji.visible(List.of());open=false;query="";hits.clear();textures.close();screen=null;search=null;}
    static void closePicker(){ClientGuildEmoji.visible(List.of());open=false;if(search!=null){search.visible=false;search.active=false;}if(screen!=null&&MC.screen==screen&&input!=null){input=NativeComposer.input(screen);screen.setFocused(input);input.setFocused(true);}}
    public static void openPicker(){if(MC.screen instanceof ChatScreen){open=true;if(search!=null){search.visible=true;search.active=true;screen.setFocused(search);search.setFocused(true);}}}
    private static int clamp(int value,int low,int high){return Math.max(low,Math.min(Math.max(low,high),value));}
    private static void syncInput(){if(input==null||screen==null||smiley==null)return;if(input.getWidth()!=appliedInputWidth)inputWidth=input.getWidth();appliedInputWidth=Math.max(40,inputWidth-26);input.setWidth(appliedInputWidth);smiley.setX(clamp(input.getX()+inputWidth-23,0,screen.width-18));smiley.setY(clamp(input.getY()-5,0,screen.height-16));}
    private static int px(){return clamp(input.getX()+inputWidth-pw()-5,4,screen.width-pw()-4);}private static int pw(){return Math.min(350,screen.width-8);}
    private static int ph(){int available=Math.max(input.getY()-14,screen.height-input.getY()-input.getHeight()-8);return Math.min(Math.min(260,screen.height-28),Math.max(160,available));}
    private static int categoryStep(){return Math.max(9,Math.min(28,(ph()-92)/6));}
    private static int py(){int above=input.getY()-10-ph();return clamp(above>=4?above:input.getY()+input.getHeight()+8,4,screen.height-ph()-4);}
    private static int cardX(){return clamp(input.getX(),4,screen.width-156);}
    private static int cardY(){int above=input.getY()-114;return clamp(above>=4?above:input.getY()+input.getHeight()+8,4,screen.height-108);}
    private static boolean inside(double x,double y,int a,int b,int w,int h){return x>=a&&x<a+w&&y>=b&&y<b+h;}
    private static List<Item> items(){
        var list=new ArrayList<Item>();
        if(tab==1)return list;
        if(tab==0){if(!ClientGuildEmoji.snapshot().fixture())return list;try{if(demoGif==null)demoGif=Attachment.fromResource("dance.gif");list.add(new Item("","dance.gif","本機示範 GIF（非遠端搜尋）","",demoGif,false,true,"",true));}catch(Exception ignored){}return list;}
        if(category==5){
            var snapshot=ClientGuildEmoji.snapshot();
            for(var entry:snapshot.entries()){
                if(guildFilter==1&&entry.animated()||guildFilter==2&&!entry.animated())continue;
                String source=snapshot.fixture()?"TEST 示範（非 Discord）":"Discord · "+(entry.animated()?"動畫":"靜態");
                if(!entry.available())source+=" · 不可用";else if(!entry.permitted())source+=" · 角色限制";
                String name=":"+entry.name()+":";if(snapshot.entries().stream().filter(e->e.name().equalsIgnoreCase(entry.name())).count()>1)name+=" #"+entry.id().substring(entry.id().length()-6);
                list.add(new Item("",name,source,entry.markup(),ClientGuildEmoji.preview(entry.id()),true,false,entry.id(),entry.usable()));
            }
        }
        return list;
    }
    private static List<Item> filtered(){String q=query.toLowerCase(Locale.ROOT);if(tab==2&&category!=5){String key=category+"\n"+q;if(!key.equals(builtinKey)){builtinKey=key;builtinItems=TwemojiCatalog.entries().stream().filter(e->q.isBlank()?e.category()==category:(e.name()+" "+e.value()).toLowerCase(Locale.ROOT).contains(q)).map(e->new Item(e.value(),e.name(),"Twemoji "+TwemojiCatalog.VERSION,e.value(),null,false,false,"",true)).toList();}return builtinItems;}return items().stream().filter(i->q.isBlank()||(i.name()+i.source()+i.glyph()).toLowerCase(Locale.ROOT).contains(q)).toList();}
    private static void pick(Item item){
        if(!item.id().isEmpty()){
            var snapshot=ClientGuildEmoji.snapshot();var current=snapshot.find(item.id());if(current==null||!current.usable())return;
            NativeComposer.insertEmoji(snapshot.fixture()?":"+current.name()+":":current.markup(),true);closePicker();return;
        }
        if(item.file()){NativeComposer.stage(()->item.media());}else NativeComposer.insertEmoji(item.value(),item.custom());closePicker();screen.setFocused(input);input.setFocused(true);}
    @SubscribeEvent(priority=EventPriority.HIGH) public static void mouse(ScreenEvent.MouseButtonPressed.Pre event){
        if(!(event.getScreen() instanceof ChatScreen)||event.getButton()!=0)return;double x=event.getMouseX(),y=event.getMouseY();
        syncInput();
        if(open){
            if(inside(x,y,px(),py(),pw(),ph())){
                event.setCanceled(true);
                if(inside(x,y,px()+10,py()+35,pw()-20,20)){search.mouseClicked(x,y,0);screen.setFocused(search);return;}
                if(tab==2&&category==5&&inside(x,y,px()+38,py()+64,pw()-42,16)){guildFilter=Math.max(0,Math.min(2,(int)((x-px()-38)/65)));scroll=0;return;}
                if(y<py()+29){int t=(int)((x-px()-10)/64);if(t>=0&&t<3){tab=t;scroll=0;query="";search.setValue("");}return;}
                if(x<px()+34&&y>=py()+64&&y<py()+ph()-28){category=Math.max(0,Math.min(5,(int)((y-py()-64)/categoryStep())));scroll=0;return;}
                for(var h:hits)if(inside(x,y,h.x(),h.y(),h.w(),h.h())){pick(h.item());return;}return;
            }
            if(!inside(x,y,smiley.getX(),smiley.getY(),18,16))closePicker();
        }
        if(cardClick(x,y)){event.setCanceled(true);return;}
        // A relocated input can overlap chat history; typing controls own that area.
        if(input!=null&&input.isMouseOver(x,y)||smiley!=null&&inside(x,y,smiley.getX(),smiley.getY(),18,16))return;
        var actor=NativeChat.actorAt(x,y);if(actor!=null){NativeComposer.insertMention(actor);event.setCanceled(true);return;}
        if(NativeChat.revealAt(x,y))event.setCanceled(true);
    }
    @SubscribeEvent public static void scroll(ScreenEvent.MouseScrolled.Pre event){if(open&&MC.screen==screen&&inside(event.getMouseX(),event.getMouseY(),px(),py(),pw(),ph())){scroll=Math.max(0,scroll+(event.getScrollDelta()<0?1:-1));event.setCanceled(true);}}
    @SubscribeEvent(priority=EventPriority.HIGH) public static void keys(ScreenEvent.KeyPressed.Pre event){if(!open||!(event.getScreen() instanceof ChatScreen))return;if(event.getKeyCode()==256){closePicker();screen.setFocused(input);event.setCanceled(true);}else if(event.getKeyCode()==257){var all=filtered();if(!all.isEmpty())pick(all.get(0));event.setCanceled(true);}}
    private static boolean cardClick(double x,double y){
        if(NativeComposer.selected()==null&&!NativeComposer.validating())return false;int cy=cardY(),ox=cardX()-4;
        if(inside(x,y,ox+134,cy+4,18,18)){NativeComposer.removeStaged();return true;}
        if(inside(x,y,ox+112,cy+4,18,18)){NativeComposer.toggleSpoiler();return true;}
        if(inside(x,y,ox+8,cy+26,144,72)&&NativeComposer.isSpoiler()){NativeComposer.togglePreview();return true;}return false;
    }
    @SubscribeEvent(priority=EventPriority.HIGH) public static void shiftChat(CustomizeGuiOverlayEvent.Chat event){if(!ChatPlusCompat.custom()&&MC.screen instanceof ChatScreen&&(NativeComposer.selected()!=null||NativeComposer.validating()))event.setPosY(event.getPosY()-(int)(112*MC.gui.getChat().getScale()));}
    @SubscribeEvent public static void render(ScreenEvent.Render.Post event){
        if(!(event.getScreen() instanceof ChatScreen owner))return;screen=owner;syncInput();var g=event.getGuiGraphics();textures.begin();
        try{
            card(g,event.getMouseX(),event.getMouseY());if(!open){ClientGuildEmoji.visible(List.of());return;}
            int x=px(),y=py(),w=pw(),h=ph();g.fill(x,y,x+w,y+h,0xff2b2d31);g.fill(x,y,x+w,y+30,0xff232428);
            String[] tabs={"GIF","貼圖","表情符號"};for(int i=0;i<3;i++){if(i==tab)g.fill(x+8+i*64,y+5,x+66+i*64,y+26,0xff404249);UiText.draw(g,tabs[i],x+16+i*64,y+9,0xffeeeeee);}
            search.setX(x+10);search.setY(y+35);search.setWidth(w-20);search.visible=true;search.active=true;search.render(g,event.getMouseX(),event.getMouseY(),event.getPartialTick());
            g.fill(x,y+61,x+32,y+h-27,0xff232428);String[] cats={"☺","♞","♨","♫","❤","D"};for(int i=0;i<6;i++){if(i==category)g.fill(x+3,y+64+i*categoryStep(),x+29,y+64+(i+1)*categoryStep()-3,0xff4e5058);g.drawString(MC.font,cats[i],x+11,y+64+i*categoryStep()+Math.max(0,(categoryStep()-9)/2),0xffb5bac1,false);}
            g.fill(x,y+h-27,x+w,y+h,0xff1e1f22);hits.clear();var all=filtered();Item hover=null;var visible=new ArrayList<String>();
            boolean guild=tab==2&&category==5;int header=guild?18:0;
            if(guild){String[] filters={"全部","靜態","動畫"};for(int i=0;i<3;i++){if(guildFilter==i)g.fill(x+38+i*65,y+64,x+99+i*65,y+79,0xff4e5058);UiText.draw(g,filters[i],x+43+i*65,y+65,0xffdddddd);}
                if(!ClientGuildEmoji.snapshot().ready())UiText.draw(g,"伺服器表情目錄暫不可用",x+42,y+91,0xffb5bac1);
            }
            if(tab==1){UiText.draw(g,"尚未連接貼圖來源",x+48,y+85,0xffb5bac1);}else if(tab==0&&!ClientGuildEmoji.snapshot().fixture()){UiText.draw(g,"GIF 服務未啟用",x+48,y+85,0xffb5bac1);}else{
                int cell=tab==0?80:28,columns=Math.max(1,(w-42)/cell),rows=Math.max(1,(h-96-header)/cell),maxScroll=Math.max(0,(all.size()+columns-1)/columns-rows);scroll=Math.min(scroll,maxScroll);
                for(int row=0;row<rows;row++)for(int col=0;col<columns;col++){
                    int index=(row+scroll)*columns+col;if(index>=all.size())continue;var item=all.get(index);int ix=x+38+col*cell,iy=y+65+header+row*cell;var hit=new Hit(ix,iy,cell-2,cell-2,item);hits.add(hit);if(!item.id().isEmpty()&&item.enabled())visible.add(item.id());
                    if(inside(event.getMouseX(),event.getMouseY(),ix,iy,cell-2,cell-2)){g.fill(ix,iy,ix+cell-2,iy+cell-2,0xff4e5058);hover=item;}
                    var builtin=item.glyph().isEmpty()?null:TwemojiCatalog.exact(item.glyph());var builtinImage=builtin==null?null:TwemojiCatalog.asset(builtin);if(builtinImage!=null)draw(g,builtinImage,ix+3,iy+3,cell-8,cell-8);else if(item.media()!=null)draw(g,item.media(),ix+3,iy+3,cell-8,cell-8);else if(!item.id().isEmpty()){g.drawCenteredString(MC.font,item.enabled()?"…":"×",ix+cell/2,iy+8,item.enabled()?0xffb5bac1:0xff66686b);}else{g.pose().pushPose();g.pose().translate(ix+3,iy+4,0);g.pose().scale(2,2,1);g.drawString(MC.font,item.glyph(),0,0,0xffffcf55,false);g.pose().popPose();}
                }
            }
            ClientGuildEmoji.visible(visible);
            if(hover!=null)UiText.draw(g,UiText.fit(hover.name()+" · "+hover.source(),w-16),x+8,y+h-20,0xffdbdee1);else UiText.draw(g,UiText.fit(tab==0?(ClientGuildEmoji.snapshot().fixture()?"TEST 本機示範 · 非 GIF 服務":"GIF 服務未啟用"):tab==1?"貼圖來源不可用":(guild?ClientGuildEmoji.snapshot().label()+" · "+all.size()+(ClientGuildEmoji.snapshot().complete()?"":" · 目錄已達上限"):"Twemoji "+TwemojiCatalog.VERSION+" · "+all.size()),w-16),x+8,y+h-20,0xffb5bac1);
        }finally{textures.end();}
    }
    private static void card(GuiGraphics g,int mx,int my){
        var a=NativeComposer.selected();if(a==null&&!NativeComposer.validating())return;int y=cardY(),ox=cardX()-4;g.fill(ox+4,y,ox+156,y+104,0xff2b2d31);g.fill(ox+8,y+26,ox+152,y+98,0xff1e1f22);
        if(a==null)UiText.draw(g,"正在驗證圖片…",ox+16,y+53,0xffb5bac1);
        else if(NativeComposer.previewVisible())draw(g,a,ox+8,y+26,144,72);else{UiText.centered(g,"暴雷內容",ox+80,y+47,0xffeeeeee);UiText.centered(g,"點擊檢視",ox+80,y+64,0xffb5bac1);}
        UiText.draw(g,a==null?"圖片":UiText.fit(a.name(),96),ox+10,y+6,0xffeeeeee);
        g.fill(ox+112,y+4,ox+130,y+22,NativeComposer.isSpoiler()?0xff5865f2:0xff404249);g.drawString(MC.font,"◉",ox+117,y+9,0xffeeeeee,false);g.fill(ox+134,y+4,ox+152,y+22,0xff404249);g.drawString(MC.font,"×",ox+140,y+9,0xffff6666,false);
        // A covered card must not show a tooltip over the active picker.
        if(open&&inside(mx,my,px(),py(),pw(),ph()))return;
        if(a!=null&&inside(mx,my,ox+8,y+2,100,22))UiText.tooltip(g,a.name(),mx,my);else if(inside(mx,my,ox+112,y+4,18,18))UiText.tooltip(g,"暴雷內容",mx,my);else if(inside(mx,my,ox+134,y+4,18,18))UiText.tooltip(g,"移除待發圖片",mx,my);
    }
    private static void draw(GuiGraphics g,Attachment a,int x,int y,int w,int h){double scale=Math.min(w/(double)a.media().width(),h/(double)a.media().height());int dw=(int)(a.media().width()*scale),dh=(int)(a.media().height()*scale);var t=textures.frame(a.digest(),a.media(),(System.nanoTime()-started)/1_000_000);g.blit(t,x+(w-dw)/2,y+(h-dh)/2,dw,dh,0,0,a.media().width(),a.media().height(),a.media().width(),a.media().height());}
    private static final class Smiley extends AbstractButton {
        Smiley(int x,int y){super(x,y,18,16,Component.literal("表情、GIF 與貼圖"));}
        protected void updateWidgetNarration(net.minecraft.client.gui.narration.NarrationElementOutput output){output.add(net.minecraft.client.gui.narration.NarratedElementType.TITLE,getMessage());}
        public void onPress(){if(open)closePicker();else openPicker();}
        protected void renderWidget(GuiGraphics g,int mx,int my,float partial){
            syncInput();isHovered=inside(mx,my,getX(),getY(),width,height);
            // Original geometric icon: no reference screenshot or external texture pixels.
            int cx=getX()+8,cy=getY()+7,color=isHoveredOrFocused()?0xffffffff:0xffb5bac1;
            for(int y=-6;y<=6;y++)for(int x=-6;x<=6;x++){int r=x*x+y*y;if(r>=26&&r<=40)g.fill(cx+x,cy+y,cx+x+1,cy+y+1,color);}
            g.fill(cx-3,cy-2,cx-1,cy,color);g.fill(cx+2,cy-2,cx+4,cy,color);
            for(int x=-3;x<=3;x++){int y=Math.abs(x)==3?1:Math.abs(x)==2?2:3;g.fill(cx+x,cy+y,cx+x+1,cy+y+1,color);}
            if(isHovered())UiText.tooltip(g,getMessage().getString(),mx,my);
        }
    }
}
