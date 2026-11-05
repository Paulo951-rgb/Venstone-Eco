package fr.venstone.economy;

import java.util.Locale;

/**
 * Discrete interface scale steps. Kept free of client types so the clamp and formatting rules are
 * unit-testable; the client only turns an index into a render factor.
 */
public final class UiScale {
    public static final float MIN = 0.60f;
    public static final float MAX = 1.60f;
    public static final float STEP = 0.10f;
    /** Number of steps from {@link #MIN} to {@link #MAX} inclusive. */
    public static final int STEPS = 11;
    /** Index of the neutral 100% step. */
    public static final int DEFAULT_INDEX = 4;

    private UiScale() { }

    public static int clampIndex(int index) { return Math.max(0, Math.min(STEPS - 1, index)); }

    public static float factor(int index) { return MIN + STEP * clampIndex(index); }

    /** Nearest step for a stored factor; invalid input falls back to the neutral step. */
    public static int nearestIndex(float factor) {
        if (!Float.isFinite(factor)) return DEFAULT_INDEX;
        return clampIndex(Math.round((factor - MIN) / STEP));
    }

    public static int next(int index) { return clampIndex(index + 1); }

    public static int previous(int index) { return clampIndex(index - 1); }

    public static boolean isDefault(int index) { return clampIndex(index) == DEFAULT_INDEX; }

    /** Human-readable percentage, e.g. {@code 110%}. */
    public static String label(int index) { return Math.round(factor(index) * 100) + "%"; }

    /** One decimal factor as text, e.g. {@code 1.1}. Used in logs and diagnostics. */
    public static String factorLabel(int index) { return String.format(Locale.ROOT, "%.1f", factor(index)); }
}
