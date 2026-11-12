package dev.frostguard.tasks.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class BearJoinListScrollerTest {

    @Test
    void theListMovesDownStepByStepThenBackToTheTop() {
        BearJoinListScroller s = new BearJoinListScroller();
        for (int i = 1; i <= BearJoinListScroller.MAX_STEPS_DOWN; i++) {
            assertEquals(BearJoinListScroller.Move.DOWN, s.afterNothingToJoin());
            assertEquals(i, s.depth());
        }
        assertEquals(BearJoinListScroller.Move.TO_TOP, s.afterNothingToJoin());
        assertEquals(0, s.depth());
        // and round again
        assertEquals(BearJoinListScroller.Move.DOWN, s.afterNothingToJoin());
    }

    @Test
    void reopeningTheWarScreenStartsAtTheTop() {
        BearJoinListScroller s = new BearJoinListScroller();
        s.afterNothingToJoin();
        s.afterNothingToJoin();
        s.reset();
        assertEquals(0, s.depth());
        assertEquals(BearJoinListScroller.Move.DOWN, s.afterNothingToJoin());
    }
}
