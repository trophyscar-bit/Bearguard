package dev.frostguard.app.panel.heroes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.frostguard.engine.squads.JoinPlanStore;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class JoinPlanBannerTest {

    private static JoinPlanStore.Stored stored(String summary, String previous, String changedAt) {
        return new JoinPlanStore.Stored("2026-11-14T07:20:00Z", summary, previous, changedAt, 100_000);
    }

    @Test
    void aChangedPlanIsOneLineWithWhatItWas() {
        var line = SquadsLayoutController.joinPlanLine(
                stored("Joins: 3 × 100k + 1 × 62k", "Joins: 4 × 100k", "2026-11-14T07:20:00Z"),
                Instant.parse("2026-11-14T09:00:00Z"));
        assertEquals("Join plan changed: 3 × 100k + 1 × 62k (was 4 × 100k)", line.orElseThrow());
    }

    @Test
    void anUnchangedFirstOrOldPlanSaysNothing() {
        Instant now = Instant.parse("2026-11-14T09:00:00Z");
        assertTrue(SquadsLayoutController.joinPlanLine(stored("Joins: 5 × 100k", null, null), now).isEmpty());
        assertTrue(SquadsLayoutController.joinPlanLine(
                stored("Joins: 4 × 100k", "Joins: 5 × 100k", "2026-11-12T07:20:00Z"), now).isEmpty(),
                "a change from more than 36 hours ago is not news any more");
        assertTrue(SquadsLayoutController.joinPlanLine(null, now).isEmpty());
    }
}
