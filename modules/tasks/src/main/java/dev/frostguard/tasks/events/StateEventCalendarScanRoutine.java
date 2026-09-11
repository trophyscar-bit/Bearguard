package dev.frostguard.tasks.events;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.OcrSettingsData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.engine.nav.SearchConfigConstants;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.engine.service.EventScheduleService;

/**
 * Reads Events -&gt; Calendar -- the state-wide event week grid -- once a day, feeding the
 * sidebar's "Upcoming Events" calendar. Read-only.
 *
 * <p>Live-checked 2026-09-11: the panel header prints an explicit
 * {@code "UTC Time yyyy-MM-dd HH:mm:ss"} line (useful as a clock-sync sanity check), followed by
 * seven day columns (e.g. {@code "Fri 09/11"}) with the current day highlighted. That week's grid
 * had no event bars posted in it, so the exact on-screen shape of a populated day (bar text,
 * whether it prints a time range or just a label) is <b>not yet observed</b> -- this looks for a
 * plain {@code HH:mm-HH:mm} range near each day label as a best-effort first pass and logs the
 * raw panel text at DEBUG every run specifically so the real format can be confirmed and this
 * parser tightened the next time a state event is actually scheduled. Do not trust
 * {@link #recordStateEventsFrom(String)} as complete until that happens.</p>
 */
public class StateEventCalendarScanRoutine extends DelayedTask {

    private static final ZoneId RUN_ZONE = ZoneId.of("America/New_York");
    private static final LocalTime DAILY_RUN_TIME = LocalTime.of(20, 5);

    private static final int PANEL_SETTLE_MS = 1200;

    private static final PointData PANEL_TOP_LEFT = new PointData(10, 230);
    private static final PointData PANEL_BOTTOM_RIGHT = new PointData(710, 1200);

    private static final PointData TAB_STRIP_SWIPE_FROM = new PointData(150, 141);
    private static final PointData TAB_STRIP_SWIPE_TO = new PointData(600, 141);

    private static final Pattern DAY_LABEL = Pattern.compile(
            "(Mon|Tue|Wed|Thu|Fri|Sat|Sun)\\s*(\\d{2}/\\d{2})");
    private static final Pattern TIME_RANGE = Pattern.compile("(\\d{1,2}:\\d{2})\\s*[-–]\\s*(\\d{1,2}:\\d{2})");

    public StateEventCalendarScanRoutine(AccountDescriptor profile, TpDailyTaskEnum tpDailyTask) {
        super(profile, tpDailyTask);
    }

    @Override
    protected LaunchPoint getRequiredStartLocation() {
        return LaunchPoint.WORLD;
    }

    @Override
    protected void execute() {
        ImageSearchResultData eventsBtn = templateSearchHelper.locatePattern(
                TemplatesEnum.HOME_EVENTS_BUTTON, SearchConfigConstants.SINGLE_WITH_RETRIES);
        if (!eventsBtn.isFound()) {
            logInfo("StateEventCalendarScanRoutine | Events icon not found; rechecking tomorrow.");
            reschedule(nextRunTime());
            return;
        }
        tapNear(eventsBtn.getPoint());
        sleepTask(PANEL_SETTLE_MS);

        ImageSearchResultData calendarTab = templateSearchHelper.locatePattern(
                TemplatesEnum.EVENTS_CALENDAR_TAB, SearchConfigConstants.SINGLE_WITH_RETRIES);
        if (!calendarTab.isFound()) {
            // Calendar is the leftmost tab; if a prior session left the strip scrolled away from
            // it, one swipe toward the start brings it back into view.
            swipe(TAB_STRIP_SWIPE_FROM, TAB_STRIP_SWIPE_TO);
            sleepTask(600);
            calendarTab = templateSearchHelper.locatePattern(
                    TemplatesEnum.EVENTS_CALENDAR_TAB, SearchConfigConstants.QUICK_SEARCH);
        }
        if (!calendarTab.isFound()) {
            logWarning("StateEventCalendarScanRoutine | Calendar tab not found even after scrolling; "
                    + "skipping this pass.");
            pressBack();
            reschedule(nextRunTime());
            return;
        }
        tapNear(calendarTab.getPoint());
        sleepTask(PANEL_SETTLE_MS);

        String panelText = readPanelBlock();
        logDebug("StateEventCalendarScanRoutine | raw panel text: " + panelText);
        int recorded = recordStateEventsFrom(panelText);
        logInfo("StateEventCalendarScanRoutine | Recorded " + recorded + " state event day(s).");

        pressBack();
        sleepTask(600);
        pressBack();

        reschedule(nextRunTime());
    }

    /**
     * Best-effort parse: for each recognised day label, looks for a time range on the same line.
     * Deliberately conservative -- see the class-level note. Writes nothing when no range is
     * found near a day, rather than guessing.
     */
    private int recordStateEventsFrom(String panelText) {
        if (panelText == null || panelText.isBlank()) {
            return 0;
        }

        int recorded = 0;
        Matcher dayMatcher = DAY_LABEL.matcher(panelText);
        int previousEnd = -1;
        String previousDay = null;

        while (dayMatcher.find()) {
            if (previousDay != null) {
                recorded += recordOneDay(previousDay, panelText.substring(previousEnd, dayMatcher.start()));
            }
            previousDay = dayMatcher.group(1) + " " + dayMatcher.group(2);
            previousEnd = dayMatcher.end();
        }
        if (previousDay != null) {
            recorded += recordOneDay(previousDay, panelText.substring(previousEnd));
        }
        return recorded;
    }

    private int recordOneDay(String dayLabel, String followingText) {
        Matcher range = TIME_RANGE.matcher(followingText);
        if (!range.find()) {
            return 0;
        }

        LocalDate date = parseDayLabelDate(dayLabel);
        if (date == null) {
            return 0;
        }

        LocalDateTime start = date.atTime(parseHm(range.group(1)));
        LocalDateTime end = date.atTime(parseHm(range.group(2)));
        if (end.isBefore(start)) {
            end = end.plusDays(1);
        }

        EventScheduleService.obtain().recordWindow(
                "STATE_CALENDAR_" + date, "State Event (" + dayLabel + ")", false, start, end);
        logInfo("StateEventCalendarScanRoutine | " + dayLabel + ": " + range.group(1) + "-" + range.group(2) + ".");
        return 1;
    }

    private LocalTime parseHm(String hm) {
        String[] parts = hm.split(":");
        return LocalTime.of(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
    }

    /** The day column only prints "MM/dd"; the year is inferred from the current UTC date. */
    private LocalDate parseDayLabelDate(String dayLabel) {
        try {
            String[] parts = dayLabel.split("\\s+");
            String[] md = parts[1].split("/");
            int month = Integer.parseInt(md[0]);
            int day = Integer.parseInt(md[1]);
            LocalDate today = LocalDate.now(java.time.ZoneOffset.UTC);
            LocalDate candidate = LocalDate.of(today.getYear(), month, day);
            // The calendar shows a week straddling "today"; a Jan candidate seen while today is in
            // December means the label rolled into next year, and vice versa.
            if (candidate.isBefore(today.minusDays(30))) {
                candidate = candidate.plusYears(1);
            } else if (candidate.isAfter(today.plusDays(30))) {
                candidate = candidate.minusYears(1);
            }
            return candidate;
        } catch (Exception ex) {
            return null;
        }
    }

    private LocalDateTime nextRunTime() {
        ZonedDateTime nowEst = ZonedDateTime.now(RUN_ZONE);
        ZonedDateTime candidate = nowEst.toLocalDate().atTime(DAILY_RUN_TIME).atZone(RUN_ZONE);
        if (!candidate.isAfter(nowEst)) {
            candidate = candidate.plusDays(1);
        }
        return candidate.withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
    }

    private String readPanelBlock() {
        try {
            return stringHelper.attemptRecognition(
                    PANEL_TOP_LEFT, PANEL_BOTTOM_RIGHT, 2, 200L,
                    OcrSettingsData.forTextBlock(),
                    s -> s != null && !s.isBlank(),
                    String::trim);
        } catch (Exception ex) {
            return null;
        }
    }
}
