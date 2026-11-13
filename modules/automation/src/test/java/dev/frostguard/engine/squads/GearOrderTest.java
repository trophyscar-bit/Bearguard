package dev.frostguard.engine.squads;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.frostguard.engine.squads.GearRanker.GearPiece;
import dev.frostguard.engine.squads.GearRanker.GearState;
import dev.frostguard.engine.squads.GearRanker.Upgrade;

class GearOrderTest {

    private static final List<GearPiece> PIECES = List.of(
            new GearPiece("Mia", "Goggles", 100, 60, true), new GearPiece("Mia", "Boots", 81, 50, true),
            new GearPiece("Flint", "Gauntlets", 85, 58, true), new GearPiece("Flint", "Belt", 85, 50, true),
            new GearPiece("Bahiti", "Goggles", 80, 50, true), new GearPiece("Bahiti", "Boots", 80, 42, true),
            new GearPiece("Sergey", "Gauntlets", 76, 30, true), new GearPiece("Sergey", "Belt", 76, 46, true),
            new GearPiece("Molly", "Goggles", 70, 30, true), new GearPiece("Molly", "Boots", 69, 30, true),
            new GearPiece("Molly", "Gauntlets", 45, 0, false));
    private static final Map<String, Double> WEIGHTS = Map.of(
            "Bahiti", 1.14, "Mia", 0.3, "Flint", 0.3, "Sergey", 0.1, "Molly", 0.1);

    private static List<String> names(List<Upgrade> upgrades) {
        return upgrades.stream().map(u -> u.hero() + " " + u.slot()).toList();
    }

    @Test
    void weakestFirstStartsWithTheLowestMasteryAndATieGoesToTheLowerGearLevel() {
        List<Upgrade> plan = GearOrder.plan(new GearState(PIECES, WEIGHTS, null), GearOrder.WEAKEST, 3);

        assertEquals(List.of("Molly Boots", "Molly Goggles", "Sergey Gauntlets"), names(plan));
    }

    @Test
    void weakestFirstBringsEveryoneUpEvenlyInsteadOfFinishingOnePiece() {
        List<Upgrade> plan = GearOrder.plan(new GearState(PIECES, WEIGHTS, null), GearOrder.WEAKEST, 12);

        List<Integer> from = plan.stream().map(Upgrade::fromMastery).toList();
        assertEquals(from.stream().sorted().toList(), from, "every forge is on the lowest piece left: " + names(plan));
        assertTrue(names(plan).containsAll(List.of("Molly Boots", "Molly Goggles", "Sergey Gauntlets", "Bahiti Boots")), names(plan).toString());
    }

    @Test
    void rallyLeadersFirstPutsEveryLeaderPieceBeforeAnyoneElse() {
        List<Upgrade> plan = GearOrder.plan(new GearState(PIECES, WEIGHTS, null), GearOrder.LEADS_THEN_WEAKEST, 6);

        assertEquals("Bahiti Boots", names(plan).getFirst(), "the weakest leader piece goes first");
        assertTrue(plan.stream().allMatch(u -> List.of("Bahiti", "Mia", "Flint").contains(u.hero())), names(plan).toString());
    }

    @Test
    void aHeroLeftUntickedNeverGetsAForgeInEitherOrder() {
        Map<String, Double> withoutMolly = Map.of("Bahiti", 1.14, "Mia", 0.3, "Flint", 0.3, "Sergey", 0.1);

        for (String order : GearOrder.CHOICES) {
            List<Upgrade> plan = GearOrder.plan(new GearState(PIECES, withoutMolly, null), order, 20);
            assertFalse(names(plan).stream().anyMatch(n -> n.startsWith("Molly")), order + ": " + names(plan));
        }
        assertTrue(GearOrder.plan(new GearState(PIECES, Map.of(), null), GearOrder.WEAKEST, 5).isEmpty());
    }

    @Test
    void offRolePiecesAndUnreadPiecesAreNeverPlanned() {
        List<GearPiece> pieces = List.of(new GearPiece("Molly", "Gauntlets", 45, 0, false), new GearPiece("Molly", "Goggles", 70, null, true));

        assertTrue(GearOrder.plan(new GearState(pieces, Map.of("Molly", 0.1), null), GearOrder.WEAKEST, 5).isEmpty());
    }

    @Test
    void theOldOrderWordsStillLoadAndNothingUnreadableBreaksIt() {
        assertEquals(GearOrder.BEST_RETURN, GearOrder.normalize("Rally squads"), "the old default ranked by squad weight and return per stone");
        assertEquals(GearOrder.BEST_RETURN, GearOrder.normalize("Balanced"));
        assertEquals(GearOrder.BEST_RETURN, GearOrder.normalize(null));
        assertEquals(GearOrder.BEST_RETURN, GearOrder.normalize("nonsense"));
        assertEquals(GearOrder.WEAKEST, GearOrder.normalize(GearOrder.WEAKEST));
        assertEquals(GearOrder.LEADS_THEN_WEAKEST, GearOrder.normalize(GearOrder.LEADS_THEN_WEAKEST));
        assertEquals(List.of("Best return per stone", "Rally leaders first, then weakest", "Weakest first"), GearOrder.CHOICES, "alphabetical");
    }

    @Test
    void bestReturnPlansByWeightedStatPerStoneAndNeverPicksTheLowestPieceBecauseItIsLowest() {
        GearState state = new GearState(PIECES, WEIGHTS, null);

        List<Upgrade> plan = GearOrder.plan(state, GearOrder.BEST_RETURN, 8);

        assertEquals(names(GearRanker.rolling(state, 8)), names(plan));
        assertTrue(names(plan).stream().noneMatch(n -> n.startsWith("Molly")), "Molly is weighted 0 and is skipped: " + names(plan));
        assertEquals("Bahiti", plan.getFirst().hero(), "the hero who fights most goes first");
    }

    @Test
    void anOldRallySquadsSettingPlansLikeBestReturn() {
        GearState state = new GearState(PIECES, WEIGHTS, null);

        assertEquals(names(GearOrder.plan(state, GearOrder.BEST_RETURN, 5)), names(GearOrder.plan(state, "Rally squads", 5)));
    }
}
