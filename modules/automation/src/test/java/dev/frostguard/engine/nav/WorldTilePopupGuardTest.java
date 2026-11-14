package dev.frostguard.engine.nav;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.Deque;

import org.junit.jupiter.api.Test;

class WorldTilePopupGuardTest {

    @Test
    void doesNothingOnAClearMap() {
        // Back on a clean World map asks to quit the game; it must not be pressed on a guess.
        Guard guard = new Guard(false);

        assertTrue(guard.clear());
        assertEquals(0, guard.backPresses);
    }

    @Test
    void backsOutOfThePopupRatherThanTappingIt() {
        Guard guard = new Guard(true, false);

        assertTrue(guard.clear());
        assertEquals(1, guard.backPresses);
    }

    @Test
    void refusesTheScreenWhenOneBackDoesNotCloseIt() {
        Guard guard = new Guard(true, true, true);

        assertFalse(guard.clear(), "A popup still up must stop the caller tapping beneath it");
        assertEquals(1, guard.backPresses,
                "One back per reading: a second could land on the bare map and open Quit Game");
    }

    private static final class Guard implements WorldTilePopupGuard {
        private final Deque<Boolean> readings = new ArrayDeque<>();
        private int backPresses;

        private Guard(Boolean... onScreen) {
            for (Boolean reading : onScreen) {
                readings.add(reading);
            }
        }

        @Override
        public boolean popupOnScreen() {
            return readings.isEmpty() ? false : readings.remove();
        }

        @Override
        public void pressBack() {
            backPresses++;
        }
    }
}
