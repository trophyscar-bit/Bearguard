package dev.frostguard.tasks.shop;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

/** Run-time rules shared by the once-a-day Gem Shop chest claims. */
final class ShopChestSchedule {

    static final int RETRY_MIN_MINUTES = 30;
    static final int RETRY_MAX_MINUTES = 60;
    static final int RESET_GUARD_MINUTES = 20;

    private ShopChestSchedule() {
    }

    /** Tomorrow's 00:00 UTC reset plus {@code postResetMinutes}, in local time. */
    static LocalDateTime nextRunAfterSuccess(ZonedDateTime utcNow, int postResetMinutes) {
        ZonedDateTime next = utcNow.withZoneSameInstant(ZoneOffset.UTC).toLocalDate().plusDays(1)
                .atStartOfDay(ZoneOffset.UTC).plusMinutes(postResetMinutes);
        return next.withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
    }

    /**
     * {@code utcNow} plus the retry delay, except that a landing inside the 20 minutes before
     * the 00:00 UTC reset is pushed to the post-reset slot instead.
     */
    static LocalDateTime nextRetryAfterFailure(ZonedDateTime utcNow, int delayMinutes, int postResetMinutes) {
        ZonedDateTime candidate = utcNow.withZoneSameInstant(ZoneOffset.UTC).plusMinutes(delayMinutes);
        ZonedDateTime reset = candidate.toLocalDate().plusDays(1).atStartOfDay(ZoneOffset.UTC);
        if (!candidate.isBefore(reset.minusMinutes(RESET_GUARD_MINUTES))) {
            candidate = reset.plusMinutes(postResetMinutes);
        }
        return candidate.withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
    }
}
