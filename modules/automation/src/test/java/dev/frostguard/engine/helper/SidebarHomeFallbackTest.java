package dev.frostguard.engine.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import org.junit.jupiter.api.Test;

class SidebarHomeFallbackTest {

    private static final class Screens implements SidebarHomeFallback.Screens {
        final List<String> calls = new ArrayList<>();
        boolean closedOnWorld = true;
        boolean homeReached = true;

        @Override
        public void recordFailedOpen() {
            calls.add("frame");
        }

        @Override
        public boolean sidebarClosedOnWorld() {
            return closedOnWorld;
        }

        @Override
        public boolean switchToHome() {
            calls.add("home");
            return homeReached;
        }

        @Override
        public boolean switchToWorld() {
            calls.add("world");
            return true;
        }
    }

    private final Screens screens = new Screens();
    private final SidebarHomeFallback fallback = new SidebarHomeFallback(screens);

    private boolean open(Boolean... results) {
        Queue<Boolean> queue = new ArrayDeque<>(List.of(results));
        return fallback.open(queue::remove);
    }

    @Test
    void aSidebarThatOpensFirstTimeNeverLeavesTheScreen() {
        assertTrue(open(true));
        fallback.restoreWorld();

        assertEquals(List.of(), screens.calls);
    }

    @Test
    void aFailureOnWorldKeepsTheFrameAndTriesFromHome() {
        assertTrue(open(false, true));

        assertEquals(List.of("frame", "home"), screens.calls);
    }

    @Test
    void closingThePanelPutsTheCallerBackOnWorldOnce() {
        open(false, true);

        fallback.restoreWorld();
        fallback.restoreWorld();

        assertEquals(List.of("frame", "home", "world"), screens.calls);
    }

    @Test
    void followingARowToAnotherScreenDoesNotReturnToWorld() {
        open(false, true);

        fallback.forget();
        fallback.restoreWorld();

        assertEquals(List.of("frame", "home"), screens.calls);
    }

    @Test
    void aFailureOffWorldStaysPutAndReportsFailure() {
        screens.closedOnWorld = false;

        assertFalse(open(false));

        assertEquals(List.of("frame"), screens.calls);
    }

    @Test
    void failingFromHomeAsWellGoesBackToWorld() {
        assertFalse(open(false, false));

        assertEquals(List.of("frame", "home", "world"), screens.calls);
    }

    @Test
    void notReachingHomeReportsFailureWithoutASecondAttempt() {
        screens.homeReached = false;

        assertFalse(open(false));

        assertEquals(List.of("frame", "home"), screens.calls);
    }
}
