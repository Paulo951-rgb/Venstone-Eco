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
    @TempDir Path dir;

    private SalesTracker fresh() { return new SalesTracker(dir.resolve("config.json")); }

    @Test void seedsDefaultsOnFirstRunOnly() throws Exception {
        SalesTracker tracker = fresh();
        assertFalse(tracker.items().isEmpty());

        Path file = dir.resolve("config.json");
        assertTrue(Files.exists(file));

        // An existing but empty file must not be re-seeded.
        Files.writeString(file, "");
        SalesTracker reloaded = new SalesTracker(file);
        assertTrue(reloaded.items().isEmpty());
    }

    @Test void addItemRejectsInvalidInput() {
        SalesTracker tracker = fresh();
        int before = tracker.items().size();
        assertFalse(tracker.addItem("", "x", 1, 1));
        assertFalse(tracker.addItem("  ", "x", 1, 1));
        assertFalse(tracker.addItem("minecraft:zombie", "x", 1, 0));
        assertFalse(tracker.addItem("minecraft:CACTUS", "dup", 1, 1));
        assertEquals(before, tracker.items().size());
    }

    @Test void addItemSanitizesValues() {
        SalesTracker tracker = fresh();
        assertTrue(tracker.addItem("  MineCraft:Diamond  ", "", 1_000_000_000d, 999_999));
        TrackedItem item = tracker.findByNameOrId("minecraft:diamond");
        assertNotNull(item);
        assertEquals("minecraft:diamond", item.id);
        assertEquals("minecraft:diamond", item.name);
        assertEquals(ShopTextParser.MAX_UNIT_PRICE, item.unitPrice, 1e-9);
        assertTrue(item.limitItems <= TrackedItem.MAX_LIMIT_ITEMS);
    }

    @Test void removeSelectedKeepsSelectionValid() {
        SalesTracker tracker = fresh();
        int size = tracker.items().size();
        tracker.selected();
        tracker.removeSelected();
        assertEquals(size - 1, tracker.items().size());
        assertNotNull(tracker.selected());
    }

    @Test void recordSaleUpdatesKnownItem() {
        SalesTracker tracker = fresh();
        assertTrue(tracker.recordConfirmedSale("Vous avez vendu 10 x Cactus pour 5 €"));
        TrackedItem cactus = tracker.findByNameOrId("minecraft:cactus");
        assertNotNull(cactus);
        assertEquals(10, cactus.soldItems(System.currentTimeMillis()));
        assertEquals(0.5, cactus.unitPrice, 1e-9);
    }

    @Test void recordSaleIgnoresUnknownItem() {
        SalesTracker tracker = fresh();
        assertFalse(tracker.recordConfirmedSale("Vous avez vendu 10 x Blaze Rod pour 5 €"));
    }

    @Test void recordSaleIgnoresDisabledItem() {
        SalesTracker tracker = fresh();
        TrackedItem cactus = tracker.findByNameOrId("minecraft:cactus");
        cactus.enabled = false;
        assertFalse(tracker.recordConfirmedSale("Vous avez vendu 10 x Cactus pour 5 €"));
    }

    @Test void recordSaleDeduplicatesRapidDuplicate() {
        SalesTracker tracker = fresh();
        String message = "Vous avez vendu 10 x Cactus pour 5 €";
        assertTrue(tracker.recordConfirmedSale(message));
        assertFalse(tracker.recordConfirmedSale(message));
        TrackedItem cactus = tracker.findByNameOrId("minecraft:cactus");
        assertEquals(10, cactus.soldItems(System.currentTimeMillis()));
    }

    @Test void updateShopDetailsReadsLore() {
        SalesTracker tracker = fresh();
        tracker.updateShopDetails("Cactus Prix: 0,42 € Limite: 12 stacks", "Cactus");
        TrackedItem cactus = tracker.findByNameOrId("minecraft:cactus");
        assertNotNull(cactus);
        assertEquals(0.42, cactus.unitPrice, 1e-9);
        assertEquals(12 * 64, cactus.limitItems);
    }

    @Test void persistsAndReloads() {
        Path file = dir.resolve("persist.json");
        SalesTracker tracker = new SalesTracker(file);
        tracker.addItem("minecraft:wheat", "Blé", 0.33, 10);
        tracker.save();

        SalesTracker reloaded = new SalesTracker(file);
        TrackedItem wheat = reloaded.findByNameOrId("minecraft:wheat");
        assertNotNull(wheat);
        assertEquals(0.33, wheat.unitPrice, 1e-9);
        assertEquals(10 * 64, wheat.limitItems);
    }

    @Test void survivesCorruptConfig() throws Exception {
        Path file = dir.resolve("corrupt.json");
        Files.writeString(file, "{ this is not valid json ]");
        SalesTracker tracker = new SalesTracker(file);
        assertTrue(tracker.items().isEmpty());
    }

    @Test void findByNameMatchesIdPathAndDisplayName() {
        SalesTracker tracker = fresh();
        assertEquals("minecraft:sugar_cane", tracker.findByNameOrId("Canne à sucre").id);
        assertEquals("minecraft:sugar_cane", tracker.findByNameOrId("sugar cane").id);
        assertEquals("minecraft:sugar_cane", tracker.findByNameOrId("minecraft:sugar_cane").id);
        assertNull(tracker.findByNameOrId("inconnu"));
        assertNull(tracker.findByNameOrId(""));
        assertNull(tracker.findByNameOrId(null));
    }
}
