package dev.discraft.core;

import javax.swing.*;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** Independent Swing acceptance harness, explicitly NOT a Minecraft UI test. */
public final class PreviewHarness {
    private static final AtomicReference<Throwable> failure = new AtomicReference<>();
    private static final Set<Integer> paintedFrames = Collections.synchronizedSet(new HashSet<>());
    private static JFrame window;
    private static ChatSession session;
    private static Attachment pending;
    private static long start;
    private static int currentFrame;
    private static javax.swing.Timer animation;
    public static void main(String[] args) throws Exception {
        Path evidence = Path.of(args.length > 0 ? args[0] : "build/evidence"); Files.createDirectories(evidence);
        SwingUtilities.invokeAndWait(() -> {
            try { openAndSend(); } catch (Throwable e) { failure.set(e); }
        });
        if (failure.get() != null) throw new AssertionError("Viewer setup", failure.get());
        try {
            var robot = new Robot();
            BufferedImage first = captureFrame(robot, 0), second = captureFrame(robot, 1);
            ImageIO.write(first, "png", evidence.resolve("preview-frame0.png").toFile());
            ImageIO.write(second, "png", evidence.resolve("preview-frame1.png").toFile());
            captureFrame(robot, 2);
            int changed = 0;
            for (int y=0;y<first.getHeight();y++) for(int x=0;x<first.getWidth();x++) if(first.getRGB(x,y)!=second.getRGB(x,y)) changed++;
            if(changed < 100 || !paintedFrames.containsAll(Set.of(0,1,2))) throw new AssertionError("GIF did not visibly animate: " + changed);
            if(session.snapshot().size()!=6) throw new AssertionError("Expected two fixtures and two outbound/peer round trips");
            Files.writeString(evidence.resolve("preview-result.txt"), "PASS independent Swing harness; NOT Minecraft\nPNG and GIF attach/send buttons executed\n6 transcript messages = 2 inbound fixtures + 2 outbound + 2 peer replies\nGIF rendered frames: " + new TreeSet<>(paintedFrames) + "\nChanged screenshot pixels: " + changed + "\n");
            System.out.println(Files.readString(evidence.resolve("preview-result.txt")));
        } finally {
            SwingUtilities.invokeAndWait(() -> { animation.stop(); session.close(); window.dispose(); });
        }
    }
    private static BufferedImage captureFrame(Robot robot, int index) throws Exception {
        long deadline = System.nanoTime() + 3_000_000_000L;
        while(System.nanoTime()<deadline) {
            final boolean[] ready = {false};
            final Rectangle[] bounds = {null};
            SwingUtilities.invokeAndWait(() -> { ready[0] = currentFrame==index; bounds[0]=window.getBounds(); });
            if(ready[0]) return robot.createScreenCapture(bounds[0]);
            Thread.sleep(10);
        }
        throw new AssertionError("Animation frame timeout: " + index);
    }
    private static void openAndSend() throws Exception {
        session = new ChatSession(new MockTransport(), ChatSession.Permissions.preview(), MockTransport.EMOJI,
                () -> System.nanoTime()/1_000_000);
        session.poll();
        window = new JFrame("DisCraft independent preview • LOCAL MOCK • NOT Minecraft");
        window.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        var composer = new JTextField(28);
        var png = new JButton("Attach fixture PNG"); var gif = new JButton("Attach fixture GIF"); var send = new JButton("Send to local mock");
        png.addActionListener(e -> { try { pending=Attachment.fromResource("landscape.png"); } catch(Exception x) { failure.set(x); } });
        gif.addActionListener(e -> { try { pending=Attachment.fromResource("dance.gif"); } catch(Exception x) { failure.set(x); } });
        send.addActionListener(e -> {
            try { session.send(composer.getText(), List.of(pending)); session.poll(); composer.setText(""); pending=null; }
            catch(Exception x) { failure.set(x); }
        });
        var toolbar = new JPanel(); toolbar.add(composer); toolbar.add(png); toolbar.add(gif); toolbar.add(send);
        var fixture = Attachment.fromResource("dance.gif");
        var icon = Attachment.fromResource("emoji.png");
        start = System.nanoTime();
        var canvas = new JPanel() {
            @Override public void paintComponent(Graphics graphics) {
                super.paintComponent(graphics);
                var g=(Graphics2D)graphics; g.setColor(new Color(0x152033)); g.fillRect(0,0,getWidth(),getHeight());
                g.setFont(new Font("SansSerif",Font.BOLD,17)); g.setColor(new Color(0xa6c9ff));
                g.drawString("DisCraft | LOCAL MOCK | Independent Java viewer (not Minecraft)",20,28);
                int i=0;
                long elapsed=(System.nanoTime()-start)/1_000_000;
                currentFrame=fixture.media().frameIndex(elapsed); paintedFrames.add(currentFrame);
                for(var message:session.snapshot()) {
                    int x=16+(i%2)*440,y=48+(i/2)*198; i++;
                    g.setColor(new Color(message.origin()==Message.Origin.CLIENT?0x2c3d59:0x233247));g.fillRoundRect(x,y,424,184,12,12);
                    g.setColor(new Color(0x92c5ff));g.setFont(new Font("SansSerif",Font.BOLD,14));g.drawString(message.author(),x+12,y+22);
                    g.setColor(Color.WHITE);g.setFont(new Font("SansSerif",Font.PLAIN,13));
                    String plain=Content.plain(message.content());g.drawString(plain.substring(0,Math.min(49,plain.length())),x+12,y+46);
                    g.drawImage(icon.media().frames().get(0).image(),x+12,y+58,24,24,null);
                    for(var media:message.attachments()) {
                        var decoded=media.media();g.drawImage(decoded.frames().get(decoded.frameIndex(elapsed)).image(),x+52,y+58,192,96,null);
                        g.drawString(media.name()+" | "+decoded.frames().size()+" frame(s)",x+12,y+173);
                    }
                }
            }
        };
        canvas.setPreferredSize(new Dimension(900,640));window.add(canvas,BorderLayout.CENTER);window.add(toolbar,BorderLayout.SOUTH);
        window.pack();window.setLocation(20,20);window.setVisible(true);
        composer.setText("UI send image 世界 :discraft:"); png.doClick(); send.doClick();
        composer.setText("UI send animated GIF 😀 :dance:"); gif.doClick(); send.doClick();
        if(failure.get()!=null)throw new AssertionError(failure.get());
        animation=new javax.swing.Timer(15,e->canvas.repaint());animation.start();
    }
}
