package dev.frostguard.tasks.events;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import dev.frostguard.api.configs.TpDailyTaskEnum;

/**
 * Turns the doable missions a sweep found into "run that task soon". It only schedules a task that is
 * already switched on for the profile; a disabled one is logged and left alone, since the sweep never
 * enables anything. Each task is nudged at most once per {@link #MIN_GAP}, so a mission that stays unfinished
 * does not pull the same task forward at every pass.
 */
final class EventMissionTrigger {

    /** The task runs this long after the sweep that asked for it. */
    static final Duration RUN_AFTER = Duration.ofMinutes(4);
    static final Duration MIN_GAP = Duration.ofHours(3);

    /** What the trigger needs from the app; the routine wires the real ones, tests wire fakes. */
    interface Environment {
        boolean enabled(TpDailyTaskEnum task);

        void schedule(TpDailyTaskEnum task, LocalDateTime due);

        LocalDateTime now();
    }

    private static final Map<String, LocalDateTime> LAST_NUDGE = new ConcurrentHashMap<>();

    private EventMissionTrigger() {
    }

    /**
     * @param owner   who is asking (the profile and the event), so one profile's throttle never holds another's
     * @param skip    tasks the caller already handles itself, such as 921's own Polar Terror rally
     * @return how many tasks were scheduled
     */
    static int run(String owner, EventMissionSweep.Result result, Set<TpDailyTaskEnum> skip, Environment env,
            Consumer<String> log) {
        int scheduled = 0;
        for (TpDailyTaskEnum task : EventMissionSweep.tasksOf(result)) {
            String mission = result.doable().stream().filter(m -> m.verdict().task() == task)
                    .map(EventMissionSweep.Mission::title).findFirst().orElse(task.getName());
            if (skip.contains(task)) {
                continue;
            }
            if (!env.enabled(task)) {
                log.accept("Mission \"" + mission + "\" is doable but task off: " + task.getName() + " is not enabled.");
                continue;
            }
            LocalDateTime now = env.now();
            String key = owner + "|" + task.name();
            LocalDateTime last = LAST_NUDGE.get(key);
            if (last != null && now.isBefore(last.plus(MIN_GAP))) {
                log.accept("Mission \"" + mission + "\": " + task.getName() + " was already scheduled at "
                        + last.toLocalTime().withNano(0) + "; not again yet.");
                continue;
            }
            LAST_NUDGE.put(key, now);
            env.schedule(task, now.plus(RUN_AFTER));
            log.accept("Mission \"" + mission + "\" is not done: scheduling " + task.getName() + " in "
                    + RUN_AFTER.toMinutes() + " min.");
            scheduled++;
        }
        return scheduled;
    }

    /** Forgets earlier nudges; tests use it so they do not leak into each other. */
    static void resetForTests() {
        LAST_NUDGE.clear();
    }
}
