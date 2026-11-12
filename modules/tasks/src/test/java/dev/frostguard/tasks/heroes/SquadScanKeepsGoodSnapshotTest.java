package dev.frostguard.tasks.heroes;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.frostguard.api.squads.HeroRecord;
import dev.frostguard.api.squads.SquadSnapshot;
import dev.frostguard.api.squads.TroopOverview;

/**
 * On 11 November the 02:16 scan lost the Heroes grid after three heroes and never opened Chief
 * Profile. It saved "3 heroes, no troop lines, 0 saved squads" over Tuesday's complete scan, and
 * the Saved squads and Troops tabs went blank.
 */
class SquadScanKeepsGoodSnapshotTest {

    private static List<HeroRecord> heroes(int n) {
        return Collections.nCopies(n, (HeroRecord) null);
    }

    private static SquadSnapshot snap(int heroes, TroopOverview troops) {
        return new SquadSnapshot("2026-11-11T07:28:19Z", heroes(heroes), troops, List.of(), List.of());
    }

    private static TroopOverview troops() {
        return new TroopOverview(null, null, List.of());
    }

    @Test
    void aScanThatNeverReachedTroopsDoesNotReplaceOneThatDid() {
        assertTrue(SquadScanRoutine.isWorse(snap(21, null), snap(21, troops())));
    }

    @Test
    void aScanThatLostHeroesDoesNotReplaceAFullOne() {
        assertTrue(SquadScanRoutine.isWorse(snap(3, troops()), snap(21, troops())));
    }

    @Test
    void anEqualOrBetterScanIsSaved() {
        assertFalse(SquadScanRoutine.isWorse(snap(21, troops()), snap(21, troops())));
        assertFalse(SquadScanRoutine.isWorse(snap(21, troops()), snap(21, null)));
        assertFalse(SquadScanRoutine.isWorse(snap(22, troops()), snap(21, troops())));
    }
}
