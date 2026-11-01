package dev.frostguard.tasks.heroes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

class SquadScanScheduleTest {

    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private static final LocalTime SLOT = LocalTime.of(7, 15);

    @Test
    void theNightlySlotIsHeldInUtcAcrossTheDaylightSavingChange() {
        // Before the change 07:15 UTC is 03:15 EDT; after it, 02:15 EST. Same UTC moment both nights.
        assertEquals(LocalDateTime.of(2026, 10, 31, 3, 15),
                SquadScanRoutine.nextOccurrenceLocal(Instant.parse("2026-10-30T20:00:00Z"), SLOT, NEW_YORK));
        assertEquals(LocalDateTime.of(2026, 11, 2, 2, 15),
                SquadScanRoutine.nextOccurrenceLocal(Instant.parse("2026-11-01T20:00:00Z"), SLOT, NEW_YORK));
    }

    @Test
    void onlyTheTwoHoursAfterTheSlotCountAsTheNightlyRun() {
        assertFalse(SquadScanRoutine.withinSlotWindow(Instant.parse("2026-10-31T07:14:59Z"), SLOT));
        assertTrue(SquadScanRoutine.withinSlotWindow(Instant.parse("2026-10-31T07:15:00Z"), SLOT));
        assertTrue(SquadScanRoutine.withinSlotWindow(Instant.parse("2026-10-31T09:14:59Z"), SLOT));
        // A bot started mid-day, or a preempted run retried in the morning, does not scan.
        assertFalse(SquadScanRoutine.withinSlotWindow(Instant.parse("2026-10-31T09:15:00Z"), SLOT));
        assertFalse(SquadScanRoutine.withinSlotWindow(Instant.parse("2026-10-31T17:00:00Z"), SLOT));
        // A slot just before midnight UTC stays open past it.
        assertTrue(SquadScanRoutine.withinSlotWindow(Instant.parse("2026-11-01T00:30:00Z"), LocalTime.of(23, 30)));
    }

    @Test
    void aSlotAlreadyPassedTodayMovesToTomorrow() {
        assertEquals(LocalDateTime.of(2026, 11, 1, 7, 15),
                SquadScanRoutine.nextOccurrenceLocal(Instant.parse("2026-10-31T07:15:00Z"), SLOT, ZoneId.of("UTC")));
        assertEquals(LocalDateTime.of(2026, 10, 31, 7, 15),
                SquadScanRoutine.nextOccurrenceLocal(Instant.parse("2026-10-31T07:14:59Z"), SLOT, ZoneId.of("UTC")));
    }
}
