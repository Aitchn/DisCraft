package dev.discraft.forge.smoke;

import com.mojang.blaze3d.platform.NativeImage;
import dev.discraft.forge.DisCraft;
import net.minecraft.client.*;
import net.minecraft.client.gui.*;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.*;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.*;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import java.nio.file.*;
import java.util.concurrent.CompletableFuture;

/** Native client comparison only: original cached resources, no server connection or Discord. */
@Mod.EventBusSubscriber(modid=DisCraft.ID,value=Dist.CLIENT)
public final class VanillaItemAcceptance {
    private static int phase,ticks;private static boolean done;private static CompletableFuture<Void> reload;
    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event){
        if(!Boolean.getBoolean("discraft.vanillaItemsSmoke")||done||event.phase!=TickEvent.Phase.END)return;var mc=Minecraft.getInstance();
        try{
            if(phase==0){
                if(mc.screen instanceof AccessibilityOnboardingScreen){mc.screen.children().stream().filter(w->w instanceof Button b&&b.getMessage().getString().equals("Continue")).map(w->(Button)w).findFirst().ifPresent(Button::onPress);return;}
                if(!(mc.screen instanceof TitleScreen)||mc.getOverlay()!=null)return;
                mc.getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);mc.options.guiScale().set(2);mc.resizeDisplay();mc.options.languageCode="zh_tw";mc.getLanguageManager().setSelected("zh_tw");reload=mc.reloadResourcePacks();phase=1;return;
            }
            if(phase==1){if(!reload.isDone()||mc.getOverlay()!=null)return;reload.join();
                if(!new ItemStack(Items.DIAMOND_SWORD).getHoverName().getString().equals("鑽石劍")||!new ItemStack(Items.STONE).getHoverName().getString().equals("石頭"))throw new AssertionError("Native Minecraft Traditional Chinese names differ from headless labels");
                mc.setScreen(new Comparison());phase=2;return;
            }
            if(phase==2&&++ticks>30){mc.getToasts().clear();Path out=Path.of(System.getProperty("discraft.vanillaItemsOutput"));Files.createDirectories(out);
                try(var image=Screenshot.takeScreenshot(mc.getMainRenderTarget())){if(image.getWidth()!=1100||image.getHeight()!=800||(int)mc.getWindow().getGuiScale()!=2)throw new AssertionError("Unexpected native framebuffer size or GUI scale");image.writeToFile(out.resolve("native-vs-headless-1100x800.png"));}
                Files.writeString(out.resolve("client-result.txt"),"PASS Actual Minecraft 1.20.1 native item renderer comparison: diamond sword, stone, native zh_tw labels; original 1100x800 framebuffer, GUI scale 2; no server or Discord connection\n");done=true;mc.stop();
            }
        }catch(Throwable failed){try{Files.writeString(Path.of(System.getProperty("discraft.vanillaItemsOutput")).resolve("client-result.txt"),"FAIL "+failed.getClass().getSimpleName()+": "+failed.getMessage()+"\n");}catch(Exception ignored){}done=true;mc.stop();}
    }
    private static final class Comparison extends Screen {
        private final ResourceLocation[] cards=new ResourceLocation[2];
        Comparison(){super(Component.literal("Original vanilla assets — headless/native comparison"));}
        @Override protected void init(){try{String[] names={"diamond-sword","stone"};Path input=Path.of(System.getProperty("discraft.vanillaItemsCards"));for(int i=0;i<2;i++)try(var stream=Files.newInputStream(input.resolve("vanilla-"+names[i]+".png"))){cards[i]=minecraft.getTextureManager().register("discraft-vanilla-evidence-"+i,new DynamicTexture(NativeImage.read(stream)));}}catch(Exception rejected){throw new IllegalStateException("Missing local rendered evidence",rejected);}}
        @Override public void render(GuiGraphics g,int mouseX,int mouseY,float partial){
            g.fill(0,0,width,height,0xff151922);g.drawString(font,"Headless PNG (original pixels)",30,14,0xffffff,false);g.drawString(font,"Native Minecraft (icon 4x)",290,14,0xffffff,false);
            Item[] items={Items.DIAMOND_SWORD,Items.STONE};for(int i=0;i<2;i++){int y=45+i*167;g.blit(cards[i],30,y,144,128,0,0,288,256,288,256);g.pose().pushPose();g.pose().translate(320,y+10,0);g.pose().scale(4,4,4);g.renderItem(new ItemStack(items[i]),0,0);g.pose().popPose();g.drawString(font,new ItemStack(items[i]).getHoverName(),320,y+90,0xffffff,false);}
            g.drawString(font,"Local vanilla 1.20.1 assets / zh_tw; software lighting is approximate",14,height-20,0xbbc9dd,false);
        }
        @Override public void removed(){for(var card:cards)if(card!=null)minecraft.getTextureManager().release(card);}
    }
}
