package dev.frostguard.tasks.heroes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.frostguard.api.squads.HeroGear;
import dev.frostguard.engine.squads.GearRanker.GearState;
import dev.frostguard.engine.squads.HeroGearStore;
import dev.frostguard.engine.squads.HeroGearStore.GearHalt;
import dev.frostguard.engine.squads.HeroGearStore.RaiseInFlight;
import dev.frostguard.engine.squads.QuickEquipPass;
import dev.frostguard.engine.squads.QuickEquipPass.GearTab;

class GearNightTest {

    private static final long PROFILE = 7;
    private static final Instant NOW = Instant.parse("2026-11-10T07:45:00Z");
    private static final GearState SCANNED = new GearState(List.of(), Map.of(), "scanned");
    private static final GearState ENHANCED = new GearState(List.of(), Map.of(), "enhanced");

    @TempDir
    Path workspace;

    private HeroGearStore store;

    @BeforeEach
    void setUp() {
        store = new HeroGearStore(workspace);
    }

    @Test
    void runsQuickEquipThenScanThenEnhanceThenForgeThenHome() throws IOException {
        Fake fake = new Fake();

        run(fake);

        assertEquals(List.of("quickEquip", "scan", "enhance", "forge", "home"), fake.calls);
        assertSame(SCANNED, fake.enhancedFrom);
        assertSame(ENHANCED, fake.forgedFrom);
        assertTrue(store.loadHalt(PROFILE).isEmpty());
    }

    @Test
    void aStopFromEnhanceSkipsTheForgeAndStillGoesHomeWithoutAHalt() throws IOException {
        Fake fake = new Fake();
        fake.enhanceResult = new GearNight.Enhanced(null, null);

        run(fake);

        assertEquals(List.of("quickEquip", "scan", "enhance", "home"), fake.calls);
        assertTrue(store.loadHalt(PROFILE).isEmpty());
    }

    @Test
    void anUnconfirmedPressHaltsSkipsTheForgeAndBacksOutOnly() throws IOException {
        Fake fake = new Fake();
        fake.enhanceResult = new GearNight.Enhanced(null, "PRESS_UNCONFIRMED: no read");

        run(fake);

        assertEquals(List.of("quickEquip", "scan", "enhance", "backOut"), fake.calls);
        GearHalt halt = store.loadHalt(PROFILE).orElseThrow();
        assertEquals("Enhance: PRESS_UNCONFIRMED: no read", halt.reason());
        assertEquals(NOW.toString(), halt.at());
    }

    @Test
    void aFailedScanSkipsEnhanceAndForge() throws IOException {
        Fake fake = new Fake();
        fake.scanResult = null;

        run(fake);

        assertEquals(List.of("quickEquip", "scan", "home"), fake.calls);
    }

    @Test
    void aStopFromQuickEquipHaltsAndSkipsEverythingElse() throws IOException {
        Fake fake = new Fake();
        fake.quickEquipResult = "Mia slot 1 went down";

        run(fake);

        assertEquals(List.of("quickEquip", "home"), fake.calls);
        assertEquals("Quick Equip: Mia slot 1 went down", store.loadHalt(PROFILE).orElseThrow().reason());
    }

    @Test
    void aSavedHaltBlocksEveryStepOnEveryLaterRun() throws IOException {
        Fake first = new Fake();
        first.quickEquipResult = "damage";
        run(first);

        for (int night = 0; night < 3; night++) {
            Fake later = new Fake();
            run(later);
            assertEquals(List.of("halted", "home"), later.calls);
        }
    }

    @Test
    void clearingTheHaltLetsTheNightRunAgain() throws IOException {
        store.saveHalt(PROFILE, new GearHalt("damage", NOW.toString(), GearHalt.QUICK_EQUIP));
        store.saveLeadBaseline(PROFILE, Map.of("Mia", List.of(new HeroGear(1, 100, 6))));

        store.clearHalt(PROFILE);

        assertTrue(store.loadLeadBaseline(PROFILE).isEmpty(), "a retry must not re-find the damage just accepted");
        Fake fake = new Fake();
        run(fake);
        assertEquals(List.of("quickEquip", "scan", "enhance", "forge", "home"), fake.calls);
    }

    @Test
    void anUnreadableHaltFileRunsNoStep() throws IOException {
        java.nio.file.Files.createDirectories(workspace.resolve("squads"));
        java.nio.file.Files.writeString(workspace.resolve("squads").resolve("hero-gear-halt-" + PROFILE + ".json"), "{not json");
        Fake fake = new Fake();

        assertThrows(IOException.class, () -> run(fake));

        assertEquals(List.of("home"), fake.calls);
    }

    @Test
    void anExceptionFromEnhanceStillGoesHome() {
        Fake fake = new Fake();
        fake.enhanceThrows = true;

        assertThrows(IllegalStateException.class, () -> run(fake));

        assertEquals(List.of("quickEquip", "scan", "enhance", "home"), fake.calls);
    }

    @Test
    void aRealQuickEquipStopMeansNoScanAndNoBuyAndHaltsLaterNights() throws IOException {
        Walk walk = new Walk();
        Fake fake = new Fake();
        fake.quickEquipStep = () -> GearNight.haltReason(QuickEquipPass.run(walk, store, PROFILE));

        run(fake);

        assertEquals(List.of("quickEquip", "home"), fake.calls);
        assertEquals(1, walk.presses);
        assertTrue(store.loadHalt(PROFILE).orElseThrow().reason().contains("Mia slot 1"));

        Walk later = new Walk();
        Fake next = new Fake();
        next.quickEquipStep = () -> GearNight.haltReason(QuickEquipPass.run(later, store, PROFILE));
        run(next);

        assertEquals(List.of("halted", "home"), next.calls);
        assertEquals(0, later.presses);
        assertEquals(0, later.opens);
    }

    private static final RaiseInFlight MIA_BOOTS = new RaiseInFlight("Mia", "Boots", 81);
    private static final Map<String, List<HeroGear>> BASELINE = Map.of("Mia", List.of(new HeroGear(1, 100, 6)));

    @Test
    void aRaiseLeftInFlightSavesAHaltAndPressesNothing() throws IOException {
        store.saveRaiseInFlight(PROFILE, MIA_BOOTS);
        Fake fake = new Fake();

        run(fake);

        assertEquals(List.of("backOut"), fake.calls);
        GearHalt halt = store.loadHalt(PROFILE).orElseThrow();
        assertTrue(halt.reason().contains("Mia Boots at +81"), halt.reason());
        assertEquals(GearHalt.ENHANCE, halt.source());
    }

    @Test
    void anExceptionWithAPressInFlightHaltsAndUsesOnlyBack() throws IOException {
        Fake fake = new Fake();
        fake.enhanceThrows = true;
        fake.onEnhance = () -> store.saveRaiseInFlight(PROFILE, MIA_BOOTS);

        assertThrows(IllegalStateException.class, () -> run(fake));

        assertEquals(List.of("quickEquip", "scan", "enhance", "backOut"), fake.calls);
        assertTrue(store.loadHalt(PROFILE).isPresent());
    }

    @Test
    void anEarlierHaltIsNotOverwrittenByTheMarkerBehindIt() throws IOException {
        store.saveHalt(PROFILE, new GearHalt("Enhance: PRESS_UNCONFIRMED", NOW.toString(), GearHalt.ENHANCE));
        store.saveRaiseInFlight(PROFILE, MIA_BOOTS);
        Fake fake = new Fake();

        run(fake);

        assertEquals(List.of("halted", "home"), fake.calls);
        assertEquals("Enhance: PRESS_UNCONFIRMED", store.loadHalt(PROFILE).orElseThrow().reason());
    }

    @Test
    void clearingTheHaltDropsTheMarkerSoTheNightCanRun() throws IOException {
        store.saveRaiseInFlight(PROFILE, MIA_BOOTS);
        run(new Fake());

        store.clearHalt(PROFILE);

        assertTrue(store.loadRaiseInFlight(PROFILE).isEmpty());
        Fake fake = new Fake();
        run(fake);
        assertEquals(List.of("quickEquip", "scan", "enhance", "forge", "home"), fake.calls);
    }

    @Test
    void aFinishedNightDoesNothingUntilTheNextNight() throws IOException {
        run(new Fake());

        Fake again = new Fake();
        run(again);
        assertEquals(List.of("finished"), again.calls);

        Fake tomorrow = new Fake();
        GearNight.run(tomorrow, store, PROFILE, () -> NOW.plus(java.time.Duration.ofHours(24)));
        assertEquals(List.of("quickEquip", "scan", "enhance", "forge", "home"), tomorrow.calls);
    }

    @Test
    void aNightEndedByAFailedScanIsFinished() throws IOException {
        Fake fake = new Fake();
        fake.scanResult = null;
        run(fake);

        Fake again = new Fake();
        run(again);

        assertEquals(List.of("finished"), again.calls);
    }

    @Test
    void aRunCutOffLeavesTheNightOpenWithWhatItUsed() throws IOException {
        Fake cut = new Fake();
        cut.enhanceThrows = true;
        cut.onEnhance = () -> cut.budget.spendRaise();
        assertThrows(IllegalStateException.class, () -> run(cut));

        Fake retry = new Fake();
        Instant later = NOW.plus(java.time.Duration.ofMinutes(7));
        GearNight.run(retry, store, PROFILE, () -> later);

        assertEquals(List.of("quickEquip", "scan", "enhance", "forge", "home"), retry.calls);
        assertEquals(NOW.plus(NightBudget.TIME_CAP), retry.budget.deadline(), "the same cap, not a new one");
        assertEquals(2, retry.budget.raisesLeft(3));
    }

    @Test
    void anUncheckedBaselineSkipsEverythingAfterQuickEquip() throws IOException {
        store.saveLeadBaseline(PROFILE, BASELINE);
        Fake fake = new Fake();

        run(fake);

        assertEquals(List.of("quickEquip", "baselineUnchecked", "home"), fake.calls);
    }

    @Test
    void aSkippedRunIsNotAFinishedNight() throws IOException {
        store.saveLeadBaseline(PROFILE, BASELINE);
        run(new Fake());
        store.clearLeadBaseline(PROFILE);

        Fake retry = new Fake();
        run(retry);

        assertEquals(List.of("quickEquip", "scan", "enhance", "forge", "home"), retry.calls);
    }

    @Test
    void quickEquipUntickedWithNoBaselineOpensNothing() throws IOException {
        Run run = new Run();

        assertEquals(null, GearNight.quickEquip(false, run, store, PROFILE));

        assertEquals(0, run.gridOpens);
    }

    @Test
    void quickEquipUntickedStillRechecksAndPressesNothing() throws IOException {
        store.saveLeadBaseline(PROFILE, Map.of("Mia", Walk.FULL));
        Run run = new Run();

        assertEquals(null, GearNight.quickEquip(false, run, store, PROFILE));

        assertEquals(1, run.gridOpens);
        assertEquals(0, run.walk.presses);
        assertEquals(1, run.walk.opens);
        assertTrue(store.loadLeadBaseline(PROFILE).isEmpty());
    }

    @Test
    void quickEquipUntickedRecheckFindingDamageHalts() throws IOException {
        store.saveLeadBaseline(PROFILE, Map.of("Mia", Walk.FULL));
        Run run = new Run();
        run.walk.presses = 1;

        String reason = GearNight.quickEquip(false, run, store, PROFILE);

        assertTrue(reason.contains("Mia slot 1"), reason);
        assertEquals(1, run.walk.presses, "nothing pressed");
    }

    @Test
    void aGridThatWillNotOpenKeepsTheBaselineAndSkipsTheNight() throws IOException {
        store.saveLeadBaseline(PROFILE, BASELINE);
        Run run = new Run();
        run.gridOpensFine = false;
        Fake fake = new Fake();
        fake.quickEquipStep = () -> {
            try {
                return GearNight.quickEquip(true, run, store, PROFILE);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        };

        run(fake);

        assertEquals(List.of("quickEquip", "baselineUnchecked", "home"), fake.calls);
        assertTrue(store.loadLeadBaseline(PROFILE).isPresent());
    }

    @Test
    void clearKeepsTheBaselineUnlessTheQuickEquipCheckRaisedTheHalt() throws IOException {
        store.saveLeadBaseline(PROFILE, BASELINE);
        store.saveHalt(PROFILE, new GearHalt("Enhance: no read", NOW.toString(), GearHalt.ENHANCE));

        store.clearHalt(PROFILE);

        assertTrue(store.loadHalt(PROFILE).isEmpty());
        assertTrue(store.loadLeadBaseline(PROFILE).isPresent());

        store.saveHalt(PROFILE, new GearHalt("Quick Equip: damage", NOW.toString(), GearHalt.QUICK_EQUIP));
        store.clearHalt(PROFILE);

        assertTrue(store.loadLeadBaseline(PROFILE).isEmpty());
    }

    @Test
    void aHaltThatFailsToSaveIsRetriedOnce() throws IOException {
        java.nio.file.Path blocked = blockHaltSaves();
        Fake fake = new Fake();
        fake.quickEquipResult = "damage";
        fake.onHaltUnsaved = () -> java.nio.file.Files.delete(blocked);

        run(fake);

        assertEquals(List.of("quickEquip", "haltUnsaved", "home"), fake.calls);
        assertEquals("Quick Equip: damage", store.loadHalt(PROFILE).orElseThrow().reason());
    }

    @Test
    void aHaltThatCannotBeSavedPressesNothingElseAndDoesNotThrow() throws IOException {
        blockHaltSaves();
        Fake fake = new Fake();
        fake.quickEquipResult = "damage";

        run(fake);

        assertEquals(List.of("quickEquip", "haltUnsaved", "haltUnsaved", "backOut"), fake.calls);
    }

    @Test
    void anEnhanceHaltThatCannotBeSavedStillSkipsTheForge() throws IOException {
        blockHaltSaves();
        Fake fake = new Fake();
        fake.enhanceResult = new GearNight.Enhanced(null, "PRESS_UNCONFIRMED: no read");

        run(fake);

        assertEquals(List.of("quickEquip", "scan", "enhance", "haltUnsaved", "haltUnsaved", "backOut"), fake.calls);
    }

    private java.nio.file.Path blockHaltSaves() throws IOException {
        return java.nio.file.Files.createDirectories(
                workspace.resolve("squads").resolve("hero-gear-halt-" + PROFILE + ".json.tmp"));
    }

    @FunctionalInterface
    private interface Hook {
        void run() throws IOException;
    }

    private final class Run implements GearNight.QuickEquipRun {
        final Walk walk = new Walk();
        boolean gridOpensFine = true;
        int gridOpens;

        @Override
        public boolean openGrid() {
            gridOpens++;
            return gridOpensFine;
        }

        @Override
        public QuickEquipPass.Screens screens() {
            return walk;
        }

        @Override
        public void report(QuickEquipPass.Outcome outcome) {
        }
    }

    @Test
    void backOutReadsBeforeEachBackAndStopsOnceSettled() {
        int[] backs = { 0 };

        assertTrue(GearNight.backOut(() -> backs[0] == 2, () -> backs[0]++));

        assertEquals(2, backs[0]);
    }

    @Test
    void backOutPressesNothingWhenAlreadySettled() {
        int[] backs = { 0 };

        assertTrue(GearNight.backOut(() -> true, () -> backs[0]++));

        assertEquals(0, backs[0]);
    }

    @Test
    void backOutGivesUpAfterItsLimit() {
        int[] backs = { 0 };

        assertFalse(GearNight.backOut(() -> false, () -> backs[0]++));

        assertEquals(GearNight.MAX_BACKS, backs[0]);
    }

    private void run(Fake fake) throws IOException {
        GearNight.run(fake, store, PROFILE, () -> NOW);
    }

    @FunctionalInterface
    private interface QuickEquipStep {
        String run();
    }

    private static final class Fake implements GearNight.Steps {
        final List<String> calls = new ArrayList<>();
        String quickEquipResult;
        QuickEquipStep quickEquipStep;
        GearState scanResult = SCANNED;
        GearNight.Enhanced enhanceResult = new GearNight.Enhanced(ENHANCED, null);
        boolean enhanceThrows;
        Hook onEnhance = () -> { };
        Hook onHaltUnsaved = () -> { };
        NightBudget budget;
        GearState enhancedFrom;
        GearState forgedFrom;

        @Override
        public void halted(GearHalt halt) {
            calls.add("halted");
        }

        @Override
        public void haltUnsaved(GearHalt halt, IOException cause) {
            calls.add("haltUnsaved");
            try {
                onHaltUnsaved.run();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public void alreadyFinished() {
            calls.add("finished");
        }

        @Override
        public void begin(NightBudget night) {
            budget = night;
        }

        @Override
        public void baselineUnchecked() {
            calls.add("baselineUnchecked");
        }

        @Override
        public String quickEquip() {
            calls.add("quickEquip");
            return quickEquipStep != null ? quickEquipStep.run() : quickEquipResult;
        }

        @Override
        public GearState scanIfDue() {
            calls.add("scan");
            return scanResult;
        }

        @Override
        public GearNight.Enhanced enhance(GearState state) {
            calls.add("enhance");
            enhancedFrom = state;
            try {
                onEnhance.run();
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            if (enhanceThrows) throw new IllegalStateException("preempted");
            return enhanceResult;
        }

        @Override
        public void forge(GearState state) {
            calls.add("forge");
            forgedFrom = state;
        }

        @Override
        public void home() {
            calls.add("home");
        }

        @Override
        public void backOutHome() {
            calls.add("backOut");
        }
    }

    /** All five leads then one lit hero; pressing it strips Mia's first piece. */
    private static final class Walk implements QuickEquipPass.Screens {
        private static final List<String> NAMES = List.of("Mia", "Flint", "Molly", "Bahiti", "Sergey", "Jessie");
        private static final List<HeroGear> FULL = List.of(new HeroGear(1, 100, 6), new HeroGear(2, 55, 1),
                new HeroGear(3, 50, null), new HeroGear(4, 81, 5));
        int presses;
        int opens;
        private int index;

        @Override
        public GearTab openFirstHero() {
            opens++;
            index = 0;
            return tab();
        }

        @Override
        public GearTab nextHero(String current) {
            index++;
            return index < NAMES.size() ? tab() : null;
        }

        private GearTab tab() {
            String name = NAMES.get(index);
            return new GearTab(name, "Jessie".equals(name) && presses == 0, null);
        }

        @Override
        public List<HeroGear> readGear(String hero) {
            return FULL;
        }

        @Override
        public List<HeroGear> rereadGear(String hero) {
            return "Mia".equals(hero) && presses > 0
                    ? List.of(new HeroGear(1, 90, 6), FULL.get(1), FULL.get(2), FULL.get(3)) : FULL;
        }

        @Override
        public boolean press(String hero) {
            presses++;
            return true;
        }

        @Override
        public void closeToGrid() {
        }

        @Override
        public boolean pastStop() {
            return false;
        }
    }
}
