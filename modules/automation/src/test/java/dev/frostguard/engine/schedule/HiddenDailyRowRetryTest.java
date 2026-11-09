package dev.frostguard.engine.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

class HiddenDailyRowRetryTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 9, 15, 13);

    @Test
    void waitsTwoHoursWhenTheResetIsFurtherAway() {
        assertEquals(NOW.plusHours(2), DelayedTask.hiddenRowRetryAt(NOW, NOW.plusHours(5)));
    }

    @Test
    void comesBackAtTheResetWhenItIsSooner() {
        assertEquals(NOW.plusMinutes(30), DelayedTask.hiddenRowRetryAt(NOW, NOW.plusMinutes(30)));
    }

    @Test
    void ignoresAResetAlreadyInThePast() {
        assertEquals(NOW.plusHours(2), DelayedTask.hiddenRowRetryAt(NOW, NOW.minusMinutes(1)));
    }
}
