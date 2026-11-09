package dev.frostguard.app.panel.misc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StatisticsHeadlineTest {

    @Test
    void aMeasuredChangeShowsAsSigned() {
        assertEquals("+5", StatisticsLayoutController.headline(5L, StatisticsLayoutController::fmtSigned).replace(" ", ""));
    }

    @Test
    void measuredWithNoMovementSaysNoChange() {
        assertEquals("No change", StatisticsLayoutController.headline(0L, change -> "number"));
    }

    @Test
    void noReadingSaysNoDataAndNeverANumber() {
        assertEquals("no data", StatisticsLayoutController.headline(null, change -> "number"));
    }
}
