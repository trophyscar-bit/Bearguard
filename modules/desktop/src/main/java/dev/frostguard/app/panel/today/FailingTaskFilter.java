package dev.frostguard.app.panel.today;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.TaskFailureStreakData;

import java.time.Duration;
import java.time.LocalDateTime;

/** Decides which stored failure streaks are still worth showing. Nothing here changes the stored rows. */
final class FailingTaskFilter {

    /**
     * The slowest scheduled tasks run once a week, and a task that runs again either clears its
     * streak or refreshes it. One day past a week therefore means nothing has tried it since.
     */
    static final Duration MAX_FAILURE_AGE = Duration.ofDays(8);

    private FailingTaskFilter() {
    }

    static boolean isCurrent(TaskFailureStreakData streak, boolean switchedOff, LocalDateTime now) {
        return streak.consecutiveFailures() > 0
                && !switchedOff
                && streak.lastFailureAt().plus(MAX_FAILURE_AGE).isAfter(now);
    }

    /** The switch behind a stored task key, or null for a custom task or a task that has none. */
    static ConfigurationKeyEnum switchOf(String taskKey) {
        if (taskKey == null || taskKey.contains(":")) {
            return null;
        }
        try {
            return TpDailyTaskEnum.valueOf(taskKey).getConfigKey();
        } catch (IllegalArgumentException retiredTaskName) {
            return null;
        }
    }
}
