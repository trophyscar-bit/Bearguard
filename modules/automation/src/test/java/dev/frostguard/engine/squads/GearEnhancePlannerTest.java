package dev.frostguard.engine.squads;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import dev.frostguard.engine.squads.GearEnhancePlanner.Raise;
import dev.frostguard.engine.squads.GearRanker.GearPiece;

class GearEnhancePlannerTest {

    @Test
    void anUnreadPieceIsSkippedRatherThanTakenAsZero() {
        List<GearPiece> gear = List.of(new GearPiece("Mia", "Goggles", null, 60, true),
                new GearPiece("Mia", "Boots", 3, null, true), new GearPiece("Mia", "Belt", 80, 40, true));

        assertEquals(List.of(new Raise("Mia", "Belt", 80, 85)), GearEnhancePlanner.plan(gear, Set.of("Mia"), 5, 1));
    }

    private static final List<GearPiece> GEAR = List.of(
            new GearPiece("Mia", "Goggles", 100, 60, true),
            new GearPiece("Mia", "Boots", 81, 50, true),
            new GearPiece("Mia", "Belt", 50, 0, false),
            new GearPiece("Bahiti", "Goggles", 80, 50, true),
            new GearPiece("Bahiti", "Boots", 80, 42, true),
            new GearPiece("Flint", "Gauntlets", 85, 58, true),
            new GearPiece("Flint", "Belt", 85, 50, true),
            new GearPiece("Sergey", "Gauntlets", 76, 30, true),
            new GearPiece("Sergey", "Belt", 76, 46, true),
            new GearPiece("Molly", "Goggles", 70, 30, true),
            new GearPiece("Molly", "Boots", 69, 30, true));

    private static final Set<String> WITHOUT_MOLLY = Set.of("Mia", "Bahiti", "Flint", "Sergey");

    @Test
    void liftsTheWeakestPieceToItsNextStep() {
        assertEquals(new Raise("Molly", "Boots", 69, 70),
                GearEnhancePlanner.plan(GEAR, Set.of("Molly", "Sergey"), 5, 1).getFirst());
    }

    @Test
    void movesToAnotherHeroOnceTheWeakestHasRisen() {
        List<Raise> raises = GearEnhancePlanner.plan(GEAR, WITHOUT_MOLLY, 5, 5);

        assertEquals(new Raise("Sergey", "Gauntlets", 76, 80), raises.get(0));
        assertEquals(new Raise("Sergey", "Belt", 76, 80), raises.get(1));
        assertEquals(new Raise("Sergey", "Gauntlets", 80, 85), raises.get(2));
        assertEquals(new Raise("Bahiti", "Boots", 80, 85), raises.get(3));
        assertEquals(new Raise("Sergey", "Belt", 80, 85), raises.get(4));
    }

    @Test
    void theLowestLevelGoesFirstEvenWithAHigherBonus() {
        List<GearPiece> gear = List.of(new GearPiece("Mia", "Boots", 60, 0, true), new GearPiece("Flint", "Belt", 50, 100, true));

        assertEquals(new Raise("Flint", "Belt", 50, 55), GearEnhancePlanner.plan(gear, Set.of("Mia", "Flint"), 5, 1).getFirst());
    }

    @Test
    void aTieOnLevelGoesToTheLowestBonus() {
        List<GearPiece> gear = List.of(new GearPiece("Mia", "Boots", 50, 60, true), new GearPiece("Flint", "Belt", 50, 10, true));

        assertEquals(new Raise("Flint", "Belt", 50, 55), GearEnhancePlanner.plan(gear, Set.of("Mia", "Flint"), 5, 1).getFirst());
    }

    @Test
    void skipsOffRolePiecesAndExcludedHeroes() {
        assertTrue(GearEnhancePlanner.plan(GEAR, WITHOUT_MOLLY, 5, 20).stream()
                .noneMatch(r -> r.hero().equals("Molly") || r.slot().equals("Gauntlets") && r.hero().equals("Mia")
                        || r.hero().equals("Mia") && r.slot().equals("Belt")));
    }

    @Test
    void rejectsAStepBelowOne() {
        assertThrows(IllegalArgumentException.class, () -> GearEnhancePlanner.plan(GEAR, WITHOUT_MOLLY, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> GearEnhancePlanner.plan(GEAR, WITHOUT_MOLLY, -5, 1));
    }

    @Test
    void stopsAtTheCap() {
        List<GearPiece> nearlyDone = List.of(new GearPiece("Mia", "Boots", 98, 50, true));

        assertEquals(List.of(new Raise("Mia", "Boots", 98, 100)),
                GearEnhancePlanner.plan(nearlyDone, Set.of("Mia"), 5, 5));
    }
}
