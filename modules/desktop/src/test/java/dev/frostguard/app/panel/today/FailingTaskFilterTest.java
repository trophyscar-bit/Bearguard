package dev.frostguard.app.panel.today;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.domain.TaskFailureStreakData;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FailingTaskFilterTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 9, 14, 0);

    private static TaskFailureStreakData failedAt(LocalDateTime last) {
        return new TaskFailureStreakData(1L, "WAR_ACADEMY", "x", 1, last, last);
    }

    @Test
    void aRecentFailureOfARunningTaskIsShown() {
        assertTrue(FailingTaskFilter.isCurrent(failedAt(NOW.minusHours(1)), false, NOW));
        assertTrue(FailingTaskFilter.isCurrent(failedAt(NOW.minusDays(8).plusMinutes(1)), false, NOW));
    }

    @Test
    void aFailureOlderThanTheThresholdIsHidden() {
        assertFalse(FailingTaskFilter.isCurrent(failedAt(NOW.minusDays(8)), false, NOW));
        assertFalse(FailingTaskFilter.isCurrent(failedAt(LocalDateTime.of(2026, 10, 25, 18, 38)), false, NOW));
    }

    @Test
    void aSwitchedOffTaskIsHiddenHoweverRecent() {
        assertFalse(FailingTaskFilter.isCurrent(failedAt(NOW.minusMinutes(1)), true, NOW));
    }

    @Test
    void aClearedStreakIsHidden() {
        var cleared = new TaskFailureStreakData(1L, "WAR_ACADEMY", "x", 0, NOW, NOW);
        assertFalse(FailingTaskFilter.isCurrent(cleared, false, NOW));
    }

    @Test
    void theSwitchIsFoundFromTheStoredTaskKey() {
        assertEquals(ConfigurationKeyEnum.WAR_ACADEMY_TASK_BOOL, FailingTaskFilter.switchOf("WAR_ACADEMY"));
        assertNull(FailingTaskFilter.switchOf("GAME_ANALYTICS_LABYRINTH"), "a button-only task has no switch");
        assertNull(FailingTaskFilter.switchOf("CUSTOM_TASK:bg_telemetry"), "a custom task has no switch");
        assertNull(FailingTaskFilter.switchOf("A_TASK_THAT_NO_LONGER_EXISTS"));
    }

    @Test
    void aRoutinesOwnReasonIsReadBackAndAnExceptionHasNone() {
        var handled = new TaskFailureStreakData(1L, "WAR_ACADEMY",
                TaskFailureStreakData.handledSignature("War Academy has no row in the City sidebar"), 1, NOW, NOW);
        assertEquals("War Academy has no row in the City sidebar", handled.handledReason());
        assertNull(failedAt(NOW).handledReason());
    }
}
