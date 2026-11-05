package fr.venstone.economy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DurationsTest {
    @Test void formatsUnderOneHourAsMinutesAndSeconds() {
        assertEquals("00:00", Durations.format(0));
        assertEquals("00:59", Durations.format(59_000));
        assertEquals("01:00", Durations.format(60_000));
        assertEquals("59:59", Durations.format(3_599_000));
    }

    @Test void formatsHoursWhenLongEnough() {
        assertEquals("1h 00m", Durations.format(3_600_000));
        assertEquals("1h 05m", Durations.format(3_900_000));
        assertEquals("12h 34m", Durations.format(45_240_000));
    }

    @Test void clampsNegativeDurations() {
        assertEquals("00:00", Durations.format(-1));
        assertEquals("00:00", Durations.format(Long.MIN_VALUE));
    }
}
