package fr.venstone.economy;

/**
 * Stack-aware quantities and revenue maths.
 *
 * <p>The sell limit is the same for every item: {@link #LIMIT_STACKS} stacks. The number of items
 * that represents depends on the item's real maximum stack size, which is read from the item stack
 * rather than assumed to be 64.
 */
public final class StackMath {
    /** Sell limit applied to every item, in stacks. */
    public static final int LIMIT_STACKS = 36;
    /** Stack size assumed when the real one is unknown or invalid. */
    public static final int DEFAULT_STACK_SIZE = 64;
    /** Largest stack size Minecraft uses; anything above is treated as a bad reading. */
    public static final int MAX_STACK_SIZE = 64;

    private StackMath() { }

    /** Keeps a stack size inside the valid range, falling back to {@link #DEFAULT_STACK_SIZE}. */
    public static int clampStackSize(int stackSize) {
        return stackSize >= 1 && stackSize <= MAX_STACK_SIZE ? stackSize : DEFAULT_STACK_SIZE;
    }

    /** Item capacity of the shared stack limit, for the given item stack size. */
    public static int limitItems(int stackSize) { return LIMIT_STACKS * clampStackSize(stackSize); }

    /** Whole stacks represented by a quantity, rounded down. */
    public static int wholeStacks(int items, int stackSize) {
        int size = clampStackSize(stackSize);
        return Math.max(0, items) / size;
    }

    /** Revenue for a quantity at a unit price, rounded to cents. Zero for unusable input. */
    public static double revenue(int items, double unitPrice) {
        if (items <= 0 || !Double.isFinite(unitPrice) || unitPrice <= 0) return 0;
        return round2(items * unitPrice);
    }

    /** Revenue of one full stack at a unit price, rounded to cents. */
    public static double perStack(double unitPrice, int stackSize) {
        return revenue(clampStackSize(stackSize), unitPrice);
    }

    /**
     * Revenue scaled to an hour, so a value earned over a shorter window can be compared on a common
     * basis. Returns 0 when the window is unknown.
     */
    public static double perHour(double revenue, long windowMillis) {
        if (revenue <= 0 || windowMillis <= 0) return 0;
        return round2(revenue * (3_600_000.0 / windowMillis));
    }

    /** Formats a quantity as stacks with one decimal, e.g. {@code 12.3}. */
    public static String stacks(int items, int stackSize) {
        int size = clampStackSize(stackSize);
        return String.format(java.util.Locale.ROOT, "%.1f", items / (double) size);
    }

    private static double round2(double value) {
        if (!Double.isFinite(value)) return 0;
        return Math.round(value * 100.0) / 100.0;
    }
}
