package dev.frostguard.tasks.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

class BearMarchTallyTest {

    private static final List<Integer> SQUADS = List.of(1, 2, 3);
    private static final Instant T0 = Instant.parse("2026-11-10T19:00:00Z");

    private final BearMarchTally tally = new BearMarchTally();

    @Test
    void everySquadIsWorthTryingAtTheStart() {
        assertEquals(SQUADS, tally.likelyHome(SQUADS, T0));
    }

    @Test
    void sentSquadsAreSkippedSoLaterJoinsGoStraightToTheNextSquad() {
        tally.markSent(1, T0);
        assertEquals(List.of(2, 3), tally.likelyHome(SQUADS, T0.plusSeconds(1)));
        tally.markSent(2, T0.plusSeconds(1));
        assertEquals(List.of(3), tally.likelyHome(SQUADS, T0.plusSeconds(2)));
    }

    @Test
    void sentSquadIsTriedAgainAfterItCouldHaveReturned() {
        tally.markSent(1, T0);
        assertEquals(SQUADS, tally.likelyHome(SQUADS, T0.plus(BearMarchTally.SENT_AWAY)));
    }

    @Test
    void troopSquadsGoOutInOrder() {
        List<Integer> troops = List.of(4, 5, 6);
        tally.markSent(4, T0);
        assertEquals(List.of(5, 6), tally.troopSquadsToTry(troops, T0.plusSeconds(1)));
    }

    @Test
    void aFreeMarchStillGetsATroopSquadWhenTheTallyThinksAllAreAway() {
        List<Integer> troops = List.of(4, 5, 6);
        troops.forEach(squad -> tally.markSent(squad, T0));
        assertEquals(troops, tally.troopSquadsToTry(troops, T0.plusSeconds(1)));
    }

    @Test
    void squadSeenAwayIsCheckedAgainSooner() {
        tally.markSeenAway(2, T0);
        assertEquals(List.of(1, 3), tally.likelyHome(SQUADS, T0.plusSeconds(14)));
        assertEquals(SQUADS, tally.likelyHome(SQUADS, T0.plus(BearMarchTally.SEEN_AWAY)));
    }
}
