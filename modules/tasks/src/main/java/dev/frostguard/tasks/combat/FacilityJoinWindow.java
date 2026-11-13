package dev.frostguard.tasks.combat;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * When the Facility rally joiner runs. The start comes from the calendar gate; it has no schedule of
 * its own. An unknown start means "ask later", never "now".
 */
final class FacilityJoinWindow {

    enum Kind { UNKNOWN, WAIT, RUN }

    /** @param at when to look again (UNKNOWN, WAIT) or when the window closes (RUN); UTC. */
    record Decision(Kind kind, LocalDateTime at) {
    }

    /** How long before the start the bot opens the War screen: time enough to recall gatherers so every march is free to join. */
    static final Duration LEAD = Duration.ofMinutes(5);
    /** How long before the start the pre-battle check runs. */
    static final Duration PREFLIGHT_LEAD = Duration.ofMinutes(30);
    /** How often to ask again when the calendar has no time. */
    static final Duration ASK_AGAIN = Duration.ofMinutes(30);

    /** How long a candidate time is checked for a live facility rally before it is given up as a lost vote. */
    static final Duration PROBE = Duration.ofMinutes(15);

    private FacilityJoinWindow() {
    }

    /**
     * Until a facility rally is seen the time is only a candidate (the alliance's sign-up picks three
     * times and the game keeps one battle), so it is watched for {@link #PROBE}, counted from now when
     * the bot arrives late, and never past the battle's end.
     */
    static LocalDateTime probeEnd(LocalDateTime nowUtc, LocalDateTime startUtc, Duration length, Duration probeLength) {
        LocalDateTime end = startUtc.plus(length);
        LocalDateTime probe = nowUtc.isAfter(startUtc) ? nowUtc.plus(probeLength) : startUtc.plus(probeLength);
        return probe.isBefore(end) ? probe : end;
    }

    /** True from the pre-battle check's time until the joiner opens the War screen. */
    static boolean preflightDue(LocalDateTime nowUtc, LocalDateTime startUtc) {
        return !nowUtc.isBefore(startUtc.minus(PREFLIGHT_LEAD)) && nowUtc.isBefore(startUtc.minus(LEAD));
    }

    static Decision decide(LocalDateTime nowUtc, Optional<LocalDateTime> startUtc, Duration length) {
        if (startUtc.isEmpty()) return new Decision(Kind.UNKNOWN, nowUtc.plus(ASK_AGAIN));
        LocalDateTime start = startUtc.get();
        LocalDateTime end = start.plus(length);
        if (!nowUtc.isBefore(end)) return new Decision(Kind.UNKNOWN, nowUtc.plus(ASK_AGAIN));
        if (nowUtc.isBefore(start.minus(PREFLIGHT_LEAD))) return new Decision(Kind.WAIT, start.minus(PREFLIGHT_LEAD));
        if (nowUtc.isBefore(start.minus(LEAD))) return new Decision(Kind.WAIT, start.minus(LEAD));
        return new Decision(Kind.RUN, end);
    }
}
