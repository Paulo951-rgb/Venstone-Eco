package fr.venstone.economy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class ShopTextParserTest {
    @Test void readsFrenchSaleConfirmation() {
        ShopTextParser.Sale sale = ShopTextParser.readSale("Vous avez vendu 10 x Canne à sucre pour 1,20 €");
        assertNotNull(sale);
        assertEquals(10, sale.amount());
        assertEquals("Canne à sucre", sale.itemName());
        assertEquals(1.20, sale.totalPrice(), 1e-9);
    }

    @Test void readsEnglishSaleConfirmation() {
        ShopTextParser.Sale sale = ShopTextParser.readSale("Sold 5x Cactus for 0.25");
        assertNotNull(sale);
        assertEquals(5, sale.amount());
        assertEquals("Cactus", sale.itemName());
        assertEquals(0.25, sale.totalPrice(), 1e-9);
    }

    @Test void toleratesThousandsSeparator() {
        ShopTextParser.Sale sale = ShopTextParser.readSale("Vendu 1 000 melon_slice pour 60 €");
        assertNotNull(sale);
        assertEquals(1000, sale.amount());
    }

    @Test void toleratesTrailingPunctuation() {
        assertNotNull(ShopTextParser.readSale("Vous avez vendu 10 x Cactus pour 0,50 €."));
        assertNotNull(ShopTextParser.readSale("Vous avez vendu 10 x Cactus pour 0,50 € !"));
    }

    @Test void rejectsNegatedSale() {
        assertNull(ShopTextParser.readSale("Tu n'as pas vendu 5 x Cactus pour 1 €"));
        assertNull(ShopTextParser.readSale("Vous n'avez jamais vendu 5 x Cactus pour 1 €"));
    }

    @Test void rejectsResoldPrefix() {
        assertNull(ShopTextParser.readSale("Vous avez revendu 5 x Cactus pour 1 €"));
    }

    @Test void rejectsTrailingTextAfterPrice() {
        assertNull(ShopTextParser.readSale("Vous avez vendu 10 x Cactus pour 1,20 € et merci"));
    }

    @Test void rejectsBlankItemName() {
        assertNull(ShopTextParser.readSale("vendu 10 pour 1"));
    }

    @Test void rejectsOversizedSaleAmount() {
        assertNull(ShopTextParser.readSale("Vous avez vendu 99999999 x Cactus pour 1 €"));
    }

    @Test void rejectsMalformedInput() {
        assertNull(ShopTextParser.readSale(null));
        assertNull(ShopTextParser.readSale(""));
        assertNull(ShopTextParser.readSale("aucun texte utile"));
    }

    @Test void readsPriceWithLabel() {
        assertEquals(0.12, ShopTextParser.readUnitPrice("Prix de vente: 0,12 €"), 1e-9);
        assertEquals(12.0, ShopTextParser.readUnitPrice("Prix vente : 12"), 1e-9);
        assertEquals(5.0, ShopTextParser.readUnitPrice("Prix: 5"), 1e-9);
        assertEquals(0.30, ShopTextParser.readUnitPrice("sell price: 0.30"), 1e-9);
    }

    @Test void ignoresTextWithoutPriceLabel() {
        assertNull(ShopTextParser.readUnitPrice("Ventes cumulées: 500"));
        assertNull(ShopTextParser.readUnitPrice("Aventurier: 3"));
        assertNull(ShopTextParser.readUnitPrice("Ceci vaut 5 euros"));
    }

    @Test void readsExplicitVenteLabel() {
        assertEquals(1.0, ShopTextParser.readUnitPrice("Vente: 1"), 1e-9);
        assertEquals(1.5, ShopTextParser.readUnitPrice("Vente : 1,5 €"), 1e-9);
    }

    @Test void readsLimitWithPluralLabels() {
        assertEquals(2304, ShopTextParser.readLimitItems("Limites : 2304"));
        assertEquals(10, ShopTextParser.readLimitItems("Restants: 10 items"));
    }

    @Test void rejectsOutOfRangePrice() {
        assertNull(ShopTextParser.readUnitPrice("Prix: 2000000"));
    }

    @Test void readsLimitInItems() {
        assertEquals(2304, ShopTextParser.readLimitItems("Limite : 2304"));
        assertEquals(64, ShopTextParser.readLimitItems("Maximum: 64"));
    }

    @Test void readsLimitInStacks() {
        assertEquals(2304, ShopTextParser.readLimitItems("Limite: 36 stacks"));
        assertEquals(64, ShopTextParser.readLimitItems("Limite: 1 stack"));
    }

    @Test void rejectsInvalidLimit() {
        assertNull(ShopTextParser.readLimitItems("Limite: 0"));
        assertNull(ShopTextParser.readLimitItems("Limite: 99999999999 stacks"));
        assertNull(ShopTextParser.readLimitItems(null));
    }

    // --- Real Venstone / EconomyShopGUI strings, captured in-game. ---

    @Test void readsRealVenstoneSaleConfirmation() {
        ShopTextParser.Sale sale = ShopTextParser.readSale("Vous avez vendu avec succès x656 Canne à sucre pour 78.72€.");
        assertNotNull(sale);
        assertEquals(656, sale.amount());
        assertEquals("Canne à sucre", sale.itemName());
        assertEquals(78.72, sale.totalPrice(), 1e-9);
    }

    @Test void realVenstoneSaleUnitPriceMatchesLore() {
        ShopTextParser.Sale sale = ShopTextParser.readSale("Vous avez vendu avec succès x656 Canne à sucre pour 78.72€.");
        assertEquals(0.12, sale.totalPrice() / sale.amount(), 1e-9);
    }

    @Test void readsRealVenstoneTooltipPriceAndLimit() {
        String tooltip = "Sugar Cane Clic gauche: Acheter: 2.00€ Clic droit: Vendre: 0.12€ Limite d'achat: 192 Limite de vente: 384";
        // The sell price must win over the buy price, and the sell limit over the buy limit.
        assertEquals(0.12, ShopTextParser.readUnitPrice(tooltip), 1e-9);
        assertEquals(384, ShopTextParser.readLimitItems(tooltip));
    }

    @Test void sellPriceWinsOverBuyPrice() {
        assertEquals(0.12, ShopTextParser.readUnitPrice("Clic gauche: Acheter: 2.00€ Clic droit: Vendre: 0.12€"), 1e-9);
        assertEquals(0.12, ShopTextParser.readUnitPrice("Buy Price 2.00 Sell Price 0.12"), 1e-9);
    }

    @Test void sellLimitWinsOverBuyLimit() {
        assertEquals(384, ShopTextParser.readLimitItems("Limite d'achat: 192 Limite de vente: 384"));
    }
}
