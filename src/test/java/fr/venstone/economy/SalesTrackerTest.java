package fr.venstone.economy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SalesTrackerTest {
    private static final long NOW = 1_700_000_000_000L;

    @TempDir Path dir;

    /** Controllable clock so time-based behaviour is deterministic. */
    private static final class TestClock implements Clock {
        private long value;
        TestClock(long start) { this.value = start; }
        @Override public long now() { return value; }
        void advance(long millis) { value += millis; }
    }

    private SalesTracker tracker;
    private TestClock clock;
    private Path file;

    private SalesTracker fresh() {
        file = dir.resolve("config.json");
        clock = new TestClock(NOW);
        tracker = new SalesTracker(file, clock);
        return tracker;
    }

    @Test void seedsDefaultsOnFirstRunOnly() throws Exception {
        SalesTracker seeded = fresh();
        assertFalse(seeded.items().isEmpty());
        assertTrue(Files.exists(file));

        Files.writeString(file, "");
        SalesTracker reloaded = new SalesTracker(file, new TestClock(NOW));
        assertTrue(reloaded.items().isEmpty());
    }

    @Test void addItemRejectsInvalidInput() {
        SalesTracker t = fresh();
        int before = t.items().size();
        assertFalse(t.addItem("", "x", 1, 1));
        assertFalse(t.addItem("  ", "x", 1, 1));
        assertFalse(t.addItem("bad id!", "x", 1, 1));
        assertFalse(t.addItem("minecraft:CACTUS", "dup", 1, 1));
        assertEquals(before, t.items().size());
    }

    @Test void addItemSanitizesValues() {
        SalesTracker t = fresh();
        assertTrue(t.addItem("  MineCraft:Diamond  ", "", 1_000_000_000d, 999_999));
        TrackedItem diamond = t.findByNameOrId("minecraft:diamond");
        assertNotNull(diamond);
        assertEquals("minecraft:diamond", diamond.id);
        assertEquals("diamond", diamond.name);
        assertEquals(ShopTextParser.MAX_UNIT_PRICE, diamond.configuredPrice, 1e-9);
        assertTrue(diamond.limitItems <= TrackedItem.MAX_LIMIT_ITEMS);
    }

    @Test void addItemSelectsTheNewItem() {
        SalesTracker t = fresh();
        t.addItem("minecraft:diamond", "Diamant", 5, 1);
        assertEquals("minecraft:diamond", t.selected().id);
    }

    @Test void removeSelectedKeepsSelectionValid() {
        SalesTracker t = fresh();
        int size = t.items().size();
        t.selected();
        t.removeSelected();
        assertEquals(size - 1, t.items().size());
        assertNotNull(t.selected());
    }

    @Test void selectionWrapsAround() {
        SalesTracker t = fresh();
        int size = t.items().size();
        t.select(size - 1);
        t.selectNext();
        assertEquals(0, t.selectedIndex());
        t.selectPrevious();
        assertEquals(size - 1, t.selectedIndex());
    }

    @Test void recordSaleUpdatesKnownItem() {
        SalesTracker t = fresh();
        assertTrue(t.recordConfirmedSale("Vous avez vendu 10 x Cactus pour 5 €"));
        TrackedItem cactus = t.findByNameOrId("minecraft:cactus");
        assertNotNull(cactus);
        assertEquals(10, cactus.soldItems(clock.now()));
        assertEquals(0.5, cactus.observedPrice, 1e-9);
        // A confirmed sale must never overwrite the user's configured price.
        assertEquals(0.05, cactus.configuredPrice, 1e-9);
    }

    @Test void recordSaleIgnoresUnknownItem() {
        assertFalse(fresh().recordConfirmedSale("Vous avez vendu 10 x Blaze Rod pour 5 €"));
    }

    @Test void recordSaleIgnoresDisabledItem() {
        SalesTracker t = fresh();
        t.findByNameOrId("minecraft:cactus").enabled = false;
        assertFalse(t.recordConfirmedSale("Vous avez vendu 10 x Cactus pour 5 €"));
    }

    @Test void recordSaleDeduplicatesRapidDuplicate() {
        SalesTracker t = fresh();
        String message = "Vous avez vendu 10 x Cactus pour 5 €";
        assertTrue(t.recordConfirmedSale(message));
        assertFalse(t.recordConfirmedSale(message));
        assertEquals(10, t.findByNameOrId("minecraft:cactus").soldItems(clock.now()));
    }

    @Test void recordSaleAllowsRepeatedSaleAfterWindow() {
        SalesTracker t = fresh();
        String message = "Vous avez vendu 10 x Cactus pour 5 €";
        assertTrue(t.recordConfirmedSale(message));
        clock.advance(5_000);
        assertTrue(t.recordConfirmedSale(message));
        assertEquals(20, t.findByNameOrId("minecraft:cactus").soldItems(clock.now()));
    }

    @Test void applyShopPricesReadsBothPricesButNeverTheLimit() {
        SalesTracker t = fresh();
        t.applyShopPrices("minecraft:cactus", 0.42, 0.60);
        TrackedItem cactus = t.findByNameOrId("minecraft:cactus");
        assertNotNull(cactus);
        assertEquals(0.42, cactus.observedPrice, 1e-9);
        assertEquals(0.60, cactus.observedBuyPrice, 1e-9);
        // The sell limit is universal (36 stacks), so no lore limit can ever change it.
        assertEquals(StackMath.limitItems(cactus.stackSize()), cactus.limitItems);
    }

    @Test void applyShopPricesIgnoresAbsurdValuesAndUnknownItems() {
        SalesTracker t = fresh();
        t.applyShopPrices("minecraft:cactus", ShopTextParser.MAX_UNIT_PRICE + 1, -3.0);
        assertEquals(0.0, t.findByNameOrId("minecraft:cactus").observedPrice, 1e-9);
        assertEquals(0.0, t.findByNameOrId("minecraft:cactus").observedBuyPrice, 1e-9);
        t.applyShopPrices("minecraft:not_tracked", 1.0, 2.0);
        assertNull(t.findByNameOrId("minecraft:not_tracked"));
    }

    @Test void tickExpiresLotsAndPersists() {
        SalesTracker t = fresh();
        t.addSale(t.findByNameOrId("minecraft:cactus"), 64);
        clock.advance(SalesTracker.WINDOW_MILLIS + 1);
        t.tick();
        assertEquals(0, t.findByNameOrId("minecraft:cactus").soldItems(clock.now()));
        SalesTracker reloaded = new SalesTracker(file, new TestClock(clock.now()));
        assertEquals(0, reloaded.findByNameOrId("minecraft:cactus").soldItems(clock.now()));
    }

    @Test void persistsAndReloads() {
        SalesTracker t = fresh();
        t.addItem("minecraft:wheat", "Blé", 0.33, 10);

        SalesTracker reloaded = new SalesTracker(file, new TestClock(NOW));
        TrackedItem wheat = reloaded.findByNameOrId("minecraft:wheat");
        assertNotNull(wheat);
        assertEquals(0.33, wheat.configuredPrice, 1e-9);
        // The sell limit is universal: it is re-derived as 36 stacks, not from the old argument.
        assertEquals(StackMath.limitItems(10), wheat.limitItems);
    }

    @Test void persistsHudVisibility() {
        SalesTracker t = fresh();
        t.setHudVisible(false);
        t.save();
        assertFalse(new SalesTracker(file, new TestClock(NOW)).hudVisible());
    }

    @Test void persistsSelectedIndex() {
        SalesTracker t = fresh();
        t.select(2);
        t.save();
        assertEquals(2, new SalesTracker(file, new TestClock(NOW)).selectedIndex());
    }

    @Test void persistsHudCorner() {
        SalesTracker t = fresh();
        t.setHudCorner(SalesTracker.HudCorner.BOTTOM_LEFT);
        t.save();
        assertEquals(SalesTracker.HudCorner.BOTTOM_LEFT, new SalesTracker(file, new TestClock(NOW)).hudCorner());
    }

    @Test void migratesLegacyArrayConfig() throws Exception {
        Path legacy = dir.resolve("legacy.json");
        Files.writeString(legacy, "[{\"id\":\"minecraft:cactus\",\"name\":\"Cactus\",\"unitPrice\":0.05,\"limitItems\":2304}]");
        SalesTracker t = new SalesTracker(legacy, new TestClock(NOW));
        TrackedItem cactus = t.findByNameOrId("minecraft:cactus");
        assertNotNull(cactus);
        assertEquals(0.05, cactus.configuredPrice, 1e-9);
    }

    @Test void survivesCorruptConfig() throws Exception {
        Path corrupt = dir.resolve("corrupt.json");
        Files.writeString(corrupt, "{ this is not valid json ]");
        SalesTracker t = new SalesTracker(corrupt, new TestClock(NOW));
        assertTrue(t.items().isEmpty());
    }

    @Test void toleratesWronglyTypedFields() throws Exception {
        Path typed = dir.resolve("typed.json");
        Files.writeString(typed, "{\"schemaVersion\":\"two\",\"selectedIndex\":\"x\",\"hudVisible\":\"yes\","
                + "\"hudCorner\":7,\"items\":[{\"id\":\"minecraft:cactus\",\"limitItems\":64}]}");
        SalesTracker t = new SalesTracker(typed, new TestClock(NOW));
        assertEquals(1, t.items().size());
        assertEquals("minecraft:cactus", t.items().get(0).id);
    }

    @Test void skipsMalformedItemButKeepsTheRest() throws Exception {
        Path mixed = dir.resolve("mixed.json");
        Files.writeString(mixed, "{\"schemaVersion\":2,\"items\":[\"not-an-object\",{\"id\":\"minecraft:cactus\"}]}");
        SalesTracker t = new SalesTracker(mixed, new TestClock(NOW));
        assertEquals(1, t.items().size());
        assertEquals("minecraft:cactus", t.items().get(0).id);
    }

    @Test void ignoresNewerSchemaWithoutDataLoss() throws Exception {
        Path future = dir.resolve("future.json");
        Files.writeString(future, "{\"schemaVersion\":99,\"items\":[{\"id\":\"minecraft:cactus\"}]}");
        SalesTracker t = new SalesTracker(future, new TestClock(NOW));
        assertEquals(1, t.items().size());
    }

    @Test void reportsSaveErrors() throws Exception {
        // Parent path is a regular file, so the directory cannot be created and the save must fail.
        Path notADirectory = dir.resolve("not-a-dir");
        Files.writeString(notADirectory, "x");
        SalesTracker t = new SalesTracker(notADirectory.resolve("config.json"), new TestClock(NOW));
        t.save();
        assertNotNull(t.lastError());
        assertTrue(t.hasRecentError());
    }

    @Test void findByNamePrefersLongestMatch() {
        SalesTracker t = fresh();
        t.addItem("minecraft:melon", "Melon", 0.2, 1);
        t.addItem("minecraft:melon_slice", "Tranche", 0.1, 1);
        assertEquals("minecraft:melon_slice", t.findByNameOrId("melon slice").id);
    }

    @Test void findByNameMatchesIdPathAndDisplayName() {
        SalesTracker t = fresh();
        assertEquals("minecraft:sugar_cane", t.findByNameOrId("Canne à sucre").id);
        assertEquals("minecraft:sugar_cane", t.findByNameOrId("sugar cane").id);
        assertEquals("minecraft:sugar_cane", t.findByNameOrId("minecraft:sugar_cane").id);
        assertNull(t.findByNameOrId("inconnu"));
        assertNull(t.findByNameOrId(""));
        assertNull(t.findByNameOrId(null));
    }

    @Test void clockSurvivesWallJumpBackwards() {
        // A monotonic clock must not expire lots early when the wall clock jumps back.
        SalesTracker t = fresh();
        t.addSale(t.findByNameOrId("minecraft:cactus"), 64);
        clock.advance(1_000);
        assertEquals(64, t.findByNameOrId("minecraft:cactus").soldItems(clock.now()));
    }
}
