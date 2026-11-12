package dev.frostguard.tasks.pets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;

class PetAdventureStartOutcomeTest {

    @Test
    void rechecksAnUnconfirmedStartOnTheCompletionIntervalInsteadOfTheShortRetry() {
        LocalDateTime now = LocalDateTime.of(2026, 9, 28, 12, 0);

        assertEquals(now.plusHours(2), PetAdventureChestRoutine.unverifiedStartRecheck(now));
    }

    @Test
    void spentAttemptsWithAnAdventureRunningComeBackForItsChestBeforeTheReset() {
        // 30 Oct 08:29: the last attempt started an adventure and the task slept to the 20:00
        // reset, leaving the finished chest in the Beast Cage all day.
        LocalDateTime now = LocalDateTime.of(2026, 10, 30, 8, 29);
        LocalDateTime reset = LocalDateTime.of(2026, 10, 30, 20, 0);
        assertEquals(now.plusHours(2), PetAdventureChestRoutine.afterAttemptsSpent(now, reset, true));
    }

    @Test
    void spentAttemptsWithNothingRunningWaitForTheReset() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 30, 8, 29);
        LocalDateTime reset = LocalDateTime.of(2026, 10, 30, 20, 0);
        assertEquals(reset, PetAdventureChestRoutine.afterAttemptsSpent(now, reset, false));
        // Close to the reset, the reset comes first either way.
        LocalDateTime late = LocalDateTime.of(2026, 10, 30, 19, 0);
        assertEquals(reset, PetAdventureChestRoutine.afterAttemptsSpent(late, reset, true));
    }

    @Test
    void treatsATimerUnderThePinAsAnOccupiedChest() {
        PointData chest = new PointData(180, 360);
        ImageSearchResultData timer = ImageSearchResultData.hit(175, 450, 95);
        assertTrue(PetAdventureDecisions.occupiedByTimer(chest, List.of(timer)));
    }

    @Test
    void leavesAChestIdleWhenTheTimerBelongsToAnotherPin() {
        PointData chest = new PointData(600, 700);
        ImageSearchResultData timer = ImageSearchResultData.hit(175, 450, 95);
        assertFalse(PetAdventureDecisions.occupiedByTimer(chest, List.of(timer)));
    }
}
