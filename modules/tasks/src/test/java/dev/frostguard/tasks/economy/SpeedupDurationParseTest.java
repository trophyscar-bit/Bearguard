package dev.frostguard.tasks.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * The speedup row parser, and the reason it refuses a row it cannot read end to end.
 *
 * <p>Each component used to be picked out wherever it appeared, and one hit was enough to return a
 * number. The durations are right-aligned, so the longer ones reach further left, and the value
 * column's edge was clipping the leading number off them. The two faults together turned a
 * partly-read row into a plausible small figure: "1 day(s)5 hr(s)2 min" lost its "1" and came back
 * as 302 minutes instead of 1,742, which the Statistics tab then reported as fifteen hours of
 * construction speedup spent on a night nothing was spent.</p>
 */
class SpeedupDurationParseTest {

    @Test
    void readsEveryComponentOfAWholeDuration() {
        assertEquals(16_185L, ResourceStockpileRoutine.parseDurationMinutes("11 day(s)5 hr(s)45 min"));
        assertEquals(624L, ResourceStockpileRoutine.parseDurationMinutes("10 hr(s)24 min"));
        assertEquals(730L, ResourceStockpileRoutine.parseDurationMinutes("12 hr(s)10 min"));
        assertEquals(1_440L, ResourceStockpileRoutine.parseDurationMinutes("1 day(s)"));
        assertEquals(8L, ResourceStockpileRoutine.parseDurationMinutes("8 min"));
    }

    /** The reader spaces these unpredictably, so spacing must not decide the answer. */
    @Test
    void spacingDoesNotMatter() {
        assertEquals(2_745L, ResourceStockpileRoutine.parseDurationMinutes("1 day(s)21 hr(s)45 min"));
        assertEquals(2_745L, ResourceStockpileRoutine.parseDurationMinutes("1day(s)21hr(s)45min"));
        assertEquals(2_745L, ResourceStockpileRoutine.parseDurationMinutes(" 1 day(s) 21 hr(s) 45 min "));
    }

    /** A unit with no number in front of it means the number was clipped, not that it was zero. */
    @Test
    void aUnitWithNoNumberIsRefused() {
        assertNull(ResourceStockpileRoutine.parseDurationMinutes("hr(s)24 min"),
                "the training bucket's run of small readings came from exactly this");
        assertNull(ResourceStockpileRoutine.parseDurationMinutes("hr(s)16 min"));
        assertNull(ResourceStockpileRoutine.parseDurationMinutes("day(s)5 hr(s)2 min"),
                "1d5h2m is 1742 minutes; read as 302 it looked like a spend that never happened");
    }

    /** Anything the pattern does not account for is a bad read, not a partial answer. */
    @Test
    void leftoverCharactersAreRefused() {
        assertNull(ResourceStockpileRoutine.parseDurationMinutes("s)8 min"));
        assertNull(ResourceStockpileRoutine.parseDurationMinutes("Speedup 10 hr(s)24 min"));
        assertNull(ResourceStockpileRoutine.parseDurationMinutes("10 hr(s)24 min 5"));
        assertNull(ResourceStockpileRoutine.parseDurationMinutes("--"));
        assertNull(ResourceStockpileRoutine.parseDurationMinutes(""));
        assertNull(ResourceStockpileRoutine.parseDurationMinutes(null));
    }

    /** Components out of order are not a duration this panel produces. */
    @Test
    void componentsMustBeInOrder() {
        assertNull(ResourceStockpileRoutine.parseDurationMinutes("45 min 11 day(s)"));
    }
}
