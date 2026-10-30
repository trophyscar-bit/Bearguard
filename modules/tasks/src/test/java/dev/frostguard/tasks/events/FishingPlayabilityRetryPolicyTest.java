package dev.frostguard.tasks.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

class FishingPlayabilityRetryPolicyTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 15, 12, 0);
    private static final LocalDateTime DAILY_RESET = LocalDateTime.of(2026, 10, 16, 2, 0);

    @Test
    void threeShortRetriesThenParkUntilDailyReset() {
        assertEquals(NOW.plusMinutes(5), FishingPlayabilityRetryPolicy.retryAt(NOW, DAILY_RESET, 1));
        assertEquals(NOW.plusMinutes(5), FishingPlayabilityRetryPolicy.retryAt(NOW, DAILY_RESET, 2));
        assertEquals(NOW.plusMinutes(5), FishingPlayabilityRetryPolicy.retryAt(NOW, DAILY_RESET, 3));
        assertEquals(DAILY_RESET, FishingPlayabilityRetryPolicy.retryAt(NOW, DAILY_RESET, 4));
        assertEquals(DAILY_RESET, FishingPlayabilityRetryPolicy.retryAt(NOW, DAILY_RESET, 5));
        assertFalse(FishingPlayabilityRetryPolicy.parksUntilReset(3));
        assertTrue(FishingPlayabilityRetryPolicy.parksUntilReset(4));
        assertEquals(null, FishingPlayabilityRetryPolicy.terminalSnapshotType(3));
        assertEquals("unplayable", FishingPlayabilityRetryPolicy.terminalSnapshotType(4));
    }

    @Test
    void doesNotScheduleAShortRetryPastTheUpcomingReset() {
        LocalDateTime upcomingReset = NOW.plusMinutes(3);

        assertEquals(upcomingReset, FishingPlayabilityRetryPolicy.retryAt(NOW, upcomingReset, 1));
        assertEquals(upcomingReset, FishingPlayabilityRetryPolicy.retryAt(NOW, upcomingReset, 3));
        assertEquals(upcomingReset, FishingPlayabilityRetryPolicy.retryAt(NOW, upcomingReset, 4));
    }

    @Test
    void rejectsANonPositiveMissCount() {
        assertThrows(IllegalArgumentException.class,
                () -> FishingPlayabilityRetryPolicy.retryAt(NOW, DAILY_RESET, 0));
    }
}
