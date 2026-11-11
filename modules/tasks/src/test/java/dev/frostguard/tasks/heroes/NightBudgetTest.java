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
        NightBudget budget = NightBudget.open(store, PROFILE, NOW);

        assertEquals(NOW.plus(Duration.ofMinutes(20)), budget.deadline());
        assertEquals(3, budget.buysLeft(3));
        assertEquals(5, budget.levelsLeft(5));
        assertFalse(budget.finished());
    }

    @Test
    void everythingBeforeTheForgeStopsFiveMinutesShortOfTheCap() throws IOException {
        assertEquals(NOW.plus(Duration.ofMinutes(15)), NightBudget.open(store, PROFILE, NOW).reserveAt());
    }

    @Test
    void aRerunTheSameNightKeepsTheCapAndWhatWasUsed() throws IOException {
        NightBudget first = NightBudget.open(store, PROFILE, NOW);
        first.spendBuy(3, NOW);
        first.spendLevels(3);

        NightBudget retry = NightBudget.open(store, PROFILE, NOW.plus(Duration.ofMinutes(9)));

        assertEquals(NOW.plus(NightBudget.TIME_CAP), retry.deadline());
        assertEquals(2, retry.buysLeft(3));
        assertEquals(2, retry.levelsLeft(5));
    }

    @Test
    void aBuyIsOnDiskBeforeItsTap() throws IOException {
        NightBudget.open(store, PROFILE, NOW).spendBuy(3, NOW);

        assertEquals(2, NightBudget.open(store, PROFILE, NOW).buysLeft(3));
    }

    @Test
    void theNextNightStartsOver() throws IOException {
        NightBudget first = NightBudget.open(store, PROFILE, NOW);
        first.spendBuy(3, NOW);
        first.finish();

        NightBudget next = NightBudget.open(store, PROFILE, NOW.plus(Duration.ofHours(24)));

        assertFalse(next.finished());
        assertEquals(3, next.buysLeft(3));
        assertEquals(NOW.plus(Duration.ofHours(24)).plus(NightBudget.TIME_CAP), next.deadline());
    }

    @Test
    void aFinishedNightStaysFinished() throws IOException {
        NightBudget.open(store, PROFILE, NOW).finish();

        assertTrue(NightBudget.open(store, PROFILE, NOW.plus(Duration.ofHours(19))).finished());
    }

    @Test
    void noBuyPastTheCapAndNoneIsCounted() throws IOException {
        NightBudget budget = NightBudget.open(store, PROFILE, NOW);

        assertFalse(budget.spendBuy(3, budget.deadline().plusSeconds(1)));

        assertEquals(3, budget.buysLeft(3));
    }

    @Test
    void noBuyPastTheCount() throws IOException {
        NightBudget budget = NightBudget.open(store, PROFILE, NOW);

        assertTrue(budget.spendBuy(1, NOW));
        assertFalse(budget.spendBuy(1, NOW));
    }

    @Test
    void aLiveRaiseCountsItsLevelsBeforeItRuns() throws IOException {
        NightBudget budget = NightBudget.open(store, PROFILE, NOW);
        int[] seen = { -1 };

        budget.counted((raise, live) -> {
            try {
                seen[0] = NightBudget.open(store, PROFILE, NOW).levelsLeft(5);
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
        NightBudget budget = NightBudget.open(store, PROFILE, NOW);

        budget.counted((raise, live) -> new GearEnhancePass.Outcome(raise.from() + 1, 1, Stop.QUICK_SELECT_SHORT, "short"))
                .run(RAISE, true);

        assertEquals(4, budget.levelsLeft(5));
        assertEquals(4, NightBudget.open(store, PROFILE, NOW).levelsLeft(5));
    }

    @Test
    void anUnconfirmedPressKeepsItsWholeCount() throws IOException {
        NightBudget budget = NightBudget.open(store, PROFILE, NOW);

        budget.counted((raise, live) -> new GearEnhancePass.Outcome(raise.from() + 1, 1, Stop.PRESS_UNCONFIRMED, "no read"))
                .run(RAISE, true);

        assertEquals(1, budget.levelsLeft(5));
    }

    @Test
    void aRaiseCutOffMidwayKeepsItsWholeCount() throws IOException {
        NightBudget budget = NightBudget.open(store, PROFILE, NOW);

        assertThrows(IllegalStateException.class, () -> budget.counted((raise, live) -> {
            throw new IllegalStateException("preempted");
        }).run(RAISE, true));

        assertEquals(1, NightBudget.open(store, PROFILE, NOW).levelsLeft(5));
    }

    @Test
    void aLedgerSavedAsRaisesCountsFiveLevelsForEach() throws IOException {
        Files.createDirectories(workspace.resolve("squads"));
        Files.writeString(workspace.resolve("squads").resolve("hero-gear-night-" + PROFILE + ".json"),
                "{\"startedAt\":\"" + NOW + "\",\"buys\":2,\"raises\":1,\"finished\":false}");

        NightBudget budget = NightBudget.open(store, PROFILE, NOW.plus(Duration.ofMinutes(5)));

        assertEquals(5, budget.levelsLeft(10));
        assertEquals(1, budget.buysLeft(3));
        assertEquals(NOW.plus(NightBudget.TIME_CAP), budget.deadline());
    }

    @Test
    void aDryRunRaiseIsNotCounted() throws IOException {
        NightBudget budget = NightBudget.open(store, PROFILE, NOW);

        budget.counted(REACHES).run(RAISE, false);

        assertEquals(5, budget.levelsLeft(5));
    }

    @Test
    void aRaiseThatCannotBeCountedDoesNotRun() throws IOException {
        NightBudget budget = NightBudget.open(store, PROFILE, NOW);
        Files.createDirectories(workspace.resolve("squads").resolve("hero-gear-night-" + PROFILE + ".json.tmp"));
        boolean[] ran = { false };

        assertThrows(Abort.class, () -> budget.counted((raise, live) -> {
            ran[0] = true;
            return REACHES.run(raise, live);
        }).run(RAISE, true));

        assertFalse(ran[0]);
    }
}
