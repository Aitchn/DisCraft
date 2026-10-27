package dev.discraft.core;

import java.io.*;
import java.nio.file.*;
import java.util.HexFormat;
import java.security.*;

/** Encoded bytes cannot enter the pipeline until decoded and validated. */
public final class Attachment {
    private final String name, digest;
    private final byte[] bytes;
    private final MediaAsset media;
    private Attachment(String name, byte[] bytes) throws IOException {
        this.name = Limits.text(name, 120, "attachment name");
        this.bytes = bytes.clone();
        this.media = MediaDecoder.decode(this.bytes);
        try { digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(this.bytes)); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
    public static Attachment of(String name, byte[] bytes) throws IOException { return new Attachment(name, bytes); }
    public static Attachment fromResource(String name) throws IOException {
        if (!name.matches("[a-zA-Z0-9_.-]+")) throw new IOException("Invalid fixture name");
        try (var in = Attachment.class.getResourceAsStream("/fixtures/" + name)) {
            if (in == null) throw new IOException("Missing fixture: " + name);
            return new Attachment(name, readBounded(in));
        }
    }
    public static Attachment fromUpload(Path root, String relativeName) throws IOException {
        if (relativeName.isBlank() || relativeName.contains("/") || relativeName.contains("\\")
                || relativeName.equals("..") || Path.of(relativeName).isAbsolute()) throw new IOException("Enter a filename, not a path");
        Path base = root.toRealPath();
        Path target = base.resolve(relativeName).normalize();
        if (!target.startsWith(base) || !target.toRealPath().startsWith(base) || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Choose a regular file inside the DisCraft upload directory");
        try (var in = Files.newInputStream(target, LinkOption.NOFOLLOW_LINKS)) {
            return new Attachment(target.getFileName().toString(), readBounded(in));
        }
    }
    private static byte[] readBounded(InputStream in) throws IOException {
        byte[] bytes = in.readNBytes(Limits.MEDIA_BYTES + 1);
        if (bytes.length > Limits.MEDIA_BYTES) throw new IOException("Media byte limit: 4 MiB");
        return bytes;
    }
    public String name() { return name; }
    public String digest() { return digest; }
    public byte[] bytes() { return bytes.clone(); }
    public MediaAsset media() { return media; }
}
