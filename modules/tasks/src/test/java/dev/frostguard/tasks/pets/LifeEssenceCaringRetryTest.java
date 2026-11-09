package dev.frostguard.tasks.pets;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class LifeEssenceCaringRetryTest {

    @Test
    void probesAgainWithinMinutesRatherThanWaitingOutAnHour() {
        LifeEssenceCaringRetry retry = new LifeEssenceCaringRetry();

        assertEquals(Duration.ofMinutes(5), retry.afterNavigationFailure().retryDelay());
        assertEquals(Duration.ofMinutes(10), retry.afterNavigationFailure().retryDelay());
        assertEquals(Duration.ofMinutes(15), retry.afterNavigationFailure().retryDelay());
        assertEquals(Duration.ofMinutes(20), retry.afterNavigationFailure().retryDelay());
        assertEquals(Duration.ofMinutes(25), retry.afterNavigationFailure().retryDelay());
    }

    @Test
    void settlesOnHourlyProbesOnceTheShortBackoffIsSpent() {
        LifeEssenceCaringRetry retry = new LifeEssenceCaringRetry();
        for (int failure = 0; failure < 5; failure++) {
            retry.afterNavigationFailure();
        }

        assertEquals(Duration.ofHours(1), retry.afterNavigationFailure().retryDelay());
        assertEquals(Duration.ofHours(1), retry.afterNavigationFailure().retryDelay());
        assertEquals(6, retry.failures(), "A spent backoff must not keep growing");
    }

    @Test
    void startsOverAfterTheMenuIsReachedAgain() {
        LifeEssenceCaringRetry retry = new LifeEssenceCaringRetry();
        retry.afterNavigationFailure();
        retry.afterNavigationFailure();

        retry.afterSuccess();

        assertEquals(0, retry.failures());
        assertEquals(Duration.ofMinutes(5), retry.afterNavigationFailure().retryDelay(),
                "The next miss after a reached menu starts at the shortest probe");
    }

    @Test
    void sharesTheClaimRoutinePolicyRatherThanItsOwnSchedule() {
        LifeEssenceCaringRetry retry = new LifeEssenceCaringRetry();

        for (int previousFailures = 0; previousFailures < 8; previousFailures++) {
            assertEquals(LifeEssenceRetryPolicy.afterFailure(previousFailures).retryDelay(),
                    retry.afterNavigationFailure().retryDelay(),
                    "Caring and claiming must probe on the same cadence");
        }
    }
}
