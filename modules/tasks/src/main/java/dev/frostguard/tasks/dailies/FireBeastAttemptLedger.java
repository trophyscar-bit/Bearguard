package dev.frostguard.tasks.dailies;

import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * Remembers Fire Beast attempts across Intel runs.
 *
 * <p>Every scheduled Intel run gets a brand-new routine instance, and the app itself gets closed and
 * relaunched, so a try count kept on the instance resets whenever either happens. On 11 September
 * that handed one unbeatable Fire Beast three fresh tries on every run: two tries at 12:13 and 12:14,
 * the app closed mid-wait at 12:16, and the relaunched Intel run at 12:18 started again at 1/3. The
 * count and the give-up are stored against the Intel cycle they belong to -- named by the refresh
 * that ends it, 00:00, 08:00 or 16:00 UTC -- so they survive both and still clear themselves when a
 * new cycle brings new beasts.
 */
final class FireBeastAttemptLedger {

    private FireBeastAttemptLedger() {}

    /** The Intel cycle a moment belongs to, named by the refresh that ends it (epoch millis). */
    static long cycleOf(LocalDateTime now, ZoneId zone) {
        return IntelCyclePolicy.nextRefresh(now, zone).atZone(zone).toInstant().toEpochMilli();
    }

    /** Tries already spent this cycle. A count from another cycle was spent on beasts that are gone. */
    static int triesThisCycle(int storedTries, long storedCycle, long currentCycle) {
        return storedCycle == currentCycle ? Math.max(0, storedTries) : 0;
    }

    /** True while Fire Beasts are being left alone. Zero means they were never given up on. */
    static boolean leftAlone(long skipUntilMillis, long nowMillis) {
        return nowMillis < skipUntilMillis;
    }
}
