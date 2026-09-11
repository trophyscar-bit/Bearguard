package dev.frostguard.tasks.dailies;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class FireBeastAttemptLedgerTest {

    private static final ZoneOffset UTC = ZoneOffset.UTC;

    @Test
    void aLaterRunInTheSameCycleResumesTheTriesAlreadySpent() {
        long cycle = FireBeastAttemptLedger.cycleOf(LocalDateTime.of(2026, 9, 11, 12, 13), UTC);
        assertEquals(2, FireBeastAttemptLedger.triesThisCycle(2, cycle, cycle));
    }

    /** The 11 September loop: two tries, the app closed mid-wait, Intel back three minutes later. */
    @Test
    void aRestartMidWaitDoesNotHandTheSameBeastFreshTries() {
        long before = FireBeastAttemptLedger.cycleOf(LocalDateTime.of(2026, 9, 11, 12, 15), UTC);
        long after = FireBeastAttemptLedger.cycleOf(LocalDateTime.of(2026, 9, 11, 12, 18), UTC);
        assertEquals(before, after);
        assertEquals(2, FireBeastAttemptLedger.triesThisCycle(2, before, after));
    }

    @Test
    void aNewIntelCycleStartsTheCountAgain() {
        long morning = FireBeastAttemptLedger.cycleOf(LocalDateTime.of(2026, 9, 11, 7, 30), UTC);
        long afternoon = FireBeastAttemptLedger.cycleOf(LocalDateTime.of(2026, 9, 11, 8, 30), UTC);
        assertNotEquals(morning, afternoon, "08:00 UTC is an Intel refresh");
        assertEquals(0, FireBeastAttemptLedger.triesThisCycle(3, morning, afternoon));
    }

    @Test
    void aCountWithNoRecordedCycleOrANegativeCountIsNotTrusted() {
        long cycle = FireBeastAttemptLedger.cycleOf(LocalDateTime.of(2026, 9, 11, 12, 0), UTC);
        assertEquals(0, FireBeastAttemptLedger.triesThisCycle(3, 0L, cycle));
        assertEquals(0, FireBeastAttemptLedger.triesThisCycle(-1, cycle, cycle));
    }

    @Test
    void givingUpHoldsUntilTheRefreshAndNoLonger() {
        long refresh = FireBeastAttemptLedger.cycleOf(LocalDateTime.of(2026, 9, 11, 12, 0), UTC);
        assertTrue(FireBeastAttemptLedger.leftAlone(refresh, refresh - 1));
        assertFalse(FireBeastAttemptLedger.leftAlone(refresh, refresh));
        assertFalse(FireBeastAttemptLedger.leftAlone(0L, refresh), "0 means never given up");
    }
}
