package dev.frostguard.engine.squads;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import dev.frostguard.engine.squads.ForgeDialogReader.GearDetails;
import dev.frostguard.engine.squads.GearEnhancePass.Stop;
import dev.frostguard.engine.squads.GearEnhancePlanner.Raise;
import dev.frostguard.engine.squads.GearEnhanceStep.Outcome;
import dev.frostguard.engine.squads.GearEnhanceStep.Raiser;
import dev.frostguard.engine.squads.GearEnhanceStep.Settings;
import dev.frostguard.engine.squads.GearRanker.GearPiece;
import dev.frostguard.engine.squads.GearRanker.GearState;
import dev.frostguard.engine.squads.GearScan.Abort;

class GearEnhanceStepTest {

    private static final long PROFILE = 7;
    private static final Instant NOW = Instant.parse("2026-11-10T07:45:00Z");
    private static final Instant STOP_BY = NOW.plusSeconds(900);
    private static final GearState GEAR = new GearState(List.of(
            new GearPiece("Mia", "Boots", 81, 50, true),
            new GearPiece("Flint", "Belt", 40, 30, true),
            new GearPiece("Molly", "Boots", 10, 0, true)), Map.of("Mia", 1.0), NOW.toString());
    private static final Set<String> TICKED = Set.of("Mia", "Flint");
    private static final Raiser REACHES = (raise, live) -> reached(raise);

    @TempDir
    Path workspace;

    private HeroGearStore store;
    private FakeNavigator navigator;
    private Instant now;

    @BeforeEach
    void setUp() throws IOException {
        store = new HeroGearStore(workspace);
        store.saveGear(PROFILE, GEAR);
        navigator = new FakeNavigator();
        now = NOW;
    }

    @Test
    void raisesTheTickedWeakestFirstAndSavesAfterEachConfirmedRaise() throws IOException {
        List<Integer> flintLevelsSeen = new ArrayList<>();

        Outcome outcome = run(2, 2, (raise, live) -> {
            flintLevelsSeen.add(savedLevel("Flint", "Belt"));
            return reached(raise);
        });

        assertNull(outcome.stop());
        assertEquals(2, outcome.raises());
        assertEquals(List.of("Flint/Belt", "Flint/Belt"), navigator.opened);
        assertEquals(List.of(40, 42), flintLevelsSeen, "the first raise was on disk before the second began");
        assertEquals(44, saved("Flint", "Belt").enhancement());
        assertEquals(10, saved("Molly", "Boots").enhancement(), "unticked hero untouched");
        assertEquals(2, navigator.closes);
    }

    @Test
    void theScanDateIsClearedBeforeTheFirstLivePressAndComesBackAfterwards() throws IOException {
        List<String> seen = new ArrayList<>();

        Outcome outcome = run(5, 1, (raise, live) -> {
            seen.add(scanDate());
            return reached(raise);
        });

        assertEquals(java.util.Collections.singletonList(null), seen);
        assertEquals(NOW.toString(), store.loadGear(PROFILE).orElseThrow().scannedAt());
        assertEquals(NOW.toString(), outcome.state().scannedAt());
    }

    @Test
    void anInterruptedRaiseLeavesAScanDueAndPropagates() throws IOException {
        assertThrows(IllegalStateException.class, () -> run(5, 3, (raise, live) -> {
            throw new IllegalStateException("preempted");
        }));

        GearState after = store.loadGear(PROFILE).orElseThrow();
        assertNull(after.scannedAt());
        assertTrue(GearScan.isDue(after, NOW, 7));
    }

    @Test
    void aLevelThatCouldNotBeSavedLeavesTheScanDue() throws IOException {
        Outcome outcome = run(5, 3, (raise, live) -> {
            try {
                Files.createDirectories(gearFile().resolveSibling(gearFile().getFileName() + ".tmp"));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            return reached(raise);
        });

        assertTrue(outcome.stop().startsWith("could not save the gear state"), outcome.stop());
        assertTrue(outcome.saveFailed());
        assertNull(scanDate());
    }

    @Test
    void aDryRunLeavesTheScanDateAlone() throws IOException {
        List<String> seen = new ArrayList<>();

        GearEnhanceStep.run(GEAR, new Settings(TICKED, 5, 3, false, STOP_BY), navigator, (raise, live) -> {
            seen.add(scanDate());
            return new GearEnhancePass.Outcome(raise.from(), 0, Stop.DRY_RUN, "would raise");
        }, store, PROFILE, () -> now);

        assertEquals(List.of(NOW.toString()), seen);
    }

    @Test
    void aNullEnhancementOrMasteryIsSkippedNotTreatedAsZero() throws IOException {
        writeGear("""
                {"pieces":[
                  {"hero":"Mia","slot":"Goggles","enhancement":null,"masteryPercent":60,"onRole":true},
                  {"hero":"Mia","slot":"Boots","enhancement":3,"masteryPercent":null,"onRole":true},
                  {"hero":"Flint","slot":"Belt","masteryPercent":10,"onRole":true},
                  {"hero":"Flint","slot":"Gauntlets","enhancement":80,"masteryPercent":40,"onRole":true}],
                 "heroWeights":{"Mia":1.0,"Flint":1.0}}""");
        GearState loaded = store.loadGear(PROFILE).orElseThrow();
        assertNull(loaded.pieces().getFirst().enhancement());
        assertNull(loaded.pieces().get(1).masteryPercent());

        Outcome outcome = GearEnhanceStep.run(loaded, new Settings(TICKED, 5, 3, true, STOP_BY), navigator, REACHES, store, PROFILE, () -> now);

        assertEquals(List.of("Flint/Gauntlets", "Flint/Gauntlets", "Flint/Gauntlets"), navigator.opened);
        assertEquals(3, outcome.raises());
        GearState after = store.loadGear(PROFILE).orElseThrow();
        assertNull(after.pieces().getFirst().enhancement(), "a save keeps the piece unread");
        assertNull(after.pieces().get(1).masteryPercent(), "a save keeps the piece unread");
    }

    @Test
    void outOfTimeEndsTheStepWithoutAStop() {
        now = STOP_BY;

        Outcome outcome = run(5, 3, REACHES);

        assertNull(outcome.stop());
        assertNotNull(outcome.note());
        assertEquals(0, outcome.raises());
        assertTrue(navigator.opened.isEmpty());
    }

    @Test
    void timeRunningOutPartWayKeepsWhatWasDone() throws IOException {
        Outcome outcome = run(2, 3, (raise, live) -> {
            now = STOP_BY;
            return reached(raise);
        });

        assertNull(outcome.stop());
        assertEquals(1, outcome.raises());
        assertEquals(42, saved("Flint", "Belt").enhancement());
    }

    @Test
    void thePassRunningOutOfTimeSavesPartialLevelsAndLetsTheForgeGo() throws IOException {
        Outcome outcome = run(5, 3, (raise, live) -> new GearEnhancePass.Outcome(raise.from() + 2, 2, Stop.DEADLINE, "time cap"));

        assertNull(outcome.stop());
        assertEquals("time cap", outcome.note());
        assertEquals(42, saved("Flint", "Belt").enhancement());
        assertEquals(1, navigator.closes);
        assertEquals(NOW.toString(), scanDate());
    }

    @Test
    void runningOutOfFodderLetsTheForgeGo() {
        Outcome outcome = run(5, 3, (raise, live) -> new GearEnhancePass.Outcome(raise.from(), 0, Stop.QUICK_SELECT_SHORT, "short"));

        assertNull(outcome.stop());
        assertFalse(outcome.pressUnconfirmed());
        assertEquals(1, navigator.closes);
    }

    @ParameterizedTest
    @EnumSource(value = Stop.class, names = { "WRONG_BUTTON", "WRONG_PIECE", "WRONG_LEVEL", "DIALOG_UNREADABLE",
            "PREVIEW_MISMATCH", "UNSAFE_FODDER", "LIST_SCAN_FAILED", "SELECTION_CHANGED", "MARKER_UNSAVED" })
    void anyOtherStopEndsTheNightKeepsConfirmedLevelsAndIsNotAnUnconfirmedPress(Stop stop) throws IOException {
        Outcome outcome = run(5, 3, (raise, live) -> new GearEnhancePass.Outcome(raise.from() + 1, 1, stop, "nope"));

        assertEquals(stop + ": nope", outcome.stop());
        assertFalse(outcome.pressUnconfirmed());
        assertEquals(41, saved("Flint", "Belt").enhancement());
        assertEquals(1, outcome.raises());
        assertEquals(0, navigator.closes);
        assertEquals(NOW.toString(), scanDate());
    }

    @Test
    void anUnconfirmedPressSavesNoLevelClearsTheScanDateAndTapsNoMore() throws IOException {
        Outcome outcome = run(5, 3, (raise, live) -> new GearEnhancePass.Outcome(raise.from() + 2, 2, Stop.PRESS_UNCONFIRMED, "no read"));

        assertTrue(outcome.pressUnconfirmed());
        assertEquals("PRESS_UNCONFIRMED: no read", outcome.stop());
        assertEquals(40, saved("Flint", "Belt").enhancement(), "no new level, even for levels confirmed earlier in this raise");
        assertEquals(0, outcome.raises());
        assertNull(scanDate());
        assertEquals(0, navigator.closes);
        assertEquals(1, navigator.opened.size(), "the remaining raises are skipped");
    }

    @Test
    void anUnconfirmedPressOnALaterRaiseKeepsEarlierRaisesAndStillClearsTheScanDate() throws IOException {
        int[] calls = { 0 };

        Outcome outcome = run(2, 3, (raise, live) -> ++calls[0] == 1 ? reached(raise)
                : new GearEnhancePass.Outcome(raise.from(), 0, Stop.PRESS_UNCONFIRMED, "no read"));

        assertTrue(outcome.pressUnconfirmed());
        assertEquals(42, saved("Flint", "Belt").enhancement());
        assertNull(scanDate());
    }

    @Test
    void aNavigationAbortIsAStop() {
        navigator.abort = true;

        Outcome outcome = run(5, 3, REACHES);

        assertEquals("wrong screen", outcome.stop());
    }

    @Test
    void aFailedCloseAfterARaiseIsAStopButTheRaiseIsSaved() throws IOException {
        navigator.abortOnClose = true;

        Outcome outcome = run(2, 3, REACHES);

        assertEquals("no grid", outcome.stop());
        assertEquals(42, saved("Flint", "Belt").enhancement());
    }

    @Test
    void aDryRunFinishesWithoutSaving() throws IOException {
        byte[] before = Files.readAllBytes(gearFile());
        boolean[] liveSeen = { true };

        Outcome outcome = GearEnhanceStep.run(GEAR, new Settings(TICKED, 5, 3, false, STOP_BY), navigator, (raise, live) -> {
            liveSeen[0] = live;
            return new GearEnhancePass.Outcome(raise.from(), 0, Stop.DRY_RUN, "dry run");
        }, store, PROFILE, () -> now);

        assertEquals(false, liveSeen[0]);
        assertNull(outcome.stop());
        assertEquals("dry run", outcome.note());
        assertEquals(new String(before), new String(Files.readAllBytes(gearFile())));
        assertEquals(1, navigator.closes);
    }

    @Test
    void aPanelThatDisagreesCorrectsTheSaveAndRaisesNothing() throws IOException {
        navigator.panelLevel = 47;
        int[] calls = { 0 };

        Outcome outcome = run(5, 3, (raise, live) -> {
            calls[0]++;
            return reached(raise);
        });

        assertEquals(0, calls[0]);
        assertNull(outcome.stop());
        assertEquals(47, saved("Flint", "Belt").enhancement());
    }

    @Test
    void unusableSettingsDoNothing() {
        assertNotNull(run(0, 3, REACHES).note());
        assertNotNull(run(5, 0, REACHES).note());
        assertTrue(navigator.opened.isEmpty());
    }

    private static GearEnhancePass.Outcome reached(Raise raise) {
        return new GearEnhancePass.Outcome(raise.to(), raise.to() - raise.from(), Stop.TARGET_REACHED, "reached");
    }

    private Outcome run(int step, int maxRaises, Raiser raiser) {
        return GearEnhanceStep.run(GEAR, new Settings(TICKED, step, maxRaises, true, STOP_BY), navigator, raiser, store, PROFILE, () -> now);
    }

    private GearPiece saved(String hero, String slot) throws IOException {
        return store.loadGear(PROFILE).orElseThrow().pieces().stream()
                .filter(p -> p.hero().equals(hero) && p.slot().equals(slot)).findFirst().orElseThrow();
    }

    private int savedLevel(String hero, String slot) {
        try {
            return saved(hero, slot).enhancement();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private String scanDate() {
        try {
            return store.loadGear(PROFILE).orElseThrow().scannedAt();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private Path gearFile() {
        return workspace.resolve("squads").resolve("hero-gear-" + PROFILE + ".json");
    }

    private void writeGear(String json) throws IOException {
        Files.writeString(gearFile(), json);
    }

    /** Reports each piece at its currently saved level unless told otherwise. */
    private final class FakeNavigator implements GearEnhanceStep.Navigator {
        final List<String> opened = new ArrayList<>();
        Integer panelLevel;
        boolean abort;
        boolean abortOnClose;
        int closes;

        @Override
        public GearDetails open(String hero, String slot) {
            if (abort) throw new Abort("wrong screen");
            opened.add(hero + "/" + slot);
            return new GearDetails("Guardian " + slot, panelLevel != null ? panelLevel : savedLevel(hero, slot), "Enhance");
        }

        @Override
        public void closeToGrid() {
            if (abortOnClose) throw new Abort("no grid");
            closes++;
        }
    }
}
