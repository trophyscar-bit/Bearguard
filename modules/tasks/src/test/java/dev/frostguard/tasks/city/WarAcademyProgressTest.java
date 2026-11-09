package dev.frostguard.tasks.city;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class WarAcademyProgressTest {

    @Test
    void onlyADecreaseConfirmsRedemptionProgress() {
        assertTrue(WarAcademyRoutine.redemptionMadeProgress(5, 0));
        assertTrue(WarAcademyRoutine.redemptionMadeProgress(5, 3));
        assertFalse(WarAcademyRoutine.redemptionMadeProgress(5, 5));
        assertFalse(WarAcademyRoutine.redemptionMadeProgress(0, 0));
        assertFalse(WarAcademyRoutine.redemptionMadeProgress(5, 6));
    }

    @Test
    void aMissingRowWaitsForTheResetWhileAFailedOpenRetriesSoon() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 9, 13, 27);
        LocalDateTime reset = LocalDateTime.of(2026, 11, 9, 19, 0);

        assertEquals(reset, WarAcademyRoutine.retryAt(WarAcademyRoutine.Route.WAR_ACADEMY_NOT_LISTED, now, reset));
        assertEquals(now.plusMinutes(5), WarAcademyRoutine.retryAt(WarAcademyRoutine.Route.NAVIGATION_FAILED, now, reset));
    }
}
