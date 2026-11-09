package dev.frostguard.engine.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.domain.AccountDescriptor;

class BearTrapParticipationScheduleTest {

    private static final ZoneId UTC = ZoneId.of("UTC");

    private static Clock at(String instant) {
        return Clock.fixed(Instant.parse(instant), UTC);
    }

    private static LocalDateTime nextRun(AccountDescriptor profile, String instant) {
        return BearTrapParticipationSchedule.resolve(profile, at(instant), UTC).orElseThrow().nextRun();
    }

    private static AccountDescriptor profileStartingAtNoon() {
        AccountDescriptor profile = new AccountDescriptor(1L);
        profile.setConfig(ConfigurationKeyEnum.BEAR_TRAP_PREPARATION_TIME_INT, 15);
        profile.setConfig(ConfigurationKeyEnum.BEAR_TRAP_SCHEDULE_DATETIME_STRING, "10-11-2026 12:00");
        return profile;
    }

    @Test
    void theRoutineIsQueuedForTheMomentTheWindowOpensSoItsPreparationRuns() {
        assertEquals(LocalDateTime.of(2026, 11, 10, 11, 45), nextRun(profileStartingAtNoon(), "2026-11-09T12:00:00Z"));
    }

    @Test
    void insideTheWindowItRunsImmediately() {
        assertEquals(LocalDateTime.of(2026, 11, 10, 11, 50), nextRun(profileStartingAtNoon(), "2026-11-10T11:50:00Z"));
    }

    @Test
    void afterTheWindowItRollsForwardFortyEightHoursFromTheEnteredTime() {
        assertEquals(LocalDateTime.of(2026, 11, 12, 11, 45), nextRun(profileStartingAtNoon(), "2026-11-10T14:00:00Z"));
    }

    @Test
    void settingsLeftFromTheFormerSecondTimerAreNotRead() {
        AccountDescriptor profile = profileStartingAtNoon();
        profile.setConfig(ConfigurationKeyEnum.BEAR_TRAP_NUMBER_INT, 2);
        profile.setConfig(ConfigurationKeyEnum.BEAR_TRAP_TIMER_2_SCHEDULE_DATETIME_STRING, "10-11-2026 06:00");

        assertEquals(LocalDateTime.of(2026, 11, 10, 11, 45), nextRun(profile, "2026-11-09T12:00:00Z"));
    }

    @Test
    void noStartTimeMeansNoSchedule() {
        assertTrue(BearTrapParticipationSchedule.resolve(new AccountDescriptor(1L), Clock.systemUTC(), UTC).isEmpty());
    }
}
