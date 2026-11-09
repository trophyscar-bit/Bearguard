package dev.frostguard.tasks.pets;

/**
 * Tracks the caring routine's run of failed walks to the Life Essence menu and turns each one into
 * a retry delay.
 *
 * <p>The Daily sidebar only carries the Life Essence row while the island has something to collect,
 * so a miss on the way in usually means "not yet" rather than "broken". On 2026-11-08 the row was
 * reachable twice in the whole day, at 15:58 and 18:44: the claim routine's short 5/10/15/20/25
 * minute probe caught both windows, while caring's flat hour caught neither and cared for no island
 * at all. Caring therefore shares the claim routine's policy.
 *
 * <p>The run is held in memory rather than stored. A restart resetting it to the shortest delay
 * only makes the next probe more eager, which is the safe direction, and it needs no new setting.
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
