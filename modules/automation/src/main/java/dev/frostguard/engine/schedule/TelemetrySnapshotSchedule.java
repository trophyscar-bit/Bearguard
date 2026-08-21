package dev.frostguard.engine.schedule;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;

/** Decides when the telemetry collector should next take a snapshot. */
public final class TelemetrySnapshotSchedule {

    /** Bedtime bookend for the "last night" window. */
    public static final LocalTime SLEEP_ANCHOR = LocalTime.of(23, 0);

    /** Wake bookend. The report reader's grace window is measured from this anchor. */
    public static final LocalTime WAKE_ANCHOR = LocalTime.of(8, 30);

    private TelemetrySnapshotSchedule() {}

    /**
     * Returns the soonest of {@code now + interval}, the next bedtime anchor, or the next wake
     * anchor. Anchors that have already passed today roll to tomorrow, so the result is always
     * strictly after {@code now}.
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
