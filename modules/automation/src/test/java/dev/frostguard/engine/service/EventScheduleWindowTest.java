package dev.frostguard.engine.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.frostguard.data.entity.EventScheduleEntry;

/** Window lookup over the State-calendar rows, shaped like the 2026-11-10 Wander Theater row. */
class EventScheduleWindowTest {

    private static final String PREFIX = "STATE_GANTT_WANDER_THEATER_";
    private static final EventScheduleEntry THEATER = new EventScheduleEntry(PREFIX + "2026_11_10", "Wander Theater",
            true, LocalDateTime.of(2026, 11, 10, 0, 0), LocalDateTime.of(2026, 11, 15, 23, 59), null);
    private static final EventScheduleEntry MISSIONS = new EventScheduleEntry(
            "STATE_GANTT_WANDERER_MISSIONS_2026_11_10", "Wanderer Missions",
            true, LocalDateTime.of(2026, 11, 10, 0, 0), LocalDateTime.of(2026, 11, 15, 23, 59), null);
    private static final EventScheduleEntry NEXT = new EventScheduleEntry(PREFIX + "2026_12_08", "Wander Theater",
            false, LocalDateTime.of(2026, 12, 8, 0, 0), LocalDateTime.of(2026, 12, 13, 23, 59), null);

    @Test
    void theWindowContainingNowWins() {
        assertEquals(THEATER, EventScheduleService.windowFor(List.of(NEXT, MISSIONS, THEATER), PREFIX,
                LocalDateTime.of(2026, 11, 12, 21, 0)).orElseThrow());
    }

    @Test
    void afterTheEndTheNextWindowIsReturned() {
        assertEquals(NEXT, EventScheduleService.windowFor(List.of(THEATER, NEXT), PREFIX,
                LocalDateTime.of(2026, 11, 16, 0, 0)).orElseThrow());
    }

    @Test
    void beforeTheStartTheComingWindowIsReturned() {
        assertEquals(THEATER, EventScheduleService.windowFor(List.of(NEXT, THEATER), PREFIX,
                LocalDateTime.of(2026, 11, 9, 23, 0)).orElseThrow());
    }

    @Test
    void onlyAPastWindowOrOtherEventsMeansUnknown() {
        assertTrue(EventScheduleService.windowFor(List.of(THEATER, MISSIONS), PREFIX,
                LocalDateTime.of(2026, 11, 20, 0, 0)).isEmpty());
        assertTrue(EventScheduleService.windowFor(List.of(MISSIONS), PREFIX,
                LocalDateTime.of(2026, 11, 12, 0, 0)).isEmpty());
    }
}
