package dev.frostguard.engine.squads;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.frostguard.api.squads.HeroGear;
import dev.frostguard.engine.squads.GearRanker.GearPiece;
import dev.frostguard.engine.squads.GearRanker.GearState;
import dev.frostguard.engine.squads.GearScan.Abort;
import dev.frostguard.engine.squads.QuickEquipPass.GearTab;
import dev.frostguard.engine.squads.QuickEquipPass.Outcome;

class QuickEquipPassTest {

    private static final long PROFILE = 7;
    private static final String SCANNED_AT = Instant.parse("2026-11-10T07:45:00Z").toString();
    private static final String[] FIVE_LEADS_AND_JESSIE = { "Mia", "Flint", "Molly", "Bahiti", "Sergey", "Jessie" };
    private static final List<HeroGear> FULL = List.of(new HeroGear(1, 100, 6), new HeroGear(2, 55, 1),
            new HeroGear(3, 50, null), new HeroGear(4, 81, 5));

    @TempDir
    Path workspace;

    private HeroGearStore store;

    @BeforeEach
    void saveGear() throws IOException {
        store = new HeroGearStore(workspace);
        store.saveGear(PROFILE, new GearState(List.of(new GearPiece("Mia", "Boots", 81, 50, true)),
                Map.of("Mia", 1.0), SCANNED_AT));
    }

    @Test
    void pressesOnlyLitHeroesInWalkOrderAndEndsAtNoGearTab() throws IOException {
        Fake fake = new Fake("Mia", "Flint", "Molly", "Bahiti", "Sergey", "Jessie").lit("Molly", "Jessie");

        Outcome outcome = run(fake);

        assertEquals(List.of("Molly", "Jessie"), fake.pressed);
        assertEquals(6, outcome.heroes());
        assertEquals(2, outcome.presses());
        assertFalse(outcome.stopNight());
        assertNull(outcome.problem());
        assertNull(outcome.truncated());
        assertEquals(3, fake.opens, "baseline, walk, re-read");
        assertTrue(store.loadLeadBaseline(PROFILE).isEmpty(), "a clean re-read clears the baseline");
    }

    @Test
    void noLitButtonMeansNoRereadAndTheSavedScanStands() throws IOException {
        Fake fake = new Fake("Mia", "Flint", "Molly", "Bahiti", "Sergey", "Jessie");

        Outcome outcome = run(fake);

        assertEquals(0, outcome.presses());
        assertEquals(2, fake.opens, "baseline and walk only");
        assertTrue(store.loadLeadBaseline(PROFILE).isEmpty(), "nothing pressed, so nothing to keep");
        assertEquals(SCANNED_AT, store.loadGear(PROFILE).orElseThrow().scannedAt());
    }

    @Test
    void savedGearIsDistrustedBeforeTheFirstPress() throws IOException {
        Fake fake = new Fake("Mia", "Flint", "Molly", "Bahiti", "Sergey", "Jessie").lit("Jessie");
        fake.onPress = () -> {
            try {
                assertNull(store.loadGear(PROFILE).orElseThrow().scannedAt());
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        };

        run(fake);

        assertEquals(List.of("Jessie"), fake.pressed);
        assertTrue(GearScan.isDue(store.loadGear(PROFILE).orElseThrow(), Instant.now(), 7));
        assertEquals(1, store.loadGear(PROFILE).orElseThrow().pieces().size());
    }

    @Test
    void aLeadThatDropsEnhancementStopsTheNight() {
        assertStops(List.of(new HeroGear(1, 99, 6), FULL.get(1), FULL.get(2), FULL.get(3)));
    }

    @Test
    void aLeadThatDropsMasteryStopsTheNight() {
        assertStops(List.of(new HeroGear(1, 100, 5), FULL.get(1), FULL.get(2), FULL.get(3)));
    }

    @Test
    void aLeadThatLosesItsLevelStopsTheNight() {
        assertStops(List.of(new HeroGear(1, 100, null), FULL.get(1), FULL.get(2), FULL.get(3)));
    }

    @Test
    void aLeadPieceThatGoesMissingStopsTheNight() {
        Fake fake = new Fake(FIVE_LEADS_AND_JESSIE).lit("Jessie");
        fake.after = List.of(new HeroGear(1, 0, 0), FULL.get(1), FULL.get(2), FULL.get(3));

        Outcome outcome = run(fake);

        assertTrue(outcome.stopNight());
        assertTrue(outcome.problem().contains("Mia slot 1 went missing"), outcome.problem());
    }

    @Test
    void aRealLostLevelIsDamage() {
        Fake fake = new Fake(FIVE_LEADS_AND_JESSIE).lit("Jessie");
        fake.after = List.of(new HeroGear(1, 100, 0), FULL.get(1), FULL.get(2), FULL.get(3));

        Outcome outcome = run(fake);

        assertTrue(outcome.problem().contains("Mia slot 1 went down"), outcome.problem());
    }

    @Test
    void aPieceStillUnreadAfterRetriesIsAFailedRereadNotDamage() throws IOException {
        Fake fake = new Fake(FIVE_LEADS_AND_JESSIE).lit("Jessie");
        fake.after = List.of(new HeroGear(1, null, null), FULL.get(1), FULL.get(2), FULL.get(3));

        Outcome outcome = run(fake);

        assertTrue(outcome.stopNight());
        assertTrue(outcome.problem().startsWith("the march leads could not be re-read: Mia slot 1"), outcome.problem());
        assertTrue(store.loadLeadBaseline(PROFILE).isPresent());
    }

    @Test
    void realDamageIsReportedAheadOfAnUnreadPiece() {
        Fake fake = new Fake(FIVE_LEADS_AND_JESSIE).lit("Jessie");
        fake.after = List.of(new HeroGear(1, null, null), new HeroGear(2, 40, 1), FULL.get(2), FULL.get(3));

        Outcome outcome = run(fake);

        assertTrue(outcome.problem().contains("Mia slot 2 went down"), outcome.problem());
    }

    @Test
    void noLevelReadingOnAPieceThatNeverHadOneIsClean() {
        Fake fake = new Fake(FIVE_LEADS_AND_JESSIE).lit("Jessie");
        fake.after = List.of(FULL.get(0), FULL.get(1), new HeroGear(3, 50, null), FULL.get(3));

        Outcome outcome = run(fake);

        assertFalse(outcome.stopNight(), String.valueOf(outcome.problem()));
    }

    @Test
    void aBaselineLevelThatDidNotReadIsTakenFromTheSavedScan() throws IOException {
        Fake fake = new Fake(FIVE_LEADS_AND_JESSIE).lit("Jessie");
        fake.before = List.of(FULL.get(0), FULL.get(1), FULL.get(2), new HeroGear(4, 81, null));
        fake.after = List.of(FULL.get(0), FULL.get(1), FULL.get(2), new HeroGear(4, 81, 0));
        fake.onPress = () -> {
            try {
                assertEquals(5, store.loadLeadBaseline(PROFILE).orElseThrow().leads().get("Mia").get(3).mastery());
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        };

        Outcome outcome = run(fake);

        assertTrue(outcome.stopNight());
        assertTrue(outcome.problem().contains("Mia slot 4 went down"), outcome.problem());
    }

    @Test
    void aBaselineWithNoLevelStaysNoneWhenTheSavedScanAgrees() throws IOException {
        store.saveGear(PROFILE, new GearState(List.of(new GearPiece("Mia", "Belt", 50, 0, false)), Map.of("Mia", 1.0), SCANNED_AT));
        Fake fake = new Fake(FIVE_LEADS_AND_JESSIE).lit("Jessie");
        fake.after = List.of(FULL.get(0), FULL.get(1), new HeroGear(3, 50, 0), FULL.get(3));

        Outcome outcome = run(fake);

        assertFalse(outcome.stopNight(), String.valueOf(outcome.problem()));
    }

    @Test
    void aCleanRecheckReplacesTheBaselineWithWhatItRead() throws IOException {
        Fake interrupted = preempted();
        assertThrows(IllegalStateException.class, () -> run(interrupted));

        Fake retry = new Fake(FIVE_LEADS_AND_JESSIE).lit("Jessie");
        retry.before = List.of(new HeroGear(1, 120, 6), FULL.get(1), FULL.get(2), FULL.get(3));
        retry.after = List.of(new HeroGear(1, 110, 6), FULL.get(1), FULL.get(2), FULL.get(3));
        retry.onPress = () -> {
            try {
                assertEquals(120, store.loadLeadBaseline(PROFILE).orElseThrow().leads().get("Mia").getFirst().enhancement());
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        };

        Outcome outcome = run(retry);

        assertTrue(outcome.stopNight(), "110 is only damage against the fresh 120, not the old 100");
        assertTrue(outcome.problem().contains("Mia slot 1 went down"), outcome.problem());
    }

    @Test
    void recheckOnlyComparesAndPressesNothing() throws IOException {
        Fake interrupted = preempted();
        assertThrows(IllegalStateException.class, () -> run(interrupted));

        Fake check = new Fake(FIVE_LEADS_AND_JESSIE).lit("Jessie");

        Outcome outcome = QuickEquipPass.recheck(check, store, PROFILE);

        assertFalse(outcome.stopNight());
        assertTrue(check.pressed.isEmpty());
        assertEquals(1, check.opens);
        assertTrue(store.loadLeadBaseline(PROFILE).isEmpty());
    }

    @Test
    void recheckWithNoBaselineTouchesNothing() {
        Fake check = new Fake(FIVE_LEADS_AND_JESSIE);

        assertFalse(QuickEquipPass.recheck(check, store, PROFILE).stopNight());

        assertEquals(0, check.opens);
    }

    @Test
    void outOfTimeBeforeTheWalkOpensNothing() {
        Fake fake = new Fake(FIVE_LEADS_AND_JESSIE).lit("Jessie");
        fake.pastStopAfter = 0;

        Outcome outcome = run(fake);

        assertFalse(outcome.stopNight());
        assertEquals(0, fake.opens);
    }

    @Test
    void runningOutOfTimeEndsTheWalkAndStillRereadsAfterAPress() {
        Fake fake = new Fake("Mia", "Flint", "Molly", "Bahiti", "Sergey", "Jessie", "Philly").lit("Jessie", "Philly");
        fake.pastStopWhenPressed = true;

        Outcome outcome = run(fake);

        assertEquals(List.of("Jessie"), fake.pressed);
        assertFalse(outcome.stopNight(), String.valueOf(outcome.problem()));
        assertEquals(5, fake.rereads);
        assertTrue(outcome.truncated().contains("out of time"), outcome.truncated());
    }

    @Test
    void aLeadThatImprovesIsFine() {
        Fake fake = new Fake("Mia", "Flint", "Molly", "Bahiti", "Sergey", "Jessie").lit("Jessie");
        fake.after = List.of(new HeroGear(1, 120, 6), FULL.get(1), new HeroGear(3, 50, 2), FULL.get(3));

        Outcome outcome = run(fake);

        assertFalse(outcome.stopNight());
        assertNull(outcome.problem());
    }

    @Test
    void aLeadMissingFromTheRereadStopsTheNight() {
        Fake fake = new Fake("Mia", "Flint", "Molly", "Bahiti", "Sergey", "Jessie").lit("Jessie");
        fake.dropAfterPress = "Bahiti";

        Outcome outcome = run(fake);

        assertTrue(outcome.stopNight());
        assertNotNull(outcome.problem());
    }

    @Test
    void pressesAreCappedAndTheLeadsAreStillChecked() {
        List<String> names = new ArrayList<>(QuickEquipPass.LEADS);
        for (int i = 0; i < QuickEquipPass.MAX_PRESSES + 5; i++) names.add("Hero" + i);
        Fake fake = new Fake(names.toArray(String[]::new));
        fake.lit(names.subList(QuickEquipPass.LEADS.size(), names.size()).toArray(String[]::new));

        Outcome outcome = run(fake);

        assertEquals(QuickEquipPass.MAX_PRESSES, outcome.presses());
        assertEquals(QuickEquipPass.MAX_PRESSES, fake.pressed.size());
        assertEquals(3, fake.opens);
        assertEquals(names.size(), outcome.heroes(), "the rest are walked without pressing, to name them");
        assertTrue(outcome.truncated().contains("Hero" + QuickEquipPass.MAX_PRESSES), outcome.truncated());
        assertTrue(outcome.truncated().contains("Hero" + (QuickEquipPass.MAX_PRESSES + 4)), outcome.truncated());
    }

    @Test
    void aLitButtonOnAnUnreadNameIsNotPressed() {
        Fake fake = new Fake("Mia", "Flint", "Molly", "Bahiti", "Sergey", null, "Jessie").lit((String) null);

        Outcome outcome = run(fake);

        assertTrue(fake.pressed.isEmpty());
        assertEquals(7, outcome.heroes());
    }

    @Test
    void aHeroComingUpAgainEndsTheWalkWithoutASecondPress() {
        Fake fake = new Fake("Mia", "Flint", "Molly", "Bahiti", "Sergey", "Jessie", "Mia", "Jessie").lit("Jessie");

        Outcome outcome = run(fake);

        assertEquals(List.of("Jessie"), fake.pressed);
        assertEquals(6, outcome.heroes());
        assertTrue(outcome.truncated().contains("Mia came up again"), outcome.truncated());
    }

    @Test
    void aMissingLeadBeforeTheWalkPressesNothingAndLeavesTheNightAlone() throws IOException {
        Fake fake = new Fake("Mia", "Flint", "Jessie").lit("Jessie");

        Outcome outcome = run(fake);

        assertTrue(fake.pressed.isEmpty());
        assertFalse(outcome.stopNight());
        assertNotNull(outcome.problem());
        assertEquals(SCANNED_AT, store.loadGear(PROFILE).orElseThrow().scannedAt());
    }

    @Test
    void anUnreadableLeadTileBeforeTheWalkPressesNothing() {
        Fake fake = new Fake("Mia", "Flint", "Molly", "Bahiti", "Sergey", "Jessie").lit("Jessie");
        fake.before = List.of(new HeroGear(1, null, null), FULL.get(1), FULL.get(2), FULL.get(3));

        Outcome outcome = run(fake);

        assertTrue(fake.pressed.isEmpty());
        assertFalse(outcome.stopNight());
        assertNotNull(outcome.problem());
    }

    @Test
    void aWalkThatDiesBeforeAnyPressLeavesTheNightAlone() {
        Fake fake = new Fake("Mia", "Flint", "Molly", "Bahiti", "Sergey", "Jessie");
        fake.abortOnOpen = 2;

        Outcome outcome = run(fake);

        assertFalse(outcome.stopNight());
        assertNotNull(outcome.problem());
    }

    @Test
    void aWalkThatDiesAfterAPressWithLeadsUnverifiedStopsTheNight() {
        Fake fake = new Fake("Mia", "Flint", "Molly", "Bahiti", "Sergey", "Jessie", "Philly").lit("Jessie");
        fake.abortAfterPress = true;

        Outcome outcome = run(fake);

        assertTrue(outcome.stopNight());
        assertEquals(1, outcome.presses());
        assertTrue(outcome.problem().contains("could not be re-read"), outcome.problem());
    }

    @Test
    void anOddButtonLookIsReportedAndNotPressed() {
        Fake fake = new Fake("Mia", "Flint", "Molly", "Bahiti", "Sergey", "Jessie");
        fake.oddLooks.put("Jessie", "button averages 1,2,3");

        Outcome outcome = run(fake);

        assertEquals(List.of("Jessie: button averages 1,2,3"), outcome.oddLooks());
        assertTrue(fake.pressed.isEmpty());
    }

    @Test
    void everyHeroWithAGearSetIsGuarded() {
        assertEquals(GearForgeGuard.GEAR_SET.keySet(), Set.copyOf(QuickEquipPass.LEADS));
    }

    @Test
    void mollyAndSergeyLosingGearStopTheNight() {
        for (String lead : List.of("Molly", "Sergey")) {
            Fake fake = new Fake(FIVE_LEADS_AND_JESSIE).lit("Jessie");
            fake.damagedLead = lead;
            fake.after = List.of(new HeroGear(1, 99, 6), FULL.get(1), FULL.get(2), FULL.get(3));

            Outcome outcome = run(fake);

            assertTrue(outcome.stopNight(), lead);
            assertTrue(outcome.problem().contains(lead + " slot 1"), outcome.problem());
            clearBaseline();
        }
    }

    @Test
    void theBaselineIsOnDiskBeforeTheFirstPress() throws IOException {
        Fake fake = new Fake(FIVE_LEADS_AND_JESSIE).lit("Jessie");
        boolean[] saved = { false };
        fake.onPress = () -> {
            try {
                saved[0] = store.loadLeadBaseline(PROFILE).orElseThrow().leads().keySet().containsAll(QuickEquipPass.LEADS);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        };

        run(fake);

        assertTrue(saved[0]);
    }

    @Test
    void anExceptionThatIsNotAnAbortKeepsTheBaselineAndLeavesAScanDue() throws IOException {
        Fake fake = preempted();

        assertThrows(IllegalStateException.class, () -> run(fake));

        assertTrue(store.loadLeadBaseline(PROFILE).isPresent());
        assertNull(store.loadGear(PROFILE).orElseThrow().scannedAt());
    }

    @Test
    void anExceptionBeforeAnyPressSavesNoBaseline() throws IOException {
        Fake fake = new Fake(FIVE_LEADS_AND_JESSIE);
        fake.throwOnOpen = 2;

        assertThrows(IllegalStateException.class, () -> run(fake));

        assertTrue(store.loadLeadBaseline(PROFILE).isEmpty());
        assertEquals(SCANNED_AT, store.loadGear(PROFILE).orElseThrow().scannedAt());
    }

    @Test
    void aRetryComparesAgainstTheSavedBaselineNotANewReading() throws IOException {
        Fake interrupted = preempted();
        assertThrows(IllegalStateException.class, () -> run(interrupted));

        Fake retry = new Fake(FIVE_LEADS_AND_JESSIE).lit("Jessie");
        retry.before = List.of(new HeroGear(1, 90, 6), FULL.get(1), FULL.get(2), FULL.get(3));

        Outcome outcome = run(retry);

        assertTrue(outcome.stopNight());
        assertTrue(outcome.problem().contains("Mia slot 1"), outcome.problem());
        assertTrue(retry.pressed.isEmpty(), "nothing is pressed on top of damage");
        assertEquals(0, retry.baselineReads, "no new baseline is taken");
        assertTrue(store.loadLeadBaseline(PROFILE).isPresent(), "kept until a clean re-read");
    }

    @Test
    void aCleanRetryClearsTheBaselineAndCarriesOn() throws IOException {
        Fake interrupted = preempted();
        assertThrows(IllegalStateException.class, () -> run(interrupted));

        Fake retry = new Fake(FIVE_LEADS_AND_JESSIE).lit("Jessie");

        Outcome outcome = run(retry);

        assertFalse(outcome.stopNight());
        assertEquals(List.of("Jessie"), retry.pressed);
        assertEquals(0, retry.baselineReads);
        assertEquals(3, retry.opens, "check, walk, re-read");
        assertTrue(store.loadLeadBaseline(PROFILE).isEmpty());
    }

    @Test
    void aRetryThatCannotRereadStopsTheNightAndKeepsTheBaseline() throws IOException {
        Fake interrupted = preempted();
        assertThrows(IllegalStateException.class, () -> run(interrupted));

        Fake retry = new Fake(FIVE_LEADS_AND_JESSIE).lit("Jessie");
        retry.abortOnOpen = 1;

        Outcome outcome = run(retry);

        assertTrue(outcome.stopNight());
        assertTrue(retry.pressed.isEmpty());
        assertTrue(store.loadLeadBaseline(PROFILE).isPresent());
    }

    @Test
    void theAfterPressCheckUsesTheStrictReread() {
        Fake fake = new Fake(FIVE_LEADS_AND_JESSIE).lit("Jessie");

        run(fake);

        assertEquals(5, fake.baselineReads);
        assertEquals(5, fake.rereads);
    }

    @Test
    void aWalkThatDiesNamesTheLeadsNotReached() {
        Fake fake = new Fake(FIVE_LEADS_AND_JESSIE);
        fake.abortOnNextInWalk = true;

        Outcome outcome = run(fake);

        assertTrue(outcome.truncated().contains("[Flint, Molly, Bahiti, Sergey]"), outcome.truncated());
    }

    private Fake preempted() {
        Fake fake = new Fake(FIVE_LEADS_AND_JESSIE).lit("Jessie");
        fake.onPress = () -> {
            throw new IllegalStateException("preempted");
        };
        return fake;
    }

    private void clearBaseline() {
        try {
            store.clearLeadBaseline(PROFILE);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private void assertStops(List<HeroGear> after) {
        Fake fake = new Fake("Mia", "Flint", "Molly", "Bahiti", "Sergey", "Jessie").lit("Jessie");
        fake.after = after;

        Outcome outcome = run(fake);

        assertTrue(outcome.stopNight(), String.valueOf(outcome));
        assertTrue(outcome.problem().contains("Mia slot 1"), outcome.problem());
    }

    private Outcome run(Fake fake) {
        return QuickEquipPass.run(fake, store, PROFILE);
    }

    /** Scripted walk; {@code after} is what the leads read once anything has been pressed. */
    private static final class Fake implements QuickEquipPass.Screens {
        final List<String> pressed = new ArrayList<>();
        final Map<String, String> oddLooks = new LinkedHashMap<>();
        List<HeroGear> before = FULL;
        List<HeroGear> after = FULL;
        String dropAfterPress;
        Runnable onPress = () -> { };
        String damagedLead = "Mia";
        int abortOnOpen = -1;
        int throwOnOpen = -1;
        boolean abortOnNextInWalk;
        int baselineReads;
        int rereads;
        boolean abortAfterPress;
        int pastStopAfter = -1;
        boolean pastStopWhenPressed;
        int opens;
        private final List<String> names;
        private final List<String> lit = new ArrayList<>();
        private int index;
        private boolean onGrid = true;

        Fake(String... names) {
            this.names = new ArrayList<>(java.util.Arrays.asList(names));
        }

        Fake lit(String... heroes) {
            lit.addAll(java.util.Arrays.asList(heroes));
            return this;
        }

        @Override
        public GearTab openFirstHero() {
            opens++;
            if (opens == abortOnOpen) throw new Abort("forced at open " + opens);
            if (opens == throwOnOpen) throw new IllegalStateException("preempted");
            if (abortAfterPress && !pressed.isEmpty()) throw new Abort("forced after press");
            assertTrue(onGrid, "opened off the grid");
            onGrid = false;
            index = 0;
            return tab();
        }

        @Override
        public GearTab nextHero(String current) {
            assertFalse(onGrid, "arrow on the grid");
            if (abortOnNextInWalk && opens == 2) throw new Abort("forced in the walk");
            if (abortAfterPress && !pressed.isEmpty()) throw new Abort("forced after press");
            index++;
            if (dropAfterPress != null && !pressed.isEmpty() && index < names.size()
                    && dropAfterPress.equals(names.get(index))) {
                index++;
            }
            return index < names.size() ? tab() : null;
        }

        private GearTab tab() {
            String name = names.get(index);
            return new GearTab(name, lit.contains(name) && !pressed.contains(name), oddLooks.get(name));
        }

        @Override
        public List<HeroGear> readGear(String hero) {
            baselineReads++;
            return gear(hero);
        }

        @Override
        public List<HeroGear> rereadGear(String hero) {
            rereads++;
            return gear(hero);
        }

        private List<HeroGear> gear(String hero) {
            assertEquals(names.get(index), hero);
            boolean damaged = damagedLead.equals(hero);
            return !pressed.isEmpty() && damaged ? after : damaged ? before : FULL;
        }

        @Override
        public boolean press(String hero) {
            onPress.run();
            pressed.add(hero);
            return true;
        }

        @Override
        public void closeToGrid() {
            onGrid = true;
        }

        @Override
        public boolean pastStop() {
            return opens == pastStopAfter || pastStopWhenPressed && !pressed.isEmpty();
        }
    }
}
