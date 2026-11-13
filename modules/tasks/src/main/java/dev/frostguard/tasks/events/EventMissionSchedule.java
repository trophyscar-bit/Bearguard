package dev.frostguard.tasks.events;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * When an event-mission task passes by: every three hours from the 00:00 UTC reset, plus two fixed passes
 * before the next reset (22:00 and 23:30 UTC, 17:00 and 18:30 EST) so a mission that completes late in the
 * day is claimed before it goes. Written in UTC, so it follows the game's reset whatever the local zone
 * and daylight saving do.
 */
final class EventMissionSchedule {

    /** The first pass after the reset, a few minutes in so the new day's missions have loaded. */
    static final LocalTime FIRST_PASS_UTC = LocalTime.of(0, 15);
    static final int GRID_HOURS = 3;
    /** The two guaranteed late passes, in UTC. */
    static final List<LocalTime> FIXED_PASSES_UTC = List.of(LocalTime.of(22, 0), LocalTime.of(23, 30));
    /** A grid pass this close before a fixed pass is dropped, since the fixed one covers it. */
    private static final int GRID_CLEARANCE_MINUTES = 60;

    private static final List<LocalTime> PASSES_UTC = buildPasses();

    private EventMissionSchedule() {
    }

    static List<LocalTime> passesUtc() {
        return PASSES_UTC;
    }

    /** The first pass strictly after {@code now}, as a local date-time in {@code now}'s zone. */
    static LocalDateTime nextPass(ZonedDateTime now) {
        return nextPassAtOrAfter(now.plusNanos(1));
    }

    /** The first pass at or after {@code from}, as a local date-time in {@code from}'s zone. */
    static LocalDateTime nextPassAtOrAfter(ZonedDateTime from) {
        ZonedDateTime utc = from.withZoneSameInstant(ZoneOffset.UTC);
        for (int day = 0; day <= 1; day++) {
            for (LocalTime pass : PASSES_UTC) {
                ZonedDateTime candidate = utc.toLocalDate().plusDays(day).atTime(pass).atZone(ZoneOffset.UTC);
                if (!candidate.isBefore(utc)) {
                    return candidate.withZoneSameInstant(from.getZone()).toLocalDateTime();
                }
            }
        }
        throw new IllegalStateException("no pass in the next two days");
    }

    static LocalDateTime nextPass(ZoneId zone) {
        return nextPass(ZonedDateTime.now(zone));
    }

    private static List<LocalTime> buildPasses() {
        List<LocalTime> passes = new ArrayList<>();
        LocalTime grid = FIRST_PASS_UTC;
        do {
            if (!coveredByFixedPass(grid)) {
                passes.add(grid);
            }
            grid = grid.plusHours(GRID_HOURS);
        } while (grid.isAfter(FIRST_PASS_UTC));
        passes.addAll(FIXED_PASSES_UTC);
        passes.sort(LocalTime::compareTo);
        return List.copyOf(passes);
    }

    private static boolean coveredByFixedPass(LocalTime grid) {
        return FIXED_PASSES_UTC.stream().anyMatch(fixed -> {
            long minutesBefore = Duration.between(grid, fixed).toMinutes();
            return minutesBefore >= 0 && minutesBefore < GRID_CLEARANCE_MINUTES;
        });
    }
}
