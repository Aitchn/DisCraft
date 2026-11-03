package dev.discraft.core;

import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;

/** Animated PNG (Discord animated sticker) decoding, built from real ImageIO-encoded frames. */
final class ApngTest {
    private ApngTest() {}
    private static int checks;
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
    private static byte[] chunk(String type, byte[] data) throws IOException {
        var out = new ByteArrayOutputStream(); byte[] name = type.getBytes("ISO-8859-1");
        out.write(ByteBuffer.allocate(4).putInt(data.length).array()); out.write(name); out.write(data);
        var crc = new CRC32(); crc.update(name); crc.update(data); out.write(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
        return out.toByteArray();
    }
    private static byte[] png(int w, int h, int argb) throws IOException {
        var image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) image.setRGB(x, y, argb);
        var out = new ByteArrayOutputStream(); ImageIO.write(image, "png", out); return out.toByteArray();
    }
    private static byte[] idat(byte[] png) throws IOException {
        var data = new ByteArrayOutputStream(); var buffer = ByteBuffer.wrap(png); int at = 8;
        while (at + 12 <= png.length) {
            int length = buffer.getInt(at); String type = new String(png, at + 4, 4, "ISO-8859-1");
            if (type.equals("IDAT")) data.write(png, at + 8, length);
            at += 12 + length;
        }
        return data.toByteArray();
    }
    private static byte[] fctl(int seq, int w, int h, int x, int y, int delayMs, int dispose, int blend) throws IOException {
        var b = ByteBuffer.allocate(26); b.putInt(seq).putInt(w).putInt(h).putInt(x).putInt(y).putShort((short) delayMs).putShort((short) 1000).put((byte) dispose).put((byte) blend);
        return chunk("fcTL", b.array());
    }
    /** Frames: full red, then a 2x2 blue patch at (1,1) over it; the second frame's dispose op is configurable. */
    static byte[] apng(int plays, int secondDispose, boolean breakCount) throws IOException {
        var out = new ByteArrayOutputStream();
        out.write(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'});
        byte[] red = png(4, 4, 0xffff0000), blue = png(2, 2, 0xff0000ff);
        var ihdr = ByteBuffer.allocate(13).putInt(4).putInt(4).put((byte) 8).put((byte) 6).put((byte) 0).put((byte) 0).put((byte) 0).array();
        out.write(chunk("IHDR", ihdr));
        out.write(chunk("acTL", ByteBuffer.allocate(8).putInt(breakCount ? 5 : 3).putInt(plays).array()));
        out.write(fctl(0, 4, 4, 0, 0, 100, 0, 0)); out.write(chunk("IDAT", idat(red)));
        var second = ByteBuffer.allocate(4 + idat(blue).length).putInt(1).put(idat(blue)).array();
        out.write(fctl(1, 2, 2, 1, 1, 200, secondDispose, 0)); out.write(chunk("fdAT", second));
        out.write(fctl(2, 4, 4, 0, 0, 50, 0, 1)); out.write(chunk("fdAT", ByteBuffer.allocate(4 + idat(red).length).putInt(3).put(idat(red)).array()));
        out.write(chunk("IEND", new byte[0]));
        return out.toByteArray();
    }
    static void run() throws Exception {
        var media = MediaDecoder.decode(apng(3, 1, false));
        check(media.format().equals("png") && media.animated() && media.frames().size() == 3, "APNG decodes to its animated frames");
        check(media.width() == 4 && media.height() == 4 && media.plays() == 3, "APNG keeps canvas size and finite play count");
        check(media.frames().get(0).delayMillis() == 100 && media.frames().get(1).delayMillis() == 200 && media.frames().get(2).delayMillis() == 50, "APNG frame delays preserved");
        check(media.frames().get(0).image().getRGB(1, 1) == 0xffff0000 && media.frames().get(1).image().getRGB(1, 1) == 0xff0000ff && media.frames().get(1).image().getRGB(0, 0) == 0xffff0000, "APNG sub-rectangle frame composites over the previous canvas");
        check(media.frames().get(2).image().getRGB(1, 1) == 0xffff0000, "APNG frame 3 repaints the full canvas after background disposal");
        var restored = MediaDecoder.decode(apng(0, 2, false));
        check(restored.plays() == 0 && restored.frames().get(2).image().getRGB(1, 1) == 0xffff0000, "APNG infinite loop and previous-disposal decode");
        var staticFallback = MediaDecoder.decode(apng(0, 0, true));
        check(!staticFallback.animated() && staticFallback.frames().size() == 1, "Inconsistent APNG frame count falls back to the static default image under the same limits");
        var fixture = Attachment.fromResource("sticker-apng.png");
        check(fixture.media().animated() && fixture.media().frames().size() == 3 && fixture.bytes().length < 2048, "Bundled APNG sticker fixture decodes as an animation");
        var plain = MediaDecoder.decode(png(8, 8, 0xff00ff00));
        check(!plain.animated() && plain.width() == 8, "Plain PNG still decodes as a single frame");
        byte[] truncated = Arrays.copyOf(apng(0, 1, false), 120);
        try { MediaDecoder.decode(truncated); check(false, "Truncated APNG must not decode"); } catch (IOException expected) { check(true, "Truncated APNG rejected"); }
        System.out.println("PASS: " + checks + " APNG checks");
    }
}
