package dev.frostguard.engine.service;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.data.entity.EventScheduleEntry;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/**
 * Asks the State calendar whether an event is running before a task goes looking for it.
 *
 * <p>An event task that is enabled but whose event is not on this week is not broken: Alliance
 * Mobilization logged "tab was not in view" six times in one night for the ordinary reason that the
 * event was over. The calendar already knows when each event runs, so the task can park until the
 * next start instead of hunting a tab the game is not showing.</p>
 *
 * <p>Only a calendar that has actually been read today may park a task. The marker bg_calendar
 * writes after a completed sweep is the evidence; without it, or with yesterday's, the verdict is
 * {@link Verdict#shouldRun()} and the task behaves exactly as it did before. A calendar that fails
 * to read must never look like an event that is not running -- that would turn tasks off silently,
 * which is the one failure nobody would see in the log.</p>
 */
public final class EventCalendarGate {

    /** Local time, the way the task log writes every other scheduled moment. */
    private static final DateTimeFormatter START_FORMAT = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss");
    /** Long enough past the UTC day roll for bg_calendar's sweep (00:12 UTC) to have finished. */
    private static final int SWEEP_SETTLED_MINUTES = 45;

    private EventCalendarGate() {
    }

    /** What the calendar says about one event at one moment. */
    public static final class Verdict {

        private final boolean run;
        private final LocalDateTime nextStartUtc;
        private final String reason;

        private Verdict(boolean run, LocalDateTime nextStartUtc, String reason) {
            this.run = run;
            this.nextStartUtc = nextStartUtc;
            this.reason = reason;
        }

        /** True when the task should carry on: the event is running, or the calendar cannot say. */
        public boolean shouldRun() {
            return run;
        }

        /** When the event opens next, present only on a verdict that parks the task. */
        public Optional<LocalDateTime> nextStartUtc() {
            return Optional.ofNullable(nextStartUtc);
        }

        /** The same instant in the machine's own zone, which is what the scheduler compares against. */
        public Optional<LocalDateTime> nextStartLocal() {
            return nextStartUtc()
                    .map(utc -> utc.atOffset(ZoneOffset.UTC)
                            .atZoneSameInstant(ZoneId.systemDefault())
                            .toLocalDateTime());
        }

        /** One line for the log, naming the event and what the calendar said about it. */
        public String reason() {
            return reason;
        }
    }

    /**
     * The calendar's verdict for {@code eventName}, whose rows the scan keys by its upper-cased name.
     *
     * @param profile   the profile whose calendar was read; its scan marker decides whether the rows
     *                  are fresh enough to park a task on
     * @param eventName the event's name as the calendar stores it, for example "Alliance Mobilization"
     */
    public static Verdict check(AccountDescriptor profile, String eventName, LocalDateTime nowUtc) {
        String marker = profile == null ? null : profile.getConfig(
                ConfigurationKeyEnum.BG_TELEMETRY_LAST_STATE_CALENDAR_SCAN_DATE_STRING, String.class);
        return check(EventScheduleService.obtain().findAll(), marker, eventName, nowUtc);
    }

    /**
     * The key prefix the State-calendar scan writes for an event, which is its name upper-cased with
     * every run of other characters turned into one underscore, and the start date appended.
     */
    public static String keyPrefixFor(String eventName) {
        String name = eventName == null ? "" : eventName;
        return "STATE_GANTT_" + name.toUpperCase().replaceAll("[^A-Z0-9]+", "_") + "_";
    }

    static Verdict check(List<EventScheduleEntry> entries, String scanMarker, String eventName,
                         LocalDateTime nowUtc) {
        if (!readToday(scanMarker, nowUtc)) {
            return new Verdict(true, null, eventName
                    + ": the calendar has not been read for " + nowUtc.toLocalDate()
                    + ", so this pass goes ahead as it would without a calendar.");
        }

        Optional<EventScheduleEntry> window =
                EventScheduleService.windowFor(entries, keyPrefixFor(eventName), nowUtc);
        if (window.isEmpty()) {
            // A completed sweep that did not draw this event is evidence that it is not on: the chart
            // shows every event of the week, so absence from a chart that was read is absence from
            // the week. The 10 Nov sweep found seventeen live events and Alliance Mobilization was
            // not among them, which is exactly the night it logged six missing-tab warnings.
            return new Verdict(false, nextSweep(nowUtc), eventName
                    + " is not running; today's calendar does not carry it.");
        }

        EventScheduleEntry entry = window.get();
        LocalDateTime start = entry.getActiveSince();
        if (start == null || !start.isAfter(nowUtc)) {
            return new Verdict(true, null, eventName + " is running.");
        }
        LocalDateTime startLocal = start.atOffset(ZoneOffset.UTC)
                .atZoneSameInstant(ZoneId.systemDefault())
                .toLocalDateTime();
        return new Verdict(false, start, eventName + " is not running; the calendar has it starting "
                + startLocal.format(START_FORMAT) + ".");
    }

    /**
     * When the calendar is next read: after the game's day rolls at 00:00 UTC, which is when
     * bg_calendar sweeps. Parking an event with no window until then is as long as the evidence for
     * its absence lasts -- tomorrow's chart gets its own say.
     */
    private static LocalDateTime nextSweep(LocalDateTime nowUtc) {
        return nowUtc.toLocalDate().plusDays(1).atStartOfDay().plusMinutes(SWEEP_SETTLED_MINUTES);
    }

    /**
     * Whether a sweep finished for today's game day. The marker is "{@code <UTC date>/<version>}", so
     * only its date part is compared -- a scanner version bump is not a reason to distrust the rows
     * it has already stored.
     */
    private static boolean readToday(String scanMarker, LocalDateTime nowUtc) {
        if (scanMarker == null || scanMarker.isBlank()) {
            return false;
        }
        String date = scanMarker.split("/", 2)[0].trim();
        try {
            return LocalDate.parse(date).equals(nowUtc.toLocalDate());
        } catch (RuntimeException notADate) {
            return false;
        }
    }
}
