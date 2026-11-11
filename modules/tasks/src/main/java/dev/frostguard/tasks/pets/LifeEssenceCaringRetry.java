package dev.frostguard.tasks.pets;

/**
 * Tracks the caring routine's run of failed sidebar walks to the Life Essence menu and turns each
 * one into a retry delay.
 *
 * <p>Only a genuine sidebar failure belongs here. A Daily row the game is not listing is
 * {@code NOT_OFFERED} and defers on its own schedule, because nothing is collectable until the row
 * comes back and probing sooner would only churn.
 *
 * <p>What this is for is the transient failure, and the costly one. Caring reaches an island by
 * travelling to it on the world map, and on the way back the sidebar often will not open yet: on
 * 2026-11-09 the routine cared for one island at 03:31 and again at 06:39, and both times the very
 * next step logged {@code Sidebar open not confirmed: triggerTaps=2/2 ... observed=closed/unknown
 * rootScreen=true} — the panel never opened, so the row was never even scanned. On the flat hour
 * that ended the day at one island of three, twice. The condition clears in moments: at 03:33:56,
 * two minutes after the miss, a sidebar opened on the first trigger tap.
 *
 * <p>So caring shares the claim routine's 5/10/15/20/25-minute backoff before settling on an hour,
 * which turns an hour lost into minutes lost.
 *
 * <p>The run is held in memory rather than stored. A restart resetting it to the shortest delay
 * only makes the next attempt more eager, which is the safe direction, and it needs no new setting.
 */
final class LifeEssenceCaringRetry {

    private int failures;

    LifeEssenceRetryPolicy.Decision afterNavigationFailure() {
        LifeEssenceRetryPolicy.Decision decision = LifeEssenceRetryPolicy.afterFailure(failures);
        failures = decision.persistedFailures();
        return decision;
    }

    void afterSuccess() {
        failures = 0;
    }

    int failures() {
        return failures;
    }
}
