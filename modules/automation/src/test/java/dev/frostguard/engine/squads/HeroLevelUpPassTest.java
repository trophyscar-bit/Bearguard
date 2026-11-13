package dev.frostguard.engine.squads;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.api.squads.HeroGear;
import dev.frostguard.engine.squads.ForgeDialogReader.ForgeDialog;
import dev.frostguard.engine.squads.ForgeDialogReader.GearDetails;
import dev.frostguard.engine.squads.GearScan.HeroPage;

/** Real frames of Mia's Stats tab: Lv 78 with the balance green, and Lv 79 after the level-up with it red. */
class HeroLevelUpPassTest {

    private static final String AFFORDABLE_78 = "hero-upgrade-mia-affordable";
    private static final String SHORT_79 = "scan-mia";

    private static Map<String, Integer> levels(Object... pairs) {
        Map<String, Integer> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) map.put((String) pairs[i], (Integer) pairs[i + 1]);
        return map;
    }

    @Test
    void anAffordableLowestHeroIsUpgradedOnceAndTheNewLevelIsConfirmedOnScreen() throws Exception {
        Rig rig = new Rig(AFFORDABLE_78).after(PointData.class.cast(HeroLevelUpPass.UPGRADE), SHORT_79);

        HeroLevelUpPass.Outcome outcome = new HeroLevelUpPass(new Walk("Mia"), rig).run(levels("Mia", 78), Set.of("Mia"), true);

        assertNull(outcome.halt(), outcome.halt());
        assertEquals(List.of(HeroLevelUpPass.UPGRADE), rig.taps, "one press, on the Upgrade button and nothing else");
        assertEquals(List.of("Mia Lv 78 to Lv 79 (2100000 XP, balance 2300000)"), outcome.upgraded());
        assertEquals(79, outcome.levels().get("Mia"));
        assertTrue(outcome.notes().getFirst().contains("saving, needs 2400000 XP"), "after the level-up the balance is short again: " + outcome.notes());
    }

    @Test
    void practiceSaysWhatItWouldDoAndPressesNothing() throws Exception {
        Rig rig = new Rig(AFFORDABLE_78);

        HeroLevelUpPass.Outcome outcome = new HeroLevelUpPass(new Walk("Mia"), rig).run(levels("Mia", 78), Set.of("Mia"), false);

        assertTrue(rig.taps.isEmpty());
        assertEquals(List.of("would upgrade Mia Lv 78 to Lv 79 (2100000 XP, balance 2300000)"), outcome.notes());
        assertTrue(outcome.upgraded().isEmpty());
    }

    @Test
    void aHeroThatIsNotTheLowestWaitsItsTurn() throws Exception {
        Rig rig = new Rig(AFFORDABLE_78);

        HeroLevelUpPass.Outcome outcome = new HeroLevelUpPass(new Walk("Mia"), rig).run(levels("Mia", 78, "Flint", 77), Set.of("Mia", "Flint"), true);

        assertTrue(rig.taps.isEmpty(), "Flint at Lv 77 comes first, so Mia is left alone");
        assertTrue(outcome.notes().getFirst().contains("not the lowest (Lv 77)"), outcome.notes().toString());
    }

    @Test
    void aMaxedHeroIsNeverTheLowestAndIsNeverPressed() throws Exception {
        Rig rig = new Rig(AFFORDABLE_78);

        HeroLevelUpPass.Outcome outcome = new HeroLevelUpPass(new Walk("Mia"), rig).run(levels("Mia", 78, "Molly", 80), Set.of("Mia"), true);

        assertEquals(List.of(HeroLevelUpPass.UPGRADE), rig.taps.subList(0, 1), "Mia is the lowest of those who can still level");
        assertEquals(80, outcome.levels().get("Molly"));
    }

    @Test
    void aBalanceThatIsRedIsNeverPressedWhateverTheNumbersLookLike() throws Exception {
        Rig rig = new Rig(SHORT_79);

        HeroLevelUpPass.Outcome outcome = new HeroLevelUpPass(new Walk("Mia"), rig).run(levels("Mia", 79), Set.of("Mia"), true);

        assertTrue(rig.taps.isEmpty());
        assertTrue(outcome.notes().getFirst().contains("saving, needs 2400000 XP, has 382377"), outcome.notes().toString());
    }

    @Test
    void aPressThatDoesNotRaiseTheLevelStopsWithNoSecondTap() throws Exception {
        Rig rig = new Rig(AFFORDABLE_78);

        HeroLevelUpPass.Outcome outcome = new HeroLevelUpPass(new Walk("Mia"), rig).run(levels("Mia", 78), Set.of("Mia"), true);

        assertEquals(1, rig.taps.size());
        assertNotNull(outcome.halt());
        assertTrue(outcome.halt().contains("Lv 79 was not confirmed"), outcome.halt());
        assertTrue(outcome.upgraded().isEmpty());
    }

    @Test
    void anUnexpectedScreenAfterThePressStopsTheWholePass() throws Exception {
        Rig rig = new Rig(AFFORDABLE_78).after(HeroLevelUpPass.UPGRADE, "scan-grid");

        HeroLevelUpPass.Outcome outcome = new HeroLevelUpPass(new Walk("Mia"), rig).run(levels("Mia", 78), Set.of("Mia"), true);

        assertEquals(1, rig.taps.size());
        assertTrue(outcome.halt().contains("unexpected screen"), outcome.halt());
    }

    @Test
    void aPageForTheWrongHeroIsNotTrusted() throws Exception {
        Rig rig = new Rig(AFFORDABLE_78);

        HeroLevelUpPass.Outcome outcome = new HeroLevelUpPass(new Walk("Mia"), rig).run(levels("Flint", 78), Set.of("Flint"), true);

        assertTrue(rig.taps.isEmpty(), "the frame is Mia's: nothing is pressed for Flint");
        assertNull(outcome.halt());
    }

    @Test
    void theTimeCapStopsBeforeAPress() throws Exception {
        Rig rig = new Rig(AFFORDABLE_78);
        rig.late = true;

        HeroLevelUpPass.Outcome outcome = new HeroLevelUpPass(new Walk("Mia"), rig).run(levels("Mia", 78), Set.of("Mia"), true);

        assertTrue(rig.taps.isEmpty());
        assertTrue(outcome.halt().contains("time cap"), outcome.halt());
    }

    /** Shows a frame, and the frame its tap leads to. */
    private static final class Rig implements HeroLevelUpPass.Device {
        private String screen;
        private final Map<PointData, String> next = new LinkedHashMap<>();
        final List<PointData> taps = new ArrayList<>();
        boolean late;

        Rig(String start) {
            screen = start;
        }

        Rig after(PointData tap, String to) {
            next.put(tap, to);
            return this;
        }

        @Override
        public RawImageData capture() {
            try {
                return HeroUpgradeButtonFrameTest.frame(screen);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public void tap(PointData point, int radius) {
            taps.add(point);
            screen = next.getOrDefault(point, screen);
        }

        @Override
        public void pause(int millis) {
        }

        @Override
        public boolean pastDeadline() {
            return late;
        }
    }

    /** A walk that opens the first card to the named hero; the page itself is the Rig's frame. */
    private static final class Walk implements GearScan.Screens {
        private final String hero;

        Walk(String hero) {
            this.hero = hero;
        }

        @Override
        public int openGrid() {
            return 1;
        }

        @Override
        public String openHero(int index) {
            return hero;
        }

        @Override
        public HeroPage readHero(String name) {
            throw new AssertionError();
        }

        @Override
        public List<HeroGear> openGearTab(String name) {
            throw new AssertionError();
        }

        @Override
        public GearDetails openDetails(String slot) {
            throw new AssertionError();
        }

        @Override
        public ForgeDialog openForge() {
            throw new AssertionError();
        }

        @Override
        public void closeForge() {
            throw new AssertionError();
        }

        @Override
        public void closeDetails() {
            throw new AssertionError();
        }

        @Override
        public void closeHero() {
        }
    }
}
