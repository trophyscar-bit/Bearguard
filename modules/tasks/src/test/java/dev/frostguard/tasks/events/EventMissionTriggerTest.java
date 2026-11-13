package dev.frostguard.tasks.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.tasks.events.EventMissionClassifier.Verdict;
import dev.frostguard.tasks.events.EventMissionSweep.Mission;
import dev.frostguard.tasks.events.EventMissionSweep.Result;

class EventMissionTriggerTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 13, 12, 0);

    private final Map<TpDailyTaskEnum, LocalDateTime> scheduled = new HashMap<>();
    private final List<String> log = new ArrayList<>();
    private LocalDateTime now = NOW;
    private Set<TpDailyTaskEnum> on = Set.of(TpDailyTaskEnum.GATHER_RESOURCES, TpDailyTaskEnum.INTEL);

    private final EventMissionTrigger.Environment env = new EventMissionTrigger.Environment() {
        @Override
        public boolean enabled(TpDailyTaskEnum task) {
            return on.contains(task);
        }

        @Override
        public void schedule(TpDailyTaskEnum task, LocalDateTime due) {
            scheduled.put(task, due);
        }

        @Override
        public LocalDateTime now() {
            return now;
        }
    };

    @BeforeEach
    void clean() {
        EventMissionTrigger.resetForTests();
    }

    private static Result doable(TpDailyTaskEnum... tasks) {
        List<Mission> missions = new ArrayList<>();
        for (TpDailyTaskEnum task : tasks) {
            missions.add(new Mission("mission for " + task.name(), new Verdict(EventMissionClassifier.Kind.DOABLE, task)));
        }
        return new Result(0, missions, List.of(), List.of(), false, 1);
    }

    @Test
    void anEnabledTaskIsScheduledAFewMinutesOut() {
        int count = EventMissionTrigger.run("p1|921", doable(TpDailyTaskEnum.GATHER_RESOURCES), Set.of(), env, log::add);
        assertEquals(1, count);
        assertEquals(NOW.plusMinutes(4), scheduled.get(TpDailyTaskEnum.GATHER_RESOURCES));
    }

    @Test
    void aDisabledTaskIsNeverScheduledOrEnabled() {
        int count = EventMissionTrigger.run("p1|921", doable(TpDailyTaskEnum.BEAST_HUNTING), Set.of(), env, log::add);
        assertEquals(0, count);
        assertTrue(scheduled.isEmpty());
        assertTrue(log.get(0).contains("doable but task off"), log.get(0));
    }

    @Test
    void theCallersOwnTaskIsLeftAlone() {
        int count = EventMissionTrigger.run("p1|921", doable(TpDailyTaskEnum.GATHER_RESOURCES, TpDailyTaskEnum.INTEL),
                Set.of(TpDailyTaskEnum.GATHER_RESOURCES), env, log::add);
        assertEquals(1, count);
        assertTrue(scheduled.containsKey(TpDailyTaskEnum.INTEL));
        assertTrue(!scheduled.containsKey(TpDailyTaskEnum.GATHER_RESOURCES));
    }

    @Test
    void theSameTaskIsNotPulledForwardAgainWithinThreeHours() {
        EventMissionTrigger.run("p1|921", doable(TpDailyTaskEnum.INTEL), Set.of(), env, log::add);
        scheduled.clear();
        now = NOW.plusHours(2);
        assertEquals(0, EventMissionTrigger.run("p1|921", doable(TpDailyTaskEnum.INTEL), Set.of(), env, log::add));
        assertTrue(scheduled.isEmpty());
        now = NOW.plusHours(3).plusMinutes(1);
        assertEquals(1, EventMissionTrigger.run("p1|921", doable(TpDailyTaskEnum.INTEL), Set.of(), env, log::add));
    }

    @Test
    void oneProfilesThrottleDoesNotHoldAnother() {
        EventMissionTrigger.run("p1|921", doable(TpDailyTaskEnum.INTEL), Set.of(), env, log::add);
        assertEquals(1, EventMissionTrigger.run("p2|921", doable(TpDailyTaskEnum.INTEL), Set.of(), env, log::add));
    }
}
