package dev.frostguard.tasks.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.frostguard.api.domain.PointData;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class BearRallyTargetsTest {

    private static final Instant T0 = Instant.parse("2026-11-10T19:00:00Z");
    private static final PointData TOP = new PointData(600, 300);
    private static final PointData MIDDLE = new PointData(600, 520);
    private static final PointData BOTTOM = new PointData(600, 740);

    private final BearRallyTargets targets = new BearRallyTargets();

    private int nextY(List<PointData> icons, Instant now) {
        return targets.next(icons, now).orElseThrow().getY();
    }

    @Test
    void picksTheTopmostOpenRally() {
        assertEquals(300, nextY(List.of(BOTTOM, TOP, MIDDLE), T0));
    }

    @Test
    void emptyWhenNoRallyIsOpenSoTheCallerKeepsScanning() {
        assertEquals(Optional.empty(), targets.next(List.of(), T0));
    }

    @Test
    void movesToTheNextRallyWhenOneFillsUp() {
        targets.markFailed(TOP, T0);
        assertEquals(520, nextY(List.of(TOP, MIDDLE), T0.plusSeconds(1)));
    }

    @Test
    void filledRallyIsTriedAgainAfterARest() {
        targets.markFailed(TOP, T0);
        assertEquals(300, nextY(List.of(TOP, MIDDLE), T0.plus(BearRallyTargets.FAILED_REST)));
    }

    @Test
    void joinedRallyIsNotHitAgainEvenIfItsPlusStaysVisible() {
        targets.markJoined(TOP, T0);
        assertEquals(Optional.empty(), targets.next(List.of(TOP), T0.plusSeconds(19)));
        assertEquals(300, nextY(List.of(TOP), T0.plus(BearRallyTargets.JOINED_REST)));
    }

    @Test
    void iconOnTheSameRowCountsAsTheSameRally() {
        targets.markFailed(TOP, T0);
        assertEquals(Optional.empty(), targets.next(List.of(new PointData(420, 310)), T0.plusSeconds(1)));
    }
}
