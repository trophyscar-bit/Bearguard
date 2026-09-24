package dev.frostguard.tasks.social;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Optional;

/**
 * When the nightly reconcile is owed, and how the ordinary schedule makes room for it.
 *
 * <p>There is no second timer. The routine already has one schedule, and a derived timer that has
 * to be kept in step with it is how the app came to show ten hours while the bot acted in three
 * minutes. So the reconcile is a property of a pass, not of a schedule: the first pass at or after
 * the slot each day is the reconcile, and the ordinary schedule is only nudged so that pass lands
 * on the slot rather than up to a whole interval after it.
 *
 * <p>Owed rather than merely scheduled is what makes a pause harmless. A bot that was stopped
 * through the slot comes back with a slot behind it and nothing started since, so its first pass
 * on resuming is the reconcile -- the night is filled in whenever it is next running, not only if
 * it happened to be running at the time.
 */
final class ChatReconcileSchedule {

    private ChatReconcileSchedule() {
    }

    /** The most recent moment at or before {@code now} at which the slot fell. */
    static LocalDateTime latestSlot(LocalDateTime now, LocalTime slot) {
        LocalDateTime today = now.toLocalDate().atTime(slot);
        return today.isAfter(now) ? today.minusDays(1) : today;
    }

    /** The first moment strictly after {@code now} at which the slot falls. */
    static LocalDateTime nextSlot(LocalDateTime now, LocalTime slot) {
        LocalDateTime today = now.toLocalDate().atTime(slot);
        return today.isAfter(now) ? today : today.plusDays(1);
    }

    /**
     * Whether a reconcile is owed: a slot has passed and none has started since.
     *
     * <p>With nothing on record it is owed. That costs one long pass the first time the feature is
     * on, which is also the pass that fills whatever gap is already there.
     */
    static boolean due(LocalDateTime now, LocalTime slot, Optional<LocalDateTime> lastStart) {
        return lastStart.isEmpty() || lastStart.get().isBefore(latestSlot(now, slot));
    }

    /**
     * Pulls the next ordinary pass forward to the slot when it would otherwise fall after it.
     *
     * @param pad a little past the slot, never the exact second -- the same rule every other timer
     *            here follows, so the bot does not act on a boundary
     */
    static LocalDateTime pullToSlot(LocalDateTime due, LocalDateTime now, LocalTime slot,
                                    Duration pad) {
        LocalDateTime next = nextSlot(now, slot);
        return due.isAfter(next) ? next.plus(pad) : due;
    }
}
