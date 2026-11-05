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

class ShopCatalogTest {
    private static final long NOW = 1_700_000_000_000L;

    @TempDir Path dir;

    private long clock = NOW;
    private Path file;
    private ShopCatalog catalog;

    private ShopCatalog fresh() {
        file = dir.resolve("prices.json");
        clock = NOW;
        catalog = new ShopCatalog(file, () -> clock);
        return catalog;
    }

    @Test void recordsASellPrice() {
        ShopCatalog c = fresh();
        assertTrue(c.record("minecraft:cactus", "Cactus", 64, 0.05, null, 2304, null));
        ShopCatalog.PriceEntry entry = c.get("minecraft:cactus");
        assertNotNull(entry);
        assertEquals(0.05, entry.sellPrice, 1e-9);
        assertEquals(64, entry.stackSize);
        assertEquals(2304, entry.sellLimit);
        assertTrue(entry.hasSellPrice());
    }

    @Test void idIsCanonicalisedAndRejectsGarbage() {
        ShopCatalog c = fresh();
        assertTrue(c.record("CACTUS", "Cactus", 64, 0.05, null, null, null));
        assertNotNull(c.get("minecraft:cactus"));
        assertFalse(c.record("bad id!", "x", 64, 0.05, null, null, null));
        assertFalse(c.record(null, "x", 64, 0.05, null, null, null));
    }

    @Test void aMissingFieldNeverErasesAKnownValue() {
        ShopCatalog c = fresh();
        c.record("minecraft:cactus", "Cactus", 64, 0.05, 0.10, 2304, 500);
        // A later tooltip that failed to expose the sell price must not wipe it.
        c.record("minecraft:cactus", "Cactus", 64, null, null, null, null);
        ShopCatalog.PriceEntry entry = c.get("minecraft:cactus");
        assertEquals(0.05, entry.sellPrice, 1e-9);
        assertEquals(0.10, entry.buyPrice, 1e-9);
        assertEquals(2304, entry.sellLimit);
        assertEquals(500, entry.buyLimit);
    }

    @Test void aNewValueUpdatesTheStoredPrice() {
        ShopCatalog c = fresh();
        c.record("minecraft:cactus", "Cactus", 64, 0.05, null, null, null);
        assertTrue(c.record("minecraft:cactus", "Cactus", 64, 0.07, null, null, null));
        assertEquals(0.07, c.get("minecraft:cactus").sellPrice, 1e-9);
    }

    @Test void identicalValueDoesNotReportAChange() {
        ShopCatalog c = fresh();
        c.record("minecraft:cactus", "Cactus", 64, 0.05, null, null, null);
        assertFalse(c.record("minecraft:cactus", "Cactus", 64, 0.05, null, null, null));
    }

    @Test void invalidPricesAreIgnored() {
        ShopCatalog c = fresh();
        c.record("minecraft:cactus", "Cactus", 64, -1.0, Double.NaN, -5, 0);
        ShopCatalog.PriceEntry entry = c.get("minecraft:cactus");
        assertFalse(entry.hasSellPrice());
        assertFalse(entry.hasBuyPrice());
        assertFalse(entry.hasSellLimit());
    }

    @Test void absurdPriceIsRejected() {
        ShopCatalog c = fresh();
        c.record("minecraft:cactus", "Cactus", 64, ShopTextParser.MAX_UNIT_PRICE + 1, null, null, null);
        assertFalse(c.get("minecraft:cactus").hasSellPrice());
    }

    @Test void limitItemsHonoursTheRealStackSize() {
        ShopCatalog c = fresh();
        // 36 stacks of 16-item items is 576 items, not 2304.
        c.record("minecraft:egg", "Œuf", 16, 0.02, null, null, null);
        assertEquals(StackMath.limitItems(16), c.get("minecraft:egg").limitItems());
    }

    @Test void staleEntriesAreDetected() {
        ShopCatalog c = fresh();
        c.record("minecraft:cactus", "Cactus", 64, 0.05, null, null, null);
        assertFalse(c.isStale(c.get("minecraft:cactus"), NOW));
        assertFalse(c.isStale(c.get("minecraft:cactus"), NOW + ShopCatalog.STALE_MILLIS));
        assertTrue(c.isStale(c.get("minecraft:cactus"), NOW + ShopCatalog.STALE_MILLIS + 1));
    }

    @Test void entriesWithoutASellPriceAreNotSellable() {
        ShopCatalog c = fresh();
        c.record("minecraft:cactus", "Cactus", 64, null, 0.10, null, null);
        assertEquals(0, c.sellableEntries().size());
    }

    @Test void sellableEntriesAreSortedByPriceDescending() {
        ShopCatalog c = fresh();
        c.record("minecraft:cactus", "Cactus", 64, 0.05, null, null, null);
        c.record("minecraft:diamond", "Diamant", 64, 5.0, null, null, null);
        c.record("minecraft:wheat", "Blé", 64, 0.33, null, null, null);
        var entries = c.sellableEntries();
        assertEquals(3, entries.size());
        assertEquals("minecraft:diamond", entries.get(0).id);
        assertEquals("minecraft:wheat", entries.get(1).id);
        assertEquals("minecraft:cactus", entries.get(2).id);
    }

    @Test void persistsAndReloads() {
        ShopCatalog c = fresh();
        c.record("minecraft:cactus", "Cactus", 64, 0.05, 0.10, 2304, null);
        assertTrue(c.save());

        ShopCatalog reloaded = new ShopCatalog(file, () -> clock);
        ShopCatalog.PriceEntry entry = reloaded.get("minecraft:cactus");
        assertNotNull(entry);
        assertEquals(0.05, entry.sellPrice, 1e-9);
        assertEquals(0.10, entry.buyPrice, 1e-9);
        assertEquals(2304, entry.sellLimit);
        assertEquals("Cactus", entry.name);
    }

    @Test void clearEmptiesTheCatalogue() {
        ShopCatalog c = fresh();
        c.record("minecraft:cactus", "Cactus", 64, 0.05, null, null, null);
        c.clear();
        assertEquals(0, c.size());
        assertNull(c.get("minecraft:cactus"));
    }

    @Test void corruptedFileDoesNotThrow() throws Exception {
        file = dir.resolve("prices.json");
        Files.writeString(file, "{ this is not json");
        ShopCatalog c = new ShopCatalog(file, () -> clock);
        assertEquals(0, c.size());
        // A fresh reading still works after the bad file.
        assertTrue(c.record("minecraft:cactus", "Cactus", 64, 0.05, null, null, null));
    }

    @Test void malformedEntryIsSkippedButOthersSurvive() throws Exception {
        file = dir.resolve("prices.json");
        Files.writeString(file, """
                {"schemaVersion":1,"items":[
                  {"id":"minecraft:cactus","name":"Cactus","sellPrice":0.05,"stackSize":64},
                  {"id":null},
                  {"id":"minecraft:diamond","name":"Diamant","sellPrice":5.0,"stackSize":64}
                ]}""");
        ShopCatalog c = new ShopCatalog(file, () -> clock);
        assertNotNull(c.get("minecraft:cactus"));
        assertNotNull(c.get("minecraft:diamond"));
        assertEquals(2, c.size());
    }

    @Test void placeholderEntryIsNotSellableUntilAPriceArrives() {
        ShopCatalog c = fresh();
        // A first reading with no usable price still registers the entry...
        assertTrue(c.record("minecraft:cactus", "Cactus", 64, null, null, null, null));
        assertNotNull(c.get("minecraft:cactus"));
        assertEquals(0, c.sellableEntries().size());
        // ...and the next scan fills it in.
        assertTrue(c.record("minecraft:cactus", "Cactus", 64, 0.05, null, null, null));
        assertEquals(1, c.sellableEntries().size());
    }

    @Test void identicalReadingIsStillNotStale() {
        ShopCatalog c = fresh();
        c.record("minecraft:cactus", "Cactus", 64, 0.05, null, null, null);
        // Re-seeing the same price must refresh the freshness marker even without a value change.
        clock = NOW + ShopCatalog.STALE_MILLIS - 1;
        assertFalse(c.record("minecraft:cactus", "Cactus", 64, 0.05, null, null, null));
        assertFalse(c.isStale(c.get("minecraft:cactus"), clock));
    }

    @Test void missingFileStartsEmpty() {
        file = dir.resolve("does-not-exist.json");
        assertEquals(0, new ShopCatalog(file, () -> clock).size());
    }
}
