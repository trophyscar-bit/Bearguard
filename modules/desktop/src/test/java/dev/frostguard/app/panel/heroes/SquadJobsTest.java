package dev.frostguard.app.panel.heroes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.frostguard.api.squads.SavedSquad;
import dev.frostguard.api.squads.SquadSnapshot;
import java.util.List;
import org.junit.jupiter.api.Test;

class SquadJobsTest {

    private static SavedSquad squad(int slot, List<String> heroes, long inf, long lan, long mrk) {
        return new SavedSquad(slot, SavedSquad.State.SAVED, heroes, List.of(), List.of(inf, lan, mrk), "141,610/141,610", null);
    }

    /** The squads as General set them on Nov 12: B1-B3 one hero each, B4-B5 none, all 100,000; B8 the host. */
    private static SquadSnapshot snapshot() {
        return new SquadSnapshot("2026-11-13T07:15:00Z", List.of(), null, List.of(
                squad(1, List.of("Jessie"), 30_250, 30_250, 39_500),
                squad(2, List.of("Jasser"), 30_250, 30_250, 39_500),
                squad(3, List.of("Seo-yoon"), 30_250, 30_250, 39_500),
                squad(4, List.of(), 50_000, 50_000, 0),
                squad(5, List.of(), 50_000, 50_000, 0),
                squad(7, List.of("Jessie", "Bahiti", "Sergey"), 1_355, 1_356, 132_859),
                squad(8, List.of("Mia", "Flint", "Bahiti"), 13_557, 13_557, 108_456)), List.of());
    }

    private static final List<Integer> HEROES = List.of(1, 2, 3);
    private static final List<Integer> TROOPS = List.of(4, 5);

    @Test
    void eachSquadIsShownByItsJobInSquadOrder() {
        List<SquadJobs.Row> rows = SquadJobs.rows(snapshot(), true, 8, HEROES, TROOPS, 100_000);
        assertEquals(List.of(1, 2, 3, 4, 5, 8), rows.stream().map(SquadJobs.Row::slot).toList());
        assertEquals("Bear join with heroes", rows.get(0).job());
        assertEquals("Bear join, troops only", rows.get(3).job());
        assertEquals("Rally host", rows.get(5).job());
        assertEquals("Jessie", rows.get(0).heroes());
        assertEquals("100,000 (30,250 / 30,250 / 39,500)", rows.get(0).sends());
    }

    @Test
    void aMatchingSquadHasNothingToFlagAndTheHostSaysItSendsMax() {
        List<SquadJobs.Row> rows = SquadJobs.rows(snapshot(), true, 8, HEROES, TROOPS, 100_000);
        assertEquals("", rows.get(0).check());
        assertEquals(SquadJobs.Level.OK, rows.get(0).level());
        assertEquals("Sends as many troops as it can carry.", rows.get(5).check());
    }

    @Test
    void aTroopsOnlySquadWithoutHeroesIsNotAProblem() {
        SquadJobs.Row four = SquadJobs.rows(snapshot(), true, 8, HEROES, TROOPS, 100_000).get(3);
        assertEquals("", four.check());
        assertEquals("—", four.heroes());
    }

    @Test
    void aHeroJoinWithoutHeroesIsFlaggedInWords() {
        SquadJobs.Row row = SquadJobs.rows(snapshot(), true, 8, List.of(1, 2, 3, 4), List.of(5), 100_000).get(3);
        assertTrue(row.check().startsWith("Squad 4 has no heroes"), row.check());
        assertEquals(SquadJobs.Level.WARN, row.level());
    }

    @Test
    void aSquadWithNoTroopsSaysSoBeforeAnythingElse() {
        SquadSnapshot empty = new SquadSnapshot("2026-11-13T07:15:00Z", List.of(), null, List.of(
                squad(5, List.of("Sergey", "Molly", "Bahiti"), 0, 0, 0)), List.of());
        SquadJobs.Row row = SquadJobs.rows(empty, false, 8, List.of(), List.of(5), 100_000).get(0);
        assertEquals("Has no troops, so it cannot join.", row.check());
        assertEquals(SquadJobs.Level.WARN, row.level());
    }

    @Test
    void aSquadThatDoesNotSendTheSettingIsFlagged() {
        SquadJobs.Row row = SquadJobs.rows(snapshot(), true, 8, HEROES, TROOPS, 120_000).get(0);
        assertEquals("Sends 100,000, but Troops per join is 120,000. Edit the squad in the game to match.", row.check());
        assertEquals(SquadJobs.Level.WARN, row.level());
    }

    @Test
    void aSquadTheScanNeverSawOrCouldNotReadIsSaidSo() {
        SquadSnapshot partial = new SquadSnapshot("2026-11-13T07:15:00Z", List.of(), null, List.of(
                new SavedSquad(1, SavedSquad.State.UNREADABLE, List.of(), List.of(), List.of(), null, null),
                new SavedSquad(2, SavedSquad.State.EMPTY_OR_LOCKED, List.of(), List.of(), List.of(), null, null)), List.of());
        List<SquadJobs.Row> rows = SquadJobs.rows(partial, false, 8, List.of(1, 2, 3), List.of(), 100_000);
        assertEquals("The scan could not read this squad. Press Scan now.", rows.get(0).check());
        assertEquals("Empty or locked in the game. Save a squad here first.", rows.get(1).check());
        assertTrue(rows.get(2).check().startsWith("Not read in the last scan"), rows.get(2).check());
        assertEquals(SquadJobs.Level.WARN, rows.get(2).level());
    }

    @Test
    void theScopeNamesTheSquadsTheSettingAffects() {
        assertEquals("Applies to join squads 1, 2, 3, 4 and 5 only. Squad 8, your rally, always sends as many as it can carry.",
                SquadJobs.perJoinScope(true, 8, HEROES, TROOPS));
        assertEquals("Applies to join squads 1, 2, 3, 4 and 5 only. You are not hosting, so the rally squad is not used.",
                SquadJobs.perJoinScope(false, 8, HEROES, TROOPS));
        assertTrue(SquadJobs.perJoinScope(true, 8, List.of(), List.of()).startsWith("No join squads are set."));
    }

    @Test
    void savedSquadsWithoutAJobAreListed() {
        assertEquals("Saved but not used by the bot: 7.", SquadJobs.unusedNote(snapshot(), true, 8, HEROES, TROOPS));
        assertEquals("Saved but not used by the bot: 7 and 8.", SquadJobs.unusedNote(snapshot(), false, 8, HEROES, TROOPS));
        assertEquals("", SquadJobs.unusedNote(null, true, 8, HEROES, TROOPS));
    }
}
