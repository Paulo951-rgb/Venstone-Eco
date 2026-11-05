package fr.venstone.economy;

import java.util.Locale;
import java.util.regex.Pattern;

/** Pure helpers for {@code namespace:path} identifiers, usable without a running client. */
public final class ItemIds {
    private static final Pattern FORMAT = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    private static final String DEFAULT_NAMESPACE = "minecraft:";

    private ItemIds() { }

    /** Returns the canonical lowercase id, or {@code null} when the text is not well formed. */
    public static String canonical(String input) {
        if (input == null) return null;
        String text = input.trim().toLowerCase(Locale.ROOT);
        if (text.isEmpty()) return null;
        if (!text.contains(":")) text = DEFAULT_NAMESPACE + text;
        return FORMAT.matcher(text).matches() ? text : null;
    }

    public static boolean isValid(String input) { return canonical(input) != null; }

    /** The path part with underscores turned into spaces, for display fallbacks. */
    public static String path(String id) {
        if (id == null) return "";
        int colon = id.indexOf(':');
        return (colon >= 0 ? id.substring(colon + 1) : id).replace('_', ' ');
    }
}
