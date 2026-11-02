package dev.frostguard.engine.squads;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.frostguard.api.squads.HeroRecord;
import dev.frostguard.api.squads.HeroSkill;
import dev.frostguard.api.squads.SquadSnapshot;

class SquadPlannerTest {

    private static final String CURSE = "Grants all troops' attack a 50% chance of cursing the target,"
            + " increasing their damage taken by 40%.";

    @Test
    void chanceScalesTheEffectAtTheCurrentLevel() {
        assertEquals(20.0, SquadPlanner.expectedValue(CURSE));
        assertEquals(25.0, SquadPlanner.expectedValue("Increases all troops' Attack by 25%."));
        assertNull(SquadPlanner.expectedValue("Summons a snowstorm."));
        assertEquals(8.0, SquadPlanner.expectedValue("increasing Troop's Defense by 4% and Health by 4%."));
    }

    @Test
    void aHitOfMoreThanFullDamageCountsOnlyTheExtra() {
        // Philly's Dosage Boost: a quarter of hits do 120% damage, a 5% gain on average.
        assertEquals(5.0, SquadPlanner.expectedValue("grants a 25% chance of dealing 120% damage."));
        // Flint's "Damage Dealt by 100%" is a doubling, not a hit size.
        assertEquals(100.0, SquadPlanner.expectedValue("Flint increases his Infantry's Damage Dealt by 100%."));
    }

    @Test
    void mixedEffectsAreNotAddedIntoOneKind() {
        SquadPlanner.Valued philly = SquadPlanner.evaluate(
                "Philly's secret remedy strengthens the soldiers, increasing Attack by 3% and Defense by 2% for all troops.");
        assertEquals(SquadPlanner.Effect.OFFENSE, philly.effect());
        assertEquals(3.0, philly.value());
        SquadPlanner.Valued molly = SquadPlanner.evaluate("Molly creates an avalanche that clouds enemy sights,"
                + " granting a 40% chance of reducing all troops' Damage Taken by 50%.");
        assertEquals(SquadPlanner.Effect.DEFENSE, molly.effect());
        assertEquals(20.0, molly.value());
    }

    @Test
    void classifiesWhoTheSkillHelps() {
        assertEquals(SquadPlanner.Effect.OFFENSE, SquadPlanner.classify(CURSE));
        assertEquals(SquadPlanner.Effect.DEFENSE,
                SquadPlanner.classify("Reduces damage taken by all troops by 15%."));
        assertEquals(SquadPlanner.Effect.DEFENSE,
                SquadPlanner.classify("Lowers the enemy's Attack by 20% for 2 turns."));
        assertEquals(SquadPlanner.Effect.GATHERING,
                SquadPlanner.classify("Increases gathering speed by 30%."));
    }

    @Test
    void lessDamageTakenIsDefenseEvenWhenTheTextMentionsTheEnemy() {
        assertEquals(SquadPlanner.Effect.DEFENSE, SquadPlanner.classify("Molly creates an avalanche that clouds"
                + " enemy sights, granting a 40% chance of reducing all troops' Damage Taken by 50%."));
    }

    @Test
    void aSkillForOneTroopTypeIsKeptOutOfBearJoins() {
        String flint = "Every flame, no matter how small, can ignite a roaring fire."
                + " Flint increases his Infantry's Damage Dealt by 100%.";
        assertEquals("Infantry", SquadPlanner.onlyFor(flint));
        assertNull(SquadPlanner.onlyFor("Increases damage dealt by 25% for all troops."));

        SquadPlanner.Plan plan = SquadPlanner.plan(snapshot(
                hero("Flint", "Infantry", 177, 80, 177, 146, flint),
                hero("Mia", "Lancer", 175, 148, 175, 65, "Summons a snowstorm."),
                hero("Jessie", "Lancer", 140, 3, 140, 4, "Increases damage dealt by 25% for all troops.")));

        assertEquals(List.of("Jessie"), plan.squads().get(1).heroes());
        assertTrue(plan.squads().get(2).heroes().isEmpty());
        assertEquals("Infantry", plan.joiners().getFirst().onlyFor());
    }

    @Test
    void leadTeamTakesTheStrongestHeroOfEachTroopType() {
        SquadSnapshot snapshot = snapshot(
                hero("Mia", "Lancer", 175, 148, 175, 65, CURSE),
                hero("Weak Lancer", "Lancer", 50, 40, 60, 60, "Increases all troops' Attack by 5%."),
                hero("Tank", "Infantry", 100, 80, 200, 190, "Reduces damage taken by all troops by 15%."),
                hero("Archer", "Marksman", 160, 150, 90, 70, "Increases all troops' Attack by 25%."));

        SquadPlanner.Plan plan = SquadPlanner.plan(snapshot);

        assertEquals(List.of("Tank", "Mia", "Archer"), plan.squads().getFirst().heroes());
        assertEquals(List.of("Weak Lancer"), plan.squads().get(10).heroes());
    }

    @Test
    void joinSquadsCarryOnlyTheBestFirstSkillHeroes() {
        SquadSnapshot snapshot = snapshot(
                hero("Mia", "Lancer", 175, 148, 175, 65, CURSE),
                hero("Archer", "Marksman", 160, 150, 90, 70, "Increases all troops' Attack by 25%."),
                hero("Tank", "Infantry", 100, 80, 200, 190, "Reduces damage taken by all troops by 15%."),
                hero("Jessie", "Lancer", 140, 3, 140, 4, "Increases damage dealt by 25% for all troops."),
                hero("Jasser", "Marksman", 120, 3, 120, 4, "Increases damage dealt by 20% for all troops."),
                hero("Sergey", "Infantry", 90, 3, 150, 140, "Reduces damage taken by 20% for all troops."));

        SquadPlanner.Plan plan = SquadPlanner.plan(snapshot);

        // Mia, Archer and Tank lead the Bear rally, so they never join it.
        assertEquals(List.of("Tank", "Mia", "Archer"), plan.squads().getFirst().heroes());
        assertEquals(List.of("Jessie"), plan.squads().get(1).heroes());
        assertEquals(List.of("Jasser"), plan.squads().get(2).heroes());
        assertTrue(plan.squads().get(3).heroes().isEmpty());
        // Tank holds the garrison, so Sergey is the best reinforcing joiner left.
        assertEquals(List.of("Tank", "Mia", "Archer"), plan.squads().get(6).heroes());
        assertEquals(List.of("Sergey"), plan.squads().get(7).heroes());
    }

    @Test
    void aHeroWithUnreadStatsIsLeftOutOfTeamsInsteadOfGuessed() {
        HeroRecord unread = new HeroRecord("Unread", "SSR", "Infantry", 2_000_000L, 80, 5.0, 10, 14_000L,
                Map.of(), Map.of(), List.of(), List.of(), List.of(), null, null);
        SquadSnapshot snapshot = snapshot(unread,
                hero("Tank", "Infantry", 100, 80, 200, 190, "Reduces damage taken by all troops by 15%."));

        SquadPlanner.Plan plan = SquadPlanner.plan(snapshot);

        assertEquals(List.of("Tank"), plan.squads().getFirst().heroes());
        assertEquals(1, plan.heroScores().size());
    }

    @Test
    void onlyGoldAndKeyPurpleHeroesAreRanked() {
        HeroRecord rare = new HeroRecord("Blue", "R", "Infantry", 900_000L, 60, 5.0, 8, 9_000L, Map.of(),
                Map.of("Attack", 500.0, "Lethality", 500.0, "Defense", 500.0, "Health", 500.0),
                List.of(), List.of(new HeroSkill("Big", 5, "Increases all troops' Attack by 90%.")), List.of(), null, null);

        HeroRecord purple = new HeroRecord("Purple", "SR", "Lancer", 900_000L, 77, 5.0, 10, 13_000L, Map.of(),
                Map.of("Attack", 500.0, "Lethality", 500.0, "Defense", 500.0, "Health", 500.0),
                List.of(), List.of(new HeroSkill("Big", 5, "Increases all troops' Attack by 90%.")), List.of(), null, null);

        SquadPlanner.Plan plan = SquadPlanner.plan(snapshot(rare, purple));

        assertTrue(plan.heroScores().isEmpty());
        assertTrue(plan.joiners().isEmpty());
        assertTrue(plan.squads().stream().allMatch(s -> s.heroes().isEmpty()));

        HeroRecord jessie = new HeroRecord("Jessie", "SR", "Lancer", 760_000L, 77, 5.0, 10, 13_000L, Map.of(),
                Map.of("Attack", 140.0, "Lethality", 3.0, "Defense", 140.0, "Health", 4.0), List.of(),
                List.of(new HeroSkill("Stand of Arms", 5, "Increases damage dealt by 25% for all troops.")), List.of(), null, null);
        SquadPlanner.Plan withJessie = SquadPlanner.plan(snapshot(rare, purple, jessie));
        assertEquals(List.of("Jessie"), withJessie.joiners().stream().map(SquadPlanner.JoinerScore::hero).toList());
    }

    private static HeroRecord hero(String name, String troopClass, double attack, double lethality,
                                   double defense, double health, String firstSkill) {
        return new HeroRecord(name, "SSR", troopClass, 1_000_000L, 77, 4.0, 10, 13_000L, Map.of(),
                Map.of("Attack", attack, "Lethality", lethality, "Defense", defense, "Health", health),
                List.of(), List.of(new HeroSkill(name + " skill", 4, firstSkill)), List.of(), null, null);
    }

    private static SquadSnapshot snapshot(HeroRecord... heroes) {
        return new SquadSnapshot("2026-10-31T17:00:00Z", List.of(heroes), null, List.of(), List.of());
    }
}
