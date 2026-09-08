package dev.frostguard.tasks.city;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * The owned-copies count is the number at the end of the row, not the first digit on it.
 *
 * <p>The crop reads through a whitelist of {@code "OwnedOWNED:0123456789 "}, which admits both
 * {@code O} and {@code 0}. So the label itself can come back as "0wned", and taking the first digit
 * run reads that zero as the count. Both directions of that mistake are real: a row that should
 * have been sent gets skipped as zero, and a stray digit ahead of the label could read as two or
 * more and send a copy that should have been left alone.
 */
class MonumentOwnedCountParseTest {

    @Test
    void aCleanRowReadsItsCount() {
        assertEquals(2, MonumentRoutine.ownedCountFrom("Owned: 2"));
        assertEquals(12, MonumentRoutine.ownedCountFrom("Owned: 12"));
        assertEquals(1, MonumentRoutine.ownedCountFrom("Owned: 1"));
    }

    @Test
    void theLabelMisreadAsAZeroDoesNotBecomeTheCount() {
        // The whole point: "0wned: 2" is two copies, not zero.
        assertEquals(2, MonumentRoutine.ownedCountFrom("0wned: 2"));
        assertEquals(3, MonumentRoutine.ownedCountFrom("0WNED: 3"));
    }

    @Test
    void aStrayLeadingDigitDoesNotInventASend() {
        // Reading the first run here would give 8 and send a copy that should be left alone.
        assertEquals(1, MonumentRoutine.ownedCountFrom("8 Owned: 1"));
    }

    @Test
    void aRowWithNoDigitsIsUnknownRatherThanZero() {
        assertNull(MonumentRoutine.ownedCountFrom("Owned:"));
        assertNull(MonumentRoutine.ownedCountFrom(""));
        assertNull(MonumentRoutine.ownedCountFrom(null));
    }
}
