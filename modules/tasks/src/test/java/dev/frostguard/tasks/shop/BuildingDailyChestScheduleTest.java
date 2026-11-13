package dev.frostguard.tasks.shop;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class BuildingDailyChestScheduleTest {

    @Test
    void aClaimWaitsForTheNextUtcMidnightPlusPadNotTwentyFourHours() {
        Instant claimedAt = Instant.parse("2026-11-13T18:30:00Z");

        assertEquals(LocalDateTime.of(2026, 11, 14, 0, 7),
                BuildingDailyChestSchedule.nextClaim(claimedAt, 7, ZoneOffset.UTC));
    }

    @Test
    void aClaimJustAfterTheResetWaitsAFullDay() {
        Instant claimedAt = Instant.parse("2026-11-14T00:01:00Z");

        assertEquals(LocalDateTime.of(2026, 11, 15, 0, 5),
                BuildingDailyChestSchedule.nextClaim(claimedAt, 5, ZoneOffset.UTC));
    }

    @Test
    void theResetIsConvertedToTheLocalZone() {
        Instant claimedAt = Instant.parse("2026-11-13T18:30:00Z");

        assertEquals(LocalDateTime.of(2026, 11, 13, 19, 10),
                BuildingDailyChestSchedule.nextClaim(claimedAt, 10, ZoneId.of("America/New_York")));
    }

    @Test
    void anUnknownScanIsRecheckedSoonNotAtTheNextReset() {
        Instant now = Instant.parse("2026-11-13T18:30:00Z");

        assertEquals(LocalDateTime.of(2026, 11, 13, 21, 30),
                BuildingDailyChestSchedule.recheck(now, BuildingDailyChestSchedule.UNKNOWN_RECHECK, ZoneOffset.UTC));
        assertEquals(Duration.ofHours(1), BuildingDailyChestSchedule.FAILED_CLAIM_RECHECK);
    }
}
