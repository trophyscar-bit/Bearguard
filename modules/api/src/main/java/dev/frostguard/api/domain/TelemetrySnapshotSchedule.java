package dev.frostguard.api.domain;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * Decides when the telemetry collector should next take a snapshot.
 *
 * <p>The "last night" report needs clean bookends, so a snapshot is always taken at the bedtime and
 * wake anchors in addition to the normal interval; the next run is whichever of those comes soonest.
 *
 * <p>{@link #WAKE_ANCHOR} has to stay in step with the reader's own wake-anchor grace window. A real
 * capture was observed landing at 08:30:43 — just past an exact 08:30:00 cutoff — so the reader
 * extends its window end by a grace period to keep that snapshot inside the window it exists to
 * bookend. If the two ever drift apart the wake snapshot is silently excluded from "last night",
 * which is a wrong answer rather than a visible failure, so both sides are asserted against this
 * constant in tests.
 *
 * <p>Lives here rather than as private methods on the collector because
 * {@code examples/custom-tasks/bg_telemetry.java} sits outside the Maven module tree and nothing
 * declared on it can be covered by a JUnit test.
 */
public final class TelemetrySnapshotSchedule {

    /** Bedtime bookend for the "last night" window. */
    public static final LocalTime SLEEP_ANCHOR = LocalTime.of(23, 0);

    /** Wake bookend. The reader's grace window is measured from this. */
    public static final LocalTime WAKE_ANCHOR = LocalTime.of(8, 30);

    private TelemetrySnapshotSchedule() {}

    /**
     * Returns the soonest of: {@code now + interval}, the next {@link #SLEEP_ANCHOR}, or the next
     * {@link #WAKE_ANCHOR}. An anchor that has already passed today rolls to tomorrow, so the result
     * is always strictly after {@code now}.
     */
    public static LocalDateTime nextRun(LocalDateTime now, Duration interval) {
        LocalDateTime next = now.plus(interval);
        for (LocalTime anchor : new LocalTime[] { SLEEP_ANCHOR, WAKE_ANCHOR }) {
            LocalDateTime candidate = now.with(anchor);
            if (!candidate.isAfter(now)) {
                candidate = candidate.plusDays(1);
            }
            if (candidate.isBefore(next)) {
                next = candidate;
            }
        }
        return next;
    }
}
