package dev.discraft.core;

import java.awt.image.BufferedImage;
import java.util.List;

/** Decoded, bounded, composited ARGB frames. No remote URL or executable content. */
public record MediaAsset(String format, int width, int height, List<Frame> frames, int plays) {
    public record Frame(BufferedImage image, int delayMillis) {public Frame{if(image==null||delayMillis<1||delayMillis>10000)throw new IllegalArgumentException("Invalid frame");}}
    /** Backward compatible internal constructor; 0 means unlimited plays. */
    public MediaAsset(String format,int width,int height,List<Frame> frames){this(format,width,height,frames,0);}
    public MediaAsset { frames = List.copyOf(frames);if(format==null||width<1||height<1||width>Limits.MEDIA_DIMENSION||height>Limits.MEDIA_DIMENSION||frames.isEmpty()||frames.size()>Limits.MEDIA_FRAMES||(long)width*height*frames.size()>Limits.MEDIA_TOTAL_PIXELS||plays<0||plays>65536)throw new IllegalArgumentException("Invalid media bounds");for(var frame:frames)if(frame.image().getWidth()!=width||frame.image().getHeight()!=height)throw new IllegalArgumentException("Invalid composited frame size"); }
    public boolean animated() { return frames.size() > 1; }
    public int frameIndex(long elapsedMillis) {
        long duration = frames.stream().mapToLong(Frame::delayMillis).sum();
        long elapsed=Math.max(0,elapsedMillis);
        if(plays>0&&elapsed>=duration*plays)return frames.size()-1;
        long at = elapsed % duration;
        for (int i = 0; i < frames.size(); i++) {
            at -= frames.get(i).delayMillis();
            if (at < 0) return i;
        }
        return 0;
    }
}
