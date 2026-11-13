package dev.frostguard.tasks.combat;

import dev.frostguard.api.domain.PointData;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Chooses which open rally to try next on the War screen. A rally just joined or just failed is
 * rested by its row, so one that filled up or already holds a march cannot stall the whole list.
 */
final class BearRallyTargets {

    static final Duration FAILED_REST = Duration.ofSeconds(5);
    static final Duration JOINED_REST = Duration.ofSeconds(20);
    /** A rally the bot decided not to join (not its target) is looked at again only after a while. */
    static final Duration REJECTED_REST = Duration.ofSeconds(30);
    static final int ROW_TOLERANCE_PX = 40;

    private record RestingRow(int y, Instant until) {
    }

    private final List<RestingRow> resting = new ArrayList<>();

    /** The topmost open rally whose row is not resting. */
    Optional<PointData> next(List<PointData> plusIcons, Instant now) {
        resting.removeIf(row -> !now.isBefore(row.until()));
        return plusIcons.stream()
                .filter(icon -> resting.stream()
                        .noneMatch(row -> Math.abs(row.y() - icon.getY()) <= ROW_TOLERANCE_PX))
                .min(Comparator.comparingInt(PointData::getY));
    }

    void markFailed(PointData icon, Instant now) {
        resting.add(new RestingRow(icon.getY(), now.plus(FAILED_REST)));
    }

    void markRejected(PointData icon, Instant now) {
        resting.add(new RestingRow(icon.getY(), now.plus(REJECTED_REST)));
    }

    void markJoined(PointData icon, Instant now) {
        resting.add(new RestingRow(icon.getY(), now.plus(JOINED_REST)));
    }
}
