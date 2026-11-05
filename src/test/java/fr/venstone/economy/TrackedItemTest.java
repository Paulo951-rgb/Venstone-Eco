package fr.venstone.economy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrackedItemTest {
    private static final long NOW = 1_000_000_000_000L;

    private static TrackedItem item(int limitItems) {
        return new TrackedItem("minecraft:cactus", "Cactus", 0.05, limitItems);
    }

    @Test void saleExpiresAfterOneHour() {
        TrackedItem item = item(64 * 36);
        item.addSale(64, NOW);
        assertEquals(64, item.soldItems(NOW + 1));
        assertEquals(64, item.soldItems(NOW + SalesTracker.WINDOW_MILLIS - 1));
        assertEquals(0, item.soldItems(NOW + SalesTracker.WINDOW_MILLIS));
    }

    @Test void eachLotExpiresOnItsOwnSchedule() {
        TrackedItem item = item(64 * 36);
        item.addSale(10, NOW);
        item.addSale(20, NOW + 30 * 60_000L);
        assertEquals(30, item.soldItems(NOW + 31 * 60_000L));
        assertEquals(20, item.soldItems(NOW + SalesTracker.WINDOW_MILLIS));
        assertEquals(0, item.soldItems(NOW + SalesTracker.WINDOW_MILLIS + 30 * 60_000L));
    }

    @Test void rapidSalesMergeIntoOneLot() {
        TrackedItem item = item(64 * 36);
        item.addSale(10, NOW);
        item.addSale(5, NOW + 1_000);
        assertEquals(1, item.lots.size());
        assertEquals(15, item.soldItems(NOW + 2_000));
    }

    @Test void distantSalesUseSeparateLots() {
        TrackedItem item = item(64 * 36);
        item.addSale(10, NOW);
        item.addSale(5, NOW + 5 * 60_000L);
        assertEquals(2, item.lots.size());
    }

    @Test void remainingNeverGoesNegative() {
        TrackedItem item = item(100);
        item.addSale(500, NOW);
        assertEquals(0, item.remainingItems(NOW));
    }

    @Test void adjustLatestSaleRemovesAcrossLots() {
        TrackedItem item = item(1000);
        item.addSale(10, NOW);
        item.addSale(20, NOW + 5 * 60_000L);
        item.adjustLatestSale(-15, NOW + 5 * 60_000L + 1);
        assertEquals(15, item.soldItems(NOW + 5 * 60_000L + 2));
    }

    @Test void adjustLatestSaleClampsAtZero() {
        TrackedItem item = item(1000);
        item.addSale(10, NOW);
        item.adjustLatestSale(-999, NOW + 1);
        assertEquals(0, item.soldItems(NOW + 2));
    }

    @Test void nextExpiryTracksOldestLot() {
        TrackedItem item = item(64 * 36);
        item.addSale(1, NOW);
        item.addSale(1, NOW + 1000);
        assertEquals(SalesTracker.WINDOW_MILLIS - 500, item.nextExpiryMillis(NOW + 500));
    }

    @Test void normalizeRepairsInvalidValues() {
        TrackedItem item = new TrackedItem("x", "x", Double.NaN, 0);
        item.lots = null;
        assertEquals(0, item.soldItems(NOW));
        assertTrue(item.limitItems > 0);
        assertEquals(0.0, item.configuredPrice, 1e-9);
        assertTrue(item.lots != null);
    }

    @Test void normalizeMigratesLegacyWindow() {
        TrackedItem item = new TrackedItem("x", "x", 1.0, 1000);
        item.soldItems = 42;
        item.windowStartedAt = NOW;
        assertEquals(42, item.soldItems(NOW + 1000));
        assertEquals(0, item.soldItems(NOW + SalesTracker.WINDOW_MILLIS + 1));
    }

    @Test void normalizeMigratesLegacyPrice() {
        TrackedItem item = new TrackedItem();
        item.id = "minecraft:cactus";
        item.name = "Cactus";
        item.limitItems = 1000;
        item.legacyUnitPrice = 0.42;
        item.normalize(NOW);
        assertEquals(0.42, item.configuredPrice, 1e-9);
        assertEquals(0.42, item.effectivePrice(), 1e-9);
    }

    @Test void resetClearsHistoryAndNotification() {
        TrackedItem item = item(64);
        item.addSale(50, NOW);
        item.limitNotified = true;
        item.reset();
        assertEquals(0, item.soldItems(NOW));
        assertFalse(item.limitNotified);
    }

    @Test void normalizeClearsNotificationOnceSpaceIsFree() {
        TrackedItem item = item(64);
        item.addSale(64, NOW);
        assertEquals(0, item.remainingItems(NOW));
        item.limitNotified = true;
        item.normalize(NOW + SalesTracker.WINDOW_MILLIS);
        assertFalse(item.limitNotified);
    }

    @Test void revenueUsesConfiguredThenObservedPrice() {
        TrackedItem item = item(64);
        item.configuredPrice = 0;
        item.observedPrice = 0.5;
        assertEquals(32.0, item.maximumRevenue(), 1e-9);
        item.configuredPrice = 1.0;
        assertEquals(64.0, item.maximumRevenue(), 1e-9);
    }

    @Test void clampLimitToCeiling() {
        TrackedItem item = new TrackedItem("x", "x", 1.0, Integer.MAX_VALUE);
        item.normalize(NOW);
        assertEquals(TrackedItem.MAX_LIMIT_ITEMS, item.limitItems);
    }

    // --- Reset models: Venstone's shop (EconomyShopGUI) has no fixed sliding window. ---

    @Test void timerModeRestocksOnceLimitIsReached() {
        TrackedItem item = item(64);
        item.resetMode = SalesTracker.ResetMode.TIMER;
        item.addSale(64, NOW);
        assertEquals(0, item.remainingItems(NOW + 1));
        // Just before the restock countdown completes, the item is still capped.
        assertEquals(64, item.soldItems(NOW + SalesTracker.WINDOW_MILLIS - 1));
        // The whole quota returns at once once the countdown elapses.
        assertEquals(0, item.soldItems(NOW + SalesTracker.WINDOW_MILLIS));
        assertEquals(64, item.remainingItems(NOW + SalesTracker.WINDOW_MILLIS));
    }

    @Test void timerModeDoesNotExpireBeforeLimitIsReached() {
        TrackedItem item = item(1000);
        item.resetMode = SalesTracker.ResetMode.TIMER;
        item.addSale(10, NOW);
        // Sales accumulate indefinitely until the limit is hit, unlike the rolling model.
        assertEquals(10, item.soldItems(NOW + SalesTracker.WINDOW_MILLIS * 3));
    }

    @Test void clockModeResetsAtTheNextHourBoundary() {
        TrackedItem item = item(64);
        item.resetMode = SalesTracker.ResetMode.CLOCK;
        item.addSale(64, NOW);
        long boundary = (NOW / SalesTracker.WINDOW_MILLIS + 1) * SalesTracker.WINDOW_MILLIS;
        assertEquals(64, item.soldItems(boundary - 1));
        assertEquals(0, item.soldItems(boundary));
    }

    @Test void nextExpiryReflectsResetMode() {
        TrackedItem rolling = item(64);
        rolling.addSale(1, NOW);
        assertEquals(SalesTracker.WINDOW_MILLIS - 1000, rolling.nextExpiryMillis(NOW + 1000));

        TrackedItem timer = item(64);
        timer.resetMode = SalesTracker.ResetMode.TIMER;
        timer.addSale(64, NOW);
        assertEquals(SalesTracker.WINDOW_MILLIS - 1000, timer.nextExpiryMillis(NOW + 1000));
    }
}
