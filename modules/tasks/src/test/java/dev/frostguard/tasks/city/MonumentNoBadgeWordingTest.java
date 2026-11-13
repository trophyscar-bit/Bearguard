package dev.frostguard.tasks.city;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** An empty Monument pass must not read like a failure; a pass that never reached it must. */
class MonumentNoBadgeWordingTest {

    @Test
    void emptyPassWordingDoesNotLookLikeAFailure() {
        String m = MonumentRoutine.NO_REWARD_BADGE_MSG.toLowerCase();
        assertFalse(m.contains("not found"));
        assertFalse(m.contains("failed"));
        assertTrue(m.contains("nothing to claim"));
    }

    @Test
    void notReachedWordingStaysADistinctFailureMessage() {
        String m = MonumentRoutine.MONUMENT_NOT_REACHED_MSG.toLowerCase();
        assertTrue(m.contains("not reached"));
        assertFalse(m.contains("nothing to claim"));
    }
}
