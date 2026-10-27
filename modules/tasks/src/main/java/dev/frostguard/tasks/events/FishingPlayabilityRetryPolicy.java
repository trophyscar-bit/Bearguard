package dev.frostguard.tasks.events;

import java.time.LocalDateTime;
import java.util.Objects;

/** Three short retries while Ice Fishing cannot be verified, then rest until daily reset. */
final class FishingPlayabilityRetryPolicy {

    static final int SHORT_RETRY_LIMIT = 3;
    static final int RETRY_MINUTES = 5;

    private FishingPlayabilityRetryPolicy() {
    }

    static LocalDateTime retryAt(LocalDateTime now, LocalDateTime dailyReset, int consecutiveMisses) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(dailyReset, "dailyReset");
        if (consecutiveMisses < 1) {
            throw new IllegalArgumentException("consecutiveMisses must be positive");
        }
        if (consecutiveMisses > SHORT_RETRY_LIMIT) {
            return dailyReset;
        }
        return earlierOf(now.plusMinutes(RETRY_MINUTES), dailyReset);
    }

    static boolean parksUntilReset(int consecutiveMisses) {
        return consecutiveMisses > SHORT_RETRY_LIMIT;
    }

    static String terminalSnapshotType(int consecutiveMisses) {
        return parksUntilReset(consecutiveMisses) ? "unplayable" : null;
    }

    private static LocalDateTime earlierOf(LocalDateTime first, LocalDateTime second) {
        return first.isBefore(second) ? first : second;
    }
}
