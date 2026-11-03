package dev.discraft.forge.server;

import java.time.*;
import java.util.regex.Pattern;

/** Minecraft-free helpers that turn Discord's machine markup into readable text. No network and no identity is trusted here. */
final class DiscordMarkup {
    private DiscordMarkup() {}
    /** Group 1-3 custom emoji, 4 user, 5 role, 6 channel, 7-8 slash command, 9-10 timestamp, 11 guild navigation. */
    static final Pattern TOKENS = Pattern.compile("<(a?):([A-Za-z0-9_]{1,32}):([0-9]{15,22})>"
            + "|<@!?([0-9]{15,22})>"
            + "|<@&([0-9]{15,22})>"
            + "|<#([0-9]{15,22})>"
            + "|</([^\\s/:<>]{1,32}(?: [^\\s/:<>]{1,32}){0,2}):([0-9]{15,22})>"
            + "|<t:(-?[0-9]{1,13})(?::([tTdDfFR]))?>"
            + "|<id:(customize|browse|guide)>");
    static final String UNKNOWN_USER = "unknown-user", UNKNOWN_ROLE = "unknown-role", UNKNOWN_CHANNEL = "unknown-channel";
    private static final String[] WEEKDAY = {"星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日"};

    static String guildNavigation(String kind) {
        return switch (kind) { case "customize" -> "#自訂身分組與頻道"; case "browse" -> "#瀏覽頻道"; default -> "#伺服器指南"; };
    }

    /** Discord timestamp styles. Absolute styles are rendered in UTC so every player sees the same text. */
    static String timestamp(String seconds, String style, long nowSeconds) {
        long value;
        try { value = Long.parseLong(seconds); } catch (NumberFormatException invalid) { return "[無效時間]"; }
        if (value < 0 || value > 253_402_300_799L) return "[無效時間]";
        var time = LocalDateTime.ofEpochSecond(value, 0, ZoneOffset.UTC);
        String date = String.format("%04d/%02d/%02d", time.getYear(), time.getMonthValue(), time.getDayOfMonth());
        String longDate = time.getYear() + "年" + time.getMonthValue() + "月" + time.getDayOfMonth() + "日";
        String clock = String.format("%02d:%02d", time.getHour(), time.getMinute()), full = clock + String.format(":%02d", time.getSecond());
        return switch (style == null ? "f" : style) {
            case "t" -> clock + " UTC";
            case "T" -> full + " UTC";
            case "d" -> date;
            case "D" -> longDate;
            case "F" -> longDate + " " + WEEKDAY[time.getDayOfWeek().getValue() - 1] + " " + clock + " UTC";
            case "R" -> relative(value, nowSeconds);
            default -> longDate + " " + clock + " UTC";
        };
    }

    static String relative(long value, long now) {
        long diff = value - now, abs = Math.abs(diff);
        String unit;
        long amount;
        if (abs < 60) { amount = abs; unit = "秒"; }
        else if (abs < 3600) { amount = abs / 60; unit = "分鐘"; }
        else if (abs < 86_400) { amount = abs / 3600; unit = "小時"; }
        else if (abs < 30L * 86_400) { amount = abs / 86_400; unit = "天"; }
        else if (abs < 365L * 86_400) { amount = abs / (30L * 86_400); unit = "個月"; }
        else { amount = abs / (365L * 86_400); unit = "年"; }
        return amount + " " + unit + (diff < 0 ? "前" : "後");
    }
}
