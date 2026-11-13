package dev.frostguard.tasks.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class FacilityDryRunTest {

    private static final Optional<FacilityDryRun.Window> WINDOW =
            FacilityDryRun.parse("2026-11-14T20:00/2026-11-14T23:00");
    private static final Duration EST = Duration.ofHours(-5);

    @Test
    void anArmedWindowParsesAndAnythingElseIsNotArmed() {
        assertEquals(Optional.of(new FacilityDryRun.Window(
                LocalDateTime.of(2026, 11, 14, 20, 0), LocalDateTime.of(2026, 11, 14, 23, 0))), WINDOW);
        for (String bad : new String[] {null, "", "  ", "2026-11-14T20:00", "x/y",
                "2026-11-14T23:00/2026-11-14T20:00"}) {
            assertEquals(Optional.empty(), FacilityDryRun.parse(bad), String.valueOf(bad));
        }
        assertEquals(FacilityDryRun.Kind.NOT_ARMED,
                FacilityDryRun.decide(Optional.empty(), LocalDateTime.of(2026, 11, 14, 21, 0), LocalTime.of(16, 0), EST).kind());
    }

    @Test
    void itWaitsForTheWindowTriesInsideItAndExpiresAfterIt() {
        assertEquals(new FacilityDryRun.Decision(FacilityDryRun.Kind.WAIT, LocalDateTime.of(2026, 11, 14, 20, 0)),
                FacilityDryRun.decide(WINDOW, LocalDateTime.of(2026, 11, 14, 18, 0), LocalTime.of(13, 0), EST));
        assertEquals(FacilityDryRun.Kind.TRY,
                FacilityDryRun.decide(WINDOW, LocalDateTime.of(2026, 11, 14, 20, 0), LocalTime.of(15, 0), EST).kind());
        assertEquals(FacilityDryRun.Kind.TRY,
                FacilityDryRun.decide(WINDOW, LocalDateTime.of(2026, 11, 14, 22, 59), LocalTime.of(17, 59), EST).kind());
        assertEquals(FacilityDryRun.Kind.EXPIRED,
                FacilityDryRun.decide(WINDOW, LocalDateTime.of(2026, 11, 14, 23, 0), LocalTime.of(18, 0), EST).kind());
    }

    @Test
    void itNeverRunsDuringTheBearTrapsHours() {
        assertEquals(false, FacilityDryRun.inBearTrapHours(LocalTime.of(13, 24)));
        assertEquals(true, FacilityDryRun.inBearTrapHours(LocalTime.of(13, 25)));
        assertEquals(true, FacilityDryRun.inBearTrapHours(LocalTime.of(14, 39)));
        assertEquals(false, FacilityDryRun.inBearTrapHours(LocalTime.of(14, 40)));

        // A window that covers those hours: at 13:50 local (18:50 UTC) it waits until 14:40 local (19:40 UTC).
        Optional<FacilityDryRun.Window> wide = FacilityDryRun.parse("2026-11-14T18:00/2026-11-14T23:00");
        assertEquals(new FacilityDryRun.Decision(FacilityDryRun.Kind.WAIT, LocalDateTime.of(2026, 11, 14, 19, 40)),
                FacilityDryRun.decide(wide, LocalDateTime.of(2026, 11, 14, 18, 50), LocalTime.of(13, 50), EST));
        // The wait never reaches past the window's end.
        Optional<FacilityDryRun.Window> short1 = FacilityDryRun.parse("2026-11-14T18:00/2026-11-14T19:00");
        assertEquals(new FacilityDryRun.Decision(FacilityDryRun.Kind.WAIT, LocalDateTime.of(2026, 11, 14, 19, 0)),
                FacilityDryRun.decide(short1, LocalDateTime.of(2026, 11, 14, 18, 50), LocalTime.of(13, 50), EST));
    }
}
