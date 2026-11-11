package dev.frostguard.engine.nav;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class WorldMarchingListTest {

    /** The control heights measured off the 2026-11-11 frames, top row first. */
    private static final int[] MEASURED = { 295, 355, 416, 477, 538, 596 };

    @Test
    void attributesEachMeasuredControlToItsOwnRow() {
        for (int row = 0; row < MEASURED.length; row++) {
            assertEquals(row, WorldMarchingList.rowAt(MEASURED[row]),
                    "control at y=" + MEASURED[row]);
        }
    }

    @Test
    void absorbsTheFewPixelsTheListShiftsBetweenFrames() {
        for (int row = 0; row < MEASURED.length; row++) {
            assertEquals(row, WorldMarchingList.rowAt(MEASURED[row] - 8));
            assertEquals(row, WorldMarchingList.rowAt(MEASURED[row] + 8));
        }
    }

    @Test
    void refusesAControlTooFarFromAnyRowRatherThanGuessing() {
        assertEquals(-1, WorldMarchingList.rowAt(200), "above the list");
        assertEquals(-1, WorldMarchingList.rowAt(700), "below the list");
        assertEquals(-1, WorldMarchingList.rowAt(1078), "the tile card's own Recall button");
    }

    @Test
    void neverReadsAControlIntoItsNeighbour() {
        // Midway between two rows is nobody's row; attributing it would recall the wrong march.
        for (int row = 0; row < MEASURED.length - 1; row++) {
            int midpoint = (MEASURED[row] + MEASURED[row + 1]) / 2;
            assertEquals(-1, WorldMarchingList.rowAt(midpoint),
                    "midpoint between rows " + row + " and " + (row + 1));
        }
    }

    @Test
    void reportsWhereARowWasExpected() {
        for (int row = 0; row < MEASURED.length; row++) {
            assertEquals(MEASURED[row], WorldMarchingList.rowCentreY(row));
        }
    }
}
