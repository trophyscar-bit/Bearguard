package dev.frostguard.tasks.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class FacilityJoinWindowTest {

    private static final LocalDateTime START = LocalDateTime.of(2026, 11, 13, 19, 0);
    private static final Duration LENGTH = Duration.ofMinutes(60);

    @Test
    void anUnknownStartMeansAskLaterNeverNow() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 13, 10, 0);
        FacilityJoinWindow.Decision d = FacilityJoinWindow.decide(now, Optional.empty(), LENGTH);
        assertEquals(FacilityJoinWindow.Kind.UNKNOWN, d.kind());
        assertEquals(now.plus(FacilityJoinWindow.ASK_AGAIN), d.at());
    }

    @Test
    void beforeTheStartItWaitsUntilTwoMinutesBefore() {
        FacilityJoinWindow.Decision d = FacilityJoinWindow.decide(START.minusHours(5), Optional.of(START), LENGTH);
        assertEquals(FacilityJoinWindow.Kind.WAIT, d.kind());
        assertEquals(START.minus(FacilityJoinWindow.LEAD), d.at());
    }

    @Test
    void fromTheLeadUntilTheEndItRunsAndStopsAtTheEnd() {
        for (LocalDateTime now : new LocalDateTime[] {START.minus(FacilityJoinWindow.LEAD), START, START.plusMinutes(59)}) {
            FacilityJoinWindow.Decision d = FacilityJoinWindow.decide(now, Optional.of(START), LENGTH);
            assertEquals(FacilityJoinWindow.Kind.RUN, d.kind(), now.toString());
            assertEquals(START.plus(LENGTH), d.at());
        }
    }

    @Test
    void aCandidateIsWatchedBrieflyAndNeverPastTheBattlesEnd() {
        // On time: 15 minutes from the start.
        assertEquals(START.plus(FacilityJoinWindow.PROBE), FacilityJoinWindow.probeEnd(START.minus(FacilityJoinWindow.LEAD), START, LENGTH, FacilityJoinWindow.PROBE));
        // Late (the bot woke 40 minutes in): 15 minutes from now.
        LocalDateTime late = START.plusMinutes(40);
        assertEquals(late.plus(FacilityJoinWindow.PROBE), FacilityJoinWindow.probeEnd(late, START, LENGTH, FacilityJoinWindow.PROBE));
        // Very late: capped by the end.
        LocalDateTime nearEnd = START.plus(LENGTH).minusMinutes(5);
        assertEquals(START.plus(LENGTH), FacilityJoinWindow.probeEnd(nearEnd, START, LENGTH, FacilityJoinWindow.PROBE));
        // A configured watch time replaces the default.
        assertEquals(START.plusMinutes(25), FacilityJoinWindow.probeEnd(START.minus(FacilityJoinWindow.LEAD), START, LENGTH, java.time.Duration.ofMinutes(25)));
    }

    @Test
    void afterTheEndItDoesNotRunAndAsksAgainLater() {
        LocalDateTime now = START.plus(LENGTH);
        FacilityJoinWindow.Decision d = FacilityJoinWindow.decide(now, Optional.of(START), LENGTH);
        assertEquals(FacilityJoinWindow.Kind.UNKNOWN, d.kind());
        assertEquals(now.plus(FacilityJoinWindow.ASK_AGAIN), d.at());
    }

    @Test
    void theSessionEndsAfterTheHoldOnceARallyIsLiveAndNeverPastTheBattle() {
        LocalDateTime battleEnd = START.plus(LENGTH);
        LocalDateTime probeEnd = START.plus(FacilityJoinWindow.PROBE);
        LocalDateTime liveAt = START.plusMinutes(5);
        assertEquals(liveAt.plus(FacilityRallyJoinRoutine.HOLD_AFTER_LIVE),
                FacilityRallyJoinRoutine.sessionEnd(true, liveAt, true, START, battleEnd, probeEnd));
        // A rally first seen near the end is capped by the battle's end.
        LocalDateTime late = battleEnd.minusMinutes(10);
        assertEquals(battleEnd, FacilityRallyJoinRoutine.sessionEnd(true, late, true, START, battleEnd, probeEnd));
        // Confirmed and no rally yet: wait a bounded time, so gathering resumes.
        assertEquals(START.plus(FacilityRallyJoinRoutine.WAIT_FOR_FIRST_RALLY),
                FacilityRallyJoinRoutine.sessionEnd(false, null, true, START, battleEnd, probeEnd));
        // A candidate-only time keeps the watch probe.
        assertEquals(probeEnd, FacilityRallyJoinRoutine.sessionEnd(false, null, false, START, battleEnd, probeEnd));
    }
}
