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
            (raise, live) -> new GearEnhancePass.Outcome(raise.to(), 1, Stop.TARGET_REACHED, "reached");

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
        assertEquals(3, budget.raisesLeft(3));
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
        first.spendRaise();
        first.spendRaise();

        NightBudget retry = NightBudget.open(store, PROFILE, NOW.plus(Duration.ofMinutes(9)));

        assertEquals(NOW.plus(NightBudget.TIME_CAP), retry.deadline());
        assertEquals(2, retry.buysLeft(3));
        assertEquals(1, retry.raisesLeft(3));
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
    void aLiveRaiseIsCountedBeforeItRuns() throws IOException {
        NightBudget budget = NightBudget.open(store, PROFILE, NOW);
        int[] seen = { -1 };

        budget.counted((raise, live) -> {
            seen[0] = budget.raisesLeft(3);
            return REACHES.run(raise, live);
        }).run(RAISE, true);

        assertEquals(2, seen[0]);
    }

    @Test
    void aDryRunRaiseIsNotCounted() throws IOException {
        NightBudget budget = NightBudget.open(store, PROFILE, NOW);

        budget.counted(REACHES).run(RAISE, false);

        assertEquals(3, budget.raisesLeft(3));
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
