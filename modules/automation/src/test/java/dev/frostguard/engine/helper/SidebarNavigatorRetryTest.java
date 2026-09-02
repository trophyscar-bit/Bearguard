package dev.frostguard.engine.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/**
 * Opening a sidebar section retries a transient miss.
 *
 * <p>The single-attempt behaviour this replaces failed real runs rather than being merely
 * theoretical. {@code MarchHelper.openLeftMenuCitySection} turns a failed open into an
 * {@code IllegalStateException}, and 18 of the 19 call sites across the task routines do not catch
 * it, so one mistimed frame killed the whole task. Live on 2026-09-02 Gather Resources failed three
 * times in a row that way, and Timer sweep the day before, both logging
 * {@code requested=CITY observed=closed/unknown} — the panel classified mid-animation, a beat
 * before it was genuinely open.
 *
 * <p>The retry is exercised here without an emulator, which is the point of extracting it.
 */
class SidebarNavigatorRetryTest {

    /** A settle that always succeeds, and counts how often it was asked to wait. */
    private static java.util.function.BooleanSupplier settleCounting(AtomicInteger waits) {
        return () -> {
            waits.incrementAndGet();
            return true;
        };
    }

    @Test
    void anOpenThatSucceedsFirstTimeDoesNotWaitAtAll() {
        AtomicInteger waits = new AtomicInteger();
        AtomicInteger recovered = new AtomicInteger(-1);

        boolean opened = SidebarNavigator.retryOpen(() -> true, settleCounting(waits), recovered::set);

        assertTrue(opened);
        assertEquals(0, waits.get(), "a first-attempt success must not sleep");
        assertEquals(-1, recovered.get(), "no recovery to report when the first attempt worked");
    }

    @Test
    void aTransientMissIsRecoveredOnTheSecondAttempt() {
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger waits = new AtomicInteger();
        AtomicInteger recovered = new AtomicInteger(-1);

        // Fails once -- the mid-animation frame -- then reads correctly.
        boolean opened = SidebarNavigator.retryOpen(
                () -> attempts.incrementAndGet() > 1, settleCounting(waits), recovered::set);

        assertTrue(opened, "a single transient miss must not fail the task");
        assertEquals(2, attempts.get());
        assertEquals(1, waits.get(), "exactly one settle between the two attempts");
        assertEquals(2, recovered.get(), "the recovery should report which attempt worked");
    }

    @Test
    void aGenuinelyClosedPanelGivesUpAfterTheBudgetRatherThanStalling() {
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger waits = new AtomicInteger();

        boolean opened = SidebarNavigator.retryOpen(
                () -> {
                    attempts.incrementAndGet();
                    return false;
                }, settleCounting(waits), n -> { });

        assertFalse(opened);
        assertEquals(3, attempts.get(), "bounded: three attempts, not an unbounded wait");
        assertEquals(2, waits.get(), "no pointless settle after the final attempt");
    }

    @Test
    void anInterruptedSettleAbandonsImmediately() {
        AtomicInteger attempts = new AtomicInteger();

        // A stop request during the wait is not a slow panel -- it must not be slept through.
        boolean opened = SidebarNavigator.retryOpen(
                () -> {
                    attempts.incrementAndGet();
                    return false;
                }, () -> false, n -> { });

        assertFalse(opened);
        assertEquals(1, attempts.get(), "an interrupt must stop the retry, not continue it");
    }
}
