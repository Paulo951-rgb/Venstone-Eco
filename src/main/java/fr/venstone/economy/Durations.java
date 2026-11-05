package fr.venstone.economy;

import java.util.Locale;

/** Formats durations for the HUD; kept free of client dependencies so it is unit-testable. */
public final class Durations {
    private Durations() { }

    /** Formats a duration as {@code Hh MMm} for hours and {@code MM:SS} below one hour. */
    public static String format(long millis) {
        long seconds = Math.max(0, millis / 1000);
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;
        return hours > 0
                ? String.format(Locale.ROOT, "%dh %02dm", hours, minutes)
                : String.format(Locale.ROOT, "%02d:%02d", minutes, secs);
    }
}
