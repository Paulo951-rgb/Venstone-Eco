package fr.venstone.economy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrackedItemTest {
    private static final long NOW = 1_000_000_000_000L;

    @Test void saleExpiresAfterOneHour() {
        TrackedItem item = new TrackedItem("minecraft:cactus", "Cactus", 0.05, 64 * 36);
        item.addSale(64, NOW);
        assertEquals(64, item.soldItems(NOW + 1));
        assertEquals(64, item.soldItems(NOW + SalesTracker.WINDOW_MILLIS - 1));
        assertEquals(0, item.soldItems(NOW + SalesTracker.WINDOW_MILLIS));
    }

    @Test void eachLotExpiresOnItsOwnSchedule() {
        TrackedItem item = new TrackedItem("minecraft:cactus", "Cactus", 0.05, 64 * 36);
        item.addSale(10, NOW);
        item.addSale(20, NOW + 30 * 60_000L);
        assertEquals(30, item.soldItems(NOW + 31 * 60_000L));
        assertEquals(20, item.soldItems(NOW + SalesTracker.WINDOW_MILLIS));
        assertEquals(0, item.soldItems(NOW + SalesTracker.WINDOW_MILLIS + 30 * 60_000L));
    }

    @Test void remainingNeverGoesNegative() {
        TrackedItem item = new TrackedItem("minecraft:cactus", "Cactus", 0.05, 100);
        item.addSale(500, NOW);
        assertEquals(0, item.remainingItems(NOW));
    }

    @Test void adjustLatestSaleRemovesAcrossLots() {
        TrackedItem item = new TrackedItem("minecraft:cactus", "Cactus", 0.05, 1000);
        item.addSale(10, NOW);
        item.addSale(20, NOW + 1);
        item.adjustLatestSale(-15, NOW + 2);
        assertEquals(15, item.soldItems(NOW + 3));
    }

    @Test void adjustLatestSaleClampsAtZero() {
        TrackedItem item = new TrackedItem("minecraft:cactus", "Cactus", 0.05, 1000);
        item.addSale(10, NOW);
        item.adjustLatestSale(-999, NOW + 1);
        assertEquals(0, item.soldItems(NOW + 2));
    }

    @Test void nextExpiryTracksOldestLot() {
        TrackedItem item = new TrackedItem("minecraft:cactus", "Cactus", 0.05, 64 * 36);
        item.addSale(1, NOW);
        item.addSale(1, NOW + 1000);
        assertEquals(SalesTracker.WINDOW_MILLIS - 500, item.nextExpiryMillis(NOW + 500));
    }

    @Test void normalizeRepairsInvalidValues() {
        TrackedItem item = new TrackedItem("x", "x", Double.NaN, 0);
        item.lots = null;
        assertEquals(0, item.soldItems(NOW));
        assertTrue(item.limitItems > 0);
        assertEquals(0.0, item.unitPrice, 1e-9);
        assertTrue(item.lots != null);
    }

    @Test void normalizeMigratesLegacyWindow() {
        TrackedItem item = new TrackedItem("x", "x", 1.0, 1000);
        item.soldItems = 42;
        item.windowStartedAt = NOW;
        assertEquals(42, item.soldItems(NOW + 1000));
        assertEquals(0, item.soldItems(NOW + SalesTracker.WINDOW_MILLIS + 1));
    }

    @Test void resetClearsHistory() {
        TrackedItem item = new TrackedItem("x", "x", 1.0, 1000);
        item.addSale(50, NOW);
        item.reset();
        assertEquals(0, item.soldItems(NOW));
    }
}
