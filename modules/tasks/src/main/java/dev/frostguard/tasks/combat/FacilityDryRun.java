package dev.frostguard.tasks.combat;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Optional;

/**
 * When the Facility joiner may rehearse a join without joining. A setting arms a UTC window; inside it
 * the task tries every {@link #RETRY} until one rally has been captured, never during the Bear Trap's
 * hours, and gives up when the window closes.
 */
final class FacilityDryRun {

    enum Kind { NOT_ARMED, WAIT, TRY, EXPIRED }

    /** @param at when to look again (WAIT); unused for the other kinds. */
    record Decision(Kind kind, LocalDateTime at) {
    }

    record Window(LocalDateTime startUtc, LocalDateTime endUtc) {
    }

    /** How long to wait before the next try when no rally could be captured. */
    static final Duration RETRY = Duration.ofMinutes(15);
    /** The Bear Trap's hours with a margin each side (local time): no rehearsal runs through them. */
    static final LocalTime BEAR_TRAP_FROM = LocalTime.of(13, 25);
    static final LocalTime BEAR_TRAP_UNTIL = LocalTime.of(14, 40);

    private FacilityDryRun() {
    }

    /** "2026-11-14T20:00/2026-11-14T23:00" (UTC). Anything else is not armed. */
    static Optional<Window> parse(String text) {
        if (text == null || text.isBlank()) return Optional.empty();
        String[] parts = text.trim().split("/");
        if (parts.length != 2) return Optional.empty();
        try {
            LocalDateTime start = LocalDateTime.parse(parts[0].trim());
            LocalDateTime end = LocalDateTime.parse(parts[1].trim());
            return end.isAfter(start) ? Optional.of(new Window(start, end)) : Optional.empty();
        } catch (java.time.format.DateTimeParseException e) {
            return Optional.empty();
        }
    }

    static boolean inBearTrapHours(LocalTime localNow) {
        return !localNow.isBefore(BEAR_TRAP_FROM) && localNow.isBefore(BEAR_TRAP_UNTIL);
    }

    /**
     * @param localNowTime the machine's local clock time, for the Bear Trap hours
     * @param localToUtcOffset how far ahead the local clock is of UTC, to turn a local wake-up into UTC
     */
    static Decision decide(Optional<Window> window, LocalDateTime nowUtc, LocalTime localNowTime,
                           Duration localToUtcOffset) {
        if (window.isEmpty()) return new Decision(Kind.NOT_ARMED, nowUtc);
        Window w = window.get();
        if (!nowUtc.isBefore(w.endUtc())) return new Decision(Kind.EXPIRED, nowUtc);
        if (nowUtc.isBefore(w.startUtc())) return new Decision(Kind.WAIT, w.startUtc());
        if (inBearTrapHours(localNowTime)) {
            // Look again when the Bear Trap's hours are over, never past the window's end.
            LocalDateTime resume = nowUtc.plus(Duration.between(localNowTime, BEAR_TRAP_UNTIL));
            return new Decision(Kind.WAIT, resume.isBefore(w.endUtc()) ? resume : w.endUtc());
        }
        return new Decision(Kind.TRY, nowUtc);
    }
}
