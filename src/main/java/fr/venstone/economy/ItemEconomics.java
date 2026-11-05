package fr.venstone.economy;

/**
 * Derived economics for one tracked item. Pure arithmetic over the configured/observed prices and
 * the item's real stack size, so it is unit-testable and safe to call from the render path.
 *
 * <p>Unit price, price per stack and price per lot are three different numbers and are never mixed:
 * {@link #unitPrice} is for one item, {@link #pricePerStack} for one full stack and
 * {@link #maxRevenue} for the whole sell quota.
 */
public final class ItemEconomics {
    private final double unitPrice;
    private final double buyPrice;
    private final int stackSize;
    private final int limitItems;
    private final int soldItems;

    public ItemEconomics(double unitPrice, double buyPrice, int stackSize, int limitItems, int soldItems) {
        this.unitPrice = Double.isFinite(unitPrice) && unitPrice > 0 ? unitPrice : 0;
        this.buyPrice = Double.isFinite(buyPrice) && buyPrice > 0 ? buyPrice : 0;
        this.stackSize = StackMath.clampStackSize(stackSize);
        this.limitItems = Math.max(0, limitItems);
        this.soldItems = Math.max(0, Math.min(soldItems, this.limitItems));
    }

    /** Builds the view for a tracked item at a given instant. */
    public static ItemEconomics of(TrackedItem item, long now) {
        if (item == null) return new ItemEconomics(0, 0, StackMath.DEFAULT_STACK_SIZE, 0, 0);
        return new ItemEconomics(item.effectiveSellPrice(), item.observedBuyPrice, item.stackSize,
                item.limitItems(), item.soldItems(now));
    }

    public double unitPrice() { return unitPrice; }
    public double buyPrice() { return buyPrice; }
    public int stackSize() { return stackSize; }
    public int limitItems() { return limitItems; }
    public int soldItems() { return soldItems; }

    /** Items still sellable before the quota resets. */
    public int remainingItems() { return Math.max(0, limitItems - soldItems); }

    /** Whole stacks of the quota. */
    public int limitStacks() { return StackMath.wholeStacks(limitItems, stackSize); }

    /** Revenue for one full stack at the unit price. */
    public double pricePerStack() { return StackMath.perStack(unitPrice, stackSize); }

    /** Revenue if the whole quota is sold at the unit price. */
    public double maxRevenue() { return StackMath.revenue(limitItems, unitPrice); }

    /** Revenue still obtainable from the remaining quota. */
    public double remainingRevenue() { return StackMath.revenue(remainingItems(), unitPrice); }

    /** Revenue already realised by the sales recorded in the current window. */
    public double earnedRevenue() { return StackMath.revenue(soldItems, unitPrice); }

    /**
     * Profit per item when the buy price is known: the sell price minus the buy price. Zero when the
     * buy price is unknown, because assuming a free buy would be a guess. A loss is reported as a
     * negative value rather than hidden.
     */
    public double unitMargin() { return buyPrice > 0 ? round2(unitPrice - buyPrice) : 0; }

    /** Profit of the whole quota when both prices are known; negative when buying costs more. */
    public double maxMargin() { return buyPrice > 0 ? round2(limitItems * (unitPrice - buyPrice)) : 0; }

    private static double round2(double value) {
        if (!Double.isFinite(value)) return 0;
        return Math.round(value * 100.0) / 100.0;
    }

    /** True when the item has a usable sell price. */
    public boolean isSellable() { return unitPrice > 0; }
}
