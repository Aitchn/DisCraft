package dev.discraft.core;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.List;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;

/**
 * Bounded APNG reader (Discord animated stickers). ImageIO only exposes the default image, so each frame is rebuilt
 * as a standalone PNG and composited here with the APNG dispose/blend rules. Every limit is checked before any
 * frame is decoded; {@link #tryDecode} returns null for plain PNGs and for anything it cannot safely animate, so the
 * caller falls back to the ordinary (static) PNG path under the same size limits.
 */
final class ApngDecoder {
    private ApngDecoder() {}
    private static final byte[] SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};
    private record Chunk(String type, int offset, int length) {}
    private record Frame(int width, int height, int x, int y, int delayMillis, int dispose, int blend, List<byte[]> data) {}

    static MediaAsset tryDecode(byte[] bytes) {
        try { return decode(bytes); } catch (IOException | RuntimeException notAnimated) { return null; }
    }

    private static MediaAsset decode(byte[] bytes) throws IOException {
        if (bytes.length < 33 || !Arrays.equals(Arrays.copyOf(bytes, 8), SIGNATURE)) return null;
        var buffer = ByteBuffer.wrap(bytes);
        var chunks = new ArrayList<Chunk>();
        int at = 8;
        while (at + 12 <= bytes.length) {
            int length = buffer.getInt(at);
            if (length < 0 || length > bytes.length - at - 12) throw new IOException("Invalid PNG chunk");
            String type = new String(bytes, at + 4, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
            chunks.add(new Chunk(type, at + 8, length));
            at += 12 + length;
            if (type.equals("IEND")) break;
        }
        byte[] header = null, palette = null, transparency = null;
        int declaredFrames = -1, plays = 0;
        boolean sawData = false;
        Frame current = null;
        var frames = new ArrayList<Frame>();
        var defaultData = new ArrayList<byte[]>();
        boolean defaultIsFrame = false;
        for (var chunk : chunks) {
            switch (chunk.type()) {
                case "IHDR" -> { if (chunk.length() != 13) throw new IOException("Invalid IHDR"); header = slice(bytes, chunk); }
                case "PLTE" -> palette = slice(bytes, chunk);
                case "tRNS" -> transparency = slice(bytes, chunk);
                case "acTL" -> {
                    if (sawData || chunk.length() != 8) throw new IOException("Invalid acTL");
                    declaredFrames = buffer.getInt(chunk.offset());
                    plays = buffer.getInt(chunk.offset() + 4);
                }
                case "fcTL" -> {
                    if (chunk.length() != 26 || declaredFrames < 1) throw new IOException("Invalid fcTL");
                    int p = chunk.offset();
                    int w = buffer.getInt(p + 4), h = buffer.getInt(p + 8), x = buffer.getInt(p + 12), y = buffer.getInt(p + 16);
                    int num = buffer.getShort(p + 20) & 0xffff, den = buffer.getShort(p + 22) & 0xffff;
                    int dispose = bytes[p + 24] & 255, blend = bytes[p + 25] & 255;
                    if (w < 1 || h < 1 || x < 0 || y < 0 || dispose > 2 || blend > 1) throw new IOException("Invalid frame control");
                    long millis = den == 0 ? num * 10L : num * 1000L / den;
                    current = new Frame(w, h, x, y, (int) Math.max(20, Math.min(10000, millis)), dispose, blend, new ArrayList<>());
                    if (!sawData) defaultIsFrame = true;
                    frames.add(current);
                    if (frames.size() > Limits.MEDIA_FRAMES) throw new IOException("Frame count");
                }
                case "IDAT" -> {
                    sawData = true;
                    if (defaultIsFrame) current.data().add(slice(bytes, chunk));
                    defaultData.add(slice(bytes, chunk));
                }
                case "fdAT" -> {
                    if (current == null || chunk.length() < 5) throw new IOException("Invalid fdAT");
                    current.data().add(Arrays.copyOfRange(bytes, chunk.offset() + 4, chunk.offset() + chunk.length()));
                }
                default -> { }
            }
        }
        if (header == null || declaredFrames < 2 || frames.size() != declaredFrames || plays < 0 || plays > 65536) return null;
        var hb = ByteBuffer.wrap(header);
        int canvasWidth = hb.getInt(0), canvasHeight = hb.getInt(4);
        if (canvasWidth < 1 || canvasHeight < 1 || canvasWidth > Limits.MEDIA_DIMENSION || canvasHeight > Limits.MEDIA_DIMENSION
                || (long) canvasWidth * canvasHeight * frames.size() > Limits.MEDIA_TOTAL_PIXELS) throw new IOException("APNG bounds");
        for (var frame : frames) {
            if ((long) frame.x() + frame.width() > canvasWidth || (long) frame.y() + frame.height() > canvasHeight || frame.data().isEmpty())
                throw new IOException("Frame outside canvas");
        }
        var canvas = new BufferedImage(canvasWidth, canvasHeight, BufferedImage.TYPE_INT_ARGB);
        var output = new ArrayList<MediaAsset.Frame>();
        for (int i = 0; i < frames.size(); i++) {
            var frame = frames.get(i);
            BufferedImage decoded = readFrame(header, palette, transparency, frame);
            if (decoded.getWidth() != frame.width() || decoded.getHeight() != frame.height()) throw new IOException("Frame size mismatch");
            // Spec: a "previous" disposal on the first frame behaves as "background".
            int dispose = i == 0 && frame.dispose() == 2 ? 1 : frame.dispose();
            BufferedImage before = dispose == 2 ? copy(canvas) : null;
            Graphics2D g = canvas.createGraphics();
            try {
                g.setComposite(frame.blend() == 0 ? AlphaComposite.Src : AlphaComposite.SrcOver);
                g.drawImage(decoded, frame.x(), frame.y(), null);
            } finally { g.dispose(); }
            decoded.flush();
            output.add(new MediaAsset.Frame(copy(canvas), frame.delayMillis()));
            if (dispose == 1) {
                Graphics2D clear = canvas.createGraphics();
                try { clear.setComposite(AlphaComposite.Clear); clear.fillRect(frame.x(), frame.y(), frame.width(), frame.height()); } finally { clear.dispose(); }
            } else if (before != null) { canvas.flush(); canvas = before; }
        }
        canvas.flush();
        return new MediaAsset("png", canvasWidth, canvasHeight, output, plays);
    }

    private static BufferedImage readFrame(byte[] header, byte[] palette, byte[] transparency, Frame frame) throws IOException {
        var png = new ByteArrayOutputStream();
        png.write(SIGNATURE);
        byte[] ihdr = header.clone();
        ByteBuffer.wrap(ihdr).putInt(0, frame.width()).putInt(4, frame.height());
        write(png, "IHDR", ihdr);
        if (palette != null) write(png, "PLTE", palette);
        if (transparency != null) write(png, "tRNS", transparency);
        for (byte[] part : frame.data()) write(png, "IDAT", part);
        write(png, "IEND", new byte[0]);
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(png.toByteArray()))) {
            var readers = ImageIO.getImageReadersByFormatName("png");
            if (!readers.hasNext()) throw new IOException("PNG reader unavailable");
            var reader = readers.next();
            try {
                reader.setInput(input, true, true);
                if (reader.getWidth(0) != frame.width() || reader.getHeight(0) != frame.height()) throw new IOException("Frame size mismatch");
                return reader.read(0);
            } finally { reader.dispose(); }
        }
    }

    private static void write(ByteArrayOutputStream out, String type, byte[] data) throws IOException {
        byte[] name = type.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        out.write(ByteBuffer.allocate(4).putInt(data.length).array());
        out.write(name);
        out.write(data);
        var crc = new CRC32();
        crc.update(name);
        crc.update(data);
        out.write(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
    }

    private static byte[] slice(byte[] bytes, Chunk chunk) { return Arrays.copyOfRange(bytes, chunk.offset(), chunk.offset() + chunk.length()); }

    private static BufferedImage copy(BufferedImage source) {
        var result = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = result.createGraphics();
        try { graphics.setComposite(AlphaComposite.Src); graphics.drawImage(source, 0, 0, null); } finally { graphics.dispose(); }
        return result;
    }
}
