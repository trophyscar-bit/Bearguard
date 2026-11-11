package dev.frostguard.engine.nav;

/**
 * Row geometry of the World "Marching" list, the panel that carries the per-march recall control.
 *
 * <p>There are two march lists in the game and only one of them can recall. The sidebar's
 * Wilderness "March Queue" lists the same marches in the same order, but its rows carry a jump
 * arrow and no recall control at all -- searching it for one finds nothing, which is why gather
 * recall reported "controls missing" in every log back to 2026-10-06. The World list is the one
 * with the recall arrow, and opening the sidebar covers it.
 *
 * <p>Row centres are measured, not modelled, from the 2026-11-11 frames: the recall control sits
 * at x=222 and the six rows at y=295, 355, 416, 477, 538 and 596. A fitted 60 px pitch is off by
 * up to 3 px at the bottom, so the measured values are used directly.
 *
 * <p>Rows do not all carry the same control: a gathering march shows recall, a returning one shows
 * speedup or view. So the n-th recall control is not the n-th march, and a row has to be resolved
 * by position rather than by counting.
 */
public final class WorldMarchingList {

    /** Marches the list can show at once. */
    public static final int ROWS = 6;

    /** Measured centre of each row's control, top row first. */
    private static final int[] ROW_CENTRE_Y = { 295, 355, 416, 477, 538, 596 };

    /**
     * Half the row pitch would be 30. Twenty keeps a control from being read into a neighbouring
     * row while still absorbing the few pixels the list shifts between frames.
     */
    private static final int ROW_TOLERANCE = 20;

    private WorldMarchingList() {
    }

    /**
     * The row a control at this height belongs to.
     *
     * @return the row index, or -1 when it is too far from any row to attribute safely
     */
    public static int rowAt(int y) {
        int best = -1;
        int bestDistance = Integer.MAX_VALUE;
        for (int row = 0; row < ROW_CENTRE_Y.length; row++) {
            int distance = Math.abs(ROW_CENTRE_Y[row] - y);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = row;
            }
        }
        return bestDistance <= ROW_TOLERANCE ? best : -1;
    }

    /** Measured centre of a row, for logging what was expected where. */
    public static int rowCentreY(int row) {
        if (row < 0 || row >= ROW_CENTRE_Y.length) {
            throw new IllegalArgumentException("No such march row: " + row);
        }
        return ROW_CENTRE_Y[row];
    }
}
