package dev.discraft.forge.smoke;

import com.mojang.blaze3d.platform.InputConstants;
import dev.discraft.core.*;
import dev.discraft.forge.*;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;
import java.nio.file.*;
import java.util.*;

/** Opt-in acceptance driver: real Minecraft widgets, renderer and local mock transport. */
@Mod.EventBusSubscriber(modid = DisCraft.ID, value = Dist.CLIENT)
public final class ClientAcceptance {
    private static final Minecraft MC = Minecraft.getInstance();
    private static final Path OUT = Path.of("../build/evidence/minecraft").toAbsolutePath().normalize();
    private static final List<String> checks = new ArrayList<>();
    private static final Set<Integer> gifFrames = new HashSet<>();
    private static int stage, ticks, checksBeforeSend;
    private static long deadline = System.nanoTime() + 240_000_000_000L;
    private static DisCraftScreen previous;
    private static boolean done;
    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event) {
        if (!Boolean.getBoolean("discraft.smoke") || done || event.phase != TickEvent.Phase.END) return;
        try {
            if (System.nanoTime() > deadline) throw new AssertionError("Timeout at stage " + stage + "; screen=" + MC.screen);
            if (++ticks % 2 != 0) return;
            if (stage == 0) {
                if (MC.screen != null && MC.screen.getClass().getSimpleName().equals("AccessibilityOnboardingScreen")) {
                    MC.screen.children().stream().filter(w -> w instanceof Button b &&
                            (b.getMessage().getString().equals("Continue") || b.getMessage().getString().equals("Done")))
                            .map(w -> (Button)w).findFirst().ifPresent(Button::onPress);
                    return;
                }
                if (!(MC.screen instanceof TitleScreen) || MC.getOverlay() != null) return;
                Files.createDirectories(OUT);
                MC.options.guiScale().set(1); MC.resizeDisplay();
                click("DisCraft mock");
                check(MC.screen instanceof DisCraftScreen, "Title-screen chat entry opens real DisCraft screen");
                stage++; return;
            }
            if (stage == 14) {
                if (!(MC.screen instanceof DisCraftScreen screen) || busy(screen)) return;
                check(screen != previous, "F8 creates a fresh screen after close");
                check(session(screen).snapshot().size() == 2, "Reopened session has exactly two initial peer messages");
                capture("reopened.png");
                screen.onClose();
                check(MC.screen instanceof TitleScreen, "Second close returns to title");
                Files.writeString(OUT.resolve("result.txt"), String.join("\n", checks) + "\nPASS " + checks.size() + " checks\n");
                done = true; MC.stop(); return;
            }
            if (!(MC.screen instanceof DisCraftScreen screen) || busy(screen)) return;
            var messages = session(screen).snapshot();
            switch (stage) {
                case 1 -> {
                    check(messages.size() == 2 && messages.stream().allMatch(m -> m.origin() == Message.Origin.RELAY), "Initial inbound mock text and PNG/GIF attachments");
                    check(messages.get(0).attachments().get(0).media().frames().size() == 1, "Inbound PNG decoded");
                    check(messages.get(1).attachments().get(0).media().frames().size() > 1, "Inbound GIF decoded as animation");
                    capture("inbound-frame0.png"); stage++;
                }
                case 2 -> {
                    Object cache = field(screen, "textures");
                    Map<?, ?> entries = (Map<?, ?>) field(cache, "entries");
                    String digest = messages.get(1).attachments().get(0).digest();
                    Object entry = entries.get(digest);
                    if (entry == null) return;
                    var method = entry.getClass().getDeclaredMethod("frame"); method.setAccessible(true);
                    gifFrames.add((Integer) method.invoke(entry));
                    if (gifFrames.size() < 2) return;
                    capture("inbound-frame1.png");
                    check(true, "Minecraft TextureCache uploaded and displayed distinct GIF frames " + gifFrames);
                    edit(screen, "text").setValue("Hello 世界 👋 :discraft: :dance:");
                    checksBeforeSend = messages.size();
                    screen.keyPressed(GLFW.GLFW_KEY_ENTER, 0, 0);
                    // A repeated click while the worker is active must not duplicate the send.
                    click("Send"); stage++;
                }
                case 3 -> {
                    check(messages.size() == checksBeforeSend + 2, "Enter sends once; concurrent repeated Send ignored; one distinct mock reply");
                    check(messages.stream().noneMatch(m -> m.author().startsWith("Echo")), "Relay echo suppressed in client transcript");
                    check(messages.get(messages.size()-2).content().stream().filter(c -> c instanceof Content.CustomEmoji).count() == 2, "Static and animated custom emoji parsed in outgoing message");
                    capture("text-emoji.png");
                    edit(screen, "text").setValue(""); click("Send"); stage++;
                }
                case 4 -> {
                    check(status(screen).startsWith("Send failed:"), "Empty-message error is shown and UI recovers");
                    edit(screen, "path").setValue("../escape.png"); click("Attach file"); stage++;
                }
                case 5 -> {
                    check(status(screen).startsWith("Attachment rejected:"), "Path traversal rejected through upload UI");
                    Path uploads = MC.gameDirectory.toPath().resolve("discraft-uploads");
                    Files.writeString(uploads.resolve("broken.png"), "invalid image");
                    for (String name : List.of("landscape.png", "dance.gif")) {
                        try (var in = Attachment.class.getResourceAsStream("/fixtures/" + name)) {
                            Files.copy(in, uploads.resolve(name), StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                    edit(screen, "path").setValue("broken.png"); click("Attach file"); stage++;
                }
                case 6 -> {
                    check(status(screen).startsWith("Attachment rejected:"), "Corrupt image rejected through upload UI");
                    capture("attachment-error.png");
                    edit(screen, "path").setValue("landscape.png"); click("Attach file"); stage++;
                }
                case 7 -> {
                    check(((Attachment)field(screen, "pending")).media().frames().size() == 1, "PNG upload attached through filename widget");
                    edit(screen, "text").setValue("PNG roundtrip"); click("Send"); stage++;
                }
                case 8 -> {
                    check(last(messages).attachments().get(0).name().equals("landscape.png"), "PNG outbound and distinct inbound mock reply preserve attachment");
                    capture("png-roundtrip.png");
                    edit(screen, "path").setValue("dance.gif"); click("Attach file"); stage++;
                }
                case 9 -> {
                    check(((Attachment)field(screen, "pending")).media().frames().size() > 1, "GIF upload attached through filename widget");
                    edit(screen, "text").setValue("GIF roundtrip :dance:"); click("Send"); stage++;
                }
                case 10 -> {
                    check(last(messages).attachments().get(0).media().frames().size() > 1, "GIF outbound and distinct inbound mock reply preserve animation");
                    capture("gif-roundtrip.png"); click("Demo GIF"); stage++;
                }
                case 11 -> {
                    check(field(screen, "pending") != null, "Demo GIF button attaches fixture");
                    click("Clear attachment");
                    check(field(screen, "pending") == null, "Clear attachment is repeatable");
                    click("Clear attachment");
                    check(field(screen, "pending") == null, "Repeated clear remains safe");
                    click(":discraft:"); click(":dance:");
                    check(edit(screen,"text").getValue().contains(":discraft::dance:"), "Emoji buttons insert both shortcuts");
                    previous = screen; screen.onClose();
                    check(MC.screen instanceof TitleScreen, "Back closes chat to original title");
                    check((Boolean)field(screen,"closed") && session(screen).snapshot().isEmpty(), "Closed screen releases session history");
                    check(((Map<?,?>)field(field(screen,"textures"),"entries")).isEmpty(), "Closed screen releases uploaded textures");
                    KeyMapping.click(InputConstants.getKey(GLFW.GLFW_KEY_F8, 0)); stage = 14;
                }
                default -> throw new AssertionError("Unexpected stage " + stage);
            }
        } catch (Throwable failure) {
            done = true;
            try {
                Files.createDirectories(OUT); capture("failure.png");
                Files.writeString(OUT.resolve("result.txt"), String.join("\n", checks) + "\nFAIL stage " + stage + ": " + failure + "\n");
            } catch (Throwable ignored) { }
            failure.printStackTrace(); MC.stop();
        }
    }
    private static Message last(List<Message> messages) { return messages.get(messages.size()-1); }
    private static Object field(Object value, String name) throws Exception {
        var field = value.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(value);
    }
    private static EditBox edit(Screen screen, String name) throws Exception { return (EditBox)field(screen,name); }
    private static ChatSession session(Screen screen) throws Exception { return (ChatSession)field(screen,"session"); }
    private static boolean busy(Screen screen) throws Exception { return (Boolean)field(screen,"busy"); }
    private static String status(Screen screen) throws Exception { return (String)field(screen,"status"); }
    private static void click(String label) {
        var button = MC.screen.children().stream().filter(w -> w instanceof Button b && b.getMessage().getString().equals(label))
                .map(w -> (Button)w).findFirst().orElseThrow(() -> new AssertionError("Missing button: " + label));
        button.onPress();
    }
    private static void capture(String name) throws Exception {
        try (var image = Screenshot.takeScreenshot(MC.getMainRenderTarget())) { image.writeToFile(OUT.resolve(name)); }
    }
    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
        checks.add("PASS " + name); System.out.println("DISCRAFT_ACCEPTANCE PASS " + name);
    }
}
