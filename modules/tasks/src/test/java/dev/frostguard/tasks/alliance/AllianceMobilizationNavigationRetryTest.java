package dev.frostguard.tasks.alliance;

import org.junit.jupiter.api.Test;

import dev.frostguard.engine.helper.NavigationHelper.EventMenuOpenResult;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins which failed opens are worth repeating inside one Alliance Mobilization pass.
 *
 * <p>Written after 10 Nov, when both runs spent all three navigation attempts on a tab that was
 * not on the strip: 19:02:24, 19:02:55, 19:03:26 and again 19:21:44, 19:22:18, 19:22:48, each
 * ending in {@code Tab not found: ALLIANCE_MOBILIZATION}. Over the same period Endless Trial was
 * reached nine times through the same {@code openEventMenu}, so the strip was readable and the
 * event simply was not scheduled.</p>
 */
class AllianceMobilizationNavigationRetryTest {

    @Test
    void doesNotReopenTheMenuForASettledAbsentTab() {
        assertFalse(AllianceMobilizationRoutine.worthAnotherNavigationAttempt(EventMenuOpenResult.TAB_ABSENT),
                "openEventMenu already swiped the strip and tried the unselected variant; a second "
                        + "pass reads the same strip for the same answer");
    }

    @Test
    void reopensTheMenuWhenThePanelNeverOpened() {
        assertTrue(AllianceMobilizationRoutine.worthAnotherNavigationAttempt(EventMenuOpenResult.PANEL_CLOSED),
                "a panel that did not open is transient and the next attempt can clear it");
    }

    @Test
    void stopsOnceTheTabIsReached() {
        assertFalse(AllianceMobilizationRoutine.worthAnotherNavigationAttempt(EventMenuOpenResult.REACHED));
    }

    @Test
    void everyOpenResultIsDecidedExplicitly() {
        // A new EventMenuOpenResult constant must be classified on purpose rather than inheriting
        // "retry" by falling through the negation.
        for (EventMenuOpenResult result : EventMenuOpenResult.values()) {
            boolean retry = AllianceMobilizationRoutine.worthAnotherNavigationAttempt(result);
            switch (result) {
                case REACHED, TAB_ABSENT -> assertFalse(retry, result + " must not be retried");
                case PANEL_CLOSED -> assertTrue(retry, result + " must be retried");
                default -> throw new AssertionError(
                        "Unclassified EventMenuOpenResult: " + result + ". Decide whether re-opening "
                                + "the menu can change it before letting it retry three times.");
            }
        }
    }
}
