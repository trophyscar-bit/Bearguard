package dev.frostguard.tasks.events;

import java.time.LocalDateTime;
import java.util.List;

import dev.frostguard.vision.convert.GameTimeUtils;

/**
 * Daily claim passes that run a fixed number of minutes after the 00:00 UTC reset, spaced seven minutes apart
 * so one pass never waits behind the next. The slots sit after the 19:00-19:15 (EST) reset cluster and the
 * Red-dot Sweep, and end before Dead Shot and Endless Trial at an hour after reset.
 */
final class PostResetClaimSlots {

    static final int HALL_OF_CHIEFS = 25;
    static final int DEFEAT_NEARBY_BEASTS = 32;
    static final int HERO_RALLY = 39;
    static final int LUCKY_CHIP = 46;
    static final int BROTHERS_IN_ARMS = 53;

    private PostResetClaimSlots() {
    }

    /** The next occurrence of reset + {@code minutesAfterReset} that is still ahead of {@code now}. */
    static LocalDateTime next(LocalDateTime now, LocalDateTime nextReset, int minutesAfterReset) {
        for (LocalDateTime candidate : List.of(nextReset.minusDays(1).plusMinutes(minutesAfterReset),
                nextReset.plusMinutes(minutesAfterReset))) {
            if (candidate.isAfter(now)) {
                return candidate;
            }
        }
        return nextReset.plusDays(1).plusMinutes(minutesAfterReset);
    }

    static LocalDateTime next(int minutesAfterReset) {
        return next(LocalDateTime.now(), GameTimeUtils.dailyResetTime(), minutesAfterReset);
    }
}
