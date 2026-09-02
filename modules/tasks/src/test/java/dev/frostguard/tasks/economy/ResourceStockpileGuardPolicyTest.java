package dev.frostguard.tasks.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The plausibility thresholds the stockpile and speedup readings are judged against.
 *
 * <p>Speedups had no guard at all until now, and could not simply borrow the stockpile one: the
 * two families move differently, and applying stockpile thresholds to a speedup bucket would have
 * been worse than leaving it unguarded. These pin the differences that matter.</p>
 */
class ResourceStockpileGuardPolicyTest {

    private static final ResourceStockpileRoutine.GuardPolicy STOCKPILE =
            ResourceStockpileRoutine.STOCKPILE_GUARD;
    private static final ResourceStockpileRoutine.GuardPolicy SPEEDUP =
            ResourceStockpileRoutine.SPEEDUP_GUARD;

    private static boolean plausible(long candidate, long cached,
                                     ResourceStockpileRoutine.GuardPolicy policy) {
        return ResourceStockpileRoutine.inBand(candidate, cached, policy.absoluteTolerance());
    }

    /** The 9/1 misread: 30 minutes between readings of 2434 and 2664. */
    @Test
    void theGeneralSpeedupMisreadIsImplausible() {
        assertFalse(plausible(30L, 2434L, SPEEDUP),
                "30 minutes against a cached 2434 is the misread that reported +1d 21h 21m");
    }

    /** Small piles move by large ratios for entirely ordinary reasons. */
    @Test
    void smallSpeedupMovesArePlausibleDespiteTheRatio() {
        assertTrue(plausible(49L, 5L, SPEEDUP),
                "five minutes becoming forty-nine is a 9.8x ratio and a normal morning's rewards");
        assertTrue(plausible(240L, 5L, SPEEDUP), "four hours gained is within the absolute tolerance");
        assertFalse(plausible(2434L, 5L, SPEEDUP), "forty hours appearing at once is not");
    }

    /** Stockpiles get no absolute tolerance -- they are large, so ratio alone is meaningful. */
    @Test
    void stockpilesJudgeOnRatioAlone() {
        assertEquals(0L, STOCKPILE.absoluteTolerance());
        assertTrue(plausible(71_200_000L, 68_000_000L, STOCKPILE));
        assertFalse(plausible(705_000_000L, 68_000_000L, STOCKPILE),
                "the dropped-decimal misread must not pass the band");
    }

    /**
     * Emptying a speedup bucket into one upgrade is normal and takes 2268 minutes to 155 -- a
     * 14.6x drop. Under the stockpile policy's 10x auto-trust ceiling that real spend would be
     * rejected forever, so the speedup policy carries no ceiling and leans on the streak instead.
     */
    @Test
    void aRealSpeedupSpendCanAlwaysBeTrustedEventually() {
        double spendRatio = 2268.0 / 155.0;
        assertTrue(spendRatio > STOCKPILE.maxTrustableRatio(),
                "this is exactly the case the stockpile ceiling would reject forever");
        assertTrue(spendRatio < SPEEDUP.maxTrustableRatio(),
                "the speedup policy must be able to believe a genuine spend");
        assertFalse(plausible(155L, 2268L, SPEEDUP),
                "it is still not accepted on the first reading -- the next one has to confirm it");
    }

    /** A misread is never confirmed by the next reading; a real change always is. Speedups move
     *  fast enough that waiting three cycles would leave the page stale for hours. */
    @Test
    void speedupsNeedFewerConfirmationsThanStockpiles() {
        assertEquals(2, SPEEDUP.streakToTrust());
        assertEquals(3, STOCKPILE.streakToTrust());
    }

    /** Durations have no decimal point to drop, so the /10 repair does not apply to them. */
    @Test
    void onlyStockpilesGetTheDroppedDecimalRepair() {
        assertTrue(STOCKPILE.decimalRepair());
        assertFalse(SPEEDUP.decimalRepair());
    }
}
