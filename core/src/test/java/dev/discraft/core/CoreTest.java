package dev.discraft.core;

import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import javax.imageio.*;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Dependency-free behavior suite; failure always exits nonzero (no test framework download). */
public final class CoreTest {
    private static int checks;
    public static void main(String[] args) throws Exception {
        ItemMarkersTest.run();TwemojiTest.run();unicode(); media(); pipeline(); uploads();
        System.out.println("PASS: " + checks + " behavioral checks (Unicode, media, GIF disposal, policy, pipeline, upload confinement)");
    }
    private static void check(boolean condition, String message) {
        checks++; if (!condition) throw new AssertionError(message);
    }
    @FunctionalInterface interface Checked { void run() throws Exception; }
    private static void rejects(Checked operation, String reason) throws Exception {
        try { operation.run(); } catch (IOException | IllegalArgumentException expected) { checks++; return; }
        throw new AssertionError("Expected rejection: " + reason);
    }
    private static void unicode() throws Exception {
        String text = "Hello 世界 👋 Ω :discraft: :dance: :unknown:";
        var parts = Content.parse(text, MockTransport.EMOJI);
        check(Content.plain(parts).equals(text), "Unicode and aliases round trip");
        check(parts.stream().filter(c -> c instanceof Content.CustomEmoji).count() == 2, "Two known custom emoji parsed");
        check(Content.parse("😀".repeat(2000), Map.of()).size() == 1, "Code point limit accepts supplementary Unicode");
        rejects(() -> Content.parse("😀".repeat(2001), Map.of()), "text limit");
        rejects(() -> Content.parse("\ud83d", Map.of()), "unpaired surrogate");
        rejects(() -> new Content.CustomEmoji("id", "ok", false, "../secret"), "emoji resource traversal");
        rejects(() -> MockTransport.inbound("x", " ", List.of()), "empty message");
    }
    private static void media() throws Exception {
        var png = Attachment.fromResource("landscape.png");
        check(png.media().width() == 192 && png.media().height() == 96, "PNG dimension decode");
        check(!png.media().animated(), "PNG still");
        var gif = Attachment.fromResource("dance.gif");
        check(gif.media().frames().size() == 3, "GIF has three decoded frames");
        check(gif.media().frames().get(0).delayMillis() == 120 && gif.media().frames().get(1).delayMillis() == 220, "GIF timing preserved");
        check(gif.media().frameIndex(119) == 0 && gif.media().frameIndex(120) == 1 && gif.media().frameIndex(340) == 2 && gif.media().frameIndex(660) == 0, "Animation scheduling and looping");
        check(gif.media().frames().get(0).image().getRGB(15,25) != gif.media().frames().get(1).image().getRGB(15,25), "GIF visibly changes");
        byte[] copy = png.bytes(); copy[0] = 0;
        check(png.bytes()[0] != 0, "Encoded media defensive copy");
        rejects(() -> MediaDecoder.decode(new byte[Limits.MEDIA_BYTES + 1]), "byte cap");
        rejects(() -> MediaDecoder.decode("not an image".getBytes()), "malformed payload");
        rejects(() -> MediaDecoder.decode(Arrays.copyOf(gif.bytes(), 10)), "truncated GIF");
        var out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(1025, 1, BufferedImage.TYPE_INT_RGB), "png", out);
        rejects(() -> MediaDecoder.decode(out.toByteArray()), "dimension before pixel decode");
        rejects(() -> MediaDecoder.decode(makeGif(61, 2, 2, false)), "frame count");
        rejects(() -> MediaDecoder.decode(makeGif(9, 1024, 1024, false)), "total GIF pixels");
        var previous = MediaDecoder.decode(makeGif(3, 4, 4, true));
        check(previous.frames().get(0).image().getRGB(0,0) == 0xffff0000, "GIF first full frame");
        check(previous.frames().get(1).image().getRGB(0,0) == 0xff0000ff, "GIF overlay offset");
        check(previous.frames().get(2).image().getRGB(0,0) == 0xffff0000, "GIF restoreToPrevious");
        // Change frame one's disposal from previous (3) to background (2) in encoded control block.
        byte[] background = makeGif(3, 4, 4, true);
        int controls = 0;
        for (int i = 0; i < background.length - 7; i++) if ((background[i]&255)==0x21 && (background[i+1]&255)==0xf9 && background[i+2]==4) {
            if (++controls == 2) { background[i+3] = (byte)((background[i+3] & ~0x1c) | (2 << 2)); break; }
        }
        var restored = MediaDecoder.decode(background);
        check(restored.frames().get(2).image().getRGB(0,0) == 0xffffff00, "GIF opaque background palette disposal");
        rejects(() -> MockTransport.inbound("x", "attachments", Collections.nCopies(5, png)), "attachment count");
        // Four 1024-square, 3-frame images exceed the message-wide decoded budget.
        var large = Attachment.of("large.gif", makeGif(3, 1024, 1024, false));
        rejects(() -> MockTransport.inbound("x", "pixels", Collections.nCopies(3, large)), "aggregate attachment pixel budget");
        var jpeg = new ByteArrayOutputStream(); ImageIO.write(new BufferedImage(8, 4, BufferedImage.TYPE_INT_RGB), "jpeg", jpeg);
        check(Attachment.of("misleading.txt", jpeg.toByteArray()).media().format().equals("jpeg"), "Sniff actual content, not extension");
    }
    private static void pipeline() throws Exception {
        var clock = new AtomicLong();
        var mock = new MockTransport();
        var session = new ChatSession(mock, ChatSession.Permissions.preview(), MockTransport.EMOJI, clock::get);
        check(session.poll() == 2, "Two fixture messages received");
        var sent = session.send("世界 😀 :dance:", List.of(Attachment.fromResource("dance.gif")));
        check(mock.sentCount() == 1, "Send transport executed");
        check(session.poll() == 1, "Distinct peer accepted, own correlation echo suppressed");
        check(session.snapshot().size() == 4, "Two fixtures + outbound + peer reply");
        check(!session.snapshot().get(3).correlationId().equals(sent.correlationId()), "Peer reply has distinct correlation");
        check(session.snapshot().get(3).attachments().get(0).media().animated(), "GIF attachment round trip");
        check(session.poll() == 0, "No repeated inbox deliveries");
        for (int i = 0; i < 4; i++) session.send("burst " + i, List.of());
        rejects(() -> session.send("sixth", List.of()), "rate burst");
        clock.addAndGet(1000); session.send("refilled", List.of());
        check(mock.sentCount() == 6, "One token refilled after second");
        for (int i = 0; i < 120; i++) { clock.addAndGet(1000); session.send("history " + i, List.of()); session.poll(); }
        check(session.snapshot().size() == Limits.HISTORY, "History bounded");
        session.close(); rejects(() -> session.send("closed", List.of()), "closed session");
        var deniedTransport = new MockTransport();
        var denied = new ChatSession(deniedTransport, new ChatSession.Permissions(false, false, false), MockTransport.EMOJI, clock::get);
        rejects(() -> denied.send("denied", List.of()), "text permission");
        rejects(() -> denied.send("", List.of(Attachment.fromResource("emoji.png"))), "media permission");
        check(deniedTransport.sentCount() == 0 && denied.poll() == 0, "Denied capability never sent or received");
        var event = MockTransport.inbound("peer", "replay", List.of());
        Transport replay = new Transport() {
            public void send(Message message) {}
            public List<Message> poll() { return List.of(event, event); }
        };
        var replaySession = new ChatSession(replay, ChatSession.Permissions.preview(), MockTransport.EMOJI, clock::get);
        check(replaySession.poll() == 1 && replaySession.poll() == 0, "Duplicate event IDs suppressed across polls");
        // Supplementary code point exactly at mock truncation boundary must not split a surrogate pair.
        var unicodeSession = new ChatSession(new MockTransport(), ChatSession.Permissions.preview(), MockTransport.EMOJI, clock::get);
        unicodeSession.send("a".repeat(999) + "😀" + "b".repeat(500), List.of());
        check(unicodeSession.poll() == 3, "Mock reply truncates at Unicode code-point boundary");
        denied.close(); replaySession.close(); unicodeSession.close();
    }
    private static void uploads() throws Exception {
        Path root = Files.createTempDirectory("discraft-uploads-");
        Path outside = Files.createTempFile("discraft-outside-", ".png");
        try {
            Files.write(root.resolve("ok.png"), Attachment.fromResource("emoji.png").bytes());
            Files.write(outside, Attachment.fromResource("emoji.png").bytes());
            check(Attachment.fromUpload(root, "ok.png").media().width() == 24, "Confined local file send entry");
            rejects(() -> Attachment.fromUpload(root, "../" + outside.getFileName()), "parent traversal");
            rejects(() -> Attachment.fromUpload(root, outside.toString()), "absolute path");
            Files.createSymbolicLink(root.resolve("link.png"), outside);
            rejects(() -> Attachment.fromUpload(root, "link.png"), "symlink escape");
            rejects(() -> Attachment.fromUpload(root, "missing.png"), "missing file");
            try (var out = Files.newOutputStream(root.resolve("big.png"))) { out.write(new byte[Limits.MEDIA_BYTES + 1]); }
            rejects(() -> Attachment.fromUpload(root, "big.png"), "bounded stream reading");
        } finally {
            try (var children = Files.list(root)) { for (var child : children.toList()) Files.delete(child); }
            Files.delete(root); Files.delete(outside);
        }
    }
    private static byte[] makeGif(int count, int width, int height, boolean disposal) throws Exception {
        var writer = ImageIO.getImageWritersByFormatName("gif").next();
        var out = new ByteArrayOutputStream();
        byte[] red = {(byte)255,(byte)255,0,0}, green = {(byte)255,0,0,(byte)255}, blue = {0,0,(byte)255,0};
        var colors = new IndexColorModel(8,4,red,green,blue);
        try (var stream = new MemoryCacheImageOutputStream(out)) {
            writer.setOutput(stream);
            var streamMetadata = writer.getDefaultStreamMetadata(null);
            var root = new IIOMetadataNode("javax_imageio_gif_stream_1.0");
            var version = new IIOMetadataNode("Version"); version.setAttribute("value", "89a"); root.appendChild(version);
            var screen = new IIOMetadataNode("LogicalScreenDescriptor");
            screen.setAttribute("logicalScreenWidth", ""+width); screen.setAttribute("logicalScreenHeight", ""+height);
            screen.setAttribute("colorResolution", "8"); screen.setAttribute("pixelAspectRatio", "0"); root.appendChild(screen);
            var table = new IIOMetadataNode("GlobalColorTable"); table.setAttribute("sizeOfGlobalColorTable", "4");
            table.setAttribute("backgroundColorIndex", "0"); table.setAttribute("sortFlag", "FALSE");
            for (int i=0;i<4;i++) { var entry = new IIOMetadataNode("ColorTableEntry"); entry.setAttribute("index", ""+i);
                entry.setAttribute("red", ""+(red[i]&255)); entry.setAttribute("green", ""+(green[i]&255)); entry.setAttribute("blue", ""+(blue[i]&255)); table.appendChild(entry); }
            root.appendChild(table); streamMetadata.setFromTree(root.getNodeName(), root);
            writer.prepareWriteSequence(streamMetadata);
            for (int i=0;i<count;i++) {
                int sizeW = disposal && i>0 ? 1 : width, sizeH = disposal && i>0 ? 1 : height;
                var image = new BufferedImage(sizeW,sizeH,BufferedImage.TYPE_BYTE_INDEXED,colors);
                int color = disposal ? (i==0?1:i==1?2:3) : i%4;
                for (int y=0;y<sizeH;y++) for(int x=0;x<sizeW;x++) image.getRaster().setSample(x,y,0,color);
                var metadata = writer.getDefaultImageMetadata(new ImageTypeSpecifier(image),null);
                var imageRoot = (IIOMetadataNode) metadata.getAsTree("javax_imageio_gif_image_1.0");
                var descriptor = (IIOMetadataNode) imageRoot.getElementsByTagName("ImageDescriptor").item(0);
                descriptor.setAttribute("imageLeftPosition", disposal && i==2 ? "1" : "0");
                descriptor.setAttribute("imageTopPosition", "0");
                var control = (IIOMetadataNode) imageRoot.getElementsByTagName("GraphicControlExtension").item(0);
                control.setAttribute("delayTime", "10"); control.setAttribute("disposalMethod", disposal && i==1 ? "restoreToPrevious" : "doNotDispose");
                metadata.setFromTree(imageRoot.getNodeName(),imageRoot);
                writer.writeToSequence(new IIOImage(image,null,metadata),null);
            }
            writer.endWriteSequence();
        } finally { writer.dispose(); }
        return out.toByteArray();
    }
}
