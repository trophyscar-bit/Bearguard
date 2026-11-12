package dev.frostguard.engine.squads;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GearManualRunTest {

    @Test
    void aRequestIsHandedOutOnceAndThenGone() {
        GearManualRun.request(4101);

        assertTrue(GearManualRun.consume(4101));
        assertFalse(GearManualRun.consume(4101));
    }

    @Test
    void noRequestMeansTheNightlySlotRuns() {
        assertFalse(GearManualRun.consume(4102));
    }

    @Test
    void eachProfileHasItsOwnRequest() {
        GearManualRun.request(4103);

        assertFalse(GearManualRun.consume(4104));
        assertTrue(GearManualRun.consume(4103));
    }
}
