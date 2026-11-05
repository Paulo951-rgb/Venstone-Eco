package fr.venstone.economy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SystemClockTest {
    @Test void timeMovesForward() {
        Clock clock = SystemClock.get();
        long first = clock.now();
        long second = clock.now();
        assertTrue(second >= first);
    }

    @Test void staysCloseToWallClock() {
        Clock clock = SystemClock.get();
        assertTrue(Math.abs(clock.now() - System.currentTimeMillis()) < 5_000);
    }

    @Test void saleSignatureIsStableAndDistinguishing() {
        ShopTextParser.Sale first = new ShopTextParser.Sale("Cactus", 10, 0.5);
        ShopTextParser.Sale same = new ShopTextParser.Sale("cactus", 10, 0.50);
        ShopTextParser.Sale different = new ShopTextParser.Sale("Cactus", 10, 0.6);
        assertEquals(ShopTextParser.saleSignature(first), ShopTextParser.saleSignature(same));
        assertNotEquals(ShopTextParser.saleSignature(first), ShopTextParser.saleSignature(different));
    }
}
