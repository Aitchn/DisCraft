package dev.discraft.forge;

import dev.discraft.core.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Local mock preview, available from the title screen or F8. No world/server required. */
public final class DisCraftScreen extends Screen {
    private final Screen parent;
    private final boolean networked;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        var thread = new Thread(task, "DisCraft media worker"); thread.setDaemon(true); return thread;
    });
    private final TextureCache textures = new TextureCache();
    private final Map<String, Attachment> emoji = new HashMap<>();
    private final long started = System.nanoTime();
    private ChatSession session;
    private EditBox text, path;
    private Attachment pending;
    // UI-thread revisions distinguish in-flight snapshots from subsequent user edits.
    private long attachmentRevision, draftRevision;
    private String status = "Loading bounded local fixtures…";
    private boolean busy = true, closed;
    private int scroll, contentHeight;
    private boolean scrollToBottom;
    private Path uploadRoot;
    private String uploadDirectoryLabel="DisCraft upload folder";
    public DisCraftScreen(Screen parent) {
        this(parent,net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get(),Minecraft.getInstance().gameDirectory.toPath());
    }
    // Explicit roots keep location presentation independently testable with isolated local directories.
    DisCraftScreen(Screen parent,Path configRoot,Path gameRoot) {
        super(Component.literal(Minecraft.getInstance().getConnection()==null?"DisCraft • LOCAL MOCK":"DisCraft • SERVER BRIDGE")); this.parent = parent;
        networked=Minecraft.getInstance().getConnection()!=null;
        worker.submit(() -> {
            try {
                uploadRoot=DisCraftPaths.uploads(configRoot,gameRoot,DisCraftPaths::warn);
                String label=DisCraftPaths.uploadLabel(configRoot,gameRoot,uploadRoot);
                Files.createDirectories(uploadRoot);
                if(networked){Minecraft.getInstance().execute(()->{if(!closed){applyUploadLabel(label);busy=false;status=ClientBridge.status();}});return;}
                var loadedEmoji = new HashMap<String, Attachment>();
                for (var definition : MockTransport.EMOJI.values())
                    loadedEmoji.put(definition.mediaKey(), Attachment.fromResource(definition.mediaKey()));
                var loaded = new ChatSession(new MockTransport(), ChatSession.Permissions.preview(), MockTransport.EMOJI,
                        () -> System.nanoTime() / 1_000_000);
                loaded.poll();
                Minecraft.getInstance().execute(() -> {
                    if (closed) { loaded.close(); return; }
                    applyUploadLabel(label);
                    session = loaded; emoji.putAll(loadedEmoji); busy = false;
                    status = "Local fixture peer ready. Files: "+uploadDirectoryLabel+" (PNG/JPEG/GIF, ≤4 MiB).";
                });
            } catch (Exception failure) { finish("Fixture load failed: " + failure.getMessage()); }
        });
    }
    private void applyUploadLabel(String label){
        uploadDirectoryLabel=label;
        if(path!=null)path.setHint(Component.literal("File inside "+label));
    }
    @Override protected void init() {
        String draft = text == null ? "" : text.getValue();
        String file = path == null ? "" : path.getValue();
        text = addRenderableWidget(new EditBox(font, 12, height - 76, Math.max(80, width - 100), 20, Component.literal("Message")));
        text.setMaxLength(4000); text.setValue(draft); text.setHint(Component.literal("Message • Unicode + :discraft: :dance:"));
        text.setResponder(value -> draftRevision++);
        addRenderableWidget(Button.builder(Component.literal("Send"), button -> send()).bounds(width - 82, height - 76, 70, 20).build());
        path = addRenderableWidget(new EditBox(font, 12, height - 50, Math.max(80, width - 230), 20, Component.literal("Upload filename")));
        path.setMaxLength(240); path.setValue(file); path.setHint(Component.literal("File inside "+uploadDirectoryLabel));
        addRenderableWidget(Button.builder(Component.literal("Attach file"), button -> attach(null)).bounds(width - 212, height - 50, 96, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Demo GIF"), button -> attach("dance.gif")).bounds(width - 110, height - 50, 98, 20).build());
        addRenderableWidget(Button.builder(Component.literal(networked?"Emoji 1":":discraft:"), button -> text.insertText(networked?ClientBridge.emojiShortcut(0):":discraft:")).bounds(12, 28, 85, 20).build());
        addRenderableWidget(Button.builder(Component.literal(networked?"Emoji 2":":dance:"), button -> text.insertText(networked?ClientBridge.emojiShortcut(1):":dance:")).bounds(103, 28, 75, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Clear attachment"), button -> {
            attachmentRevision++; pending = null; status = "Attachment cleared";
        }).bounds(184, 28, 120, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Back"), button -> onClose()).bounds(width - 62, 6, 50, 20).build());
        setInitialFocus(text);
    }
    private void attach(String fixture) {
        if (busy || (!networked && session == null)) return;
        String filename = path.getValue();
        long requestRevision = ++attachmentRevision;
        busy = true; status = "Validating and decoding media…";
        worker.submit(() -> {
            try {
                var attachment = fixture == null ? Attachment.fromUpload(uploadRoot, filename) : Attachment.fromResource(fixture);
                completeAttachment(requestRevision, attachment, null);
            } catch (Exception failure) { completeAttachment(requestRevision, null, "Attachment rejected: " + failure.getMessage()); }
        });
    }
    private void completeAttachment(long requestRevision, Attachment attachment, String failure) {
        Minecraft.getInstance().execute(() -> {
            if (closed) return;
            // Clear invalidates the result but keeps busy until the worker actually finishes.
            busy = false;
            if (requestRevision != attachmentRevision) return;
            if (failure != null) { status = failure; return; }
            pending = attachment;
            status = "Attached " + attachment.name() + " • " + attachment.media().frames().size() + " frame(s). Press Send.";
        });
    }
    private void send() {
        if (busy || (!networked && session == null)) return;
        String draft = text.getValue();
        long submittedDraftRevision = draftRevision;
        Attachment media = pending;
        busy = true;
        worker.submit(() -> {
            try {
                String delivery;
                if(networked) delivery=ClientBridge.send(draft,media);
                else {session.send(draft, media == null ? List.of() : List.of(media)); session.poll();delivery="Sent to LOCAL MOCK; distinct peer reply received; relay echo suppressed.";}
                Minecraft.getInstance().execute(() -> {
                    if (closed) return;
                    if (draftRevision == submittedDraftRevision) text.setValue("");
                    pending = null; busy = false; scrollToBottom = true;
                    status = delivery;
                });
            } catch (Exception failure) { finish("Send failed: " + failure.getMessage()); }
        });
    }
    private void finish(String message) {
        Minecraft.getInstance().execute(() -> { if (!closed) { busy = false; status = message; } });
    }
    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if (key == GLFW.GLFW_KEY_ENTER && text.isFocused()) { send(); return true; }
        return super.keyPressed(key, scan, modifiers);
    }
    @Override public boolean mouseScrolled(double x, double y, double delta) {
        scroll = Math.max(0, Math.min(Math.max(0, contentHeight - viewportHeight()), scroll - (int)(delta * 24)));
        return true;
    }
    private int viewportHeight() { return Math.max(20, height - 166); }
    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.fill(0, 0, width, height, 0xee152033);
        graphics.drawString(font, title, 12, 9, 0xffa6c9ff, false);
        int top = 56, bottom = top + viewportHeight();
        var messages = networked ? ClientBridge.snapshot() : session == null ? List.<Message>of() : session.snapshot();
        retainUnicode(messages);
        contentHeight = messages.stream().mapToInt(this::messageHeight).sum();
        if (scrollToBottom) { scroll = Math.max(0, contentHeight - viewportHeight()); scrollToBottom = false; }
        scroll = Math.max(0, Math.min(scroll, Math.max(0, contentHeight - viewportHeight())));
        textures.begin();
        graphics.enableScissor(8, top, width - 8, bottom);
        int y = top - scroll;
        for (var message : messages) y = renderMessage(graphics, message, y, top, bottom);
        contentHeight = y - top + scroll;
        graphics.disableScissor(); textures.end();
        graphics.fill(0, height - 106, width, height, 0xff152033);
        String attached = pending == null ? "No attachment" : "Ready: " + pending.name() + " (" + pending.media().frames().size() + " frames)";
        graphics.drawString(font, font.plainSubstrByWidth(attached, width - 24), 12, height - 100, 0xffb7dbbc, false);
        graphics.drawString(font, font.plainSubstrByWidth((busy ? "Working… " : "") + status, width - 24), 12, height - 20, 0xffc5ccdd, false);
        super.render(graphics, mouseX, mouseY, partialTick);
    }
    private final Set<String> unicodeAllowed=new LinkedHashSet<>();
    private record EmojiLines(List<net.minecraft.util.FormattedCharSequence> lines,Map<Integer,TwemojiCatalog.Entry> markers){}
    private void retainUnicode(List<Message> messages){var wanted=new LinkedHashSet<String>();for(var message:messages)for(var part:TwemojiCatalog.parts(Content.plain(message.content())))if(part.emoji()!=null)wanted.add(part.emoji().asset());unicodeAllowed.retainAll(wanted);for(String key:wanted)if(unicodeAllowed.size()<TwemojiCatalog.CACHE_ENTRIES)unicodeAllowed.add(key);}
    private EmojiLines emojiLines(String text){var component=Component.empty();var markers=new HashMap<Integer,TwemojiCatalog.Entry>();for(var part:TwemojiCatalog.parts(text)){if(part.emoji()==null||!unicodeAllowed.contains(part.emoji().asset()))component.append(NativeChat.emojiLiteral(part.text()));else{int color=0x350000+markers.size();markers.put(color,part.emoji());component.append(Component.literal("\u3000").withStyle(net.minecraft.network.chat.Style.EMPTY.withColor(color)));}}return new EmojiLines(font.split(component,Math.max(50,width-48)),markers);}
    private int messageHeight(Message message) {
        int lines = emojiLines(Content.plain(message.content())).lines().size();
        boolean custom = message.content().stream().anyMatch(c -> c instanceof Content.CustomEmoji);
        return 30 + lines * 10 + (custom ? 28 : 0) + message.attachments().size() * 92;
    }
    private int renderMessage(GuiGraphics graphics, Message message, int y, int top, int bottom) {
        var preview=emojiLines(Content.plain(message.content()));var lines=preview.lines();
        var custom = message.content().stream().filter(c -> c instanceof Content.CustomEmoji).map(c -> (Content.CustomEmoji)c).toList();
        int cardHeight = 30 + lines.size() * 10 + (custom.isEmpty() ? 0 : 28) + message.attachments().size() * 92;
        if (y + cardHeight >= top && y < bottom) {
            graphics.fill(12, y, width - 12, y + cardHeight - 5, message.origin() == Message.Origin.CLIENT ? 0xff2c3d59 : 0xff233247);
            graphics.drawString(font, message.author(), 20, y + 6, 0xff92c5ff, false);
            int row = y + 20;
            for (var line : lines) { graphics.drawString(font, line, 20, row, 0xfff1f1f7, false);if(row+10>=top&&row<bottom){final int baseline=row;final int[] advance={20};line.accept((index,style,point)->{var color=style.getColor();var entry=point==0x3000&&color!=null?preview.markers().get(color.getValue()):null;if(entry!=null){var image=TwemojiCatalog.asset(entry);if(image!=null)drawMedia(graphics,image,advance[0],baseline,Math.min(8,font.width("\u3000")),8);}advance[0]+=font.width(Component.literal(new String(Character.toChars(point))).setStyle(style));return true;});}row += 10; }
            int x = 20;
            for (var definition : custom) {
                Attachment asset = networked ? ClientBridge.emoji(definition.mediaKey()) : emoji.get(definition.mediaKey());
                if (asset != null && row + 24 >= top && row < bottom && x + 24 < width - 20)
                    drawMedia(graphics, asset, x, row, 24, 24);
                x += 28;
            }
            if (!custom.isEmpty()) row += 28;
            for (var attachment : message.attachments()) {
                if (row + 88 >= top && row < bottom) {
                    int w = Math.min(160, width - 48), h = Math.min(72, Math.max(1, w * attachment.media().height() / attachment.media().width()));
                    w = Math.max(1, Math.min(w, h * attachment.media().width() / attachment.media().height()));
                    drawMedia(graphics, attachment, 20, row, w, h);
                    graphics.drawString(font, font.plainSubstrByWidth(attachment.name() + " • " + attachment.media().frames().size() + " frame(s)", width - 48), 20, row + 75, 0xffb6c4d8, false);
                }
                row += 92;
            }
        }
        return y + cardHeight;
    }
    private void drawMedia(GuiGraphics graphics, Attachment attachment, int x, int y, int width, int height) {
        long elapsed = (System.nanoTime() - started) / 1_000_000;
        var location = textures.frame(attachment.digest(), attachment.media(), elapsed);
        graphics.blit(location, x, y, 0, 0, width, height, width, height);
    }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public void removed() {
        closed = true; textures.close(); worker.shutdownNow();
        if (session != null) session.close();
    }
}
