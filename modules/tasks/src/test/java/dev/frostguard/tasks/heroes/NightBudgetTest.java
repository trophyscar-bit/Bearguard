package dev.frostguard.tasks.heroes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.frostguard.engine.squads.GearEnhancePass;
import dev.frostguard.engine.squads.GearEnhancePass.Stop;
import dev.frostguard.engine.squads.GearEnhancePlanner.Raise;
import dev.frostguard.engine.squads.GearEnhanceStep;
import dev.frostguard.engine.squads.GearScan.Abort;
import dev.frostguard.engine.squads.HeroGearStore;

class NightBudgetTest {

    private static final long PROFILE = 7;
    private static final Instant NOW = Instant.parse("2026-11-10T02:30:00Z");
    private static final LocalTime SLOT = LocalTime.of(2, 30);
    private static final Raise RAISE = new Raise("Mia", "Boots", 81, 85);
    private static final GearEnhanceStep.Raiser REACHES =
            (raise, live) -> new GearEnhancePass.Outcome(raise.to(), raise.levels(), Stop.TARGET_REACHED, "reached");

    @TempDir
    Path workspace;

    private HeroGearStore store;

    @BeforeEach
    void setUp() {
        store = new HeroGearStore(workspace);
    }

    @Test
    void aNewNightGetsTheFullCapAndCounts() throws IOException {
        NightBudget budget = NightBudget.open(store, PROFILE, NOW, SLOT);

        assertEquals(NOW.plus(Duration.ofMinutes(20)), budget.deadline());
        assertEquals(3, budget.buysLeft(3));
        assertEquals(5, budget.levelsLeft(5));
        assertFalse(budget.finished());
    }

    @Test
    void everythingBeforeTheForgeStopsFiveMinutesShortOfTheCap() throws IOException {
        assertEquals(NOW.plus(Duration.ofMinutes(15)), NightBudget.open(store, PROFILE, NOW, SLOT).reserveAt());
    }

    @Test
    void aRerunTheSameNightKeepsTheCapAndWhatWasUsed() throws IOException {
        NightBudget first = NightBudget.open(store, PROFILE, NOW, SLOT);
        first.spendBuy(3, NOW);
        first.spendLevels(3);

        NightBudget retry = NightBudget.open(store, PROFILE, NOW.plus(Duration.ofMinutes(9)), SLOT);

        assertEquals(NOW.plus(NightBudget.TIME_CAP), retry.deadline());
        assertEquals(2, retry.buysLeft(3));
        assertEquals(2, retry.levelsLeft(5));
    }

    @Test
    void aBuyIsOnDiskBeforeItsTap() throws IOException {
        NightBudget.open(store, PROFILE, NOW, SLOT).spendBuy(3, NOW);

        assertEquals(2, NightBudget.open(store, PROFILE, NOW, SLOT).buysLeft(3));
    }

    @Test
    void theNextNightStartsOver() throws IOException {
        NightBudget first = NightBudget.open(store, PROFILE, NOW, SLOT);
        first.spendBuy(3, NOW);
        first.finish();

        NightBudget next = NightBudget.open(store, PROFILE, NOW.plus(Duration.ofHours(24)), SLOT);

        assertFalse(next.finished());
        assertEquals(3, next.buysLeft(3));
        assertEquals(NOW.plus(Duration.ofHours(24)).plus(NightBudget.TIME_CAP), next.deadline());
    }

    @Test
    void aFinishedSlotIsANoOpUntilTheNextSlot() throws IOException {
        NightBudget.open(store, PROFILE, NOW, SLOT).finish();

        assertTrue(NightBudget.open(store, PROFILE, NOW.plus(Duration.ofHours(19)), SLOT).finished());
        assertTrue(NightBudget.open(store, PROFILE, NOW.plus(Duration.ofHours(24)).minusSeconds(1), SLOT).finished());
        assertFalse(NightBudget.open(store, PROFILE, NOW.plus(Duration.ofHours(24)), SLOT).finished());
    }

    @Test
    void aFinishedTestRunOffTheSlotDoesNotBlockTheRealSlot() throws IOException {
        Instant test = Instant.parse("2026-11-10T13:56:00Z");
        NightBudget run = NightBudget.open(store, PROFILE, test, LocalTime.of(13, 55));
        run.spendBuy(3, test);
        run.spendLevels(5);
        run.finish();

        NightBudget real = NightBudget.open(store, PROFILE, Instant.parse("2026-11-11T02:30:00Z"), SLOT);

        assertFalse(real.finished());
        assertEquals(3, real.buysLeft(3));
        assertEquals(5, real.levelsLeft(5));
        assertEquals(Instant.parse("2026-11-11T02:50:00Z"), real.deadline());
    }

    @Test
    void aSlotMovedBackIsANewNightTheSameDay() throws IOException {
        Instant test = Instant.parse("2026-11-10T14:01:00Z");
        NightBudget.open(store, PROFILE, test, LocalTime.of(14, 0)).finish();

        assertTrue(NightBudget.open(store, PROFILE, test.plus(Duration.ofMinutes(5)), LocalTime.of(14, 0)).finished());
        assertFalse(NightBudget.open(store, PROFILE, test.plus(Duration.ofMinutes(5)), SLOT).finished());
    }

    @Test
    void aRetryLateInTheSameSlotKeepsTheCountsAndTheCap() throws IOException {
        NightBudget first = NightBudget.open(store, PROFILE, NOW, SLOT);
        first.spendBuy(3, NOW);
        first.spendLevels(4);

        Instant late = NOW.plus(Duration.ofMinutes(90));
        NightBudget retry = NightBudget.open(store, PROFILE, late, SLOT);

        assertEquals(NOW.plus(NightBudget.TIME_CAP), retry.deadline());
        assertEquals(2, retry.buysLeft(3));
        assertEquals(1, retry.levelsLeft(5));
        assertFalse(retry.spendBuy(3, late), "the cap ran out an hour ago");
    }

    @Test
    void aLedgerSavedWithoutASlotNeverBlocks() throws IOException {
        Files.createDirectories(workspace.resolve("squads"));
        Files.writeString(workspace.resolve("squads").resolve("hero-gear-night-" + PROFILE + ".json"),
                "{\"startedAt\":\"" + NOW + "\",\"buys\":3,\"levels\":5,\"finished\":true}");

        NightBudget budget = NightBudget.open(store, PROFILE, NOW.plus(Duration.ofMinutes(5)), SLOT);

        assertFalse(budget.finished());
        assertEquals(3, budget.buysLeft(3));
        assertEquals(5, budget.levelsLeft(5));
        assertEquals(NOW.plus(Duration.ofMinutes(25)), budget.deadline());
    }

    @Test
    void aRunBeforeTheSlotTimeBelongsToYesterdaysSlot() {
        assertEquals(Instant.parse("2026-11-09T02:30:00Z"), NightBudget.slotFor(NOW.minusSeconds(1), SLOT));
        assertEquals(NOW, NightBudget.slotFor(NOW, SLOT));
        assertEquals(NOW, NightBudget.slotFor(NOW.plus(Duration.ofHours(23)), SLOT));
    }

    @Test
    void noBuyPastTheCapAndNoneIsCounted() throws IOException {
        NightBudget budget = NightBudget.open(store, PROFILE, NOW, SLOT);

        assertFalse(budget.spendBuy(3, budget.deadline().plusSeconds(1)));

        assertEquals(3, budget.buysLeft(3));
    }

    @Test
    void noBuyPastTheCount() throws IOException {
        NightBudget budget = NightBudget.open(store, PROFILE, NOW, SLOT);

        assertTrue(budget.spendBuy(1, NOW));
        assertFalse(budget.spendBuy(1, NOW));
    }

    @Test
    void aLiveRaiseCountsItsLevelsBeforeItRuns() throws IOException {
        NightBudget budget = NightBudget.open(store, PROFILE, NOW, SLOT);
        int[] seen = { -1 };

        budget.counted((raise, live) -> {
            try {
                seen[0] = NightBudget.open(store, PROFILE, NOW, SLOT).levelsLeft(5);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            return REACHES.run(raise, live);
        }).run(RAISE, true);

        assertEquals(1, seen[0], "81 to 85 is four levels, on disk before the first press");
        assertEquals(1, budget.levelsLeft(5));
    }

    @Test
    void aShortRaiseGivesBackTheLevelsItDidNotPress() throws IOException {
        NightBudget budget = NightBudget.open(store, PROFILE, NOW, SLOT);

        budget.counted((raise, live) -> new GearEnhancePass.Outcome(raise.from() + 1, 1, Stop.QUICK_SELECT_SHORT, "short"))
                .run(RAISE, true);

        assertEquals(4, budget.levelsLeft(5));
        assertEquals(4, NightBudget.open(store, PROFILE, NOW, SLOT).levelsLeft(5));
    }

    @Test
    void anUnconfirmedPressKeepsItsWholeCount() throws IOException {
        NightBudget budget = NightBudget.open(store, PROFILE, NOW, SLOT);

        budget.counted((raise, live) -> new GearEnhancePass.Outcome(raise.from() + 1, 1, Stop.PRESS_UNCONFIRMED, "no read"))
                .run(RAISE, true);

        assertEquals(1, budget.levelsLeft(5));
    }

    @Test
    void aRaiseCutOffMidwayKeepsItsWholeCount() throws IOException {
        NightBudget budget = NightBudget.open(store, PROFILE, NOW, SLOT);

        assertThrows(IllegalStateException.class, () -> budget.counted((raise, live) -> {
            throw new IllegalStateException("preempted");
        }).run(RAISE, true));

        assertEquals(1, NightBudget.open(store, PROFILE, NOW, SLOT).levelsLeft(5));
    }

    @Test
    void aLedgerSavedAsRaisesCountsFiveLevelsForEach() throws IOException {
        Files.createDirectories(workspace.resolve("squads"));
        Files.writeString(workspace.resolve("squads").resolve("hero-gear-night-" + PROFILE + ".json"),
                "{\"startedAt\":\"" + NOW + "\",\"slot\":\"" + NOW + "\",\"buys\":2,\"raises\":1,\"finished\":false}");

        NightBudget budget = NightBudget.open(store, PROFILE, NOW.plus(Duration.ofMinutes(5)), SLOT);

        assertEquals(5, budget.levelsLeft(10));
        assertEquals(1, budget.buysLeft(3));
        assertEquals(NOW.plus(NightBudget.TIME_CAP), budget.deadline());
    }

    @Test
    void aDryRunRaiseIsNotCounted() throws IOException {
        NightBudget budget = NightBudget.open(store, PROFILE, NOW, SLOT);

        budget.counted(REACHES).run(RAISE, false);

        assertEquals(5, budget.levelsLeft(5));
    }

    @Test
    void aRaiseThatCannotBeCountedDoesNotRun() throws IOException {
        NightBudget budget = NightBudget.open(store, PROFILE, NOW, SLOT);
        Files.createDirectories(workspace.resolve("squads").resolve("hero-gear-night-" + PROFILE + ".json.tmp"));
        boolean[] ran = { false };

        assertThrows(Abort.class, () -> budget.counted((raise, live) -> {
            ran[0] = true;
            return REACHES.run(raise, live);
        }).run(RAISE, true));

        assertFalse(ran[0]);
    }
}
