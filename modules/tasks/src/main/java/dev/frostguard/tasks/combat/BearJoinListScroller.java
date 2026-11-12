package dev.frostguard.tasks.combat;

/**
 * Decides when the War list is scrolled. Open rallies below the first screenful were never seen
 * (Nov 12: no swipe anywhere in the join code, 247 joins missed against 45 made). When nothing
 * joinable is on screen the list moves down a step at a time, and after the last step it jumps back
 * to the top, where new rallies appear.
 */
final class BearJoinListScroller {

    enum Move { DOWN, TO_TOP }

    static final int MAX_STEPS_DOWN = 3;

    private int depth;

    /** What to do now that nothing on the visible part of the list could be joined. */
    Move afterNothingToJoin() {
        if (depth < MAX_STEPS_DOWN) {
            depth++;
            return Move.DOWN;
        }
        depth = 0;
        return Move.TO_TOP;
    }

    /** How many steps down the list currently is. */
    int depth() {
        return depth;
    }

    /** The list is known to be at the top again (the War screen was reopened). */
    void reset() {
        depth = 0;
    }
}
