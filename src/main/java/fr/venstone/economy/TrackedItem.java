package fr.venstone.economy;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import com.google.gson.annotations.SerializedName;

/** One tracked shop item: its configured values plus its rolling one-hour sale history. */
public final class TrackedItem {
    /** Sanity ceiling for a configured limit (in items). */
    public static final int MAX_LIMIT_ITEMS = 64 * 100_000;
    /** Sales of the same item recorded within this delay share a single lot. */
    private static final long LOT_MERGE_MILLIS = 60_000L;

    /** Stable identifier, e.g. {@code minecraft:cactus}. */
    public String id;
    /** Display name shown in the HUD and the configuration screen. */
    public String name;
    /** Price the user configured; never overwritten by observed sales. */
    public double configuredPrice;
    /** Last unit sell price observed in a confirmed sale, or {@code 0} when never seen. */
    public double observedPrice;
    /** Last unit buy price read from the shop, or {@code 0} when unknown. */
    public double observedBuyPrice;
    /** Real maximum stack size of this item, read from the item stack; 64 when unknown. */
    public int stackSize = StackMath.DEFAULT_STACK_SIZE;
    /**
     * Maximum number of items allowed per window. The sell limit is the same for every item
     * ({@link StackMath#LIMIT_STACKS} stacks), so this is derived from the stack size rather than
     * configured per item.
     */
    public int limitItems = StackMath.limitItems(StackMath.DEFAULT_STACK_SIZE);
    public boolean enabled;
    public List<SaleLot> lots = new ArrayList<>();
    /** Set once a limit-reached notification has been shown for the current saturation. */
    public boolean limitNotified;
    /** How a reached limit recovers; see {@link SalesTracker.ResetMode}. */
    public SalesTracker.ResetMode resetMode = SalesTracker.ResetMode.WINDOW;
    /** When the limit was first reached, used by the {@code TIMER}/{@code CLOCK} reset modes. */
    public long limitReachedAt;
    /** Start of the fixed 60-minute window, used by the {@code WINDOW} reset mode. */
    public long windowStart;
    /** Legacy single-window fields kept only so older config files can be migrated. */
    public int soldItems;
    public long windowStartedAt;
    /** Legacy pre-v2 price field, migrated into {@link #configuredPrice} on load. */
    @SerializedName("unitPrice")
    public Double legacyUnitPrice;

    public TrackedItem() { }

    public TrackedItem(String id, String name, double price, int limitItems) {
        this.id = id;
        this.name = name;
        this.configuredPrice = price;
        this.limitItems = limitItems;
        this.enabled = true;
    }

    /** Sell price used for revenue estimates: the configured price, or the observed one as fallback. */
    public double effectiveSellPrice() { return configuredPrice > 0 ? configuredPrice : observedPrice; }

    /** Real stack size, clamped to a usable value. */
    public int stackSize() { return StackMath.clampStackSize(stackSize); }

    /** Item capacity of the shared 36-stack limit for this item's stack size. */
    public int limitItems() {
        return limitItems > 0 ? limitItems : StackMath.limitItems(stackSize());
    }

    /** Drops expired lots, migrates legacy fields and clamps invalid values. */
    public void normalize(long now) {
        stackSize = StackMath.clampStackSize(stackSize);
        if (limitItems < 1) limitItems = StackMath.limitItems(stackSize);
        if (limitItems > MAX_LIMIT_ITEMS) limitItems = MAX_LIMIT_ITEMS;
        if (resetMode == null) resetMode = SalesTracker.ResetMode.WINDOW;
        if (legacyUnitPrice != null) {
            if (configuredPrice <= 0 && Double.isFinite(legacyUnitPrice) && legacyUnitPrice > 0) {
                configuredPrice = legacyUnitPrice;
            }
            legacyUnitPrice = null;
        }
        if (!Double.isFinite(configuredPrice) || configuredPrice < 0) configuredPrice = 0;
        if (!Double.isFinite(observedPrice) || observedPrice < 0) observedPrice = 0;
        if (!Double.isFinite(observedBuyPrice) || observedBuyPrice < 0) observedBuyPrice = 0;
        if (lots == null) lots = new ArrayList<>();
        if (lots.isEmpty() && soldItems > 0 && windowStartedAt > 0) lots.add(new SaleLot(windowStartedAt, soldItems));
        soldItems = 0;
        windowStartedAt = 0;

        long active = 0;
        for (SaleLot lot : lots) if (lot != null && lot.amount > 0 && lot.soldAt > 0) active += lot.amount;

        switch (resetMode) {
            case WINDOW -> {
                // Fixed 60-minute window anchored on the first sale: the whole quota returns 60
                // minutes after that first sale, then a fresh window starts at the next sale.
                // Re-anchoring on every pass keeps the window honest when a manual correction
                // removes the sale it was originally anchored on.
                if (active <= 0) {
                    windowStart = 0;
                } else {
                    windowStart = firstSaleAt();
                    if (windowStart > 0 && now - windowStart >= SalesTracker.WINDOW_MILLIS) {
                        lots.clear();
                        active = 0;
                        windowStart = 0;
                    }
                }
            }
            case TIMER -> {
                // The restock countdown starts only once the limit is fully reached, then the whole
                // quota returns at once. Before that, sales simply accumulate.
                if (active >= limitItems() && limitReachedAt <= 0) limitReachedAt = now;
                if (limitReachedAt > 0 && now - limitReachedAt >= SalesTracker.WINDOW_MILLIS) {
                    lots.clear();
                    active = 0;
                    limitReachedAt = 0;
                }
                dropAncientLots(now);
            }
            case CLOCK -> {
                // Fixed-schedule reset: the quota returns at the next hour boundary after saturation.
                if (active >= limitItems() && limitReachedAt <= 0) limitReachedAt = now;
                if (limitReachedAt > 0 && now >= nextClockBoundary(limitReachedAt)) {
                    lots.clear();
                    active = 0;
                    limitReachedAt = 0;
                }
                dropAncientLots(now);
            }
            default -> {
                Iterator<SaleLot> it = lots.iterator();
                while (it.hasNext()) {
                    SaleLot lot = it.next();
                    if (lot == null || lot.amount <= 0 || lot.soldAt <= 0 || now - lot.soldAt >= SalesTracker.WINDOW_MILLIS) it.remove();
                }
                long remaining = 0;
                for (SaleLot lot : lots) remaining += lot.amount;
                active = remaining;
                if (active >= limitItems() && limitReachedAt <= 0) limitReachedAt = now;
            }
        }
        if (active < limitItems()) {
            limitNotified = false;
            limitReachedAt = 0;
        }
    }

    /** Safety net so non-rolling modes cannot grow their lot list without bound. */
    private void dropAncientLots(long now) {
        long maxAge = SalesTracker.WINDOW_MILLIS * 24L;
        Iterator<SaleLot> it = lots.iterator();
        while (it.hasNext()) {
            SaleLot lot = it.next();
            if (lot == null || lot.amount <= 0 || lot.soldAt <= 0 || now - lot.soldAt >= maxAge) it.remove();
        }
    }

    private static long nextClockBoundary(long from) {
        return (from / SalesTracker.WINDOW_MILLIS + 1) * SalesTracker.WINDOW_MILLIS;
    }

    private long firstSaleAt() {
        long oldest = Long.MAX_VALUE;
        for (SaleLot lot : lots) if (lot != null && lot.amount > 0 && lot.soldAt > 0) oldest = Math.min(oldest, lot.soldAt);
        return oldest == Long.MAX_VALUE ? 0 : oldest;
    }

    public int soldItems(long now) {
        normalize(now);
        long total = 0;
        for (SaleLot lot : lots) total += lot.amount;
        return (int) Math.min(Integer.MAX_VALUE, total);
    }

    public int remainingItems(long now) { return Math.max(0, limitItems() - soldItems(now)); }

    public long nextExpiryMillis(long now) {
        normalize(now);
        if (resetMode == SalesTracker.ResetMode.WINDOW) {
            return windowStart > 0 ? Math.max(0, SalesTracker.WINDOW_MILLIS - (now - windowStart)) : 0;
        }
        if (resetMode != SalesTracker.ResetMode.ROLLING) {
            if (limitReachedAt <= 0) return 0;
            long boundary = resetMode == SalesTracker.ResetMode.CLOCK
                    ? nextClockBoundary(limitReachedAt)
                    : limitReachedAt + SalesTracker.WINDOW_MILLIS;
            return Math.max(0, boundary - now);
        }
        long oldest = Long.MAX_VALUE;
        for (SaleLot lot : lots) oldest = Math.min(oldest, lot.soldAt);
        return oldest == Long.MAX_VALUE ? 0 : Math.max(0, SalesTracker.WINDOW_MILLIS - (now - oldest));
    }

    /** Records a sale, merging it into the current lot when the previous one is recent. */
    public void addSale(int amount, long now) {
        if (amount <= 0) return;
        normalize(now);
        boolean merged = false;
        for (int i = lots.size() - 1; i >= 0; i--) {
            SaleLot lot = lots.get(i);
            if (now - lot.soldAt < LOT_MERGE_MILLIS) {
                lot.amount = (int) Math.min(Integer.MAX_VALUE, (long) lot.amount + amount);
                merged = true;
                break;
            }
        }
        if (!merged) lots.add(new SaleLot(now, amount));
        // Start the restock countdown at the sale that reaches the limit, not at the next tick.
        if (resetMode != SalesTracker.ResetMode.ROLLING && limitReachedAt <= 0 && soldTotal() >= limitItems()) {
            limitReachedAt = now;
        }
        // The fixed window is anchored on the very first sale of the period.
        if (resetMode == SalesTracker.ResetMode.WINDOW && windowStart <= 0) windowStart = now;
    }

    private long soldTotal() {
        long total = 0;
        for (SaleLot lot : lots) if (lot != null && lot.amount > 0) total += lot.amount;
        return total;
    }

    public void adjustLatestSale(int delta, long now) {
        normalize(now);
        if (delta > 0) { addSale(delta, now); return; }
        int remove = -delta;
        for (int i = lots.size() - 1; i >= 0 && remove > 0; i--) {
            SaleLot lot = lots.get(i);
            int taken = Math.min(remove, lot.amount);
            lot.amount -= taken;
            remove -= taken;
            if (lot.amount <= 0) lots.remove(i);
        }
    }

    public void reset() {
        lots.clear();
        soldItems = 0;
        windowStartedAt = 0;
        limitNotified = false;
        limitReachedAt = 0;
        windowStart = 0;
    }

    /** Revenue of the whole quota at the effective sell price. */
    public double maximumRevenue() { return StackMath.revenue(limitItems(), effectiveSellPrice()); }

    /** Derived economics view for this item. */
    public ItemEconomics economics(long now) { return ItemEconomics.of(this, now); }

    public static final class SaleLot {
        public long soldAt;
        public int amount;

        public SaleLot() { }

        public SaleLot(long soldAt, int amount) { this.soldAt = soldAt; this.amount = amount; }
    }
}
