package dev.frostguard.app.panel.heroes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.frostguard.api.squads.SavedSquad;
import dev.frostguard.api.squads.SquadSnapshot;
import dev.frostguard.api.squads.TroopOverview;
import dev.frostguard.api.squads.TroopStock;
import dev.frostguard.engine.squads.SquadPlanner;

/**
 * The Bear Trap strip's logic. The 11 Nov question: the account leads the bear with squad 8
 * (Mia, Flint, Bahiti) while the scan suggests Flint to lead, and 226,956 marksmen are needed
 * against what is home.
 */
class BearTrapCheckTest {

    private static final SquadPlanner.Plan PLAN = new SquadPlanner.Plan(List.of(), List.of(), List.of(), List.of(
            new SquadPlanner.Suggestion(1, "Bear Trap - lead", List.of("Flint", "Mia", "Bahiti"),
                    "Best expedition Attack% + Lethality% for each troop type."),
            new SquadPlanner.Suggestion(2, "Bear Trap - join 1", List.of("Jessie"), "x"),
            new SquadPlanner.Suggestion(3, "Bear Trap - join 2", List.of("Jasser"), "x"),
            new SquadPlanner.Suggestion(4, "Bear Trap - join 3", List.of("Seo-yoon"), "x")));

    private static SavedSquad squad(int slot, List<String> heroes, long marksmen) {
        return new SavedSquad(slot, SavedSquad.State.SAVED, heroes, null, List.of(2000L, 2000L, marksmen), null, 1L);
    }

    private static SquadSnapshot snapshot(long marksmenHome, SavedSquad... squads) {
        TroopOverview troops = new TroopOverview("1/1", "0/1", List.of(new TroopStock("Apex Marksman", "Marksman", marksmenHome)));
        return new SquadSnapshot("2026-11-11T00:00:00Z", List.of(), troops, List.of(squads), List.of());
    }

    private static SquadSnapshot plan8() {
        return snapshot(250_000,
                squad(8, List.of("Mia", "Flint", "Bahiti"), 108_456),
                squad(1, List.of("Jessie"), 39_500), squad(2, List.of("Jasser"), 39_500),
                squad(3, List.of("Seo-yoon"), 39_500), squad(4, List.of(), 0), squad(5, List.of(), 0));
    }

    @Test
    void aLeadThatDiffersSaysWhyAndNamesBothLeaders() {
        BearTrapCheck.Report report = BearTrapCheck.check(plan8(), PLAN, 8, List.of(1, 2, 3), List.of(4, 5));

        BearTrapCheck.Line lead = report.lines().get(0);
        // A deliberate choice is not a warning: it says whose pick it is, and why the scan differs on hover.
        assertEquals(BearTrapCheck.Result.CHOICE, lead.result());
        assertEquals("Differs", lead.pill());
        // One value per cell: only the leader is compared and shown.
        assertEquals("Mia", lead.saved());
        assertEquals("Flint", lead.suggested());
        assertTrue(lead.tip().contains("Flint") && lead.tip().contains("Attack%"), lead.tip());
        assertEquals(BearTrapCheck.Result.MATCH, report.lines().get(1).result());
        assertEquals(List.of(), report.issues());
    }

    @Test
    void marksmenNeededAreTheSumOverEveryBearSquad() {
        BearTrapCheck.Report report = BearTrapCheck.check(plan8(), PLAN, 8, List.of(1, 2, 3), List.of(4, 5));

        assertEquals(226_956, report.marksmen().needed());
        assertTrue(report.marksmen().ok());
        assertTrue(report.marksmen().breakdown().contains("squad 8: 108,456"), report.marksmen().breakdown());
    }

    @Test
    void shortByTheMissingMarksmen() {
        SquadSnapshot tight = snapshot(226_874,
                squad(8, List.of("Mia", "Flint", "Bahiti"), 108_456),
                squad(1, List.of("Jessie"), 39_500), squad(2, List.of("Jasser"), 39_500), squad(3, List.of("Seo-yoon"), 39_500),
                squad(4, List.of(), 0), squad(5, List.of(), 0));
        BearTrapCheck.Report report = BearTrapCheck.check(tight, PLAN, 8, List.of(1, 2, 3), List.of(4, 5));

        assertFalse(report.marksmen().ok());
        assertEquals(82, report.marksmen().shortBy());
        assertEquals("Short of marksmen by 82", report.issues().get(0).title());
    }

    @Test
    void aSquadThatWasNotReadIsNotGuessedAt() {
        SquadSnapshot missing = snapshot(250_000, squad(8, List.of("Mia", "Flint", "Bahiti"), 108_456));
        BearTrapCheck.Report report = BearTrapCheck.check(missing, PLAN, 8, List.of(1, 2, 3), List.of(4, 5));

        BearTrapCheck.Line join = report.lines().get(1);
        assertEquals(BearTrapCheck.Result.NOT_READ, join.result());
        assertEquals("Not read", join.pill());
        // Yours is a dash; the suggestion does not depend on reading your squad, so it stays.
        assertEquals(BearTrapCheck.NONE, join.saved());
        assertEquals("Jessie", join.suggested());
        assertFalse(report.squadsRead());
        // The banner and the pill name the same squads, from the same list.
        assertEquals("Couldn't read squads 1, 2, 3, 4 and 5", report.issues().get(0).title());
    }

    @Test
    void oneHeroInTwoBearSquadsIsFlagged() {
        SquadSnapshot twice = snapshot(250_000,
                squad(8, List.of("Mia", "Flint", "Bahiti"), 108_456),
                squad(1, List.of("Jessie"), 39_500), squad(2, List.of("Mia"), 39_500),
                squad(3, List.of("Seo-yoon"), 39_500), squad(4, List.of(), 0), squad(5, List.of(), 0));
        BearTrapCheck.Report report = BearTrapCheck.check(twice, PLAN, 8, List.of(1, 2, 3), List.of(4, 5));

        // One line for every hero in two squads, and the first is the one to fix first.
        assertEquals(1, report.issues().size());
        assertEquals("1 hero is in two Bear Trap squads: Mia (2, 8).", report.issues().get(0).title());
        assertEquals("Fix first: 1 hero is in two Bear Trap squads: Mia (2, 8).", report.fixFirst());

        // Result is the comparison only; the shared hero is its own value in its own column.
        assertEquals(BearTrapCheck.Result.CHOICE, report.lines().get(0).result());
        assertEquals("Differs", report.lines().get(0).pill());
        assertEquals("1 shared", report.lines().get(0).badge());
        assertEquals("1 shared", report.lines().get(2).badge());
        assertEquals("Mia", report.lines().get(2).saved());
    }

    /**
     * The fixture the owner pointed at: Jasser, Molly and Sergey each sit in two squads that march together;
     * Flint and Bahiti also sit in the troops-only squads, which do not march with them and are not a clash.
     */
    @Test
    void everyClashBetweenTheMarchingSquadsIsOneLineAndTroopOnlySquadsAreLeftOut() {
        SquadSnapshot fixture = snapshot(250_000,
                squad(8, List.of("Jasser", "Molly", "Flint"), 124_470),
                squad(1, List.of("Jessie", "Bahiti", "Sergey"), 69_696),
                squad(2, List.of("Jasser", "Molly", "Sergey"), 25_394),
                new SavedSquad(3, SavedSquad.State.EMPTY_OR_LOCKED, List.of(), null, List.of(), null, null),
                squad(4, List.of("Patrick", "Flint", "Bahiti"), 25_378),
                squad(5, List.of("Sergey", "Molly", "Bahiti"), 0));
        BearTrapCheck.Report report = BearTrapCheck.check(fixture, PLAN, 8, List.of(1, 2, 3), List.of(4, 5));

        assertEquals("Fix first: 3 heroes are in two Bear Trap squads: Jasser (2, 8), Molly (2, 8), Sergey (1, 2).",
                report.fixFirst());
        // Per row: how many of this squad's heroes are also in another marching squad.
        assertEquals("2 shared", report.lines().get(0).badge());  // lead 8: Jasser, Molly
        assertEquals("1 shared", report.lines().get(1).badge());  // join 1: Sergey
        assertEquals("3 shared", report.lines().get(2).badge());  // join 2: Jasser, Molly, Sergey
        assertNull(report.lines().get(3).badge());                // join 3: empty
        // The troops-only squads share heroes with the others but do not march with them: no clash, no cell.
        assertNull(report.lines().get(4).badge());
        assertNull(report.lines().get(5).badge());
    }

    @Test
    void theCardTheBannerAndThePillsCountTheSameList() {
        SquadSnapshot scan = new SquadSnapshot("2026-11-11T00:00:00Z", List.of(),
                new TroopOverview("1/1", "0/1", List.of(new TroopStock("Apex Marksman", "Marksman", 100_000))),
                List.of(squad(8, List.of("Mia", "Flint", "Bahiti"), 108_456), squad(1, List.of("Jessie"), 39_500),
                        squad(2, List.of("Jasser"), 39_500), squad(3, List.of("Seo-yoon"), 39_500),
                        squad(4, List.of(), 0), squad(5, List.of(), 0)),
                List.of("Chief Profile did not open, so troops and saved squads were not read"));
        BearTrapCheck.Report report = BearTrapCheck.check(scan, PLAN, 8, List.of(1, 2, 3), List.of(4, 5));

        List<BearTrapCheck.Issue> issues = SquadsLayoutController.issues(scan, report);
        // Short of marksmen, plus the Chief Profile miss: two things.
        assertEquals(1, issues.size());
        assertEquals("Not ready: short 126,956 marksmen", SquadsLayoutController.headline(issues));
        // Chief Profile is the cause the banner gives, not an item of its own.
        assertEquals("Chief Profile didn't open during the scan.", BearTrapCheck.scanWhy(scan));
    }

    @Test
    void anEmptySquadSaysEmptyEverywhereAndNeedsNoMarksmen() {
        SavedSquad empty = new SavedSquad(3, SavedSquad.State.EMPTY_OR_LOCKED, List.of(), null, List.of(), null, null);
        SquadSnapshot snap = snapshot(250_000,
                squad(8, List.of("Mia", "Flint", "Bahiti"), 108_456),
                squad(1, List.of("Jessie"), 39_500), squad(2, List.of("Jasser"), 39_500),
                empty, squad(4, List.of(), 0), squad(5, List.of(), 0));
        BearTrapCheck.Report report = BearTrapCheck.check(snap, PLAN, 8, List.of(1, 2, 3), List.of(4, 5));

        // The scanner's own word, and the suggestion beside it.
        assertEquals("Empty or locked", report.lines().get(3).pill());
        assertEquals("Seo-yoon", report.lines().get(3).suggested());
        // It needs no marksmen, so the figure is complete.
        assertTrue(report.marksmen().complete());
        assertEquals(187_456, report.marksmen().needed());
        assertEquals("squad 3 empty or locked", report.issues().get(0).headline());
    }

    @Test
    void noTroopScanMeansHomeIsUnknownNotZero() {
        SquadSnapshot noTroops = new SquadSnapshot("2026-11-11T00:00:00Z", List.of(), null, List.of(), List.of());
        BearTrapCheck.Report report = BearTrapCheck.check(noTroops, PLAN, 8, List.of(1, 2, 3), List.of(4, 5));

        assertFalse(report.marksmen().read());
        assertNull(report.marksmen().home());
        assertEquals("Not ready",
                SquadsLayoutController.headline(SquadsLayoutController.issues(noTroops, report)));
    }

    @Test
    void slotListsParseWhateverTheSettingHolds() {
        assertEquals(List.of(1, 2, 3), SquadsLayoutController.parseSlots("1,2,3"));
        assertEquals(List.of(4, 5), SquadsLayoutController.parseSlots("4, 5"));
        assertEquals(List.of(8), SquadsLayoutController.parseSlots("8"));
        assertEquals(List.of(), SquadsLayoutController.parseSlots(""));
    }
}
