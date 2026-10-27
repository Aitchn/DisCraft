package dev.discraft.core;

import javax.imageio.ImageIO;
import javax.imageio.metadata.IIOMetadata;
import org.w3c.dom.Node;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;

public final class MediaDecoder {
    private MediaDecoder() {}
    public static MediaAsset decode(byte[] bytes) throws IOException {
        if (bytes.length == 0 || bytes.length > Limits.MEDIA_BYTES) throw new IOException("Media byte limit: 4 MiB");
        // Memory-backed stream: never create disk caches for untrusted input.
        try (var input = new javax.imageio.stream.MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException("Supported media: PNG, JPEG, GIF");
            var reader = readers.next();
            try {
                reader.setInput(input, false, false);
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!Set.of("png", "jpeg", "gif").contains(format)) throw new IOException("Unsupported media format");
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (format.equals("gif")) {
                    Node logical = find(tree(reader.getStreamMetadata()), "LogicalScreenDescriptor");
                    if (logical != null) { width = number(logical, "logicalScreenWidth", width); height = number(logical, "logicalScreenHeight", height); }
                }
                dimensions(width, height);
                int count = format.equals("gif") ? reader.getNumImages(true) : 1;
                if (count < 1 || count > Limits.MEDIA_FRAMES || (long) width * height * count > Limits.MEDIA_TOTAL_PIXELS)
                    throw new IOException("Media frame/pixel budget exceeded");
                int background = 0;
                if (format.equals("gif")) {
                    Node table = find(tree(reader.getStreamMetadata()), "GlobalColorTable");
                    int index = number(table, "backgroundColorIndex", 0);
                    for (Node entry = table == null ? null : table.getFirstChild(); entry != null; entry = entry.getNextSibling()) {
                        if (number(entry, "index", -1) == index) background = 0xff000000 | number(entry, "red", 0) << 16
                                | number(entry, "green", 0) << 8 | number(entry, "blue", 0);
                    }
                    Node firstControl = find(tree(reader.getImageMetadata(0)), "GraphicControlExtension");
                    if (attr(firstControl, "transparentColorFlag", "FALSE").equalsIgnoreCase("TRUE")
                            && number(firstControl, "transparentColorIndex", -1) == index) background = 0;
                }
                var canvas = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
                Graphics2D initial = canvas.createGraphics();
                try { initial.setComposite(AlphaComposite.Src); initial.setColor(new Color(background, true)); initial.fillRect(0, 0, width, height); }
                finally { initial.dispose(); }
                var frames = new ArrayList<MediaAsset.Frame>();
                int plays=-1;
                for (int i = 0; i < count; i++) {
                    int fw = reader.getWidth(i), fh = reader.getHeight(i);
                    dimensions(fw, fh);
                    Node metadata = format.equals("gif") ? tree(reader.getImageMetadata(i)) : null;
                    if(format.equals("gif")){int declared=loopPlays(metadata);if(declared>=0){if(plays>=0&&plays!=declared)throw new IOException("Conflicting GIF loop metadata");plays=declared;}}
                    Node descriptor = find(metadata, "ImageDescriptor");
                    int x = number(descriptor, "imageLeftPosition", 0), y = number(descriptor, "imageTopPosition", 0);
                    if (x < 0 || y < 0 || (long)x + fw > width || (long)y + fh > height) throw new IOException("Frame outside canvas");
                    Node control = find(metadata, "GraphicControlExtension");
                    String disposal = attr(control, "disposalMethod", "none");
                    int delay = Math.max(20, Math.min(10000, number(control, "delayTime", 10) * 10));
                    BufferedImage previous = disposal.equals("restoreToPrevious") ? copy(canvas) : null;
                    BufferedImage decoded = reader.read(i); // Dimensions checked before decoder allocates pixels.
                    Graphics2D graphics = canvas.createGraphics();
                    try { graphics.drawImage(decoded, x, y, null); } finally { graphics.dispose(); }
                    decoded.flush();
                    frames.add(new MediaAsset.Frame(copy(canvas), delay));
                    if (disposal.equals("restoreToBackgroundColor")) {
                        graphics = canvas.createGraphics();
                        try { graphics.setComposite(AlphaComposite.Src); graphics.setColor(new Color(background, true)); graphics.fillRect(x, y, fw, fh); } finally { graphics.dispose(); }
                    } else if (previous != null) {canvas.flush();canvas = previous;}
                }
                canvas.flush();
                return new MediaAsset(format, width, height, frames,plays<0?1:plays);
            } finally { reader.dispose(); }
        } catch (IllegalArgumentException | IndexOutOfBoundsException e) { throw new IOException("Invalid media", e); }
    }
    private static int loopPlays(Node metadata)throws IOException {
        var applications=find(metadata,"ApplicationExtensions");int result=-1;
        for(Node entry=applications==null?null:applications.getFirstChild();entry!=null;entry=entry.getNextSibling()){
            String id=attr(entry,"applicationID","")+attr(entry,"authenticationCode","");if(!id.equals("NETSCAPE2.0")&&!id.equals("ANIMEXTS1.0"))continue;
            if(!(entry instanceof javax.imageio.metadata.IIOMetadataNode node)||!(node.getUserObject() instanceof byte[] data)||data.length!=3||data[0]!=1)throw new IOException("Invalid GIF loop metadata");
            int repeat=(data[1]&255)|((data[2]&255)<<8),value=repeat==0?0:repeat+1;
            if(result>=0&&result!=value)throw new IOException("Conflicting GIF loop metadata");result=value;
        }return result;
    }
    private static void dimensions(int w, int h) throws IOException {
        if (w < 1 || h < 1 || w > Limits.MEDIA_DIMENSION || h > Limits.MEDIA_DIMENSION) throw new IOException("Media dimension limit: 1024 x 1024");
    }
    private static BufferedImage copy(BufferedImage source) {
        var result = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = result.createGraphics();
        try { graphics.setComposite(AlphaComposite.Src); graphics.drawImage(source, 0, 0, null); } finally { graphics.dispose(); }
        return result;
    }
    private static Node tree(IIOMetadata metadata) {
        return metadata == null ? null : metadata.getAsTree(metadata.getNativeMetadataFormatName());
    }
    private static Node find(Node node, String name) {
        if (node == null) return null;
        if (name.equals(node.getNodeName())) return node;
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            Node found = find(child, name); if (found != null) return found;
        }
        return null;
    }
    private static String attr(Node node, String name, String fallback) {
        if (node == null || node.getAttributes() == null || node.getAttributes().getNamedItem(name) == null) return fallback;
        return node.getAttributes().getNamedItem(name).getNodeValue();
    }
    private static int number(Node node, String name, int fallback) throws IOException {
        try { return Integer.parseInt(attr(node, name, "" + fallback)); }
        catch (NumberFormatException e) { throw new IOException("Invalid media metadata", e); }
    }
}
