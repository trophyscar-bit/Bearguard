package dev.frostguard.tasks.social;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * Evidence level: automated tests.
 */
class ChatReconcileScheduleTest {

    private static final LocalTime SLOT = LocalTime.of(1, 0);

    private static LocalDateTime at(int day, int hour, int minute) {
        return LocalDateTime.of(2026, 9, day, hour, minute);
    }

    @Test
    void theLatestSlotIsYesterdaysUntilTodaysHasArrived() {
        assertEquals(at(23, 1, 0), ChatReconcileSchedule.latestSlot(at(24, 0, 30), SLOT));
        assertEquals(at(24, 1, 0), ChatReconcileSchedule.latestSlot(at(24, 1, 0), SLOT));
        assertEquals(at(24, 1, 0), ChatReconcileSchedule.latestSlot(at(24, 15, 0), SLOT));
    }

    @Test
    void theNextSlotIsStrictlyAfterNowEvenAtTheSlotItself() {
        assertEquals(at(24, 1, 0), ChatReconcileSchedule.nextSlot(at(24, 0, 30), SLOT));
        assertEquals(at(25, 1, 0), ChatReconcileSchedule.nextSlot(at(24, 1, 0), SLOT));
        assertEquals(at(25, 1, 0), ChatReconcileSchedule.nextSlot(at(24, 23, 59), SLOT));
    }

    @Test
    void aReconcileIsOwedWhenNothingIsOnRecord() {
        assertTrue(ChatReconcileSchedule.due(at(24, 15, 0), SLOT, Optional.empty()));
    }

    @Test
    void aReconcileIsNotOwedOnceOneHasStartedSinceTheLatestSlot() {
        assertFalse(ChatReconcileSchedule.due(at(24, 15, 0), SLOT, Optional.of(at(24, 1, 2))));
        // Before tonight's slot, last night's reconcile is still the one that counts.
        assertFalse(ChatReconcileSchedule.due(at(24, 0, 30), SLOT, Optional.of(at(23, 1, 2))));
    }

    @Test
    void aBotThatWasPausedThroughTheSlotOwesTheReconcileWhenItComesBack() {
        // Last reconcile was the night before; the bot was stopped at 01:00 and resumes at 09:00.
        assertTrue(ChatReconcileSchedule.due(at(24, 9, 0), SLOT, Optional.of(at(23, 1, 2))));
    }

    @Test
    void aPassDueAfterTheSlotIsPulledBackToItWithThePad() {
        Duration pad = Duration.ofSeconds(37);

        assertEquals(at(24, 1, 0).plus(pad),
                ChatReconcileSchedule.pullToSlot(at(24, 1, 20), at(24, 0, 50), SLOT, pad));
    }

    @Test
    void aPassDueBeforeTheSlotIsLeftAlone() {
        assertEquals(at(24, 0, 55),
                ChatReconcileSchedule.pullToSlot(at(24, 0, 55), at(24, 0, 25), SLOT,
                        Duration.ofSeconds(37)));
    }

    @Test
    void aPassDueDaysLaterStillLandsOnTheNextSlotNotALaterOne() {
        // A long interval must not skip a night.
        assertEquals(at(24, 1, 0).plusSeconds(5),
                ChatReconcileSchedule.pullToSlot(at(27, 12, 0), at(23, 22, 0), SLOT,
                        Duration.ofSeconds(5)));
    }
}
