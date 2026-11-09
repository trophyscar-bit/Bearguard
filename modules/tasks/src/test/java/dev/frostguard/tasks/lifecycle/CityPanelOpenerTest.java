package dev.frostguard.tasks.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import org.junit.jupiter.api.Test;

class CityPanelOpenerTest {

    private static final class Recording implements CityPanelOpener.Steps {
        final List<String> calls = new ArrayList<>();
        final Queue<Boolean> sidebarOpens = new ArrayDeque<>();
        final Queue<Boolean> readable = new ArrayDeque<>();

        @Override
        public void closeSidebar() {
            calls.add("close");
        }

        @Override
        public void openCitySection() {
            calls.add("open");
            if (!sidebarOpens.isEmpty() && !sidebarOpens.remove()) {
                throw new IllegalStateException("Could not open the requested sidebar section");
            }
        }

        @Override
        public boolean panelReadable() {
            calls.add("read");
            return readable.remove();
        }

        @Override
        public void returnHome() {
            calls.add("home");
        }
    }

    @Test
    void aPanelLeftOpenIsClosedBeforeItIsOpenedAndRead() {
        Recording steps = new Recording();
        steps.readable.add(true);

        assertTrue(new CityPanelOpener(steps, message -> { }).open(3));

        assertEquals(List.of("close", "open", "read"), steps.calls);
    }

    @Test
    void anUnreadablePanelIsClosedAndRetriedFromHome() {
        Recording steps = new Recording();
        steps.readable.addAll(List.of(false, true));

        assertTrue(new CityPanelOpener(steps, message -> { }).open(3));

        assertEquals(List.of("close", "open", "read", "close", "home", "close", "open", "read"), steps.calls);
    }

    @Test
    void aSidebarThatWillNotOpenIsRetriedRatherThanEndingTheSweep() {
        Recording steps = new Recording();
        steps.sidebarOpens.addAll(List.of(false, true));
        steps.readable.add(true);
        List<String> warnings = new ArrayList<>();

        assertTrue(new CityPanelOpener(steps, warnings::add).open(3));

        assertEquals(List.of("close", "open", "close", "home", "close", "open", "read"), steps.calls);
        assertEquals(1, warnings.size());
    }

    @Test
    void reportsFailureOnceEveryAttemptIsSpent() {
        Recording steps = new Recording();
        steps.readable.addAll(List.of(false, false, false));

        assertFalse(new CityPanelOpener(steps, message -> { }).open(3));

        assertEquals(3, steps.calls.stream().filter("open"::equals).count());
        assertEquals(3, steps.calls.stream().filter("home"::equals).count());
    }
}
