package dev.discraft.forge;

import com.mojang.blaze3d.platform.NativeImage;
import dev.discraft.core.MediaAsset;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import java.util.*;

/** Render-thread only. Keep one uploaded frame per asset, release when no longer visible. */
final class TextureCache implements AutoCloseable {
    private record Entry(ResourceLocation location, DynamicTexture texture, int frame, long origin) {}
    private final Map<String, Entry> entries = new HashMap<>();
    private final Map<String,Long> origins=new LinkedHashMap<>(128,.75f,true);
    private final Set<String> visible = new HashSet<>();
    void begin() { visible.clear(); }
    ResourceLocation frame(String key, MediaAsset media, long elapsed) {
        com.mojang.blaze3d.systems.RenderSystem.assertOnRenderThread();
        visible.add(key);
        Entry old = entries.get(key);
        long origin=old!=null?old.origin:media.animated()?origins.computeIfAbsent(key,k->elapsed):elapsed;
        while(origins.size()>128)origins.remove(origins.keySet().iterator().next());
        int frame = media.frameIndex(Math.max(0,elapsed-origin));
        if (old != null && old.frame == frame) return old.location;
        var source = media.frames().get(frame).image();
        if (old != null) {
            write(old.texture.getPixels(), source);
            old.texture.upload();
            entries.put(key, new Entry(old.location, old.texture, frame, origin));
            return old.location;
        }
        var pixels = new NativeImage(media.width(), media.height(), false);
        write(pixels, source);
        var texture = new DynamicTexture(pixels);
        var location = Minecraft.getInstance().getTextureManager().register("discraft_preview", texture);
        entries.put(key, new Entry(location, texture, frame, origin));
        return location;
    }
    private void write(NativeImage target, java.awt.image.BufferedImage source) {
        for (int y = 0; y < source.getHeight(); y++) for (int x = 0; x < source.getWidth(); x++) {
            int argb = source.getRGB(x, y);
            int abgr = (argb & 0xff00ff00) | ((argb >>> 16) & 0xff) | ((argb & 0xff) << 16);
            target.setPixelRGBA(x, y, abgr);
        }
    }
    void end() {
        entries.entrySet().removeIf(entry -> {
            if (visible.contains(entry.getKey())) return false;
            Minecraft.getInstance().getTextureManager().release(entry.getValue().location);
            return true;
        });
    }
    @Override public void close() {
        entries.values().forEach(entry -> Minecraft.getInstance().getTextureManager().release(entry.location));
        entries.clear();origins.clear();
    }
}
