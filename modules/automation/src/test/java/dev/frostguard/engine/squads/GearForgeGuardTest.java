package dev.frostguard.engine.squads;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import dev.frostguard.engine.squads.ForgeDialogReader.ForgeDialog;
import dev.frostguard.engine.squads.GearForgeGuard.Verdict;
import dev.frostguard.engine.squads.GearRanker.Upgrade;

class GearForgeGuardTest {

    private static final Upgrade BOOTS = new Upgrade("Bahiti", "Boots", 42, 44, 10, 57.75, 58.56);

    private static Verdict verdict(ForgeDialog dialog) {
        return GearForgeGuard.before(BOOTS, dialog).verdict();
    }

    @Test
    void buysWhenEverythingMatchesAndTheBalanceCoversIt() {
        assertEquals(Verdict.BUY, verdict(new ForgeDialog("Sniper Boots", 42, 44, 13, 10)));
    }

    @Test
    void savesWhenTheBalanceIsShort() {
        assertEquals(Verdict.SAVE, verdict(new ForgeDialog("Sniper Boots", 42, 44, 3, 10)));
    }

    @Test
    void stopsOnAnUnreadableDialog() {
        assertEquals(Verdict.STOP, verdict(null));
    }

    @Test
    void stopsOnTheWrongPiece() {
        assertEquals(Verdict.STOP, verdict(new ForgeDialog("Sniper Goggles", 42, 44, 13, 10)));
    }

    @Test
    void stopsWhenTheCostIsNotTheOnePlanned() {
        assertEquals(Verdict.STOP, verdict(new ForgeDialog("Sniper Boots", 42, 44, 40, 20)));
    }

    @Test
    void resyncsInsteadOfBuyingWhenMasteryDisagrees() {
        assertEquals(Verdict.RESYNC, verdict(new ForgeDialog("Sniper Boots", 44, 46, 13, 10)));
    }

    @Test
    void aPressThatDidWhatItPromisedHasNoProblem() {
        assertNull(GearForgeGuard.afterProblem(new ForgeDialog("Sniper Boots", 40, 42, 13, 10),
                new ForgeDialog("Sniper Boots", 42, 44, 3, 10)));
    }

    @Test
    void aDialogThatHasNotCaughtUpStillShowsOld() {
        ForgeDialog before = new ForgeDialog("Sniper Boots", 40, 42, 13, 10);

        assertTrue(GearForgeGuard.stillShowsOld(before, before));
        assertTrue(GearForgeGuard.stillShowsOld(before, new ForgeDialog("Sniper Boots", 42, 44, 13, 12)));
        assertTrue(GearForgeGuard.stillShowsOld(before, new ForgeDialog("Sniper Boots", 40, 42, 3, 10)));
        assertFalse(GearForgeGuard.stillShowsOld(before, new ForgeDialog("Sniper Boots", 42, 44, 3, 12)));
        assertFalse(GearForgeGuard.stillShowsOld(before, new ForgeDialog("Sniper Goggles", 40, 42, 13, 10)));
        assertFalse(GearForgeGuard.stillShowsOld(before, null));
    }

    @Test
    void flagsAPressThatChargedTheWrongAmount() {
        assertNotNull(GearForgeGuard.afterProblem(new ForgeDialog("Sniper Boots", 40, 42, 13, 10),
                new ForgeDialog("Sniper Boots", 42, 44, 1, 10)));
    }

    @Test
    void flagsAPressThatDidNotRaiseMastery() {
        assertNotNull(GearForgeGuard.afterProblem(new ForgeDialog("Sniper Boots", 40, 42, 13, 10),
                new ForgeDialog("Sniper Boots", 40, 42, 13, 10)));
    }

    @Test
    void savesWhenBuyingWouldBreakTheReserve() {
        ForgeDialog dialog = new ForgeDialog("Sniper Boots", 42, 44, 13, 10);

        assertEquals(Verdict.SAVE, GearForgeGuard.before(BOOTS, dialog, 5).verdict());
        assertEquals(Verdict.BUY, GearForgeGuard.before(BOOTS, dialog, 3).verdict());
    }
}
