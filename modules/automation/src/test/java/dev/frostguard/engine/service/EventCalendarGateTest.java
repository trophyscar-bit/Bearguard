package dev.frostguard.engine.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.frostguard.data.entity.EventScheduleEntry;

/**
 * The gate decides whether an event task looks for its tab at all. Its whole job is to park a task
 * only on evidence, so these cover what it does with evidence it does not have as closely as with
 * evidence it does.
 */
class EventCalendarGateTest {

    private static final String PREFIX = "STATE_GANTT_ALLIANCE_MOBILIZATION_";
    private static final String EVENT = "Alliance Mobilization";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 11, 18, 0);
    private static final String READ_TODAY = "2026-11-11/v12";

    private static final EventScheduleEntry RUNNING_NOW = new EventScheduleEntry(PREFIX + "2026_11_09",
            EVENT, true, LocalDateTime.of(2026, 11, 9, 0, 0), LocalDateTime.of(2026, 11, 14, 23, 59), null);
    private static final EventScheduleEntry STARTS_LATER = new EventScheduleEntry(PREFIX + "2026_11_23",
            EVENT, false, LocalDateTime.of(2026, 11, 23, 0, 0), LocalDateTime.of(2026, 11, 28, 23, 59), null);
    private static final EventScheduleEntry OTHER_EVENT = new EventScheduleEntry(
            "STATE_GANTT_WANDER_THEATER_2026_11_10", "Wander Theater",
            true, LocalDateTime.of(2026, 11, 10, 0, 0), LocalDateTime.of(2026, 11, 15, 23, 59), null);

    @Test
    void anEventInsideItsWindowRuns() {
        EventCalendarGate.Verdict verdict =
                EventCalendarGate.check(List.of(RUNNING_NOW, OTHER_EVENT), READ_TODAY, EVENT, NOW);

        assertTrue(verdict.shouldRun());
        assertTrue(verdict.nextStartUtc().isEmpty());
    }

    @Test
    void anEventThatHasNotOpenedYetParksUntilItsStart() {
        EventCalendarGate.Verdict verdict =
                EventCalendarGate.check(List.of(STARTS_LATER, OTHER_EVENT), READ_TODAY, EVENT, NOW);

        assertFalse(verdict.shouldRun());
        assertEquals(LocalDateTime.of(2026, 11, 23, 0, 0), verdict.nextStartUtc().orElseThrow());
        assertTrue(verdict.reason().contains("is not running"), verdict.reason());
    }

    /** A calendar that has not been read today is no evidence at all, so the task behaves as before. */
    @Test
    void staleCalendarDataNeverParksATask() {
        EventCalendarGate.Verdict verdict =
                EventCalendarGate.check(List.of(STARTS_LATER), "2026-11-10/v12", EVENT, NOW);

        assertTrue(verdict.shouldRun());
        assertTrue(verdict.nextStartUtc().isEmpty());
        assertTrue(verdict.reason().contains("has not been read"), verdict.reason());
    }

    /** No sweep, no evidence: every task behaves exactly as it did before the gate existed. */
    @Test
    void anUnreadableOrMissingMarkerNeverParksATask() {
        for (String marker : new String[]{null, "", "   ", "not-a-date/v12"}) {
            EventCalendarGate.Verdict verdict =
                    EventCalendarGate.check(List.of(), marker, EVENT, NOW);

            assertTrue(verdict.shouldRun(), "marker " + marker);
            assertTrue(verdict.nextStartUtc().isEmpty(), "marker " + marker);
        }
    }

    /** A scanner version bump invalidates the scan, not the rows it already stored for today. */
    @Test
    void aMarkerFromTodayUnderAnotherVersionStillCounts() {
        assertFalse(EventCalendarGate.check(List.of(STARTS_LATER), "2026-11-11/v99", EVENT, NOW).shouldRun());
    }

    /**
     * A sweep that finished and did not draw the event is evidence of absence, not a gap: the chart
     * carries every event of the week. This is the case Alliance Mobilization is in, so getting it
     * wrong would leave the gate doing nothing for the task it was built for.
     */
    @Test
    void aFreshSweepWithoutTheEventParksUntilTheNextSweep() {
        EventCalendarGate.Verdict verdict =
                EventCalendarGate.check(List.of(OTHER_EVENT), READ_TODAY, EVENT, NOW);

        assertFalse(verdict.shouldRun());
        assertEquals(LocalDateTime.of(2026, 11, 12, 0, 45), verdict.nextStartUtc().orElseThrow());
        assertTrue(verdict.reason().contains("does not carry it"), verdict.reason());
    }

    /** Past windows only: it has been and gone, and tomorrow's chart gets the next say. */
    @Test
    void anEventWhoseWindowHasEndedParksUntilTheNextSweep() {
        EventCalendarGate.Verdict verdict = EventCalendarGate.check(List.of(RUNNING_NOW), "2026-11-20/v12",
                EVENT, LocalDateTime.of(2026, 11, 20, 12, 0));

        assertFalse(verdict.shouldRun());
        assertEquals(LocalDateTime.of(2026, 11, 21, 0, 45), verdict.nextStartUtc().orElseThrow());
    }

    @Test
    void theKeyPrefixMatchesWhatTheScanWrites() {
        assertEquals(PREFIX, EventCalendarGate.keyPrefixFor(EVENT));
        assertEquals("STATE_GANTT_MIA_S_FORTUNE_HUT_", EventCalendarGate.keyPrefixFor("Mia's Fortune Hut"));
    }

    @Test
    void theParkedStartIsOfferedInTheSchedulersOwnZone() {
        EventCalendarGate.Verdict verdict =
                EventCalendarGate.check(List.of(STARTS_LATER), READ_TODAY, EVENT, NOW);

        assertEquals(LocalDateTime.of(2026, 11, 23, 0, 0)
                        .atOffset(java.time.ZoneOffset.UTC)
                        .atZoneSameInstant(java.time.ZoneId.systemDefault())
                        .toLocalDateTime(),
                verdict.nextStartLocal().orElseThrow());
    }
}
