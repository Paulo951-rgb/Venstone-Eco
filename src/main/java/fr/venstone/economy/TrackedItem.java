package fr.venstone.economy;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import com.google.gson.annotations.SerializedName;

/** One tracked shop item: its configured values plus its rolling one-hour sale history. */
public final class TrackedItem {
    /** Items per Minecraft stack; used for display and stack-based input. */
    public static final int ITEMS_PER_STACK = 64;
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
    /** Last unit price observed in a confirmed sale, or {@code 0} when never seen. */
    public double observedPrice;
    /** Maximum number of items allowed in the rolling window. */
    public int limitItems;
    public boolean enabled;
    public List<SaleLot> lots = new ArrayList<>();
    /** Set once a limit-reached notification has been shown for the current saturation. */
    public boolean limitNotified;
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

    /** Price used for revenue estimates: the configured price, or the observed one as a fallback. */
    public double effectivePrice() { return configuredPrice > 0 ? configuredPrice : observedPrice; }

    /** Drops expired lots, migrates legacy fields and clamps invalid values. */
    public void normalize(long now) {
        if (limitItems < 1) limitItems = ITEMS_PER_STACK * 36;
        if (limitItems > MAX_LIMIT_ITEMS) limitItems = MAX_LIMIT_ITEMS;
        if (legacyUnitPrice != null) {
            if (configuredPrice <= 0 && Double.isFinite(legacyUnitPrice) && legacyUnitPrice > 0) {
                configuredPrice = legacyUnitPrice;
            }
            legacyUnitPrice = null;
        }
        if (!Double.isFinite(configuredPrice) || configuredPrice < 0) configuredPrice = 0;
        if (!Double.isFinite(observedPrice) || observedPrice < 0) observedPrice = 0;
        if (lots == null) lots = new ArrayList<>();
        if (lots.isEmpty() && soldItems > 0 && windowStartedAt > 0) lots.add(new SaleLot(windowStartedAt, soldItems));
        soldItems = 0;
        windowStartedAt = 0;
        Iterator<SaleLot> it = lots.iterator();
        while (it.hasNext()) {
            SaleLot lot = it.next();
            if (lot == null || lot.amount <= 0 || lot.soldAt <= 0 || now - lot.soldAt >= SalesTracker.WINDOW_MILLIS) it.remove();
        }
        long active = 0;
        for (SaleLot lot : lots) active += lot.amount;
        if (active < limitItems) limitNotified = false;
    }

    public int soldItems(long now) {
        normalize(now);
        long total = 0;
        for (SaleLot lot : lots) total += lot.amount;
        return (int) Math.min(Integer.MAX_VALUE, total);
    }

    public int remainingItems(long now) { return Math.max(0, limitItems - soldItems(now)); }

    public long nextExpiryMillis(long now) {
        normalize(now);
        long oldest = Long.MAX_VALUE;
        for (SaleLot lot : lots) oldest = Math.min(oldest, lot.soldAt);
        return oldest == Long.MAX_VALUE ? 0 : Math.max(0, SalesTracker.WINDOW_MILLIS - (now - oldest));
    }

    /** Records a sale, merging it into the current lot when the previous one is recent. */
    public void addSale(int amount, long now) {
        if (amount <= 0) return;
        normalize(now);
        for (int i = lots.size() - 1; i >= 0; i--) {
            SaleLot lot = lots.get(i);
            if (now - lot.soldAt < LOT_MERGE_MILLIS) {
                lot.amount = (int) Math.min(Integer.MAX_VALUE, (long) lot.amount + amount);
                return;
            }
        }
        lots.add(new SaleLot(now, amount));
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
    }

    public double maximumRevenue() { return limitItems * effectivePrice(); }

    public static final class SaleLot {
        public long soldAt;
        public int amount;

        public SaleLot() { }

        public SaleLot(long soldAt, int amount) { this.soldAt = soldAt; this.amount = amount; }
    }
}
