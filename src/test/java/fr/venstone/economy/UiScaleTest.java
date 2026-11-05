package fr.venstone.economy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UiScaleTest {
    @Test void defaultIsOneHundredPercent() {
        assertEquals("100%", UiScale.label(UiScale.DEFAULT_INDEX));
        assertEquals(1.0f, UiScale.factor(UiScale.DEFAULT_INDEX), 1e-6);
        assertTrue(UiScale.isDefault(UiScale.DEFAULT_INDEX));
    }

    @Test void stepsCoverTheWholeRange() {
        assertEquals(0.6f, UiScale.factor(0), 1e-6);
        assertEquals(1.6f, UiScale.factor(UiScale.STEPS - 1), 1e-6);
        assertEquals("60%", UiScale.label(0));
        assertEquals("160%", UiScale.label(UiScale.STEPS - 1));
    }

    @Test void indexIsClamped() {
        assertEquals(0, UiScale.clampIndex(-10));
        assertEquals(UiScale.STEPS - 1, UiScale.clampIndex(999));
    }

    @Test void nextAndPreviousStopAtTheEdges() {
        assertEquals(1, UiScale.next(0));
        assertEquals(0, UiScale.previous(0));
        assertEquals(UiScale.STEPS - 1, UiScale.next(UiScale.STEPS - 1));
        assertEquals(UiScale.STEPS - 2, UiScale.previous(UiScale.STEPS - 1));
    }

    @Test void roundTripFromFactorToIndex() {
        for (int i = 0; i < UiScale.STEPS; i++) {
            assertEquals(i, UiScale.nearestIndex(UiScale.factor(i)));
        }
    }

    @Test void invalidFactorFallsBackToDefault() {
        assertEquals(UiScale.DEFAULT_INDEX, UiScale.nearestIndex(Float.NaN));
        assertEquals(UiScale.DEFAULT_INDEX, UiScale.nearestIndex(Float.POSITIVE_INFINITY));
        assertEquals(UiScale.DEFAULT_INDEX, UiScale.nearestIndex(1.0f));
    }

    @Test void scalingIsProportionalNotDistorting() {
        // Every step is the same factor on both axes, so nothing is stretched.
        for (int i = 0; i < UiScale.STEPS; i++) {
            float f = UiScale.factor(i);
            assertTrue(f > 0);
            assertEquals(f, UiScale.factor(i), 1e-6);
        }
        assertFalse(UiScale.isDefault(0));
    }
}
