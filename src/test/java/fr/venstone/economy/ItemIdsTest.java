package fr.venstone.economy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemIdsTest {
    @Test void canonicalAddsDefaultNamespace() {
        assertEquals("minecraft:cactus", ItemIds.canonical("cactus"));
        assertEquals("minecraft:cactus", ItemIds.canonical("  Cactus  "));
        assertEquals("minecraft:melon_slice", ItemIds.canonical("minecraft:melon_slice"));
    }

    @Test void canonicalRejectsMalformed() {
        assertNull(ItemIds.canonical(null));
        assertNull(ItemIds.canonical(""));
        assertNull(ItemIds.canonical("   "));
        assertNull(ItemIds.canonical("bad id!"));
        assertNull(ItemIds.canonical(":leading"));
    }

    @Test void validationMatchesCanonical() {
        assertTrue(ItemIds.isValid("diamond"));
        assertFalse(ItemIds.isValid("bad id!"));
    }

    @Test void pathStripsNamespaceAndUnderscores() {
        assertEquals("melon slice", ItemIds.path("minecraft:melon_slice"));
        assertEquals("cactus", ItemIds.path("cactus"));
        assertEquals("", ItemIds.path(null));
    }
}
