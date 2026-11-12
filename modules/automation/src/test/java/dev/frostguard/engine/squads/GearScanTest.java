package dev.frostguard.engine.squads;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import dev.frostguard.api.squads.HeroGear;
import dev.frostguard.engine.squads.ForgeDialogReader.ForgeDialog;
import dev.frostguard.engine.squads.ForgeDialogReader.GearDetails;
import dev.frostguard.engine.squads.GearRanker.GearPiece;
import dev.frostguard.engine.squads.GearRanker.GearState;
import dev.frostguard.engine.squads.GearScan.Abort;
import dev.frostguard.engine.squads.GearScan.HeroPage;
import dev.frostguard.engine.squads.HeroScreenReader.HeroXp;
import dev.frostguard.engine.squads.HeroUpgradePlanner.Instructor;
import dev.frostguard.engine.squads.HeroUpgradePlanner.Inventory;
import dev.frostguard.engine.squads.HeroUpgradePlanner.Piece;

class GearScanTest {

    private static final long PROFILE = 7;
    private static final Instant NOW = Instant.parse("2026-11-10T07:45:00Z");
    private static final List<String[]> ROSTER = List.of(
            new String[] { "Mia", "Lancer", "Saboteur" }, new String[] { "Flint", "Infantry", "Guardian" },
            new String[] { "Molly", "Lancer", "Saboteur" }, new String[] { "Bahiti", "Marksman", "Sniper" },
            new String[] { "Sergey", "Infantry", "Guardian" });
    private static final List<String> MAIN_FIVE = ROSTER.stream().map(hero -> hero[0]).toList();
    // openGrid, then per hero: openHero, readHero, openGearTab, 4 x (details, forge, closeForge, closeDetails), closeHero.
    private static final int TOTAL_STEPS = 1 + GearScan.HERO_COUNT * (3 + 4 * 4 + 1);

    @TempDir
    Path workspace;

    @Test
    void aMissingStateIsDue() {
        assertTrue(GearScan.isDue(null, NOW, 7));
    }

    @Test
    void aHandSeededFileWithoutScannedAtLoadsAndIsDue() throws IOException {
        writeGear("""
                {"pieces":[{"hero":"Mia","slot":"Goggles","enhancement":100,"masteryPercent":60,"onRole":true}],
                 "heroWeights":{"Mia":0.3},"somethingOld":1}""");

        GearState loaded = store().loadGear(PROFILE).orElseThrow();

        assertNull(loaded.scannedAt());
        assertEquals(60, loaded.pieces().getFirst().masteryPercent());
        assertTrue(GearScan.isDue(loaded, NOW, 7));
    }

    @ParameterizedTest
    @CsvSource({
            "2026-11-10T07:40:00Z, 7, false",
            "2026-11-04T01:45:00Z, 7, false",
            "2026-11-03T07:45:00Z, 7, true",
            "2026-11-03T07:44:59Z, 7, true",
            "2026-11-09T07:44:00Z, 1, true",
            "2026-11-09T07:46:00Z, 1, true",
            "2026-11-09T13:44:00Z, 1, true",
            "2026-11-09T13:46:00Z, 1, false",
            "2026-11-09T21:00:00Z, 1, false",
            "2026-11-10T07:46:00Z, 7, true",
            "not a time, 7, true",
    })
    void aScanIsDueOnlyPastItsAgeLessSixHours(String scannedAt, int days, boolean due) {
        assertEquals(due, GearScan.isDue(new GearState(List.of(), Map.of(), scannedAt), NOW, days));
    }

    @ParameterizedTest
    @CsvSource({
            "Infantry, Gauntlets, true", "Infantry, Belt, true", "Infantry, Goggles, false", "Infantry, Boots, false",
            "Lancer, Goggles, true", "Lancer, Boots, true", "Lancer, Gauntlets, false", "Lancer, Belt, false",
            "Marksman, Goggles, true", "Marksman, Boots, true", "Marksman, Gauntlets, false", "Marksman, Belt, false",
    })
    void onRoleFollowsTheTroopClass(String troopClass, String slot, boolean expected) {
        assertEquals(expected, GearScan.onRole(troopClass, slot));
    }

    @Test
    void existingWeightsStandAndANewHeroGetsAHalf() {
        GearState previous = new GearState(List.of(), Map.of("Mia", 0.3, "Molly", 0.0, "Natalia", 0.9));
        List<HeroPage> pages = List.of(page("Mia"), page("Molly"), page("Bahiti"));

        assertEquals(Map.of("Mia", 0.3, "Molly", 0.0, "Natalia", 0.9, "Bahiti", 0.5),
                GearScan.mergedWeights(previous, pages));
        assertEquals(Map.of("Bahiti", 0.5), GearScan.mergedWeights(null, List.of(page("Bahiti"))));
    }

    @Test
    void aFullScanSavesEveryPieceWithRolesAndTheScanTime() throws IOException {
        GearState previous = new GearState(List.of(), Map.of("Bahiti", 1.14));

        GearScan.Outcome outcome = GearScan.run(new FakeScreens(), store(), PROFILE, previous, NOW);

        assertNull(outcome.problem());
        GearState saved = store().loadGear(PROFILE).orElseThrow();
        assertEquals(outcome.state(), saved);
        assertEquals(NOW.toString(), saved.scannedAt());
        assertEquals(20, saved.pieces().size());
        assertEquals(new GearPiece("Mia", "Goggles", 80, 40, true), saved.pieces().getFirst());
        assertEquals(new GearPiece("Flint", "Goggles", 80, 40, false), saved.pieces().get(4));
        assertEquals(new GearPiece("Flint", "Belt", 80, 40, true), saved.pieces().get(6));
        assertEquals(1.14, saved.heroWeights().get("Bahiti"));
        assertEquals(0.5, saved.heroWeights().get("Sergey"));
        assertFalse(GearScan.isDue(saved, NOW, 7));
        try (var files = Files.list(squads())) {
            assertTrue(files.noneMatch(file -> file.toString().endsWith(".tmp")));
        }
    }

    @Test
    void aFailureAtAnyStepLeavesBothFilesUntouched() throws IOException {
        writeGear("{\"pieces\":[],\"heroWeights\":{\"Mia\":0.3}}");
        writeInventory("{\"capturedAt\":\"x\",\"heroXp\":1,\"essenceStones\":2,\"enhancementXp\":3,\"instructors\":[],\"priority\":[]}");
        byte[] gear = Files.readAllBytes(gearFile());
        byte[] inventory = Files.readAllBytes(inventoryFile());
        GearState previous = store().loadGear(PROFILE).orElseThrow();

        for (int step = 1; step <= TOTAL_STEPS; step++) {
            FakeScreens screens = new FakeScreens();
            screens.failAtStep = step;

            GearScan.Outcome outcome = GearScan.run(screens, store(), PROFILE, previous, NOW);

            assertEquals("forced failure at step " + step, outcome.problem());
            assertNull(outcome.state());
            assertArrayEquals(gear, Files.readAllBytes(gearFile()), "gear file changed at step " + step);
            assertArrayEquals(inventory, Files.readAllBytes(inventoryFile()), "inventory changed at step " + step);
        }
    }

    @Test
    void aFailedFirstScanCreatesNoFile() {
        FakeScreens screens = new FakeScreens();
        screens.failAtStep = TOTAL_STEPS;

        assertNotNull(GearScan.run(screens, store(), PROFILE, null, NOW).problem());
        assertFalse(Files.exists(gearFile()));
        assertFalse(Files.exists(inventoryFile()));
    }

    @Test
    void readsThatDisagreeAbortWithoutSaving() throws IOException {
        List<UnaryOperator<FakeScreens>> faults = List.of(
                s -> s.details(new GearDetails("Saboteur Boots", 80, "Enhance")),
                s -> s.details(new GearDetails("Sniper Goggles", 80, "Enhance")),
                s -> s.details(new GearDetails("Saboteur Goggles", 55, "Enhance")),
                s -> s.dialog(new ForgeDialog("Saboteur Boots", 40, 42, 13, 10)),
                s -> s.dialog(new ForgeDialog("Saboteur Goggles", 60, 62, 13, 14)),
                s -> s.page(new HeroPage("Mia", "Wizard", 78, null)),
                s -> s.duplicateNames());

        for (UnaryOperator<FakeScreens> fault : faults) {
            GearScan.Outcome outcome = GearScan.run(fault.apply(new FakeScreens()), store(), PROFILE, null, NOW);

            assertNotNull(outcome.problem());
            assertFalse(Files.exists(gearFile()), outcome.problem());
        }
    }

    @Test
    void aForgeSaveKeepsTheScanTime() {
        GearState scanned = new GearState(List.of(new GearPiece("Mia", "Goggles", 100, 60, true)),
                Map.of("Mia", 0.3), NOW.toString());

        GearState after = scanned.withMastery("Mia", "Goggles", 62);

        assertEquals(NOW.toString(), after.scannedAt());
        assertEquals(62, after.pieces().getFirst().masteryPercent());
    }

    @Test
    void theInventoryTakesTheScanAndKeepsWhatItDidNotRead() throws IOException {
        writeInventory("""
                {"capturedAt":"old","heroXp":9,"essenceStones":1,"enhancementXp":555,
                 "instructors":[{"name":"Mia","level":70,"xpToNext":1000}],
                 "priority":[{"hero":"Mia","piece":"Goggles","forgeCost":14,"enhanceCost":null}]}""");

        assertNull(GearScan.run(new FakeScreens(), store(), PROFILE, null, NOW).inventoryNote());

        Inventory saved = store().loadInventory(PROFILE).orElseThrow();
        assertEquals(NOW.toString(), saved.capturedAt());
        assertEquals(13, saved.essenceStones());
        assertEquals(382_377L, saved.heroXp());
        assertEquals(555, saved.enhancementXp());
        assertEquals(List.of(new Piece("Mia", "Goggles", 14, null)), saved.priority());
        assertEquals(new Instructor("Mia", 78, 2_400_000L), saved.instructors().getFirst());
    }

    @Test
    void anUnreadLevelOrXpFigureCarriesOver() {
        Inventory old = new Inventory("old", 9, 1, 555, List.of(new Instructor("Mia", 70, 1000L)), List.of());

        Inventory merged = GearScan.mergedInventory(old, List.of(new HeroPage("Mia", "Lancer", null, null)), 13, NOW);

        assertEquals(9, merged.heroXp());
        assertEquals(List.of(new Instructor("Mia", 70, 1000L)), merged.instructors());
    }

    @Test
    void aNewLevelNeverKeepsTheOldXpCost() {
        Inventory old = new Inventory("old", 9, 1, 555, List.of(new Instructor("Mia", 70, 1000L)), List.of());

        Inventory merged = GearScan.mergedInventory(old, List.of(new HeroPage("Mia", "Lancer", 71, null)), 13, NOW);

        assertEquals(List.of(new Instructor("Mia", 71, null)), merged.instructors());
    }

    @Test
    void theSameLevelKeepsTheOldXpCost() {
        Inventory old = new Inventory("old", 9, 1, 555, List.of(new Instructor("Mia", 70, 1000L)), List.of());

        Inventory merged = GearScan.mergedInventory(old, List.of(new HeroPage("Mia", "Lancer", 70, null)), 13, NOW);

        assertEquals(List.of(new Instructor("Mia", 70, 1000L)), merged.instructors());
    }

    @Test
    void instructorsTheScanDidNotSeeCarryOverInTheirSavedOrder() {
        Inventory old = new Inventory("old", 9, 1, 555, List.of(new Instructor("Natalia", 50, 700L),
                new Instructor("Mia", 70, 1000L), new Instructor("Jeronimo", 60, 800L)), List.of());
        List<HeroPage> pages = List.of(new HeroPage("Flint", "Infantry", 40, new HeroXp(9, 300)),
                new HeroPage("Mia", "Lancer", 71, new HeroXp(9, 2000)));

        Inventory merged = GearScan.mergedInventory(old, pages, 13, NOW);

        assertEquals(List.of(new Instructor("Natalia", 50, 700L), new Instructor("Mia", 71, 2000L),
                new Instructor("Jeronimo", 60, 800L), new Instructor("Flint", 40, 300L)), merged.instructors());
    }

    @Test
    void aHandEditedInventoryWithoutListsStillMerges() throws IOException {
        writeInventory("{\"capturedAt\":\"old\",\"heroXp\":9,\"essenceStones\":1,\"enhancementXp\":555}");

        assertNull(GearScan.run(new FakeScreens(), store(), PROFILE, null, NOW).inventoryNote());

        Inventory saved = store().loadInventory(PROFILE).orElseThrow();
        assertEquals(GearScan.HERO_COUNT, saved.instructors().size());
        assertEquals(List.of(), saved.priority());
    }

    @Test
    void unreadXpWithNoEarlierInventorySavesNoInventory() {
        FakeScreens screens = new FakeScreens();
        screens.noXp = true;

        GearScan.Outcome outcome = GearScan.run(screens, store(), PROFILE, null, NOW);

        assertNull(outcome.problem());
        assertNotNull(outcome.inventoryNote());
        assertTrue(Files.exists(gearFile()));
        assertFalse(Files.exists(inventoryFile()));
    }

    @Test
    void xpReadsThatDisagreeLeaveTheInventoryByteForByte() throws IOException {
        writeInventory("{\"capturedAt\":\"old\",\"heroXp\":9,\"essenceStones\":1,\"enhancementXp\":3,\"instructors\":[],\"priority\":[]}");
        byte[] before = Files.readAllBytes(inventoryFile());
        FakeScreens screens = new FakeScreens();
        screens.varyXp = true;

        GearScan.Outcome outcome = GearScan.run(screens, store(), PROFILE, null, NOW);

        assertNull(outcome.problem());
        assertNotNull(outcome.inventoryNote());
        assertTrue(Files.exists(gearFile()));
        assertArrayEquals(before, Files.readAllBytes(inventoryFile()));
    }

    @Test
    void otherHeroesAreSkippedAndTheWalkStopsOnceAllFiveAreFound() throws IOException {
        FakeScreens screens = new FakeScreens(Arrays.asList("Natalia", "Mia", null, "Flint", "Jeronimo",
                "Molly", "Bahiti", "Sergey", "Zinman"));

        GearScan.Outcome outcome = GearScan.run(screens, store(), PROFILE, null, NOW);

        assertNull(outcome.problem());
        assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7), screens.opened);
        assertEquals(8, screens.closes);
        assertEquals(MAIN_FIVE.stream().sorted().toList(),
                store().loadGear(PROFILE).orElseThrow().pieces().stream().map(GearPiece::hero).distinct().sorted().toList());
        assertEquals(20, outcome.state().pieces().size());
    }

    @Test
    void aMainHeroPastTheCardLimitAbortsAndSavesNothing() {
        List<String> cards = new ArrayList<>(List.of("Mia", "Flint", "Molly", "Bahiti"));
        cards.addAll(Collections.nCopies(GearScan.CARD_LIMIT - cards.size(), "Natalia"));
        cards.add("Sergey");
        FakeScreens screens = new FakeScreens(cards);

        GearScan.Outcome outcome = GearScan.run(screens, store(), PROFILE, null, NOW);

        assertNotNull(outcome.problem());
        assertNull(outcome.state());
        assertEquals(GearScan.CARD_LIMIT, screens.opened.size());
        assertFalse(screens.opened.contains(GearScan.CARD_LIMIT));
        assertFalse(Files.exists(gearFile()));
        assertFalse(Files.exists(inventoryFile()));
    }

    @Test
    void aGridMissingAMainHeroAbortsAndSavesNothing() {
        FakeScreens screens = new FakeScreens(List.of("Mia", "Flint", "Molly", "Bahiti", "Natalia", "Jeronimo"));

        assertNotNull(GearScan.run(screens, store(), PROFILE, null, NOW).problem());
        assertFalse(Files.exists(gearFile()));
        assertFalse(Files.exists(inventoryFile()));
    }

    @Test
    void aSkippedCardThatWillNotCloseAbortsAndSavesNothing() {
        FakeScreens screens = new FakeScreens(List.of("Natalia", "Mia", "Flint", "Molly", "Bahiti", "Sergey"));
        screens.failAtStep = 3;

        assertEquals("forced failure at step 3", GearScan.run(screens, store(), PROFILE, null, NOW).problem());
        assertEquals(List.of(0), screens.opened);
        assertFalse(Files.exists(gearFile()));
    }

    @Test
    void stoneReadsThatDisagreeLeaveTheInventoryButNotTheScan() {
        FakeScreens screens = new FakeScreens();
        screens.varyStones = true;

        GearScan.Outcome outcome = GearScan.run(screens, store(), PROFILE, null, NOW);

        assertNull(outcome.problem());
        assertNotNull(outcome.inventoryNote());
        assertTrue(Files.exists(gearFile()));
        assertFalse(Files.exists(inventoryFile()));
    }

    private HeroGearStore store() {
        return new HeroGearStore(workspace);
    }

    private Path squads() {
        return workspace.resolve("squads");
    }

    private Path gearFile() {
        return squads().resolve("hero-gear-" + PROFILE + ".json");
    }

    private Path inventoryFile() {
        return squads().resolve("hero-inventory-" + PROFILE + ".json");
    }

    private void writeGear(String json) throws IOException {
        Files.createDirectories(squads());
        Files.writeString(gearFile(), json);
    }

    private void writeInventory(String json) throws IOException {
        Files.createDirectories(squads());
        Files.writeString(inventoryFile(), json);
    }

    private static HeroPage page(String name) {
        return new HeroPage(name, "Lancer", 78, null);
    }

    /** Scripts a clean scan of all 20 pieces; each fault replaces the first read of its kind. */
    private static final class FakeScreens implements GearScan.Screens {
        int failAtStep = -1;
        boolean varyStones;
        boolean noXp;
        boolean varyXp;
        int closes;
        final List<Integer> opened = new ArrayList<>();
        private final List<String> cards;
        private boolean onGrid;
        private String hero;
        private int step;
        private int xpReads;
        private String slot;
        private GearDetails detailsFault;
        private ForgeDialog dialogFault;
        private HeroPage pageFault;
        private boolean duplicateNames;
        private int forges;

        FakeScreens() {
            this(MAIN_FIVE);
        }

        FakeScreens(List<String> cards) {
            this.cards = cards;
        }

        FakeScreens details(GearDetails fault) {
            detailsFault = fault;
            return this;
        }

        FakeScreens dialog(ForgeDialog fault) {
            dialogFault = fault;
            return this;
        }

        FakeScreens page(HeroPage fault) {
            pageFault = fault;
            return this;
        }

        FakeScreens duplicateNames() {
            duplicateNames = true;
            return this;
        }

        private void step() {
            if (++step == failAtStep) throw new Abort("forced failure at step " + step);
        }

        @Override
        public int openGrid() {
            step();
            onGrid = true;
            return cards.size();
        }

        @Override
        public String openHero(int index) {
            step();
            assertTrue(onGrid, "card " + index + " opened off the grid");
            onGrid = false;
            opened.add(index);
            hero = duplicateNames ? "Mia" : cards.get(index);
            return hero;
        }

        @Override
        public HeroPage readHero(String name) {
            step();
            assertTrue(MAIN_FIVE.contains(name), name + " should have been skipped");
            if (pageFault != null) return pageFault;
            HeroXp xp = noXp ? null : new HeroXp(varyXp ? 382_377 + xpReads++ : 382_377, 2_400_000);
            return new HeroPage(name, roster()[1], 78, xp);
        }

        private String[] roster() {
            return ROSTER.stream().filter(entry -> entry[0].equals(hero)).findFirst().orElseThrow();
        }

        @Override
        public List<HeroGear> openGearTab(String name) {
            step();
            return List.of(new HeroGear(1, 80, 4), new HeroGear(2, 80, 4), new HeroGear(3, 80, 4), new HeroGear(4, 80, 4));
        }

        @Override
        public GearDetails openDetails(String tapped) {
            step();
            slot = tapped;
            return detailsFault != null ? detailsFault : new GearDetails(set() + " " + tapped, 80, "Enhance");
        }

        @Override
        public ForgeDialog openForge() {
            step();
            if (dialogFault != null) return dialogFault;
            return new ForgeDialog(set() + " " + slot, 40, 42, varyStones ? 13 + forges++ : 13, 10);
        }

        private String set() {
            return roster()[2];
        }

        @Override
        public void closeForge() {
            step();
        }

        @Override
        public void closeDetails() {
            step();
        }

        @Override
        public void closeHero() {
            step();
            closes++;
            onGrid = true;
        }
    }
}
