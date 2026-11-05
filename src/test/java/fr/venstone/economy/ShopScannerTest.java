package fr.venstone.economy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShopScannerTest {
    private static final String CACTUS_LORE =
            "Cactus Clic droit: Vendre: 0.05€ Limite de vente: 2304";
    private static final String DIAMOND_LORE =
            "Diamant Acheter: 5.00€ Vendre: 3.50€ Limite d'achat: 64 Limite de vente: 100";

    @Test void buildsAnEntryFromAVisibleTooltip() {
        ShopScanner.ScannedItem item = ShopScanner.build("minecraft:cactus", "Cactus", 64, CACTUS_LORE);
        assertNotNull(item);
        assertEquals("minecraft:cactus", item.registryId());
        assertEquals(0.05, item.sellPrice(), 1e-9);
        assertEquals(2304, item.sellLimit());
        assertEquals(64, item.stackSize());
    }

    @Test void separatesBuyAndSellPrices() {
        ShopScanner.ScannedItem item = ShopScanner.build("minecraft:diamond", "Diamant", 64, DIAMOND_LORE);
        assertNotNull(item);
        assertEquals(3.50, item.sellPrice(), 1e-9);
        assertEquals(5.00, item.buyPrice(), 1e-9);
        assertEquals(100, item.sellLimit());
        assertEquals(64, item.buyLimit());
    }

    @Test void itemsWithoutAnyPriceAreRejected() {
        assertNull(ShopScanner.build("minecraft:stone", "Pierre", 64, "Pierre Bloc de pierre"));
    }

    @Test void navigationButtonsAreRejected() {
        assertNull(ShopScanner.build("minecraft:arrow", "Page suivante", 64, "Page suivante"));
        assertNull(ShopScanner.build("minecraft:barrier", "Fermer", 64, "Fermer le menu"));
        assertNull(ShopScanner.build("minecraft:paper", "Catégories", 64, "Catégories"));
        assertNull(ShopScanner.build("minecraft:arrow", "Next Page", 64, "Next Page"));
    }

    @Test void decorativeItemsAreRejected() {
        // No letters at all: a divider or an arrow glyph.
        assertNull(ShopScanner.build("minecraft:gray_stained_glass_pane", "■ ■ ■", 64, "■ ■ ■"));
        assertNull(ShopScanner.build("minecraft:air", "", 64, ""));
        assertNull(ShopScanner.build("minecraft:stone", "   ", 64, "   "));
    }

    @Test void itemsContainingANavigationWordAreKept() {
        // Whole-word matching: a real item name must not be filtered out by a substring.
        assertFalse(ShopScanner.isNavigation("Backpack"));
        assertFalse(ShopScanner.isNavigation("Homegrown Carrot"));
        assertFalse(ShopScanner.isNavigation("Fermez la porte"));
        assertTrue(ShopScanner.isNavigation("Fermer"));
    }

    @Test void navigationDetectionIsCaseAndAccentInsensitive() {
        assertTrue(ShopScanner.isNavigation("PRÉCÉDENT"));
        assertTrue(ShopScanner.isNavigation("precedent"));
        assertTrue(ShopScanner.isNavigation("Page 2 / 5"));
        assertFalse(ShopScanner.isNavigation("Canne à sucre"));
        assertFalse(ShopScanner.isNavigation("Cactus"));
    }

    @Test void malformedRegistryIdIsRejected() {
        assertNull(ShopScanner.build("bad id!", "Cactus", 64, CACTUS_LORE));
        assertNull(ShopScanner.build(null, "Cactus", 64, CACTUS_LORE));
    }

    @Test void nonStandardStackSizeIsPreserved() {
        ShopScanner.ScannedItem item = ShopScanner.build("minecraft:egg", "Œuf", 16,
                "Œuf Clic droit: Vendre: 0.02€");
        assertNotNull(item);
        assertEquals(16, item.stackSize());
    }

    @Test void invalidStackSizeFallsBackToSixtyFour() {
        ShopScanner.ScannedItem item = ShopScanner.build("minecraft:cactus", "Cactus", 0, CACTUS_LORE);
        assertNotNull(item);
        assertEquals(64, item.stackSize());
    }

    @Test void blankNameFallsBackToTheIdPath() {
        ShopScanner.ScannedItem item = ShopScanner.build("minecraft:melon_slice", "", 64,
                "Vendre: 0.06€");
        assertNotNull(item);
        assertEquals("melon slice", item.name());
    }

    @Test void displayNameIsTrimmed() {
        ShopScanner.ScannedItem item = ShopScanner.build("minecraft:cactus", "  Cactus  ", 64, CACTUS_LORE);
        assertNotNull(item);
        assertEquals("Cactus", item.name());
    }

    @Test void realVenstoneSaleLoreIsReadCorrectly() {
        // The exact tooltip shape captured in game.
        ShopScanner.ScannedItem item = ShopScanner.build("minecraft:sugar_cane", "Canne à sucre", 64,
                "Canne à sucre Clic droit: Vendre: 0.12€ Limite de vente: 2304");
        assertNotNull(item);
        assertEquals(0.12, item.sellPrice(), 1e-9);
        assertEquals(2304, item.sellLimit());
    }
}
