package dev.discraft.core;

import java.awt.image.BufferedImage;
import java.util.List;

/** Decoded, bounded, composited ARGB frames. No remote URL or executable content. */
public record MediaAsset(String format, int width, int height, List<Frame> frames) {
    public record Frame(BufferedImage image, int delayMillis) {}
    public MediaAsset { frames = List.copyOf(frames); }
    public boolean animated() { return frames.size() > 1; }
    public int frameIndex(long elapsedMillis) {
        long duration = frames.stream().mapToLong(Frame::delayMillis).sum();
        long at = Math.floorMod(elapsedMillis, duration);
        for (int i = 0; i < frames.size(); i++) {
            at -= frames.get(i).delayMillis();
            if (at < 0) return i;
        }
        return 0;
    }
}
