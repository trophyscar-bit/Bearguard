package dev.frostguard.tasks.events;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

class EventMissionScheduleTest {

    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    @Test
    void passesAreEveryThreeHoursFromTheResetPlusTheTwoFixedLateOnes() {
        assertEquals(List.of(LocalTime.of(0, 15), LocalTime.of(3, 15), LocalTime.of(6, 15), LocalTime.of(9, 15),
                LocalTime.of(12, 15), LocalTime.of(15, 15), LocalTime.of(18, 15), LocalTime.of(22, 0),
                LocalTime.of(23, 30)),
                EventMissionSchedule.passesUtc());
    }

    @Test
    void winterTimeGives17And1830Eastern() {
        // 2026-11-12 is EST (UTC-5).
        ZonedDateTime now = ZonedDateTime.of(2026, 11, 12, 16, 20, 0, 0, NEW_YORK);
        assertEquals(LocalDateTime.of(2026, 11, 12, 17, 0), EventMissionSchedule.nextPass(now));
        assertEquals(LocalDateTime.of(2026, 11, 12, 18, 30),
                EventMissionSchedule.nextPass(now.withHour(17).withMinute(0)));
    }

    @Test
    void afterTheLastPassTheNextDayStartsJustAfterTheReset() {
        ZonedDateTime now = ZonedDateTime.of(2026, 11, 12, 18, 31, 0, 0, NEW_YORK);
        // 00:15 UTC on the 13th is 19:15 EST on the 12th.
        assertEquals(LocalDateTime.of(2026, 11, 12, 19, 15), EventMissionSchedule.nextPass(now));
    }

    @Test
    void summerTimeFollowsUtcNotTheLocalClock() {
        // 2026-07-01 is EDT (UTC-4): 22:00 UTC is 18:00 local.
        ZonedDateTime now = ZonedDateTime.of(2026, 7, 1, 17, 0, 0, 0, NEW_YORK);
        assertEquals(LocalDateTime.of(2026, 7, 1, 18, 0), EventMissionSchedule.nextPass(now));
    }

    @Test
    void aPassExactlyNowIsNotReturnedByNextPassButIsByAtOrAfter() {
        ZonedDateTime pass = ZonedDateTime.of(2026, 11, 12, 17, 0, 0, 0, NEW_YORK);
        assertEquals(LocalDateTime.of(2026, 11, 12, 18, 30), EventMissionSchedule.nextPass(pass));
        assertEquals(LocalDateTime.of(2026, 11, 12, 17, 0), EventMissionSchedule.nextPassAtOrAfter(pass));
    }
}
