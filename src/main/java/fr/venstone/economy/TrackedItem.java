package fr.venstone.economy;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public final class TrackedItem {
    /** Items per Minecraft stack; used only for display and stack-based input. */
    public static final int ITEMS_PER_STACK = 64;
    /** Sanity ceiling for a configured limit (in items). */
    public static final int MAX_LIMIT_ITEMS = 64 * 100_000;

    public String id;
    public String name;
    public double unitPrice;
    public int limitItems;
    public boolean enabled;
    public List<SaleLot> lots = new ArrayList<>();
    /** Legacy single-window fields kept only so older config files can be migrated. */
    public int soldItems;
    public long windowStartedAt;

    public TrackedItem(String id, String name, double unitPrice, int limitItems) {
        this.id = id; this.name = name; this.unitPrice = unitPrice; this.limitItems = limitItems;
        this.enabled = true; this.lots = new ArrayList<>();
    }
    public void normalize(long now) {
        if (limitItems < 1) limitItems = 64 * 36;
        if (limitItems > MAX_LIMIT_ITEMS) limitItems = MAX_LIMIT_ITEMS;
        if (!Double.isFinite(unitPrice) || unitPrice < 0) unitPrice = 0;
        if (lots == null) lots = new ArrayList<>();
        if (lots.isEmpty() && soldItems > 0 && windowStartedAt > 0) lots.add(new SaleLot(windowStartedAt, soldItems));
        soldItems = 0; windowStartedAt = 0;
        Iterator<SaleLot> it = lots.iterator();
        while (it.hasNext()) {
            SaleLot lot = it.next();
            if (lot == null || lot.amount <= 0 || lot.soldAt <= 0 || now - lot.soldAt >= SalesTracker.WINDOW_MILLIS) it.remove();
        }
    }
    public int soldItems(long now) {
        normalize(now); long total = 0;
        for (SaleLot lot : lots) total += lot.amount;
        return (int)Math.min(Integer.MAX_VALUE, total);
    }
    public int remainingItems(long now) { return Math.max(0, limitItems - soldItems(now)); }
    public long nextExpiryMillis(long now) {
        normalize(now); long oldest = Long.MAX_VALUE;
        for (SaleLot lot : lots) oldest = Math.min(oldest, lot.soldAt);
        return oldest == Long.MAX_VALUE ? 0 : Math.max(0, SalesTracker.WINDOW_MILLIS - (now - oldest));
    }
    public long nextExpiryAt(long now) {
        long remaining = nextExpiryMillis(now); return remaining == 0 ? 0 : now + remaining;
    }
    public long totalActiveExpiryHorizon(long now) {
        normalize(now); long latest = 0;
        for (SaleLot lot : lots) latest = Math.max(latest, lot.soldAt + SalesTracker.WINDOW_MILLIS);
        return Math.max(0, latest - now);
    }
    public void addSale(int amount, long now) { if (amount > 0) { normalize(now); lots.add(new SaleLot(now, amount)); } }
    public void adjustLatestSale(int delta, long now) {
        normalize(now);
        if (delta > 0) { addSale(delta, now); return; }
        int remove = -delta;
        for (int i = lots.size() - 1; i >= 0 && remove > 0; i--) {
            SaleLot lot = lots.get(i); int taken = Math.min(remove, lot.amount);
            lot.amount -= taken; remove -= taken;
            if (lot.amount <= 0) lots.remove(i);
        }
    }
    public void reset() { lots.clear(); soldItems = 0; windowStartedAt = 0; }
    public int soldStacks(long now) { return soldItems(now) / 64; }
    public int remainingFullStacks(long now) { return remainingItems(now) / 64; }
    public double maximumRevenue() { return limitItems * unitPrice; }
    public double remainingPotentialRevenue(long now) { return remainingItems(now) * unitPrice; }
    public static final class SaleLot {
        public long soldAt; public int amount;
        public SaleLot(long soldAt, int amount) { this.soldAt = soldAt; this.amount = amount; }
    }
}
