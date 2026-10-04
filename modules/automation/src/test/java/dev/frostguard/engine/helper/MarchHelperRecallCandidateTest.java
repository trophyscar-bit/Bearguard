package dev.frostguard.engine.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import dev.frostguard.api.domain.MarchSlotState;
import dev.frostguard.api.domain.MarchSlotStatus;

class MarchHelperRecallCandidateTest {

    private static final Set<Integer> ALL_RECALLABLE = Set.of(1, 2, 3, 4, 5, 6);

    @Test
    void recallsTheGatheringMarchClosestToFinishing() {
        List<MarchSlotState> slots = List.of(
                gathering(1, Duration.ofHours(7)),
                gathering(2, Duration.ofMinutes(12)),
                gathering(3, Duration.ofHours(2)));

        Optional<MarchSlotState> chosen = MarchHelper.pickRecallCandidate(slots, ALL_RECALLABLE);

        assertEquals(2, chosen.orElseThrow().slot());
    }

    @Test
    void ignoresRowsThatDoNotOfferARecallButton() {
        List<MarchSlotState> slots = List.of(
                gathering(1, Duration.ofHours(7)),
                gathering(2, Duration.ofMinutes(12)));

        Optional<MarchSlotState> chosen = MarchHelper.pickRecallCandidate(slots, Set.of(1));

        assertEquals(1, chosen.orElseThrow().slot());
    }

    @Test
    void leavesReturningStationedAndIdleSlotsAlone() {
        List<MarchSlotState> slots = List.of(
                MarchSlotState.of(1, MarchSlotStatus.IDLE),
                new MarchSlotState(2, MarchSlotStatus.RETURNING, Duration.ofMinutes(3)),
                MarchSlotState.of(3, MarchSlotStatus.STATIONED),
                MarchSlotState.of(4, MarchSlotStatus.LOCKED));

        assertTrue(MarchHelper.pickRecallCandidate(slots, ALL_RECALLABLE).isEmpty());
    }

    @Test
    void prefersAnyReadableCountdownOverAnUnreadableOne() {
        List<MarchSlotState> slots = List.of(
                gathering(1, null),
                gathering(2, Duration.ofHours(6)));

        Optional<MarchSlotState> chosen = MarchHelper.pickRecallCandidate(slots, ALL_RECALLABLE);

        assertEquals(2, chosen.orElseThrow().slot());
    }

    @Test
    void fallsBackToAnUnreadableCountdownWhenItIsTheOnlyGatheringMarch() {
        List<MarchSlotState> slots = List.of(
                MarchSlotState.of(1, MarchSlotStatus.STATIONED),
                gathering(2, null));

        Optional<MarchSlotState> chosen = MarchHelper.pickRecallCandidate(slots, ALL_RECALLABLE);

        assertEquals(2, chosen.orElseThrow().slot());
    }

    @Test
    void reportsNothingToRecallWhenEverySlotIsBusyWithSomethingElse() {
        List<MarchSlotState> slots = List.of(
                MarchSlotState.of(1, MarchSlotStatus.STATIONED),
                MarchSlotState.of(2, MarchSlotStatus.BUSY_UNKNOWN));

        assertTrue(MarchHelper.pickRecallCandidate(slots, ALL_RECALLABLE).isEmpty());
    }

    private static MarchSlotState gathering(int slot, Duration remaining) {
        return new MarchSlotState(slot, MarchSlotStatus.GATHERING, remaining);
    }
}
