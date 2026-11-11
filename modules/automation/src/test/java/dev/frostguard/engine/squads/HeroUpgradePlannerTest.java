package dev.frostguard.engine.squads;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.frostguard.engine.squads.HeroUpgradePlanner.Instructor;
import dev.frostguard.engine.squads.HeroUpgradePlanner.Inventory;
import dev.frostguard.engine.squads.HeroUpgradePlanner.Piece;
import dev.frostguard.engine.squads.HeroUpgradePlanner.Plan;
import dev.frostguard.engine.squads.HeroUpgradePlanner.Spend;
import dev.frostguard.engine.squads.HeroUpgradePlanner.Step;

/** Balances and costs are the ones read off the game screen; null means that screen was not read. */
class HeroUpgradePlannerTest {

    private static final List<Instructor> INSTRUCTORS = List.of(
            new Instructor("Mia", 78, 2_100_000L),
            new Instructor("Flint", 78, 2_100_000L),
            new Instructor("Bahiti", 78, 2_100_000L),
            new Instructor("Sergey", 78, 2_100_000L),
            new Instructor("Molly", 80, null));
    private static final List<Piece> PRIORITY = List.of(
            new Piece("Bahiti", "Goggles", 10, 1450),
            new Piece("Bahiti", "Boots", null, null));
    private static final Inventory READ = new Inventory("2026-11-10T22:37:00Z", 2_300_000L, 23, 4640, INSTRUCTORS, PRIORITY);

    @Test
    void spendsOnlyWhatTheBalancesCover() {
        assertEquals(List.of(
                new Step(Spend.HERO_XP, "Mia", "Lv 79", 2_100_000),
                new Step(Spend.ESSENCE_STONES, "Bahiti", "Goggles", 10),
                new Step(Spend.ENHANCEMENT_XP, "Bahiti", "Goggles", 1450)),
                HeroUpgradePlanner.plan(READ).steps());
    }

    @Test
    void anUnreadCostIsNeverSpentAgainst() {
        Plan plan = HeroUpgradePlanner.plan(READ);

        assertTrue(plan.steps().stream().noneMatch(s -> s.target().equals("Boots")));
        assertTrue(plan.waiting().contains("Bahiti Boots forge: cost not read"));
        assertTrue(plan.waiting().contains("Bahiti Boots enhance: cost not read"));
    }

    @Test
    void savesForTheTopPieceInsteadOfBuyingSomethingCheaperBelowIt() {
        List<Piece> bigFirst = List.of(
                new Piece("Sergey", "Gauntlets", 40, null),
                new Piece("Bahiti", "Boots", 10, null));
        Plan plan = HeroUpgradePlanner.plan(new Inventory("t", 0, 13, 0, List.of(), bigFirst));

        assertTrue(plan.steps().isEmpty());
        assertTrue(plan.waiting().contains("Sergey Gauntlets forge: saving, needs 40 stones, 13 left"));
    }

    @Test
    void aMaxedInstructorGetsNoXp() {
        assertTrue(HeroUpgradePlanner.plan(READ).steps().stream().noneMatch(s -> s.hero().equals("Molly")));
    }

    @Test
    void xpGoesToTheLowestInstructorFirst() {
        List<Instructor> uneven = List.of(
                new Instructor("Mia", 79, 2_400_000L),
                new Instructor("Sergey", 78, 2_100_000L));
        Inventory inventory = new Inventory("t", 2_600_000L, 0, 0, uneven, List.of());

        assertEquals(List.of(new Step(Spend.HERO_XP, "Sergey", "Lv 79", 2_100_000)),
                HeroUpgradePlanner.plan(inventory).steps());
    }
}
