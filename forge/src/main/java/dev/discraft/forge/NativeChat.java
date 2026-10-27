package dev.discraft.forge;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.discraft.core.*;
import dev.discraft.forge.bridge.*;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.ChatVisiblity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.*;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.*;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;
import java.lang.reflect.Field;
import java.util.*;

/** Native ChatComponent owns wrapping, scrolling, timestamps and history. Only reserved image cells are overlaid. */
@Mod.EventBusSubscriber(modid=DisCraft.ID,value=Dist.CLIENT)
public final class NativeChat {
    private static final Minecraft MC=Minecraft.getInstance();
    private static final Field LINES=ObfuscationReflectionHelper.findField(ChatComponent.class,"f_93761_");
    private static final Field ALL=ObfuscationReflectionHelper.findField(ChatComponent.class,"f_93760_");
    private static final Field SCROLL=ObfuscationReflectionHelper.findField(ChatComponent.class,"f_93763_");
    private record Tile(String digest,int width,int height,int x,int y,int cell,int band,UUID owner,boolean spoiler) {}
    private record SpoilerHit(double x,double y,double w,double h,UUID owner) {}
    private static final List<SpoilerHit> spoilerHits=new ArrayList<>();
    private static final Set<UUID> revealed=new HashSet<>();
    private static final Map<ClickEvent,ActorRef> nameEvents=new IdentityHashMap<>();
    private static final Map<UUID,List<ClickEvent>> ownedNames=new HashMap<>();
    private record Layout(int width,int band,int cell,int emojiCell) {}
    private static Layout layout;
    private static final Map<UUID,Component> components=new HashMap<>();
    private static final Map<UUID,RelayRecord> records=new HashMap<>();
    private static final Map<Integer,Tile> tiles=new HashMap<>();
    private static final Map<UUID,Set<Integer>> markers=new HashMap<>();
    private static final Map<String,MediaAsset> assets=new HashMap<>();
    private static final TextureCache textures=new TextureCache();
    private static final Map<String,Attachment> unicodeMedia=new LinkedHashMap<>();
    private static final LinkedHashMap<UUID,Component> nativeOriginals=new LinkedHashMap<>();
    private static final Map<UUID,Component> nativeComponents=new HashMap<>();
    private static int nextColor=0x100000,chatX,chatY;
    public static int renderedTiles;
    private static final long started=System.nanoTime();

    public static void reset(){nativeOriginals.clear();nativeComponents.clear();unicodeMedia.clear();TwemojiCatalog.clearAssets();assets.clear();tiles.clear();markers.clear();components.clear();records.clear();layout=null;spoilerHits.clear();revealed.clear();nameEvents.clear();ownedNames.clear();textures.close();NativeComposer.reset();}
    public static void receive(RelayRecord record,Collection<RelayRecord> retained){
        reflow();var ids=new HashSet<UUID>();assets.clear();
        var wanted=new LinkedHashMap<String,TwemojiCatalog.Entry>();
        for(var r:retained){ids.add(r.id());for(var a:r.attachments())assets.put(a.digest(),a.media());for(var a:r.emoji().values())assets.put(a.digest(),a.media());int at=0;for(var item:r.nativeItems()){wantUnicode(r,r.text().substring(at,item.start()),wanted);at=item.end();}wantUnicode(r,r.text().substring(at),wanted);}
        syncUnicode(wanted);ids.addAll(nativeOriginals.keySet());
        markers.entrySet().removeIf(e->{if(ids.contains(e.getKey()))return false;e.getValue().forEach(tiles::remove);return true;});
        revealed.retainAll(ids);ownedNames.entrySet().removeIf(e->{if(ids.contains(e.getKey()))return false;e.getValue().forEach(nameEvents::remove);return true;});components.keySet().retainAll(ids);records.keySet().retainAll(ids);records.put(record.id(),record);
        var component=compose(record);components.put(record.id(),component);MC.gui.getChat().addMessage(component);
    }
    private static Component compose(RelayRecord record){
        var owned=new HashSet<Integer>();markers.put(record.id(),owned);
        var oldNames=ownedNames.remove(record.id());if(oldNames!=null)oldNames.forEach(nameEvents::remove);ownedNames.put(record.id(),new ArrayList<>());
        var header=NativeAuthorHeader.create(record.author(),record.actor());var component=Component.empty().append(header.component());
        if(header.authorClick()!=null){nameEvents.put(header.authorClick(),record.actor());ownedNames.get(record.id()).add(header.authorClick());}
        int at=0;for(var item:record.nativeItems()){
            appendBody(component,record,record.text().substring(at,item.start()),owned);
            if(item.item().count()==0)component.append(Component.literal(record.text().substring(item.start(),item.end())).withStyle(Style.EMPTY.withColor(ChatFormatting.GRAY).withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,Component.translatableWithFallback("discraft.item.empty","Empty hand")))));
            else{
                var stack=new net.minecraft.world.item.ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.get(new ResourceLocation(item.item().itemId())),item.item().count());stack.setTag(item.item().safeTag());
                if(stack.isEmpty())component.append(Component.literal(record.text().substring(item.start(),item.end())).withStyle(ChatFormatting.GRAY));
                else {var name=stack.getHoverName().copy();if(stack.getCount()>1)name.append(Component.literal(" ×"+stack.getCount()));component.append(ComponentUtils.wrapInSquareBrackets(name).withStyle(Style.EMPTY.withColor(name.getStyle().getColor()!=null?name.getStyle().getColor():TextColor.fromLegacyFormat(stack.getRarity().color)).withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_ITEM,new HoverEvent.ItemStackInfo(stack)))));}
            }
            at=item.end();
        }appendBody(component,record,record.text().substring(at),owned);
        for(var a:record.attachments()){
            component.append(Component.literal("\n["+a.name()+"]").withStyle(ChatFormatting.GRAY));
            int available=Math.max(layout.cell(),layout.width()/layout.cell()*layout.cell());
            double scale=Math.min(Math.min(96,available)/(double)a.media().width(),54d/a.media().height());
            int width=Math.max(1,(int)Math.round(a.media().width()*scale)),height=Math.max(1,(int)Math.round(a.media().height()*scale));
            for(int y=0;y<height;y+=layout.band()){component.append("\n");row(component,a,width,height,y,layout.cell(),layout.band(),"\u00a0",owned,record.id(),a.name().startsWith("SPOILER_"));}
        }
        return component;
    }
    static Component emojiLiteral(String text){if(text.indexOf('\ufe0e')<0)return Component.literal(text);var result=Component.empty();int at=0,next;while((next=text.indexOf('\ufe0e',at))>=0){result.append(Component.literal(text.substring(at,next)));result.append(Component.literal("\ufe0e").withStyle(Style.EMPTY.withFont(new ResourceLocation(DisCraft.ID,"selector"))));at=next+1;}return result.append(Component.literal(text.substring(at)));}
    private static void wantUnicode(RelayRecord record,String text,Map<String,TwemojiCatalog.Entry> wanted){for(var part:Content.parse(text,record.catalog()))if(part instanceof Content.Text literal)for(var span:TwemojiCatalog.parts(literal.value()))if(span.emoji()!=null)wanted.putIfAbsent(span.emoji().asset(),span.emoji());}
    private static void appendBody(MutableComponent component,RelayRecord record,String text,Set<Integer> owned){
        for(var part:Content.parse(text,record.catalog())){
            if(part instanceof Content.Text t){for(var span:TwemojiCatalog.parts(t.value())){var image=span.emoji()==null?null:unicodeMedia.get(span.emoji().asset());if(image==null)component.append(emojiLiteral(span.text()));else row(component,image,Math.min(8,layout.emojiCell()),8,0,layout.emojiCell(),8,"\u3000",owned,record.id(),false);}}
            else if(part instanceof Content.CustomEmoji e){var a=record.emoji().get(e.mediaKey());if(a!=null)row(component,a,Math.min(8,layout.emojiCell()),8,0,layout.emojiCell(),8,"\u3000",owned,record.id(),false);else component.append(":"+e.name()+":");}
        }
    }
    private static void syncUnicode(Map<String,TwemojiCatalog.Entry> wanted){
        for(var original:nativeOriginals.values())collectNative(original,wanted,new int[]{512,8192},0,new IdentityHashMap<>());
        unicodeMedia.keySet().retainAll(wanted.keySet());for(var entry:wanted.values())if(!unicodeMedia.containsKey(entry.asset())&&unicodeMedia.size()<TwemojiCatalog.CACHE_ENTRIES){var image=TwemojiCatalog.asset(entry);if(image!=null)unicodeMedia.put(entry.asset(),image);}for(var image:unicodeMedia.values())assets.put(image.digest(),image.media());
    }
    private static boolean collectNative(Component component,Map<String,TwemojiCatalog.Entry> wanted,int[] budget,int depth,IdentityHashMap<Component,Boolean> path){
        if(depth>32||--budget[0]<0||path.put(component,true)!=null)throw new IllegalArgumentException("Native emoji component bounds");
        boolean selector=false;if(component.getContents() instanceof net.minecraft.network.chat.contents.LiteralContents literal){selector=literal.text().indexOf('\ufe0e')>=0;if((budget[1]-=literal.text().length())<0)throw new IllegalArgumentException("Native emoji text bounds");for(var part:TwemojiCatalog.parts(literal.text()))if(part.emoji()!=null)wanted.putIfAbsent(part.emoji().asset(),part.emoji());}
        else if(component.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents tr){if(tr.getArgs().length>32)throw new IllegalArgumentException("Native emoji argument bounds");for(Object arg:tr.getArgs())if(arg instanceof Component nested)selector|=collectNative(nested,wanted,budget,depth+1,path);else if(arg instanceof String text)selector|=collectNative(Component.literal(text),wanted,budget,depth+1,path);}
        for(var sibling:component.getSiblings())selector|=collectNative(sibling,wanted,budget,depth+1,path);path.remove(component);return selector;
    }
    private static Component nativeEmoji(Component source,UUID owner,Set<Integer> owned){return nativeEmoji(source,owner,owned,true);}
    private static Component nativeEmoji(Component source,UUID owner,Set<Integer> owned,boolean images){
        MutableComponent result;
        if(source.getContents() instanceof net.minecraft.network.chat.contents.LiteralContents literal){if(!images)result=Component.literal(literal.text());else{result=Component.empty();for(var part:TwemojiCatalog.parts(literal.text())){var image=!images||part.emoji()==null?null:unicodeMedia.get(part.emoji().asset());if(image==null)result.append(emojiLiteral(part.text()));else row(result,image,Math.min(8,layout.emojiCell()),8,0,layout.emojiCell(),8,"\u3000",owned,owner,false);}}}
        else if(source.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents tr){Object[] args=tr.getArgs().clone();for(int i=0;i<args.length;i++)if(args[i] instanceof Component nested)args[i]=nativeEmoji(nested,owner,owned,images);else if(images&&args[i] instanceof String text)args[i]=nativeEmoji(Component.literal(text),owner,owned,true);result=Component.translatableWithFallback(tr.getKey(),tr.getFallback(),args);}
        else result=MutableComponent.create(source.getContents());
        result.setStyle(source.getStyle());for(var sibling:source.getSiblings())result.append(nativeEmoji(sibling,owner,owned,images));return result;
    }
    @SubscribeEvent(priority=EventPriority.LOWEST) public static void nativeMessage(ClientChatReceivedEvent event){
        // Disguised/server chat can legitimately use System while preserving the vanilla chat UI.
        // Action-bar overlays are a separate surface and never own chat sprite rows.
        if(event instanceof ClientChatReceivedEvent.System system&&system.isOverlay())return;
        var wanted=new LinkedHashMap<String,TwemojiCatalog.Entry>();boolean selector;try{selector=collectNative(event.getMessage(),wanted,new int[]{512,8192},0,new IdentityHashMap<>());}catch(IllegalArgumentException oversized){return;}if(wanted.isEmpty()&&!selector)return;
        reflow();UUID id=UUID.randomUUID();nativeOriginals.put(id,nativeEmoji(event.getMessage(),id,new HashSet<>(),false));while(nativeOriginals.size()>12){UUID first=nativeOriginals.keySet().iterator().next();nativeOriginals.remove(first);nativeComponents.remove(first);var old=markers.remove(first);if(old!=null)old.forEach(tiles::remove);}
        // Rebuild only a bounded union of our retained relay/native assets; no packet or signature is changed.
        assets.keySet().removeIf(key->unicodeMedia.values().stream().anyMatch(image->image.digest().equals(key)));
        for(var record:records.values()){int at=0;for(var item:record.nativeItems()){wantUnicode(record,record.text().substring(at,item.start()),wanted);at=item.end();}wantUnicode(record,record.text().substring(at),wanted);}syncUnicode(wanted);
        var owned=new HashSet<Integer>();markers.put(id,owned);var replacement=nativeEmoji(event.getMessage(),id,owned);nativeComponents.put(id,replacement);event.setMessage(replacement);
    }
    private static void row(MutableComponent text,Attachment a,int width,int height,int y,int cell,int band,String glyph,Set<Integer> owned,UUID owner,boolean spoiler){
        for(int x=0;x<width;x+=cell){
            int color;do{color=nextColor++ & 0xffffff;}while(tiles.containsKey(color));owned.add(color);tiles.put(color,new Tile(a.digest(),width,height,x,y,cell,band,owner,spoiler));
            // NBSP reserves native glyph advance without a visible character or clickable metadata.
            text.append(Component.literal(glyph).withStyle(Style.EMPTY.withFont(Minecraft.DEFAULT_FONT).withColor(color)));
        }
    }
    static ActorRef actorAt(double x,double y){
        var style=MC.gui.getChat().getClickedComponentStyleAt(x-chatX,y-(chatY-MC.getWindow().getGuiScaledHeight()+40)/MC.gui.getChat().getScale());if(style==null)return null;
        var actor=nameEvents.get(style.getClickEvent());if(actor!=null)return actor;
        var hover=style.getHoverEvent();if(hover==null||MC.getConnection()==null)return null;
        var entity=hover.getValue(HoverEvent.Action.SHOW_ENTITY);if(entity==null||entity.type!=net.minecraft.world.entity.EntityType.PLAYER)return null;
        var player=MC.getConnection().getPlayerInfo(entity.id);return player==null?null:new ActorRef(ActorRef.Kind.MINECRAFT,player.getProfile().getId().toString(),player.getProfile().getName());
    }
    static boolean revealAt(double x,double y){for(var hit:spoilerHits)if(x>=hit.x()&&x<hit.x()+hit.w()&&y>=hit.y()&&y<hit.y()+hit.h()){if(!revealed.add(hit.owner()))revealed.remove(hit.owner());return true;}return false;}
    private static void reflow(){
        var chat=MC.gui.getChat();
        var next=new Layout(Math.max(1,(int)(chat.getWidth()/chat.getScale())),Math.max(9,(int)(9*(MC.options.chatLineSpacing().get()+1))),Math.max(1,MC.font.width("\u00a0")),Math.max(1,MC.font.width("\u3000")));
        if(next.equals(layout))return;layout=next;
        // Rewrite only our own components, retaining native order, timestamps, signatures and other messages.
        var owned=new IdentityHashMap<Component,UUID>();components.forEach((id,c)->owned.put(c,id));nativeComponents.forEach((id,c)->owned.put(c,id));boolean changed=false;
        try{
            @SuppressWarnings("unchecked") var messages=(List<GuiMessage>)ALL.get(chat);
            for(int i=0;i<messages.size();i++){
                var message=messages.get(i);var id=owned.get(message.content());if(id==null)continue;
                var old=markers.remove(id);if(old!=null)old.forEach(tiles::remove);
                Component replacement;if(nativeOriginals.containsKey(id)){var fresh=new HashSet<Integer>();markers.put(id,fresh);replacement=nativeEmoji(nativeOriginals.get(id),id,fresh);nativeComponents.put(id,replacement);}else{replacement=compose(records.get(id));components.put(id,replacement);}
                messages.set(i,new GuiMessage(message.addedTime(),replacement,message.signature(),message.tag()));changed=true;
            }
        }catch(IllegalAccessException impossible){throw new IllegalStateException("Native chat reflow failed",impossible);}
        if(changed)chat.rescaleChat();
    }
    @SubscribeEvent(priority=EventPriority.LOWEST) public static void position(CustomizeGuiOverlayEvent.Chat event){chatX=event.getPosX();chatY=event.getPosY();reflow();}
    @SubscribeEvent public static void render(RenderGuiOverlayEvent.Post event){
        if(!event.getOverlay().id().equals(VanillaGuiOverlay.CHAT_PANEL.id()))return;
        renderedTiles=0;spoilerHits.clear();
        if(MC.options.hideGui||MC.options.chatVisibility().get()==ChatVisiblity.HIDDEN||assets.isEmpty())return;
        var chat=MC.gui.getChat();var graphics=event.getGuiGraphics();double scale=chat.getScale();
        try{
            @SuppressWarnings("unchecked") var lines=(List<GuiMessage.Line>)LINES.get(chat);int scroll=SCROLL.getInt(chat);
            int height=(int)(9*(MC.options.chatLineSpacing().get()+1));
            int offset=(int)Math.round(-8*(MC.options.chatLineSpacing().get()+1)+4*MC.options.chatLineSpacing().get());
            int bottom=(int)Math.floor((graphics.guiHeight()-40)/scale);
            boolean focused=MC.screen instanceof ChatScreen;
            final long elapsed=(System.nanoTime()-started)/1_000_000;
            textures.begin();graphics.pose().pushPose();
            graphics.pose().translate(chatX,(chatY-graphics.guiHeight()+40)/scale,0);graphics.pose().scale((float)scale,(float)scale,1);graphics.pose().translate(4,0,150);
            try{
                for(int n=0;n<chat.getLinesPerPage()&&n+scroll<lines.size();n++){
                    var line=lines.get(n+scroll);int age=MC.gui.getGuiTicks()-line.addedTime();
                    double fade=focused?1:Math.pow(Math.max(0,Math.min(1,(1-age/200d)*10)),2);
                    int alpha=(int)(255*fade*(MC.options.chatOpacity().get()*.9+.1));if(alpha<=3)continue;
                    final int y=bottom-n*height+offset;final float opacity=alpha/255f;final int[] x={0};
                    line.content().accept((index,style,point)->{
                        var color=style.getColor();var tile=(point==160||point==0x3000)&&color!=null?tiles.get(color.getValue()):null;
                        if(tile!=null){var a=assets.get(tile.digest());if(a!=null){
                            int dw=Math.min(tile.cell(),tile.width()-tile.x()),dh=Math.min(tile.band(),tile.height()-tile.y());
                            int sx=tile.x()*a.width()/tile.width(),sy=tile.y()*a.height()/tile.height();
                            int sw=Math.max(1,(tile.x()+dw)*a.width()/tile.width()-sx),sh=Math.max(1,(tile.y()+dh)*a.height()/tile.height()-sy);
                            boolean secret=tile.spoiler();
                            if(secret)spoilerHits.add(new SpoilerHit(chatX+scale*(4+x[0]),(chatY-graphics.guiHeight()+40)/scale+scale*y,dw*scale,dh*scale,tile.owner()));
                            if(secret&&!revealed.contains(tile.owner()))graphics.fill(x[0],y,x[0]+dw,y+dh,0xff3d3e45);
                            else{var texture=textures.frame(tile.digest(),a,elapsed);RenderSystem.enableBlend();RenderSystem.setShaderColor(1,1,1,opacity);graphics.blit(texture,x[0],y,dw,dh,sx,sy,sw,sh,a.width(),a.height());renderedTiles++;}
                        }}
                        x[0]+=MC.font.width(Component.literal(new String(Character.toChars(point))).setStyle(style));return true;
                    });
                }
            }finally{RenderSystem.setShaderColor(1,1,1,1);graphics.pose().popPose();textures.end();}
        }catch(IllegalAccessException impossible){throw new IllegalStateException("Native chat access failed",impossible);}
    }
}
