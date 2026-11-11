package dev.frostguard.engine.nav;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.Deque;

import org.junit.jupiter.api.Test;

class RecallConfirmationGuardTest {

    @Test
    void doesNothingWhenNoConfirmationIsUp() {
        Guard guard = new Guard(false);

        assertTrue(guard.clear());
        assertEquals(0, guard.backPresses, "An absent dialog must not cost a back press");
    }

    @Test
    void backsOutOfTheConfirmationRatherThanConfirmingIt() {
        Guard guard = new Guard(true, false);

        assertTrue(guard.clear());
        assertEquals(1, guard.backPresses, "Back is the only move that cancels it");
    }

    @Test
    void keepsTryingWithinItsBudgetWhenTheFirstBackDoesNotTake() {
        Guard guard = new Guard(true, true, false);

        assertTrue(guard.clear());
        assertEquals(2, guard.backPresses);
    }

    @Test
    void refusesTheScreenWhenTheConfirmationWillNotClear() {
        Guard guard = new Guard(true, true, true, true, true);

        assertFalse(guard.clear(), "A dialog that will not clear must stop the caller tapping");
        assertEquals(RecallConfirmationGuard.MAX_BACK_PRESSES, guard.backPresses,
                "Backing out forever is its own hazard; the budget is bounded");
    }

    private static final class Guard implements RecallConfirmationGuard {
        private final Deque<Boolean> readings = new ArrayDeque<>();
        private int backPresses;

        private Guard(Boolean... onScreen) {
            for (Boolean reading : onScreen) {
                readings.add(reading);
            }
        }

        @Override
        public boolean dialogOnScreen() {
            return readings.isEmpty() ? false : readings.remove();
        }

        @Override
        public void pressBack() {
            backPresses++;
        }
    }
}
