package fr.venstone.economy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemEconomicsTest {
    private static final long NOW = 1_700_000_000_000L;

    private static TrackedItem item(double price, int stackSize) {
        TrackedItem item = new TrackedItem("minecraft:cactus", "Cactus", price, StackMath.limitItems(stackSize));
        item.stackSize = stackSize;
        return item;
    }

    @Test void limitIsAlwaysThirtySixStacks() {
        ItemEconomics eco = ItemEconomics.of(item(0.05, 64), NOW);
        assertEquals(36, eco.limitStacks());
        assertEquals(2304, eco.limitItems());
    }

    @Test void nonStandardStackSizeChangesTheItemCountNotTheStackCount() {
        ItemEconomics eco = ItemEconomics.of(item(0.02, 16), NOW);
        assertEquals(36, eco.limitStacks());
        assertEquals(576, eco.limitItems());
    }

    @Test void unitStackAndLotPricesStayDistinct() {
        ItemEconomics eco = ItemEconomics.of(item(0.12, 64), NOW);
        assertEquals(0.12, eco.unitPrice(), 1e-9);
        assertEquals(7.68, eco.pricePerStack(), 1e-9);
        assertEquals(276.48, eco.maxRevenue(), 1e-9);
    }

    @Test void marginRequiresAKnownBuyPrice() {
        TrackedItem noBuy = item(0.05, 64);
        assertEquals(0.0, ItemEconomics.of(noBuy, NOW).unitMargin(), 1e-9);
        assertEquals(0.0, ItemEconomics.of(noBuy, NOW).maxMargin(), 1e-9);

        TrackedItem withBuy = item(0.05, 64);
        withBuy.observedBuyPrice = 0.02;
        ItemEconomics eco = ItemEconomics.of(withBuy, NOW);
        assertEquals(0.03, eco.unitMargin(), 1e-9);
        assertEquals(2304 * 0.03, eco.maxMargin(), 1e-9);
    }

    @Test void negativeMarginIsReportedNotHidden() {
        TrackedItem item = item(0.01, 64);
        item.observedBuyPrice = 0.05;
        ItemEconomics eco = ItemEconomics.of(item, NOW);
        assertTrue(eco.unitMargin() < 0);
    }

    @Test void remainingAndEarnedRevenueAddUpToTheMaximum() {
        TrackedItem item = item(0.12, 64);
        item.addSale(1000, NOW);
        ItemEconomics eco = ItemEconomics.of(item, NOW);
        assertEquals(2304, eco.limitItems());
        assertEquals(1304, eco.remainingItems());
        assertEquals(120.0, eco.earnedRevenue(), 1e-9);
        assertEquals(156.48, eco.remainingRevenue(), 1e-9);
        assertEquals(276.48, eco.maxRevenue(), 1e-9);
    }

    @Test void soldIsClampedToTheLimit() {
        TrackedItem item = item(0.12, 64);
        item.addSale(100_000, NOW);
        ItemEconomics eco = ItemEconomics.of(item, NOW);
        assertEquals(0, eco.remainingItems());
        assertEquals(eco.limitItems(), eco.soldItems());
    }

    @Test void unknownPriceIsNotSellable() {
        TrackedItem item = new TrackedItem("minecraft:cactus", "Cactus", 0, StackMath.limitItems(64));
        ItemEconomics eco = ItemEconomics.of(item, NOW);
        assertFalse(eco.isSellable());
        assertEquals(0.0, eco.maxRevenue(), 1e-9);
    }

    @Test void nullItemYieldsAnEmptyView() {
        ItemEconomics eco = ItemEconomics.of(null, NOW);
        assertEquals(0, eco.limitItems());
        assertEquals(0.0, eco.maxRevenue(), 1e-9);
        assertFalse(eco.isSellable());
    }

    @Test void observedPriceIsUsedWhenNoConfiguredPrice() {
        TrackedItem item = new TrackedItem("minecraft:cactus", "Cactus", 0, StackMath.limitItems(64));
        item.observedPrice = 0.07;
        ItemEconomics eco = ItemEconomics.of(item, NOW);
        assertTrue(eco.isSellable());
        assertEquals(0.07, eco.unitPrice(), 1e-9);
    }
}
