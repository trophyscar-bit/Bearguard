package dev.frostguard.tasks.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.nio.file.Files;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.runtime.WorkspacePaths;

/**
 * The plausibility guard's streak has to outlive the task object that started it.
 *
 * <p>Stopping the queue clears its backlog, so every task that runs after the next start is a new
 * object. While the streak lived in instance fields, that erased it: on 9/10 the queue was
 * stopped at 22:22 and started at 23:11, and training read 1603 and then 2310 -- a consistent
 * pair, inside the band, exactly what the streak exists to accept -- but the second reading
 * landed on a fresh object, came back "streak 1/2" and was rejected again, leaving a cached 842
 * that the account had long since left behind.</p>
 *
 * <p>Each test uses its own profile id, because the streak is now process-wide by design.</p>
 */
class SpeedupStreakPersistenceTest {

    @BeforeAll
    static void createTestWorkspace() throws IOException {
        Files.createDirectories(WorkspacePaths.current().root());
    }

    private static AccountDescriptor profile(long id, long cachedTraining) {
        AccountDescriptor p = new AccountDescriptor(id, "Streak " + id, "1", true, 1L, 30L);
        p.setConfig(ConfigurationKeyEnum.SPEEDUP_TRAINING_MIN_LONG, cachedTraining);
        return p;
    }

    private static ResourceStockpileRoutine routineFor(AccountDescriptor p) {
        return new ResourceStockpileRoutine(p, TpDailyTaskEnum.RESOURCE_STOCKPILE_SCAN);
    }

    private static Long check(ResourceStockpileRoutine routine, long reading) {
        return routine.sanityCheckAgainstCached("sp_training", reading,
                ConfigurationKeyEnum.SPEEDUP_TRAINING_MIN_LONG, ResourceStockpileRoutine.SPEEDUP_GUARD);
    }

    /** Tonight's numbers, across a queue stop. */
    @Test
    void aStreakStartedByOneTaskObjectIsFinishedByTheNext() {
        AccountDescriptor p = profile(910_001L, 842L);

        assertNull(check(routineFor(p), 1603L),
                "1603 against a cached 842 is 1.9x -- held back, streak 1 of 2");

        // The queue stops (backlog cleared) and starts again: the next run is a new object.
        assertEquals(2310L, check(routineFor(p), 2310L),
                "2310 agrees with the held 1603 (1.44x, inside the band), so the move is real");
    }

    /** One profile's held reading is no evidence about another's. */
    @Test
    void theStreakIsKeptPerProfile() {
        AccountDescriptor first = profile(910_002L, 842L);
        AccountDescriptor second = profile(910_003L, 842L);

        assertNull(check(routineFor(first), 1603L));

        assertNull(check(routineFor(second), 2310L),
                "the second profile has no held reading, so 2.7x its cache is simply refused");
    }

    /** Keeping the streak longer has not made the guard any easier to fool once. */
    @Test
    void aLoneOutlierIsStillRefused() {
        AccountDescriptor p = profile(910_004L, 842L);

        assertNull(check(routineFor(p), 2310L));
        assertNull(check(routineFor(p), 30L),
                "30 does not agree with the held 2310, so it starts its own streak rather than finishing one");
    }
}
