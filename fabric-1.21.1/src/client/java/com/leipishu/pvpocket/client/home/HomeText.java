package com.leipishu.pvpocket.client.home;

import com.leipishu.pvpocket.i18n.PvLang;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Date, time and duration formatting for the home screen.
 *
 * <p>Fixed numeric patterns with no locale involved, because the game's font has one digit width and
 * a locale that spells the month in letters would make a column of timestamps ragged. The words
 * around the numbers ("3 天前") <em>are</em> translated — the numerals are not.</p>
 */
public final class HomeText {

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private HomeText() {
    }

    private static ZoneId zone() {
        return ZoneId.systemDefault();
    }

    /** Local wall-clock time, {@code HH:mm}. */
    public static String clock() {
        return LocalTime.now().format(CLOCK);
    }

    /** {@code yyyy-MM-dd} plus the localised weekday. */
    public static String date() {
        LocalDate today = LocalDate.now();
        String weekday = PvLang.str("pvpocket.gui.home.weekday." + today.getDayOfWeek().name()
                .toLowerCase(java.util.Locale.ROOT));
        return today + " · " + weekday;
    }

    /** {@code MM-dd HH:mm}. */
    public static String stamp(long millis) {
        return Instant.ofEpochMilli(millis).atZone(zone()).format(STAMP);
    }

    /** {@code m:ss} for a round or a match length. */
    public static String duration(long millis) {
        long total = Math.max(0L, millis / 1000L);
        long minutes = total / 60L;
        long seconds = total % 60L;
        return minutes + ":" + (seconds < 10 ? "0" : "") + seconds;
    }

    /**
     * "just now" / "12 minutes ago" / "3 days ago".
     *
     * <p>Falls back to an absolute stamp past a week: "37 days ago" is a number nobody converts back
     * into a date, while a date is a date.</p>
     */
    public static String relative(long millis) {
        long delta = System.currentTimeMillis() - millis;
        if (delta < 0) {
            return stamp(millis);
        }
        long minutes = delta / 60_000L;
        if (minutes < 1) {
            return PvLang.str("pvpocket.gui.home.ago.now");
        }
        if (minutes < 60) {
            return PvLang.str("pvpocket.gui.home.ago.minutes", minutes);
        }
        long hours = minutes / 60;
        if (hours < 24) {
            return PvLang.str("pvpocket.gui.home.ago.hours", hours);
        }
        long days = hours / 24;
        if (days <= 7) {
            return PvLang.str("pvpocket.gui.home.ago.days", days);
        }
        return stamp(millis);
    }

    /** A ratio as one decimal, or an em dash when there is nothing to divide. */
    public static String percent(double ratio, boolean any) {
        if (!any) {
            return "—";
        }
        return String.format(java.util.Locale.ROOT, "%.1f%%", ratio * 100D);
    }

    /** Two decimals, or an em dash. */
    public static String ratio(double value, boolean any) {
        if (!any) {
            return "—";
        }
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }

    /** Thousands separators for the damage total. */
    public static String number(long value) {
        return String.format(java.util.Locale.ROOT, "%,d", value);
    }
}
