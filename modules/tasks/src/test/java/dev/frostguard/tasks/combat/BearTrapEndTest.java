package dev.frostguard.tasks.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.frostguard.vision.ocr.TextLine;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Nov 12: a restart at 14:03 EST set the end to 14:33; the game's bear ended at 14:30 (19:30 UTC). */
class BearTrapEndTest {

    private static TextLine line(String text) {
        return new TextLine(text, 200, 540, 200, 24, 90f);
    }

    @Test
    void aRestartInsideTheScheduledWindowEndsWithTheWindowNotThirtyMinutesFromNow() {
        LocalDateTime activation = LocalDateTime.of(2026, 11, 12, 19, 0);
        LocalDateTime restart = LocalDateTime.of(2026, 11, 12, 19, 3, 29);
        assertEquals(LocalDateTime.of(2026, 11, 12, 19, 30), BearTrapEnd.restartedEnd(restart, activation, 30));
    }

    @Test
    void withoutAWindowAroundNowItFallsBackToNowPlusThirty() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 12, 12, 0);
        assertEquals(now.plusMinutes(30), BearTrapEnd.restartedEnd(now, null, 30));
        // The scheduled bear has not started, or ended long ago.
        assertEquals(now.plusMinutes(30), BearTrapEnd.restartedEnd(now, now.plusHours(2), 30));
        assertEquals(now.plusMinutes(30), BearTrapEnd.restartedEnd(now, now.minusHours(2), 30));
    }

    @Test
    void theCountdownOnTheBearIsRead() {
        // From the 14:00:13 EST (19:00:13 UTC) frame: the bear ended 19:30:01.
        assertEquals(Duration.ofSeconds(29 * 60 + 48), BearTrapEnd.endsIn(List.of(line("Ends in: 00:29:48"))).orElseThrow());
        assertEquals(Duration.ofSeconds(25 * 60 + 45), BearTrapEnd.endsIn(List.of(line("Ends in: 00:25:45"))).orElseThrow());
        assertTrue(BearTrapEnd.endsIn(List.of(line("Ends in: 00:29:"))).isEmpty(), "a cut-off read must not be guessed");
        assertTrue(BearTrapEnd.endsIn(List.of(line("Ends in: 00 288th"))).isEmpty());
        assertTrue(BearTrapEnd.endsIn(List.of(line("Hunting Trap 1"))).isEmpty());
    }

    @Test
    void theBotNeverOutstaysTheEndTheGameShowed() {
        LocalDateTime planned = LocalDateTime.of(2026, 11, 12, 19, 33, 29);
        LocalDateTime shown = LocalDateTime.of(2026, 11, 12, 19, 30, 1);
        assertEquals(shown, BearTrapEnd.earliest(planned, shown));
        assertEquals(planned, BearTrapEnd.earliest(planned, null));
        // A longer game end than planned does not extend the run.
        assertEquals(planned, BearTrapEnd.earliest(planned, planned.plusMinutes(10)));
    }
}
