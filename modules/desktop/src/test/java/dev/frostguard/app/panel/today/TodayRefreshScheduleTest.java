package dev.frostguard.app.panel.today;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TodayRefreshScheduleTest {

    private final TodayRefreshSchedule schedule = new TodayRefreshSchedule();

    @Test
    void anEventRefreshesAtOnceWhileShowing() {
        assertEquals(TodayRefreshSchedule.Action.DEBOUNCED_RELOAD, schedule.onEvent(true));
    }

    @Test
    void beingShownAlwaysReloadsEvenWithNoEventSinceTheLastLook() {
        schedule.reloaded();

        assertEquals(TodayRefreshSchedule.Action.RELOAD, schedule.onShown());
    }

    @Test
    void theBackstopRunsWithoutAnyEvent() {
        schedule.reloaded();
        for (int second = 1; second < TodayRefreshSchedule.BACKSTOP_SECONDS; second++) {
            assertEquals(TodayRefreshSchedule.Action.NONE, schedule.onSecond());
        }

        assertEquals(TodayRefreshSchedule.Action.RELOAD, schedule.onSecond());
    }

    @Test
    void aReloadRestartsTheBackstop() {
        schedule.onSecond();
        schedule.reloaded();

        for (int second = 1; second < TodayRefreshSchedule.BACKSTOP_SECONDS; second++) {
            assertEquals(TodayRefreshSchedule.Action.NONE, schedule.onSecond());
        }
    }
}
