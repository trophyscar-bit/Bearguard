package dev.frostguard.engine.squads;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.frostguard.engine.squads.GearRanker.GearPiece;
import dev.frostguard.engine.squads.GearRanker.GearState;
import dev.frostguard.engine.squads.GearRanker.Upgrade;

/** Gear state and the forge costs are the ones read off the game screen. */
class GearRankerTest {

    private static final List<GearPiece> ON_ROLE = List.of(
            new GearPiece("Mia", "Goggles", 100, 60, true),
            new GearPiece("Mia", "Boots", 81, 50, true),
            new GearPiece("Flint", "Gauntlets", 85, 58, true),
            new GearPiece("Flint", "Belt", 85, 50, true),
            new GearPiece("Bahiti", "Goggles", 80, 50, true),
            new GearPiece("Bahiti", "Boots", 80, 42, true),
            new GearPiece("Sergey", "Belt", 76, 46, true),
            new GearPiece("Sergey", "Gauntlets", 76, 30, true),
            new GearPiece("Molly", "Goggles", 70, 30, true),
            new GearPiece("Molly", "Boots", 69, 30, true));
    private static final Map<String, Double> WEIGHTS =
            Map.of("Bahiti", 1.14, "Flint", 0.30, "Mia", 0.30, "Sergey", 0.105, "Molly", 0.0);

    @Test
    void forgeCostsMatchEveryLevelReadOnScreen() {
        assertEquals(10, GearRanker.forgeCost(0));
        assertEquals(20, GearRanker.forgeCost(10));
        assertEquals(40, GearRanker.forgeCost(30));
        assertEquals(10, GearRanker.forgeCost(46));
        assertEquals(12, GearRanker.forgeCost(58));
        assertEquals(14, GearRanker.forgeCost(60));
    }

    @Test
    void ranksTheWeakestHeavilyUsedPieceFirstAndLooksTenAhead() {
        List<Upgrade> upgrades = GearRanker.rolling(new GearState(ON_ROLE, WEIGHTS), 10);

        assertEquals(List.of("Boots", "Boots", "Boots", "Boots", "Goggles", "Boots", "Goggles", "Boots", "Goggles", "Boots"),
                upgrades.stream().map(Upgrade::slot).toList());
        assertTrue(upgrades.stream().allMatch(u -> u.hero().equals("Bahiti")));
        assertEquals(112, upgrades.stream().mapToInt(Upgrade::stones).sum());
    }

    @Test
    void offRolePiecesAreNeverRankedEvenWhenTheyPayMorePerStone() {
        List<GearPiece> pieces = List.of(
                new GearPiece("Flint", "Goggles", 50, 0, false),
                new GearPiece("Flint", "Belt", 85, 50, true));

        List<Upgrade> upgrades = GearRanker.rolling(new GearState(pieces, Map.of("Flint", 1.0)), 3);

        assertTrue(upgrades.stream().allMatch(u -> u.slot().equals("Belt")));
    }

    @Test
    void anUnusedHeroIsNotRanked() {
        List<GearPiece> molly = List.of(new GearPiece("Molly", "Goggles", 70, 30, true));

        assertTrue(GearRanker.rolling(new GearState(molly, WEIGHTS), 10).isEmpty());
    }

    @Test
    void stopsAtMasteryTen() {
        List<GearPiece> nearlyDone = List.of(new GearPiece("Mia", "Goggles", 100, 98, true));

        assertEquals(1, GearRanker.rolling(new GearState(nearlyDone, Map.of("Mia", 1.0)), 10).size());
    }

    @Test
    void gradesOnRoleAgainstTheBestAndOffRoleAgainstHalf() {
        assertEquals("?", GearRanker.grade(new GearRanker.GearPiece("Mia", "Goggles", null, 60, true)));
        assertEquals("A", GearRanker.grade(new GearRanker.GearPiece("Mia", "Goggles", 100, 60, true)));
        assertEquals("C", GearRanker.grade(new GearRanker.GearPiece("Bahiti", "Boots", 80, 42, true)));
        assertEquals("B", GearRanker.grade(new GearRanker.GearPiece("Mia", "Gauntlets", 55, 10, false)));
        assertEquals("D", GearRanker.grade(new GearRanker.GearPiece("Molly", "Belt", 40, 0, false)));
    }

    @Test
    void weightsCoverOnlyIncludedHeroes() {
        GearRanker.GearState state = new GearRanker.GearState(java.util.List.of(),
                java.util.Map.of("Bahiti", 1.14, "Mia", 0.30, "Molly", 0.0));

        java.util.Map<String, Double> rally = GearRanker.weightsFor(state, GearRanker.FOCUS_RALLY,
                java.util.Set.of("Bahiti", "Molly"));
        assertEquals(java.util.Map.of("Bahiti", 1.14, "Molly", 0.1), rally);

        java.util.Map<String, Double> balanced = GearRanker.weightsFor(state, GearRanker.FOCUS_BALANCED,
                java.util.Set.of("Bahiti", "Molly"));
        assertEquals(java.util.Map.of("Bahiti", 1.0, "Molly", 0.5), balanced);
    }
}
