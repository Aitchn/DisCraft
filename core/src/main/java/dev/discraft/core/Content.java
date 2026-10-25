package dev.discraft.core;

import java.util.*;
import java.util.regex.Pattern;

/** Loader-neutral text/emoji representation. Unknown aliases remain literal text. */
public sealed interface Content permits Content.Text, Content.CustomEmoji {
    record Text(String value) implements Content {
        public Text { Limits.text(value, Limits.TEXT_CODEPOINTS, "text"); }
    }
    record CustomEmoji(String id, String name, boolean animated, String mediaKey) implements Content {
        public CustomEmoji {
            if (!id.matches("[a-zA-Z0-9_-]{1,64}") || !name.matches("[a-zA-Z0-9_]{1,32}")
                    || !mediaKey.matches("[a-zA-Z0-9_.-]{1,80}")) throw new IllegalArgumentException("Invalid emoji");
        }
    }
    static List<Content> parse(String text, Map<String, CustomEmoji> catalog) {
        Limits.text(text, Limits.TEXT_CODEPOINTS, "text");
        var out = new ArrayList<Content>();
        var matcher = Pattern.compile(":([a-zA-Z0-9_]{1,32}):").matcher(text);
        int start = 0;
        while (matcher.find()) {
            var emoji = catalog.get(matcher.group(1));
            if (emoji == null) continue;
            if (matcher.start() > start) out.add(new Text(text.substring(start, matcher.start())));
            out.add(emoji);
            start = matcher.end();
        }
        if (start < text.length()) out.add(new Text(text.substring(start)));
        return List.copyOf(out);
    }
    static String plain(List<Content> content) {
        var out = new StringBuilder();
        for (var part : content) {
            if (part instanceof Text t) out.append(t.value());
            if (part instanceof CustomEmoji e) out.append(':').append(e.name()).append(':');
        }
        return out.toString();
    }
}
