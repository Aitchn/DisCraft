package dev.discraft.forge;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Draft-only controls: saving is explicit; Escape/Cancel never changes live preferences. */
public final class ClientSettingsScreen extends Screen {
    private final Screen parent;
    private boolean screenshot,mention;
    private Component error=Component.empty();
    public ClientSettingsScreen(Screen parent){super(Component.translatable("discraft.settings.title"));this.parent=parent;screenshot=ClientPreferences.SCREENSHOT_CLIPBOARD.get();mention=ClientPreferences.MENTION_SOUND.get();}
    private Component label(String key,boolean value){return Component.translatable(key).append(": ").append(Component.translatable(value?"options.on":"options.off"));}
    @Override protected void init(){
        int w=Math.min(360,width-32),x=(width-w)/2,y=Math.max(42,height/2-50);
        addRenderableWidget(Button.builder(label("discraft.settings.f2",screenshot),b->{screenshot=!screenshot;b.setMessage(label("discraft.settings.f2",screenshot));}).bounds(x,y,w,20).tooltip(Tooltip.create(Component.translatable("discraft.settings.f2.help"))).build());
        addRenderableWidget(Button.builder(label("discraft.settings.mention",mention),b->{mention=!mention;b.setMessage(label("discraft.settings.mention",mention));}).bounds(x,y+28,w,20).tooltip(Tooltip.create(Component.translatable("discraft.settings.mention.help"))).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"),b->save()).bounds(x,y+70,(w-8)/2,20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"),b->onClose()).bounds(x+(w+8)/2,y+70,(w-8)/2,20).build());
    }
    private void save(){
        boolean oldScreenshot=ClientPreferences.SCREENSHOT_CLIPBOARD.get(),oldMention=ClientPreferences.MENTION_SOUND.get();
        try{ClientPreferences.SCREENSHOT_CLIPBOARD.set(screenshot);ClientPreferences.MENTION_SOUND.set(mention);ClientPreferences.SPEC.save();onClose();}
        catch(RuntimeException failed){ClientPreferences.SCREENSHOT_CLIPBOARD.set(oldScreenshot);ClientPreferences.MENTION_SOUND.set(oldMention);error=Component.translatable("discraft.settings.save_failed");}
    }
    @Override public void onClose(){minecraft.setScreen(parent);}
    @Override public void render(GuiGraphics g,int mx,int my,float delta){renderBackground(g);g.drawCenteredString(font,title,width/2,20,0xffffff);g.drawCenteredString(font,Component.translatable("discraft.settings.local"),width/2,Math.max(42,height/2-50)+105,0xaaaaaa);g.drawCenteredString(font,error,width/2,height-24,0xff7777);super.render(g,mx,my,delta);}
}
