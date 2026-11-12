package dev.frostguard.tasks.pets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.frostguard.api.domain.MarchSlotState;
import dev.frostguard.api.domain.MarchSlotStatus;
import dev.frostguard.tasks.pets.PetSkillsRoutine.GatheringDeployResult;

/**
 * After the Gathering skill fires, its bonus march needs a free slot. On 11 November at 13:33 every
 * slot was out gathering ("#1=GATHERING/GATHER/WORKING/COAL(PT59M41S)"), and each failed attempt
 * rescheduled the task one minute out, so it re-opened the Pets menu every 90 seconds while the
 * skill stayed Active. A slot cannot free before the soonest march countdown ends.
 */
class PetSkillsGatheringRetryTest {

    private static final Duration ONE_MINUTE = Duration.ofMinutes(1);
    private static final Duration ONE_HOUR = Duration.ofMinutes(60);

    @Test
    void theLiveQueueFromThe13_33RunNoLongerRetriesEveryMinute() {
        List<MarchSlotState> queue = List.of(
                new MarchSlotState(1, MarchSlotStatus.GATHERING, Duration.parse("PT59M41S")));

        Duration soonest = PetSkillsRoutine.soonestMarchRelease(queue);
        Duration delay = PetSkillsRoutine.gatheringRecheckDelay(GatheringDeployResult.NO_IDLE_MARCH, soonest);

        assertEquals(Duration.parse("PT59M41S"), soonest);
        assertEquals(ONE_HOUR, delay, "a 59m41s march caps at the hour, not the old 1-minute recheck");
    }

    @Test
    void waitsForTheSoonestBusySlotPlusAMinute() {
        List<MarchSlotState> queue = List.of(
                new MarchSlotState(1, MarchSlotStatus.GATHERING, Duration.ofMinutes(42)),
                new MarchSlotState(2, MarchSlotStatus.RETURNING, Duration.ofMinutes(12)),
                MarchSlotState.of(3, MarchSlotStatus.LOCKED),
                new MarchSlotState(4, MarchSlotStatus.BUSY_UNKNOWN, null));

        Duration soonest = PetSkillsRoutine.soonestMarchRelease(queue);

        assertEquals(Duration.ofMinutes(12), soonest);
        assertEquals(Duration.ofMinutes(13),
                PetSkillsRoutine.gatheringRecheckDelay(GatheringDeployResult.NO_IDLE_MARCH, soonest));
    }

    @Test
    void neverWaitsLessThanTheOldRecheck() {
        assertEquals(ONE_MINUTE, PetSkillsRoutine.gatheringRecheckDelay(
                GatheringDeployResult.NO_IDLE_MARCH, Duration.ofSeconds(-30)));
        assertEquals(Duration.ofSeconds(80), PetSkillsRoutine.gatheringRecheckDelay(
                GatheringDeployResult.NO_IDLE_MARCH, Duration.ofSeconds(20)));
    }

    @Test
    void waitsTheFullHourWhenNoCountdownRead() {
        List<MarchSlotState> queue = List.of(
                MarchSlotState.of(1, MarchSlotStatus.BUSY_UNKNOWN),
                MarchSlotState.of(2, MarchSlotStatus.STATIONED));

        assertNull(PetSkillsRoutine.soonestMarchRelease(queue));
        assertEquals(ONE_HOUR, PetSkillsRoutine.gatheringRecheckDelay(GatheringDeployResult.NO_IDLE_MARCH, null));
    }

    @Test
    void otherOutcomesKeepTheOneMinuteRecheck() {
        for (GatheringDeployResult result : GatheringDeployResult.values()) {
            if (result == GatheringDeployResult.NO_IDLE_MARCH) continue;
            assertEquals(ONE_MINUTE, PetSkillsRoutine.gatheringRecheckDelay(result, Duration.ofMinutes(30)), result.name());
        }
    }
}
