package fr.venstone.economy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StackMathTest {
    @Test void limitIsAlwaysThirtySixStacks() {
        assertEquals(36, StackMath.LIMIT_STACKS);
        assertEquals(36 * 64, StackMath.limitItems(64));
    }

    @Test void limitHonoursNonStandardStackSizes() {
        // Eggs, snowballs and ender pearls stack to 16; the quota is still 36 stacks.
        assertEquals(36 * 16, StackMath.limitItems(16));
        assertEquals(36 * 1, StackMath.limitItems(1));
    }

    @Test void invalidStackSizeFallsBackToSixtyFour() {
        assertEquals(64, StackMath.clampStackSize(0));
        assertEquals(64, StackMath.clampStackSize(-5));
        assertEquals(64, StackMath.clampStackSize(999));
        assertEquals(64, StackMath.clampStackSize(Integer.MAX_VALUE));
        assertEquals(16, StackMath.clampStackSize(16));
        assertEquals(1, StackMath.clampStackSize(1));
    }

    @Test void wholeStacksRoundsDown() {
        assertEquals(3, StackMath.wholeStacks(200, 64));
        assertEquals(0, StackMath.wholeStacks(63, 64));
        assertEquals(12, StackMath.wholeStacks(200, 16));
        assertEquals(0, StackMath.wholeStacks(-10, 64));
    }

    @Test void revenueRoundsToCents() {
        assertEquals(78.72, StackMath.revenue(656, 0.12), 1e-9);
        assertEquals(0.0, StackMath.revenue(0, 5), 1e-9);
        assertEquals(0.0, StackMath.revenue(10, 0), 1e-9);
        assertEquals(0.0, StackMath.revenue(10, -1), 1e-9);
        assertEquals(0.0, StackMath.revenue(10, Double.NaN), 1e-9);
        assertEquals(0.0, StackMath.revenue(10, Double.POSITIVE_INFINITY), 1e-9);
        assertEquals(0.01, StackMath.revenue(1, 0.014), 1e-9);
    }

    @Test void perStackUsesTheRealStackSize() {
        assertEquals(0.12 * 64, StackMath.perStack(0.12, 64), 1e-9);
        assertEquals(0.12 * 16, StackMath.perStack(0.12, 16), 1e-9);
        // An unknown size must not silently mean "one item".
        assertEquals(0.12 * 64, StackMath.perStack(0.12, 0), 1e-9);
    }

    @Test void perHourScalesTheWindow() {
        assertEquals(100.0, StackMath.perHour(100.0, 3_600_000L), 1e-9);
        assertEquals(200.0, StackMath.perHour(100.0, 1_800_000L), 1e-9);
        assertEquals(0.0, StackMath.perHour(100.0, 0), 1e-9);
        assertEquals(0.0, StackMath.perHour(0, 3_600_000L), 1e-9);
    }

    @Test void stackFormattingIsLocaleIndependent() {
        assertEquals("1.5", StackMath.stacks(96, 64));
        assertEquals("0.0", StackMath.stacks(0, 64));
        assertEquals("6.0", StackMath.stacks(96, 16));
    }

    @Test void totalsNeverLosePrecision() {
        // 36 stacks of 64 at 0.12 must be 276.48, not 276.47999...
        assertTrue(Math.abs(StackMath.revenue(StackMath.limitItems(64), 0.12) - 276.48) < 1e-9);
    }
}
