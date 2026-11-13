package dev.frostguard.engine.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.frostguard.data.entity.EventScheduleEntry;

/**
 * Candidate battle times for a facility rally joiner. The cases that matter are the ones with no
 * answer: a rally sent at a guessed hour is worse than one not sent, and only one of a facility's
 * three candidates becomes the real battle, which this class never claims to know.
 */
class FacilityBattleCandidatesTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 13, 12, 0);
    private static final LocalDateTime JUST_READ = NOW.minusMinutes(20);

    private static EventScheduleEntry row(String key, String label, LocalDateTime start,
                                          LocalDateTime read) {
        return new EventScheduleEntry(key, label, false, start, null, read);
    }

    // The stages the live panel listed on 12 Nov for 13 Nov, as read from the saved frames:
    // Stronghold No. 2 at 03/09/13, Fortress No. 2 at 09/13/14, Fortress No. 9 at 13/14/17 UTC.
    private static final EventScheduleEntry FORTRESS_9 = row("FACILITY_BATTLE_CANDIDATE_FORTRESS_NO_9_202611131700",
            "Fortress No. 9", LocalDateTime.of(2026, 11, 13, 17, 0), JUST_READ);
    private static final EventScheduleEntry STRONGHOLD_2 = row("FACILITY_BATTLE_CANDIDATE_STRONGHOLD_NO_2_202611131300",
            "Stronghold No. 2", LocalDateTime.of(2026, 11, 13, 13, 0), JUST_READ);
    private static final EventScheduleEntry BEAR_TRAP = row("TASKLIST_BEAR_HUNT_TRAP_1_2026-11-13",
            "Bear Hunt - Trap 1", LocalDateTime.of(2026, 11, 13, 14, 0), JUST_READ);

    @Test
    void theSoonestCandidateStillAheadWins() {
        FacilityBattleCandidates.Candidate battle =
                FacilityBattleCandidates.next(List.of(FORTRESS_9, STRONGHOLD_2, BEAR_TRAP), NOW).orElseThrow();

        assertEquals(LocalDateTime.of(2026, 11, 13, 13, 0), battle.startUtc());
        assertTrue(battle.facility().contains("Stronghold No. 2"), battle.facility());
    }

    /** Task List rows share the store and must not be mistaken for a stage of a facility battle. */
    @Test
    void aBearTrapIsNotACandidate() {
        assertTrue(FacilityBattleCandidates.next(List.of(BEAR_TRAP), NOW).isEmpty());
    }

    @Test
    void aCandidateAlreadyPastIsNotTheNextOne() {
        EventScheduleEntry past = row("FACILITY_BATTLE_CANDIDATE_FORTRESS_NO_2_202611130900",
                "Fortress No. 2", LocalDateTime.of(2026, 11, 13, 9, 0), JUST_READ);

        assertEquals(LocalDateTime.of(2026, 11, 13, 17, 0),
                FacilityBattleCandidates.next(List.of(past, FORTRESS_9), NOW).orElseThrow().startUtc());
    }

    /**
     * The Fortress panel is not visited on every pass, so yesterday's stages must not be handed
     * over as today's times.
     */
    @Test
    void aStaleReadIsNoAnswerAtAll() {
        EventScheduleEntry stale = row("FACILITY_BATTLE_CANDIDATE_FORTRESS_NO_9_202611131700",
                "Fortress No. 9", LocalDateTime.of(2026, 11, 13, 17, 0), NOW.minusHours(9));

        assertTrue(FacilityBattleCandidates.next(List.of(stale), NOW).isEmpty());
    }

    @Test
    void aRowWithNoReadTimeIsNotTrusted() {
        EventScheduleEntry unread = row("FACILITY_BATTLE_CANDIDATE_FORTRESS_NO_9_202611131700",
                "Fortress No. 9", LocalDateTime.of(2026, 11, 13, 17, 0), null);

        assertTrue(FacilityBattleCandidates.next(List.of(unread), NOW).isEmpty());
    }

    /**
     * A joiner that wakes late still wants the stage that just began: its start is the only instant
     * the panel gives, so without a grace window the stage is unreachable the moment it arrives.
     */
    @Test
    void aStageThatBeganInsideTheGraceIsStillOffered() {
        EventScheduleEntry justBegun = row("FACILITY_BATTLE_CANDIDATE_FORTRESS_NO_2_202611131140",
                "Fortress No. 2", NOW.minusMinutes(20), JUST_READ);

        FacilityBattleCandidates.Candidate battle = FacilityBattleCandidates
                .next(List.of(justBegun, STRONGHOLD_2), NOW, java.time.Duration.ofMinutes(60)).orElseThrow();

        assertEquals(NOW.minusMinutes(20), battle.startUtc());
        assertTrue(battle.facility().contains("Fortress No. 2"), battle.facility());
    }

    @Test
    void aStageOlderThanTheGraceIsGone() {
        EventScheduleEntry longGone = row("FACILITY_BATTLE_CANDIDATE_FORTRESS_NO_2_202611130900",
                "Fortress No. 2", NOW.minusMinutes(90), JUST_READ);

        assertEquals(LocalDateTime.of(2026, 11, 13, 13, 0), FacilityBattleCandidates
                .next(List.of(longGone, STRONGHOLD_2), NOW, java.time.Duration.ofMinutes(60))
                .orElseThrow().startUtc());
    }

    /** Without a grace the old behaviour stands: only a start still ahead counts. */
    @Test
    void noGraceMeansOnlyStagesStillAhead() {
        EventScheduleEntry justBegun = row("FACILITY_BATTLE_CANDIDATE_FORTRESS_NO_2_202611131140",
                "Fortress No. 2", NOW.minusMinutes(20), JUST_READ);

        assertEquals(LocalDateTime.of(2026, 11, 13, 13, 0),
                FacilityBattleCandidates.next(List.of(justBegun, STRONGHOLD_2), NOW).orElseThrow().startUtc());
    }

    /** All three of a facility's candidates are offered, soonest first: the caller picks. */
    @Test
    void everyCandidateStillAheadIsListedInOrder() {
        EventScheduleEntry later = row("FACILITY_BATTLE_CANDIDATE_STRONGHOLD_NO_2_202611131700",
                "Stronghold No. 2", LocalDateTime.of(2026, 11, 13, 17, 0), JUST_READ);

        java.util.List<FacilityBattleCandidates.Candidate> all = FacilityBattleCandidates
                .upcoming(List.of(later, FORTRESS_9, STRONGHOLD_2), NOW, java.time.Duration.ZERO);

        assertEquals(3, all.size());
        assertEquals(LocalDateTime.of(2026, 11, 13, 13, 0), all.get(0).startUtc());
        assertEquals(LocalDateTime.of(2026, 11, 13, 17, 0), all.get(1).startUtc());
    }

    /** The default whitelist for a joiner with no typed setting: who is actually signed up. */
    @Test
    void theFacilitiesWithCandidatesAheadAreListedOnce() {
        EventScheduleEntry second = row("FACILITY_BATTLE_CANDIDATE_STRONGHOLD_NO_2_202611131700",
                "Stronghold No. 2", LocalDateTime.of(2026, 11, 13, 17, 0), JUST_READ);

        java.util.Set<String> facilities = FacilityBattleCandidates.facilities(
                List.of(second, FORTRESS_9, STRONGHOLD_2, BEAR_TRAP), NOW, java.time.Duration.ofMinutes(120));

        assertEquals(java.util.Set.of("Stronghold No. 2", "Fortress No. 9"), facilities);
    }

    /** A stale read gives no whitelist at all, so a joiner defaulting to it joins nothing. */
    @Test
    void aStaleReadGivesNoFacilities() {
        EventScheduleEntry stale = row("FACILITY_BATTLE_CANDIDATE_FORTRESS_NO_9_202611131700",
                "Fortress No. 9", LocalDateTime.of(2026, 11, 13, 17, 0), NOW.minusHours(9));

        assertTrue(FacilityBattleCandidates.facilities(List.of(stale), NOW,
                java.time.Duration.ofMinutes(120)).isEmpty());
    }

    @Test
    void nothingScheduledMeansNoAnswer() {
        assertTrue(FacilityBattleCandidates.next(List.of(), NOW).isEmpty());
    }

    @Test
    void theStartIsOfferedInTheSchedulersOwnZone() {
        FacilityBattleCandidates.Candidate battle =
                FacilityBattleCandidates.next(List.of(STRONGHOLD_2), NOW).orElseThrow();

        assertEquals(battle.startUtc().plusHours(2), battle.endsByUtc());
        assertEquals(battle.startUtc().atOffset(java.time.ZoneOffset.UTC)
                        .atZoneSameInstant(java.time.ZoneId.systemDefault())
                        .toLocalDateTime(),
                battle.startLocal());
    }

    // ---- confirmed times, from the Task List once the vote has resolved ------------------------

    private static EventScheduleEntry taskListRow(String facility, boolean active, LocalDateTime start,
                                                  LocalDateTime end, LocalDateTime read) {
        return new EventScheduleEntry("TASKLIST_" + facility.toUpperCase().replace(' ', '_') + "_2026-11-13",
                "Fortress Battles (" + facility + ")", active, start, end, read);
    }

    /** The form the 19:07 read on 12 Nov stored: 08:00 local, active false, no end. */
    private static final EventScheduleEntry CONFIRMED_9 = taskListRow("Fortress No. 9", false,
            LocalDateTime.of(2026, 11, 13, 13, 0), null, JUST_READ);

    @Test
    void aTaskListRowWithATimeIsConfirmedAndKeepsTheFacilityLabel() {
        FacilityBattleCandidates.Candidate battle = FacilityBattleCandidates
                .confirmed(List.of(CONFIRMED_9), NOW, java.time.Duration.ZERO).get(0);

        assertEquals("Fortress No. 9", battle.facility());
        assertEquals(LocalDateTime.of(2026, 11, 13, 13, 0), battle.startUtc());
        assertTrue(battle.confirmed());
    }

    /**
     * The scanner stores Ended as a closed window at midnight of its heading, the in-progress row as
     * active with no end, and a row whose clock mode did not read as a whole day. None is a start.
     */
    @Test
    void endedInProgressAndDateOnlyRowsAreNotStarts() {
        LocalDateTime midnight = LocalDateTime.of(2026, 11, 13, 0, 0);
        EventScheduleEntry ended = taskListRow("Fortress No. 2", false, midnight, midnight, JUST_READ);
        EventScheduleEntry running = taskListRow("Fortress No. 12", true, midnight, null, JUST_READ);
        EventScheduleEntry dateOnly = taskListRow("Stronghold No. 2", false, midnight,
                LocalDateTime.of(2026, 11, 13, 23, 59), JUST_READ);

        assertTrue(FacilityBattleCandidates.confirmed(List.of(ended, running, dateOnly), NOW,
                java.time.Duration.ofHours(24)).isEmpty());
    }

    @Test
    void otherTaskListRowsAreNotBattles() {
        assertTrue(FacilityBattleCandidates.confirmed(List.of(BEAR_TRAP), NOW,
                java.time.Duration.ZERO).isEmpty());
    }

    /**
     * The Task List panel opens only when an alliance event is close, so a settled time read at 19:07
     * must still be usable at 05:00 the next morning, nine hours on, with the battle at 08:00.
     */
    @Test
    void aSettledTimeStillStandsNineHoursAfterItWasRead() {
        EventScheduleEntry overnight = taskListRow("Fortress No. 9", false, NOW.plusHours(3), null,
                NOW.minusHours(9));

        assertEquals(1, FacilityBattleCandidates.confirmed(List.of(overnight), NOW,
                java.time.Duration.ZERO).size());
    }

    @Test
    void aTaskListReadOlderThanADayIsNotConfirmed() {
        EventScheduleEntry stale = taskListRow("Fortress No. 9", false, NOW.plusHours(3), null,
                NOW.minusHours(30));

        assertTrue(FacilityBattleCandidates.confirmed(List.of(stale), NOW, java.time.Duration.ZERO).isEmpty());
    }

    /** Candidates keep the short limit: they change as alliances vote. */
    @Test
    void aCandidateNineHoursOldIsStillRefused() {
        EventScheduleEntry old = row("FACILITY_BATTLE_CANDIDATE_FORTRESS_NO_9_202611131700",
                "Fortress No. 9", LocalDateTime.of(2026, 11, 13, 17, 0), NOW.minusHours(9));

        assertTrue(FacilityBattleCandidates.upcoming(List.of(old), NOW, java.time.Duration.ZERO).isEmpty());
    }

    /** Past its two hours there is nothing left to join, however generous the caller's grace. */
    @Test
    void aConfirmedBattleIsDroppedOnceItsWindowHasEnded() {
        EventScheduleEntry running = taskListRow("Fortress No. 2", false, NOW.minusMinutes(90), null, JUST_READ);
        EventScheduleEntry over = taskListRow("Stronghold No. 2", false, NOW.minusMinutes(150), null, JUST_READ);

        List<FacilityBattleCandidates.Candidate> got = FacilityBattleCandidates
                .confirmed(List.of(running, over), NOW, java.time.Duration.ofHours(24));

        assertEquals(1, got.size());
        assertEquals("Fortress No. 2", got.get(0).facility());
    }

    /** The facility stays settled after its battle ends, so its losing candidates do not return. */
    @Test
    void aFacilityWhoseConfirmedBattleEndedStaysSettled() {
        EventScheduleEntry over = taskListRow("Fortress No. 9", false, NOW.minusMinutes(150), null, JUST_READ);
        EventScheduleEntry laterCandidate = row("FACILITY_BATTLE_CANDIDATE_FORTRESS_NO_9_202611131700",
                "Fortress No. 9", NOW.plusHours(2), JUST_READ);

        assertTrue(FacilityBattleCandidates.schedule(List.of(over, laterCandidate), NOW,
                java.time.Duration.ofHours(24)).isEmpty());
    }

    @Test
    void aConfirmedBattleStillInsideTheGraceIsOfferedAndOlderOnesAreNot() {
        EventScheduleEntry begun = taskListRow("Fortress No. 2", false, NOW.minusMinutes(20), null, JUST_READ);
        EventScheduleEntry over = taskListRow("Stronghold No. 2", false, NOW.minusMinutes(90), null, JUST_READ);

        List<FacilityBattleCandidates.Candidate> got = FacilityBattleCandidates
                .confirmed(List.of(begun, over), NOW, java.time.Duration.ofMinutes(60));

        assertEquals(1, got.size());
        assertEquals("Fortress No. 2", got.get(0).facility());
    }

    /** Plain candidates are never confirmed, whatever else is in the store. */
    @Test
    void aCandidateIsNotConfirmed() {
        assertTrue(!FacilityBattleCandidates.upcoming(List.of(FORTRESS_9), NOW, java.time.Duration.ZERO)
                .get(0).confirmed());
    }

    // ---- the merged plan: confirmed wins ------------------------------------------------------

    @Test
    void aConfirmedTimeReplacesThatFacilitysCandidatesButNotAnothersFacilitys() {
        EventScheduleEntry strongholdCandidate = STRONGHOLD_2;

        List<FacilityBattleCandidates.Candidate> plan = FacilityBattleCandidates.schedule(
                List.of(CONFIRMED_9, FORTRESS_9, strongholdCandidate), NOW, java.time.Duration.ZERO);

        assertEquals(2, plan.size());
        // Both start at 13:00 UTC, so the tie breaks on the facility name.
        assertEquals("Fortress No. 9", plan.get(0).facility());
        assertTrue(plan.get(0).confirmed());
        assertEquals(LocalDateTime.of(2026, 11, 13, 13, 0), plan.get(0).startUtc());
        // Stronghold No. 2 has no Task List row, so it keeps its candidate; Fortress No. 9's own
        // 17:00 candidate is gone because the Task List settled it.
        assertEquals("Stronghold No. 2", plan.get(1).facility());
        assertTrue(!plan.get(1).confirmed());
    }

    /**
     * Once a battle has ended, its losing candidates must not come back: a joiner would watch the 14:00
     * and 17:00 of a battle that already happened at 13:00.
     */
    @Test
    void aFacilityTheTaskListNamesStaysSettledEvenWhenItsBattleIsOver() {
        LocalDateTime midnight = LocalDateTime.of(2026, 11, 13, 0, 0);
        EventScheduleEntry ended = taskListRow("Fortress No. 9", false, midnight, midnight, JUST_READ);

        assertTrue(FacilityBattleCandidates.schedule(List.of(ended, FORTRESS_9), NOW,
                java.time.Duration.ZERO).isEmpty());
    }

    @Test
    void withNoTaskListRowTheCandidatesAreThePlan() {
        List<FacilityBattleCandidates.Candidate> plan = FacilityBattleCandidates.schedule(
                List.of(FORTRESS_9, STRONGHOLD_2), NOW, java.time.Duration.ZERO);

        assertEquals(2, plan.size());
        assertTrue(plan.stream().noneMatch(FacilityBattleCandidates.Candidate::confirmed));
    }
}
