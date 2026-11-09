package dev.frostguard.engine.schedule;

import static dev.frostguard.api.configs.ConfigurationKeyEnum.BEAR_TRAP_EVENT_BOOL;
import static dev.frostguard.api.configs.ConfigurationKeyEnum.BEAR_TRAP_SCHEDULE_DATETIME_STRING;
import static dev.frostguard.api.configs.ConfigurationKeyEnum.BEAR_TRAP_TIMER_1_BLOCK_RALLIES_BOOL;
import static dev.frostguard.api.configs.ConfigurationKeyEnum.BEAR_TRAP_TIMER_1_ENABLED_BOOL;
import static dev.frostguard.api.configs.ConfigurationKeyEnum.BEAR_TRAP_TIMER_1_PAUSE_ALL_TASKS_BOOL;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.engine.helper.BearTrapHelper;
import dev.frostguard.engine.helper.TimeWindowHelper;

/**
 * Resolves the Bear Trap protection window: it opens the lead time before the event and ends
 * {@link #RESUME_BUFFER_MINUTES} after the event's thirty minutes. The entered start repeats every
 * forty-eight hours, matching the Bear Trap event cadence.
 */
public final class BearTrapProtectionPolicy {

    public static final int DEFAULT_LEAD_TIME_MINUTES = 15;
    public static final int ACTIVE_WINDOW_MINUTES = 30;
    /** Tasks stay held this long after the event, so nothing starts while marches are coming home. */
    public static final int RESUME_BUFFER_MINUTES = 2;
    public static final int RELEASE_BUFFER_SECONDS = 5;

    private BearTrapProtectionPolicy() {
    }

    public static Decision evaluateTask(AccountDescriptor profile, TpDailyTaskEnum task) {
        return evaluateTask(profile, task, Clock.systemUTC());
    }

    static Decision evaluateTask(AccountDescriptor profile, TpDailyTaskEnum task, Clock clock) {
        if (profile == null || task == null || isProtectionExempt(task)) {
            return Decision.allowed();
        }

        return evaluate(profile, task.canStartRally(), true, clock);
    }

    public static Decision evaluateRallyStart(AccountDescriptor profile) {
        return evaluateRallyStart(profile, Clock.systemUTC());
    }

    static Decision evaluateRallyStart(AccountDescriptor profile, Clock clock) {
        return evaluate(profile, true, false, clock);
    }

    public static boolean isFullPauseActive(AccountDescriptor profile) {
        return evaluate(profile, false, true, Clock.systemUTC()).blocked();
    }

    private static boolean isProtectionExempt(TpDailyTaskEnum task) {
        return task == TpDailyTaskEnum.BEAR_TRAP
                || task == TpDailyTaskEnum.INITIALIZE
                || task == TpDailyTaskEnum.SKIP_TUTORIAL
                || task == TpDailyTaskEnum.CREATE_CHARACTER;
    }

    private static Decision evaluate(
            AccountDescriptor profile,
            boolean rallyStartingAction,
            boolean includeFullPause,
            Clock clock) {
        if (profile == null) {
            return Decision.allowed();
        }

        List<ActiveProtection> blockers = activeProtections(profile, clock).stream()
                .filter(active -> (includeFullPause && active.pauseAllTasks())
                        || (rallyStartingAction && (active.blockRallies() || active.pauseAllTasks())))
                .toList();
        if (blockers.isEmpty()) {
            return Decision.allowed();
        }

        Instant releaseAt = blockers.stream()
                .map(ActiveProtection::releaseAt)
                .max(Instant::compareTo)
                .orElseThrow()
                .plusSeconds(RELEASE_BUFFER_SECONDS);
        boolean fullPause = includeFullPause && blockers.stream().anyMatch(ActiveProtection::pauseAllTasks);
        String traps = blockers.stream()
                .map(ActiveProtection::source)
                .distinct()
                .reduce((left, right) -> left + ", " + right)
                .orElse("");

        return new Decision(true, fullPause ? BlockReason.ALL_TASKS : BlockReason.RALLY_START, traps, releaseAt);
    }

    private static List<ActiveProtection> activeProtections(AccountDescriptor profile, Clock clock) {
        List<ActiveProtection> result = new ArrayList<>(2);
        BearTrapVisualProtection.releaseAt(profile.getId(), clock)
                .ifPresent(releaseAt -> result.add(new ActiveProtection("icon", true, false, releaseAt)));
        addIfActive(result, profile, clock);
        return result;
    }

    private static void addIfActive(
            List<ActiveProtection> result,
            AccountDescriptor profile,
            Clock clock) {
        if (!isTimerEnabled(profile)) {
            return;
        }

        LocalDateTime anchor = profile.getConfig(BEAR_TRAP_SCHEDULE_DATETIME_STRING, LocalDateTime.class);
        if (anchor == null) {
            return;
        }

        BearTrapHelper.WindowResult window = BearTrapHelper.calculateWindow(
                anchor.toInstant(ZoneOffset.UTC),
                protectionLeadTimeMinutes(profile),
                ACTIVE_WINDOW_MINUTES + RESUME_BUFFER_MINUTES,
                2,
                clock);
        // The window helper counts its closing instant as inside, but that instant is when tasks resume.
        if (window.getState() != TimeWindowHelper.WindowState.INSIDE
                || !clock.instant().isBefore(window.getCurrentWindowEnd())) {
            return;
        }

        result.add(new ActiveProtection(
                "1",
                Boolean.TRUE.equals(profile.getConfig(BEAR_TRAP_TIMER_1_BLOCK_RALLIES_BOOL, Boolean.class)),
                Boolean.TRUE.equals(profile.getConfig(BEAR_TRAP_TIMER_1_PAUSE_ALL_TASKS_BOOL, Boolean.class)),
                window.getCurrentWindowEnd()));
    }

    private static int protectionLeadTimeMinutes(AccountDescriptor profile) {
        Integer configured = profile.getConfig(
                ConfigurationKeyEnum.BEAR_TRAP_PREPARATION_TIME_INT,
                Integer.class);
        return configured != null && configured >= 0 ? configured : DEFAULT_LEAD_TIME_MINUTES;
    }

    private static boolean isTimerEnabled(AccountDescriptor profile) {
        boolean explicitlyConfigured = profile.getEntries().stream()
                .anyMatch(entry -> BEAR_TRAP_TIMER_1_ENABLED_BOOL.equals(entry.getSettingKey()));
        if (explicitlyConfigured) {
            return Boolean.TRUE.equals(profile.getConfig(BEAR_TRAP_TIMER_1_ENABLED_BOOL, Boolean.class));
        }

        // Before the guard switch existed, Bear automation itself activated the window.
        return Boolean.TRUE.equals(profile.getConfig(BEAR_TRAP_EVENT_BOOL, Boolean.class));
    }

    public enum BlockReason {
        RALLY_START,
        ALL_TASKS
    }

    public record Decision(boolean blocked, BlockReason reason, String trapNumbers, Instant releaseAt) {
        static Decision allowed() {
            return new Decision(false, null, "", null);
        }
    }

    private record ActiveProtection(
            String source,
            boolean blockRallies,
            boolean pauseAllTasks,
            Instant releaseAt) {
    }
}
