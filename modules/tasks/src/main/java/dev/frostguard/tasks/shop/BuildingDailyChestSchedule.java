package dev.frostguard.tasks.shop;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

/** When the Bakery, Milk Tea and Vinyl daily chest next becomes claimable. */
final class BuildingDailyChestSchedule {

    /** The shops reset at UTC 00:00; a pad keeps the run off the exact second. */
    static final int MIN_PAD_MINUTES = 2;
    static final int MAX_PAD_MINUTES = 12;

    /** A scan that found nothing is unknown, not claimed, so it is rechecked soon. */
    static final Duration UNKNOWN_RECHECK = Duration.ofHours(3);

    /** A claim that did not visibly take effect is retried soon. */
    static final Duration FAILED_CLAIM_RECHECK = Duration.ofHours(1);

    private BuildingDailyChestSchedule() {}

    static LocalDateTime nextClaim(Instant now, int padMinutes, ZoneId zone) {
        ZonedDateTime utcNow = now.atZone(ZoneOffset.UTC);
        ZonedDateTime resetAt = utcNow.toLocalDate().plusDays(1).atStartOfDay(ZoneOffset.UTC);
        return resetAt.plusMinutes(padMinutes).withZoneSameInstant(zone).toLocalDateTime();
    }

    static LocalDateTime recheck(Instant now, Duration after, ZoneId zone) {
        return now.plus(after).atZone(zone).toLocalDateTime();
    }
}
