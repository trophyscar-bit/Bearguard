package dev.frostguard.engine.squads;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.frostguard.api.squads.HeroGear;
import dev.frostguard.api.squads.HeroRecord;
import dev.frostguard.api.squads.HeroSkill;
import dev.frostguard.api.squads.SavedSquad;
import dev.frostguard.api.squads.SquadSnapshot;

/** The top five heroes with the gear and stats the live scan of Oct 31 read. */
class GearAdvisorTest {

    private static final SquadSnapshot SCAN = new SquadSnapshot("2026-10-31T17:00:00Z", List.of(
            hero("Mia", "SSR", "Lancer", 175.02, 148.7, 65.56, "Fate Crystal", 1, g(100, 6), g(55, 1), g(50, null), g(81, 5)),
            hero("Flint", "SSR", "Infantry", 177.34, 79.99, 146.38, "Dragonbane", 4, g(50, null), g(78, 5), g(78, 5), g(50, 1)),
            hero("Molly", "SSR", "Lancer", 200.16, 88.55, 56.33, "Yeti Spirit", 2, g(65, 2), g(45, null), g(40, null), g(61, 2)),
            hero("Bahiti", "SR", "Marksman", 140.11, 112.56, 48.4, null, null, g(80, 4), g(40, 1), g(40, 1), g(78, 4)),
            hero("Sergey", "SR", "Infantry", 140.11, 53.33, 104.76, null, null, g(50, null), g(76, 3), g(76, 4), g(50, null))),
            null, List.of(), List.of());

    @Test
    void fiftyStonesGoToBahitisGogglesFirst() {
        GearAdvisor.Advice advice = GearAdvisor.advise(SCAN, SquadPlanner.plan(SCAN), 50);
        List<GearAdvisor.Step> stones = advice.steps().stream().filter(s -> s.spend().equals("Essence stones")).toList();

        // Bahiti's lethality pair is the only main pair below Mastery 5; then each pair one level
        // at a time, infantry Health first, keeping the pair level.
        assertEquals(List.of("Bahiti Goggles 50", "Bahiti Boots 50", "Flint Gloves 60", "Flint Belt 60",
                "Bahiti Goggles 60", "Bahiti Boots 60", "Mia Boots 60", "Flint Gloves 70"),
                stones.stream().limit(8).map(s -> s.hero() + " " + s.piece().split(" ")[0] + " " + s.cost()).toList());
        assertTrue(stones.get(0).covered());
        assertFalse(stones.get(1).covered());
        assertTrue(stones.stream().noneMatch(s -> s.hero().equals("Sergey") || s.hero().equals("Molly")));
    }

    @Test
    void marksmanHeavySquadsPutMarksmanPiecesFirst() {
        SquadSnapshot bearSquads = new SquadSnapshot(SCAN.capturedAt(), SCAN.heroes(), null, List.of(
                new SavedSquad(1, SavedSquad.State.SAVED, List.of("Jessie", "Bahiti", "Sergey"), List.of(),
                        List.of(1L, 1L, 69_696L), null, null),
                new SavedSquad(2, SavedSquad.State.SAVED, List.of("Molly", "Bahiti", "Flint"), List.of(),
                        List.of(1_268L, 1_268L, 124_314L), null, null)), List.of());
        GearAdvisor.Advice advice = GearAdvisor.advise(bearSquads, SquadPlanner.plan(bearSquads), 500);

        // Once Bahiti's pair reaches 5, the marksman pair is raised before infantry Health.
        List<String> stones = advice.steps().stream().filter(s -> s.spend().equals("Essence stones"))
                .map(s -> s.hero() + " " + s.piece().split(" ")[0]).toList();
        assertEquals(List.of("Bahiti Goggles", "Bahiti Boots", "Bahiti Goggles", "Bahiti Boots", "Flint Gloves"),
                stones.subList(0, 5));
        assertTrue(advice.notes().stream().anyMatch(n -> n.startsWith("Priority follows your saved squads' troops (Marksman 99%")),
                advice.notes().toString());
        assertTrue(advice.notes().stream().anyMatch(n -> n.startsWith("Your saved squads also lead with Jessie, Molly")),
                advice.notes().toString());
    }

    @Test
    void tooFewStonesForTheFirstStepSaysSaveUp() {
        GearAdvisor.Advice advice = GearAdvisor.advise(SCAN, SquadPlanner.plan(SCAN), 30);
        assertTrue(advice.notes().stream().anyMatch(n -> n.contains("save up")), advice.notes().toString());
    }

    @Test
    void xpEvensOutTheMainPiecesLowestFirst() {
        GearAdvisor.Advice advice = GearAdvisor.advise(SCAN, SquadPlanner.plan(SCAN), 50);
        List<String> xp = advice.steps().stream().filter(s -> s.spend().equals("Enhancement XP"))
                .map(s -> s.hero() + " " + s.piece().split(" ")[0] + " " + s.next()).toList();
        assertEquals(List.of("Flint Gloves +80", "Flint Belt +80", "Bahiti Boots +80"), xp.subList(0, 3));
    }

    @Test
    void widgetsGoWhereTheNextBonusIsCheapest() {
        GearAdvisor.Advice advice = GearAdvisor.advise(SCAN, SquadPlanner.plan(SCAN), 50);
        List<String> widgets = advice.steps().stream().filter(s -> s.spend().equals("Widgets"))
                .map(s -> s.hero() + " " + s.next() + " " + s.cost()).toList();
        assertEquals(List.of("Mia +2 10", "Molly +4 35", "Flint +10 225"), widgets);
    }

    @Test
    void notesSayWhoseGearDoesNotLeadAndWhatRedGearNeeds() {
        List<String> notes = GearAdvisor.advise(SCAN, SquadPlanner.plan(SCAN), 50).notes();
        assertTrue(notes.stream().anyMatch(n -> n.startsWith("Sergey leads no march")), notes.toString());
        assertTrue(notes.stream().anyMatch(n -> n.startsWith("Molly leads the garrison")), notes.toString());
        assertTrue(notes.stream().anyMatch(n -> n.contains("Mia's Goggles is +100: Mastery 10 (340 more stones)")),
                notes.toString());
    }

    private static HeroGear g(int enhancement, Integer mastery) {
        return new HeroGear(0, enhancement, mastery);
    }

    private static HeroRecord hero(String name, String rarity, String troopClass, double attack, double lethality,
                                   double health, String exclusive, Integer exclusiveLevel, HeroGear... gear) {
        List<HeroGear> slots = List.of(new HeroGear(1, gear[0].enhancement(), gear[0].mastery()),
                new HeroGear(2, gear[1].enhancement(), gear[1].mastery()),
                new HeroGear(3, gear[2].enhancement(), gear[2].mastery()),
                new HeroGear(4, gear[3].enhancement(), gear[3].mastery()));
        return new HeroRecord(name, rarity, troopClass, 1_000_000L, 77, 5.0, 10, 13_000L, Map.of(),
                Map.of("Attack", attack, "Lethality", lethality, "Defense", attack, "Health", health), List.of(),
                List.of(new HeroSkill(name + " skill", 5, "Summons a snowstorm.")), slots, exclusive, exclusiveLevel);
    }
}
