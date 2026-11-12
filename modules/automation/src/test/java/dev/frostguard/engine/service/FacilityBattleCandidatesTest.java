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
}
