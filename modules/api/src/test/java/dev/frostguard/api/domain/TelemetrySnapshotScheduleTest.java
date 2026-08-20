package dev.frostguard.api.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;

import org.junit.jupiter.api.Test;

/**
 * Covers the bedtime/wake anchors that bookend the "last night" report, including the case the
 * anchors exist for: an hourly interval alone would not guarantee a sample at either end of the
 * window, so "last night" would silently start or end at whatever hourly sample happened to be
 * nearest instead.
 */
class TelemetrySnapshotScheduleTest {

    private static final Duration HOURLY = Duration.ofHours(1);

    @Test
    void theWakeAnchorIsOhEightThirty() {
        // Pinned deliberately: the reader's grace window is measured from this time, and a drift
        // between the two silently drops the wake snapshot out of the window it bookends.
        assertEquals(LocalTime.of(8, 30), TelemetrySnapshotSchedule.WAKE_ANCHOR);
        assertEquals(LocalTime.of(23, 0), TelemetrySnapshotSchedule.SLEEP_ANCHOR);
    }

    @Test
    void aRunShortlyBeforeWakeSnapsToTheWakeAnchorRatherThanTheHourlyInterval() {
        // 08:00 + 1h would be 09:00, half an hour past the anchor -- so "last night" would end at
        // the 08:00 sample and miss everything between. The anchor has to win.
        LocalDateTime now = LocalDateTime.of(2026, 8, 20, 8, 0);
        assertEquals(LocalDateTime.of(2026, 8, 20, 8, 30),
                TelemetrySnapshotSchedule.nextRun(now, HOURLY));
    }

    @Test
    void aRunShortlyBeforeBedtimeSnapsToTheSleepAnchor() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 20, 22, 45);
        assertEquals(LocalDateTime.of(2026, 8, 20, 23, 0),
                TelemetrySnapshotSchedule.nextRun(now, HOURLY));
    }

    @Test
    void awayFromBothAnchorsTheNormalIntervalWins() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 20, 14, 0);
        assertEquals(now.plusHours(1), TelemetrySnapshotSchedule.nextRun(now, HOURLY));
    }

    @Test
    void anAnchorAlreadyPassedTodayRollsToTomorrowRatherThanSchedudingBackwards() {
        // 08:31 is one minute past the wake anchor. Reusing today's 08:30 would schedule a run in
        // the past, which the queue would fire immediately and then again, and again.
        LocalDateTime now = LocalDateTime.of(2026, 8, 20, 8, 31);
        LocalDateTime next = TelemetrySnapshotSchedule.nextRun(now, HOURLY);
        assertTrue(next.isAfter(now), "scheduled a run at or before now: " + next);
        assertEquals(LocalDateTime.of(2026, 8, 20, 9, 31), next);
    }

    @Test
    void theResultIsAlwaysStrictlyInTheFutureAcrossEveryMinuteOfTheDay() {
        // Sweeps the whole day rather than spot-checking, because a backwards schedule is the
        // failure that would spin the queue rather than merely mistime one sample.
        LocalDateTime base = LocalDateTime.of(2026, 8, 20, 0, 0);
        for (int minute = 0; minute < 24 * 60; minute++) {
            LocalDateTime now = base.plusMinutes(minute);
            LocalDateTime next = TelemetrySnapshotSchedule.nextRun(now, HOURLY);
            assertTrue(next.isAfter(now), "non-future schedule at " + now + " -> " + next);
        }
    }

    @Test
    void exactlyOnAnAnchorSchedulesTheNextOneRatherThanRepeatingItself() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 20, 8, 30);
        LocalDateTime next = TelemetrySnapshotSchedule.nextRun(now, HOURLY);
        assertTrue(next.isAfter(now));
        assertEquals(LocalDateTime.of(2026, 8, 20, 9, 30), next);
    }
}
