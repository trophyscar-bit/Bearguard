package dev.frostguard.api.configs;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Every task id must be unique.
 *
 * <p>The id is what persists: daily_task rows, task state and the seeded template are all keyed by
 * it, and DataSeeder only inserts a template whose id is not already present. So a duplicate does
 * not fail loudly -- the second task silently never gets a template and shares scheduling state
 * with the first. That happened when Pet Skill: Mystical Finding was given 110, which Create
 * Character already held; the build compiled, every test passed, and only the seeded template
 * table showed the new task had been swallowed.
 */
class TpDailyTaskEnumIdUniquenessTest {

    @Test
    void noTwoTasksShareAnId() {
        Map<Integer, TpDailyTaskEnum> seen = new HashMap<>();
        for (TpDailyTaskEnum task : TpDailyTaskEnum.values()) {
            TpDailyTaskEnum clash = seen.put(task.getId(), task);
            assertTrue(clash == null,
                    () -> "task id " + task.getId() + " is used by both " + clash + " and " + task);
        }
    }
}
