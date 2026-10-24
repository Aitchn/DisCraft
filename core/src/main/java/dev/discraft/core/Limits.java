package dev.discraft.core;

/** Deliberately conservative preview policy. Relay must independently enforce it. */
public final class Limits {
    private Limits() {}
    public static final int TEXT_CODEPOINTS = 2000;
    public static final int MEDIA_BYTES = 4 * 1024 * 1024;
    public static final int MEDIA_DIMENSION = 1024;
    public static final int MEDIA_FRAMES = 60;
    public static final long MEDIA_TOTAL_PIXELS = 8L * 1024 * 1024;
    public static final int ATTACHMENTS = 4;
    public static final int HISTORY = 100;
    public static String text(String value, int limit, String name) {
        if (value == null || value.codePointCount(0, value.length()) > limit) {
            throw new IllegalArgumentException(name + " exceeds " + limit + " code points");
        }
        // Java strings can contain isolated surrogate code units; reject invalid Unicode.
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i)))
                    throw new IllegalArgumentException("Invalid Unicode in " + name);
            } else if (Character.isLowSurrogate(c)) throw new IllegalArgumentException("Invalid Unicode in " + name);
        }
        return value;
    }
}
