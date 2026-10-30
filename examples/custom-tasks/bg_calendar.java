package dev.frostguard.engine.listener.task.impl;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.JobMetrics;
import dev.frostguard.api.domain.OcrSettingsData.TextLayout;
import dev.frostguard.api.domain.OcrSettingsData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.ProfilesData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.api.runtime.WorkspacePaths;
import dev.frostguard.engine.helper.BearTrapHelper;
import dev.frostguard.engine.helper.NavigationHelper;
import dev.frostguard.engine.helper.TimeWindowHelper;
import dev.frostguard.engine.nav.SearchConfigConstants;
import dev.frostguard.engine.schedule.BearTrapParticipationSchedule;
import dev.frostguard.engine.schedule.CustomTaskConfigurable;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.engine.service.ConfigService;
import dev.frostguard.engine.service.CustomTaskService;
import dev.frostguard.engine.service.EventScheduleService;
import dev.frostguard.engine.service.StatisticsService;
import dev.frostguard.vision.convert.ImageConverter;
import dev.frostguard.vision.match.OpenCvPatternLocator;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the in-game Events -> Calendar chart and mirrors it into the Upcoming Events panel.
 *
 * <p>Its own task, on its own schedule, because the chart changes once a day and nothing else does.
 * It used to ride along with bg_telemetry's hourly HUD sample, which meant an hour's worth of taps
 * and swipes through the Events panel for a chart that had not moved -- and a stopped and restarted
 * bot swept it again every time it came up. Now: one sweep per game day, after the day rolls at
 * 00:00 UTC, and a restart does not earn another one.</p>
 */
public class bg_calendar extends DelayedTask implements CustomTaskConfigurable {

    /** Minutes past the UTC rollover to start, so the scan never races the game's own day change. */
    private static final int ROLLOVER_PAD_MINUTES = 12;
    private static final DateTimeFormatter UTC_INPUT_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    public bg_calendar(AccountDescriptor profile, TpDailyTaskEnum tpTask) {
        super(profile, tpTask);
        // Scheduling is in LOCAL time: TaskQueue compares against LocalDateTime.now().
        reschedule(LocalDateTime.now());
    }

    @Override
    protected Object getDistinctKey() {
        return "bg_calendar";
    }

    @Override
    protected LaunchPoint getRequiredStartLocation() {
        return LaunchPoint.WORLD;
    }

    @Override
    public void applyCustomTaskSettings(CustomTaskService.CustomTaskSettings settings) {
        if (settings == null) {
            return;
        }
        String first = settings.getFirstExecutionUtc();
        if (first != null && !first.isBlank()) {
            try {
                reschedule(LocalDateTime.parse(first, UTC_INPUT_FORMATTER)
                        .atOffset(ZoneOffset.UTC)
                        .atZoneSameInstant(ZoneId.systemDefault())
                        .toLocalDateTime());
            } catch (RuntimeException unparseable) {
                logWarning("bg_calendar | Unparseable first-execution time '" + first + "', starting now.");
            }
        }
    }

    @Override
    protected void execute() {
        try {
            maybeScanStateCalendar();
        } finally {
            scheduleNextRollover();
        }
    }

    /**
     * Next run is just after the game's next UTC day change, which is the only moment the chart has
     * anything new to say. The daily marker still guards the scan itself, so an early or repeated
     * execution -- a restart, a manual run -- reads nothing.
     */
    private void scheduleNextRollover() {
        setRecurring(true);
        LocalDateTime nextRollover = LocalDate.now(ZoneOffset.UTC)
                .plusDays(1)
                .atStartOfDay()
                .plusMinutes(ROLLOVER_PAD_MINUTES)
                .atOffset(ZoneOffset.UTC)
                .atZoneSameInstant(ZoneId.systemDefault())
                .toLocalDateTime();
        reschedule(nextRollover);
        logInfo("bg_calendar | Next calendar read at " + nextRollover + " local, after the game's day rolls.");
    }

    /**
     * The header clock ("UTC MM-dd HH:mm:ss", top-left on every screen) opens a "Task List"
     * panel: dated sections (an explicit "yyyy/MM/dd" bar, or "Today") each holding entries --
     * "Fortress Battles" / "Fortress No. N" or "Stronghold No. N" with a right-side "Ended" or a
     * start time ("HH:mm"), and "Bear Hunt - Trap N" the same way. This is a strictly better
     * alliance-events source than the Battle -&gt; Fortress summary (real dated start times, plus
     * Bear Hunt, in one screen) so it's additive alongside the existing Fortress-panel scan
     * rather than replacing it -- distinct key prefix, no collision.
     *
     * <p>Live-verified 2026-09-11 against a real "Local Time"-toggled list showing
     * "2026/09/10 -&gt; Fortress No. 9: Ended", "Today -&gt; Stronghold No. 2: Ended",
     * "Today -&gt; Fortress No. 12" (highlighted/in-progress, no time shown), and
     * "Today -&gt; Bear Hunt - Trap 1: 15:00".</p>
     */
    private static final PointData HEADER_CLOCK_POINT = new PointData(230, 23);

    // â”€â”€ Upcoming Events calendar scans â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    //
    // Read-only: never taps Claim, Enable, Occupation Income, or anything else on these screens.
    // Hall of Chiefs / Brothers in Arms / Defeat Nearby Beasts and the Fortress read run every
    // pass (hourly); the state Calendar read is gated to the first pass of each UTC day (see
    // maybeScanStateCalendar) since it has nothing new to say between two game resets.

    private static final TemplatesEnum[] ROTATING_EVENT_TABS = {
            TemplatesEnum.EVENTS_TAB_HALL_OF_CHIEFS,
            TemplatesEnum.EVENTS_TAB_BROTHERS_IN_ARMS,
            TemplatesEnum.EVENTS_TAB_DEFEAT_BEASTS,
    };

    /** The steel-blue section divider ("Deals", "Events"): never a bar. */
    private static final int[] GANTT_SECTION_HEADER = {112, 184, 209};
    /** "UTC Time 2026-09-16 02:44:52" -- the banner directly above the day columns. */
    private static final PointData GAME_CLOCK_TOP_LEFT = new PointData(120, 226);
    private static final PointData GAME_CLOCK_BOTTOM_RIGHT = new PointData(604, 290);
    private static final Pattern GAME_CLOCK_DATE = Pattern.compile("([0-9]{4})[-/]([0-9]{2})[-/]([0-9]{2})");
    /** "2026-09-18 00:00 - 2026-09-19 24:00" -- the card's own window line, always in UTC. */
    private static final Pattern TOOLTIP_WINDOW = Pattern.compile(
            "([0-9]{4})-([0-9]{2})-([0-9]{2}) +([0-9]{1,2}):([0-9]{2}) *- *"
                    + "([0-9]{4})-([0-9]{2})-([0-9]{2}) +([0-9]{1,2}):([0-9]{2})");

    private static final String STATE_GANTT_KEY_PREFIX = "STATE_GANTT_";

    /** Bump whenever a bar's span or name is read differently, to invalidate the stored scan. */
    private static final String SCAN_FORMAT_VERSION = "v12";

    /** The game day this session already read, so a failed marker write cannot loop the scan. */
    private String calendarScannedThisSession;

    /** Swipes back along the Events tab strip before giving up on finding Calendar. */
    private static final int CALENDAR_TAB_SWIPE_ATTEMPTS = 4;

    /** Shorter than this and the read is noise, not an event name. */
    private static final int MIN_CREDIBLE_LABEL_LENGTH = 3;

    /** The operator-owned list of real event names, under the workspace data directory. */
    private static final String CALENDAR_NAMES_FILE = "calendar-events.txt";

    /** Measured on three days of real reads: worst correct 76, best wrong 50. */
    private static final double NAME_MATCH_MIN_SCORE = 65.0;

    private static final double NAME_MATCH_MIN_MARGIN = 12.0;

    private static final int PANEL_SETTLE_MS = 1200;

    /** Gated to once per game day: the first hourly bg_telemetry run of each UTC date, tracked in
     *  BG_TELEMETRY_LAST_STATE_CALENDAR_SCAN_DATE_STRING. The game's own day rolls at 00:00 UTC, so
     *  that first pass is also the freshest the chart gets, and the calendar has nothing new to say
     *  between two resets.
     *
     *  <p>The stored value carries SCAN_FORMAT_VERSION so that changing how a bar is read or named
     *  invalidates what the previous version wrote, instead of leaving a day's worth of rows that
     *  no later pass will ever revisit.</p> */
    private void maybeScanStateCalendar() {
        String gameDay = LocalDate.now(ZoneOffset.UTC) + "/" + SCAN_FORMAT_VERSION;
        if (gameDay.equals(calendarScannedThisSession)) {
            return;
        }
        String lastScanned = profile.getConfig(
                ConfigurationKeyEnum.BG_TELEMETRY_LAST_STATE_CALENDAR_SCAN_DATE_STRING, String.class);
        if (gameDay.equals(lastScanned)) {
            calendarScannedThisSession = gameDay;
            logInfo("bg_calendar | Calendar: already read for " + gameDay + "; not reading it again"
                    + " until the game's next UTC day.");
            return;
        }

        // The Events button lives on the base screen, and the scans before this one leave panels
        // open. Without this the tap lands on whatever is in front and the panel never opens.
        navigationHelper.ensureCorrectScreenLocation(LaunchPoint.WORLD);

        ImageSearchResultData eventsBtn = templateSearchHelper.locatePattern(
                TemplatesEnum.HOME_EVENTS_BUTTON, SearchConfigConstants.SINGLE_WITH_RETRIES);
        if (!eventsBtn.isFound()) {
            logInfo("bg_calendar | Events icon not found; will retry the state calendar scan next run.");
            return;
        }
        tapNear(eventsBtn.getPoint());
        sleepTask(PANEL_SETTLE_MS);

        // Calendar is the leftmost tab, and the strip keeps whatever scroll position the panel was
        // last left at. One swipe back was not enough: the strip grows with the number of events
        // running, so it takes several to reach the start during a busy week.
        ImageSearchResultData calendarTab = templateSearchHelper.locatePattern(
                TemplatesEnum.EVENTS_CALENDAR_TAB, SearchConfigConstants.SINGLE_WITH_RETRIES);
        for (int attempt = 0; attempt < CALENDAR_TAB_SWIPE_ATTEMPTS && !calendarTab.isFound(); attempt++) {
            swipe(CALENDAR_TAB_STRIP_SWIPE_FROM, CALENDAR_TAB_STRIP_SWIPE_TO);
            sleepTask(600);
            calendarTab = templateSearchHelper.locatePattern(
                    TemplatesEnum.EVENTS_CALENDAR_TAB, SearchConfigConstants.QUICK_SEARCH);
        }
        if (!calendarTab.isFound()) {
            logWarning("bg_calendar | Calendar tab not found after " + CALENDAR_TAB_SWIPE_ATTEMPTS
                    + " swipes back along the tab strip; will retry next run.");
            pressBack();
            return;
        }
        tapNear(calendarTab.getPoint());
        sleepTask(PANEL_SETTLE_MS);

        // The chart is a complete snapshot of the state schedule, so this pass replaces the last
        // one outright. Keeping the old rows would leave a bar the game has dropped, or one whose
        // name this pass read differently, sitting beside its own replacement.
        // Loaded once per scan rather than per bar: both passes read the same library, and it is
        // re-read each scan so a name corrected in the folder takes effect without a restart.
        iconLibrary = loadIconLibrary();
        knownEventNames = loadKnownEventNames();
        unidentifiedCount = 0;
        unidentifiedIdentity = null;
        tooltipsRead = 0;
        cardWindows.clear();
        calendarPassAborted = false;
        logInfo("bg_calendar | Calendar: icon library holds " + iconLibrary.size()
                + " entr(ies); event list holds " + knownEventNames.size() + " name(s).");

        pendingCalendarRows.clear();

        // Read the chart a screen at a time, in small slow steps, until it stops moving.
        //
        // It used to be one read, one swipe, one more read. That swipe was a fling, and a fling
        // carries the list well past where the finger stopped: two reads covered the top screen and
        // the bottom screen and skipped the middle outright. On a live capture that dropped Alliance
        // Mobilization and Foundry Battle every single pass. A slow drag moves exactly as far as it is
        // told, each step overlaps the last so no bar can fall between two reads, and a frame that
        // is still moving is never read -- which is also why labels were coming back empty.
        int recorded = readGanttChart(false);
        long previous = chartFingerprint();
        int steps = 0;
        while (steps < CALENDAR_MAX_SCROLL_STEPS && !calendarPassAborted) {
            swipe(CALENDAR_STEP_FROM, CALENDAR_STEP_TO, CALENDAR_STEP_DURATION_MS);
            sleepTask(CALENDAR_STEP_SETTLE_MS);
            steps++;
            long current = chartFingerprint();
            if (current != 0 && current == previous) {
                // The chart has stopped: this is the last screen there is. Read it once more with
                // clipped bars allowed, because the final bar sits against the footer and is never
                // shown whole -- skipping it here would lose that event on every pass.
                recorded += readGanttChart(true);
                break;
            }
            previous = current;
            recorded += readGanttChart(false);
        }
        if (steps >= CALENDAR_MAX_SCROLL_STEPS) {
            logWarning("bg_calendar | Calendar: still scrolling after " + CALENDAR_MAX_SCROLL_STEPS
                    + " steps; stopped there, so bars below that point were not read this pass.");
        }
        logInfo("bg_calendar | State calendar: read " + (steps + 1) + " screen(s), recorded "
                + recorded + " bar reading(s).");
        // Written only once the whole chart has been read. A pass that stopped part-way used to have
        // cleared the previous read already, and left the calendar holding the six bars of its one
        // screen; the previous full read is the better answer until a new one completes.
        if (calendarPassAborted) {
            logWarning("bg_calendar | Calendar: this pass stopped early; keeping the previous read"
                    + " and discarding " + pendingCalendarRows.size() + " partial row(s).");
        } else {
            int forgotten = EventScheduleService.obtain().forgetAll(STATE_GANTT_KEY_PREFIX);
            if (forgotten > 0) {
                logInfo("bg_calendar | Calendar: cleared " + forgotten + " row(s) from the previous read.");
            }
            for (Object[] row : pendingCalendarRows.values()) {
                EventScheduleService.obtain().recordWindow((String) row[0], (String) row[1],
                        (Boolean) row[2], (LocalDateTime) row[3], (LocalDateTime) row[4]);
            }
            logInfo("bg_calendar | Calendar: stored " + pendingCalendarRows.size() + " event(s).");
        }

        pressBack();
        sleepTask(600);
        pressBack();

        if (!calendarPassAborted) {
            // Through ConfigService, not profile.setConfig: that one only edits the descriptor held
            // in memory, so the marker died with the app and every restart read the whole chart
            // again -- a stopped and restarted bot could sweep it dozens of times a day.
            calendarScannedThisSession = gameDay;
            boolean saved = ConfigService.obtain().writeAccountSetting(profile,
                    ConfigurationKeyEnum.BG_TELEMETRY_LAST_STATE_CALENDAR_SCAN_DATE_STRING, gameDay);
            if (saved) {
                logInfo("bg_calendar | Calendar: read for " + gameDay
                        + "; the next read is after the game's next UTC day rolls over.");
            } else {
                logWarning("bg_calendar | Calendar: could not save the daily marker, so this scan"
                        + " will run again next pass.");
            }
        }
    }

    /** Stable keys matching the original EventKind enum names -- NOT TemplatesEnum.name()
     *  (EVENTS_TAB_HALL_OF_CHIEFS, ...), which briefly diverged and produced duplicate rows for
     *  the same event under two different keys. */
    private static final String[] ROTATING_EVENT_KEYS = {
            "HALL_OF_CHIEFS", "BROTHERS_IN_ARMS", "DEFEAT_NEARBY_BEASTS",
    };

    /**
     * Reads the Events -&gt; Calendar Gantt chart: seven day columns across the top, and horizontal
     * bars underneath, each spanning the days its event runs.
     *
     * <p>OCR alone cannot do this. A bar's span is geometry, not text, and the labels truncate the
     * moment the bar is narrower than the name ("Work...", "Fortr...", "Snow..."). So the span
     * comes from pixels and the name comes from OCR, with the leading icon as the fallback when
     * the text is cut off.</p>
     *
     * <p>Geometry measured against live 720x1280 frames (2026-09-12), both scroll positions.
     * Column coverage separated cleanly: every covered column sampled 0.97-1.00 non-background,
     * every uncovered one 0.00 -- so this is a real gap, not a threshold that needs nursing.</p>
     */
    private static final int GANTT_LEFT = 12;

    private static final int GANTT_RIGHT = 707;

    private static final int GANTT_DAY_COLUMNS = 7;

    private static final int GANTT_CONTENT_TOP = 392;

    private static final int GANTT_CONTENT_BOTTOM = 1200;

    private static final int GANTT_MIN_BAR_HEIGHT = 18;

    /** Midway between a clipped bar (21-34px) and a whole one (51-56px) on a live sweep. */
    private static final int GANTT_FULL_BAR_MIN_HEIGHT = 44;

    /** Pale panel blue, and the wash the game paints down today's column. */
    private static final int[][] GANTT_BACKGROUNDS = {{172, 225, 231}, {213, 195, 168}};

    private static final int[] GANTT_TODAY_WASH = {213, 195, 168};

    private static final int GANTT_COLOR_TOLERANCE = 28;

    private static final int CALENDAR_STEP_DURATION_MS = 1500;

    private static final int CALENDAR_STEP_SETTLE_MS = 1200;

    /** Far more than a busy week needs; only a stuck or unrecognised screen ever reaches it. */
    private static final int CALENDAR_MAX_SCROLL_STEPS = 20;

    /**
     * A match must score at least this much, and beat the runner-up by at least the margin. Both
     * are required: a bare threshold will happily pick between two icons that are equally wrong.
     *
     * <p>Scores are OpenCV TM_CCOEFF_NORMED on the repo's usual 0-100 scale, the same matcher and
     * scale every template search in the app already uses.</p>
     */
    private static final double ICON_MATCH_MIN_SCORE = 80.0;

    private static final double ICON_MATCH_MIN_MARGIN = 15.0;

    /** How far past the bar's own fill colour a pixel has to be to count as content. */
    private static final int ICON_FILL_TOLERANCE = 40;

    private static final int ICON_RUN_TO_CONFIRM = 6;

    /** The bar's own darker edge, excluded so it never lands in a stored template. */
    private static final int ICON_BAR_BORDER = 2;

    /** Blank pixels between the icon and the start of the label. */
    private static final int ICON_LABEL_GAP = 4;

    /** Reloaded at the start of each calendar scan; empty outside one. Values are encoded PNGs,
     *  passed straight to the matcher, so nothing here decodes or rescales an image by hand. */
    private Map<String, byte[]> iconLibrary = new LinkedHashMap<>();

    /** Reloaded alongside the icon library at the start of each calendar scan. */
    private java.util.List<String> knownEventNames = java.util.List.of();

    private static double ganttColumnWidth() {
        return (GANTT_RIGHT - GANTT_LEFT) / (double) GANTT_DAY_COLUMNS;
    }

    private static int ganttColumnStart(int column) {
        return GANTT_LEFT + (int) Math.round(column * ganttColumnWidth());
    }

    private static boolean nearColor(int rgb, int[] target, int tolerance) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        return Math.abs(r - target[0]) < tolerance
                && Math.abs(g - target[1]) < tolerance
                && Math.abs(b - target[2]) < tolerance;
    }

    private static boolean isGanttBackground(int rgb) {
        for (int[] background : GANTT_BACKGROUNDS) {
            if (nearColor(rgb, background, GANTT_COLOR_TOLERANCE)) {
                return true;
            }
        }
        return false;
    }

    /** Fraction of sampled pixels in this column/row that belong to a bar rather than the panel.
     *  Returns -1 for a section-header row, which must never be mistaken for a bar. */
    private static double ganttColumnCoverage(BufferedImage frame, int column, int y) {
        int x0 = ganttColumnStart(column);
        int x1 = ganttColumnStart(column + 1);
        int sampled = 0;
        int covered = 0;
        for (int x = x0 + 10; x < x1 - 10; x += 4) {
            if (x < 0 || x >= frame.getWidth() || y < 0 || y >= frame.getHeight()) {
                continue;
            }
            int rgb = frame.getRGB(x, y);
            if (nearColor(rgb, GANTT_SECTION_HEADER, 25)) {
                return -1;
            }
            sampled++;
            if (!isGanttBackground(rgb)) {
                covered++;
            }
        }
        return sampled == 0 ? 0 : covered / (double) sampled;
    }

    /** Which column carries today's highlight wash, or -1 when none does. */
    private static int ganttTodayColumn(BufferedImage frame) {
        int best = -1;
        double bestFraction = 0.25;
        for (int column = 0; column < GANTT_DAY_COLUMNS; column++) {
            int x0 = ganttColumnStart(column);
            int x1 = ganttColumnStart(column + 1);
            int sampled = 0;
            int washed = 0;
            for (int x = x0 + 8; x < x1 - 8; x += 4) {
                for (int y = GANTT_CONTENT_TOP; y < GANTT_CONTENT_BOTTOM; y += 8) {
                    if (x >= frame.getWidth() || y >= frame.getHeight()) {
                        continue;
                    }
                    sampled++;
                    if (nearColor(frame.getRGB(x, y), GANTT_TODAY_WASH, 22)) {
                        washed++;
                    }
                }
            }
            double fraction = sampled == 0 ? 0 : washed / (double) sampled;
            if (fraction > bestFraction) {
                bestFraction = fraction;
                best = column;
            }
        }
        return best;
    }

    /**
     * The bar's own fill colour, as the most common pixel along its middle row. Modal rather than
     * sampled at a fixed offset: the icon and the label both sit on that row, so any single sample
     * point is a coin toss, but they never outnumber the fill.
     */
    private static int ganttBarFill(BufferedImage frame, int barLeft, int barRight, int centreY) {
        Map<Integer, Integer> counts = new LinkedHashMap<>();
        int best = 0;
        int bestCount = 0;
        for (int x = barLeft + 6; x < barRight - 6 && x < frame.getWidth(); x++) {
            int rgb = frame.getRGB(x, centreY) & 0xFFFFFF;
            int count = counts.merge(rgb, 1, Integer::sum);
            if (count > bestCount) {
                bestCount = count;
                best = rgb;
            }
        }
        return best;
    }

    /**
     * Where the bar's icon starts, or -1 when the bar carries no content at all.
     *
     * <p>The icon is not at a fixed offset from the bar: the game centres the icon and label
     * together inside the bar, so a wide bar's icon can sit hundreds of pixels in. Reading from a
     * fixed offset is what put the icon in the middle of the label crop and produced names like
     * ": Vault of Enigma". Finding the first sustained run of non-fill pixels locates it directly,
     * whatever the bar's width.</p>
     */
    private static int ganttIconLeft(BufferedImage frame, int barLeft, int barRight, int centreY, int fill) {
        int[] fillRgb = {(fill >> 16) & 0xFF, (fill >> 8) & 0xFF, fill & 0xFF};
        int run = 0;
        for (int x = barLeft + 4; x < barRight - 4 && x < frame.getWidth(); x++) {
            if (nearColor(frame.getRGB(x, centreY), fillRgb, ICON_FILL_TOLERANCE)) {
                run = 0;
                continue;
            }
            run++;
            if (run >= ICON_RUN_TO_CONFIRM) {
                return x - run + 1;
            }
        }
        return -1;
    }

    /**
     * The icon's box within the bar: a square the height of the bar, anchored where the bar's
     * content starts.
     *
     * <p>Square because the game's event icons are square sprites sized to the bar. Measuring the
     * box from the pixels instead was tried and does not work: the bar carries a darker border
     * along its top and bottom edge, so every column reads as full height, and the label sits only
     * a few pixels after the icon, so no gap separates them. A box measured that way swallowed the
     * label, which made a stored template match its own text rather than its icon.</p>
     */
    private static int[] ganttIconBox(int iconLeft, int bandTop, int bandBottom, int frameWidth) {
        int side = (bandBottom - bandTop) - 2 * ICON_BAR_BORDER;
        if (side <= 0 || iconLeft + side > frameWidth) {
            return null;
        }
        return new int[]{iconLeft, bandTop + ICON_BAR_BORDER, iconLeft + side, bandTop + ICON_BAR_BORDER + side};
    }

    /**
     * Saves this bar's icon under a name that has already been established.
     *
     * <p>Only ever called with a name that matched the curated list. Saving under whatever the OCR
     * said was tried first and filled the library with "Fontns" and "aWork y", which would then
     * have been matched against for good. Never overwrites: an existing file is the operator's.</p>
     */
    private void learnIcon(BufferedImage frame, int[] box, String canonicalName) {
        String fileName = canonicalName.replaceAll("[^A-Za-z0-9 ]", "").trim().replace(' ', '_');
        if (fileName.isEmpty()) {
            return;
        }
        Path target = calendarIconDir().resolve(fileName + ".png");
        if (Files.exists(target)) {
            return;
        }
        try {
            Files.createDirectories(target.getParent());
            BufferedImage crop = frame.getSubimage(box[0], box[1], box[2] - box[0], box[3] - box[1]);
            javax.imageio.ImageIO.write(crop, "png", target.toFile());
            logInfo("bg_calendar | Calendar: learned the icon for \"" + canonicalName
                    + "\", so it no longer depends on reading that label.");
        } catch (IOException | RuntimeException writeFailed) {
            logWarning("bg_calendar | Calendar: could not save the icon for \"" + canonicalName
                    + "\": " + writeFailed.getMessage());
        }
    }

    /**
     * The canonical event names, one per line, from a file the operator owns.
     *
     * <p>Re-read every scan so a name can be added or corrected without a rebuild. Blank lines and
     * lines starting with '#' are ignored.</p>
     */
    private java.util.List<String> loadKnownEventNames() {
        Path file = WorkspacePaths.current().root().resolve("data").resolve(CALENDAR_NAMES_FILE);
        if (!Files.isRegularFile(file)) {
            return java.util.List.of();
        }
        try {
            java.util.List<String> names = new java.util.ArrayList<>();
            for (String line : Files.readAllLines(file)) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                    names.add(trimmed);
                }
            }
            return names;
        } catch (IOException unreadable) {
            logWarning("bg_calendar | Calendar: could not read " + CALENDAR_NAMES_FILE + ": "
                    + unreadable.getMessage());
            return java.util.List.of();
        }
    }

    /** Letters and digits only, lower case: the OCR noise this has to survive is punctuation,
     *  spacing and case far more often than it is a wrong letter. */
    private static String normaliseForMatch(String value) {
        return value.replaceAll("[^A-Za-z0-9]+", "").toLowerCase();
    }

    private static int editDistance(String a, String b) {
        if (a.length() < b.length()) {
            String swap = a;
            a = b;
            b = swap;
        }
        int[] previous = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            int[] current = new int[b.length() + 1];
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int substitute = previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(Math.min(previous[j] + 1, current[j - 1] + 1), substitute);
            }
            previous = current;
        }
        return previous[b.length()];
    }

    /**
     * How closely an OCR read matches one candidate name, 0-100.
     *
     * <p>Scored against the best-matching window of the read as well as the whole of it, because
     * the noise is mostly at the ends -- the icon's right edge and the bar's arrow tip bleed into
     * the crop, giving "vse e a VisionofDawn" -- while a genuinely different event differs in the
     * middle, where a window cannot rescue it.</p>
     */
    private static double nameSimilarity(String observed, String candidate) {
        String o = normaliseForMatch(observed);
        String n = normaliseForMatch(candidate);
        if (o.isEmpty() || n.isEmpty()) {
            return 0;
        }
        double best = 1.0 - editDistance(o, n) / (double) Math.max(o.length(), n.length());
        for (int i = 0; o.length() > n.length() && i + n.length() <= o.length(); i++) {
            String window = o.substring(i, i + n.length());
            best = Math.max(best, 1.0 - editDistance(window, n) / (double) n.length());
        }
        return best * 100.0;
    }

    /**
     * The canonical name this read is close enough to, or null.
     *
     * <p>Thresholds measured against every label the scan actually logged over three days: the
     * worst correct match scores 76, the best wrong one 50, so 65 sits in the middle of a 26 point
     * gap. The runner-up margin is what stops a bare "Alliance" being assigned to whichever of
     * Alliance Championship or Alliance Mobilization happens to sort first.</p>
     */
    private String snapToKnownName(String observed) {
        String best = null;
        double bestScore = -1;
        double runnerUp = -1;
        for (String candidate : knownEventNames) {
            double score = nameSimilarity(observed, candidate);
            if (score > bestScore) {
                runnerUp = bestScore;
                bestScore = score;
                best = candidate;
            } else if (score > runnerUp) {
                runnerUp = score;
            }
        }
        if (best == null || bestScore < NAME_MATCH_MIN_SCORE) {
            return null;
        }
        if (runnerUp >= 0 && bestScore - runnerUp < NAME_MATCH_MIN_MARGIN) {
            logInfo("bg_calendar | Calendar: \"" + observed + "\" scores " + Math.round(bestScore)
                    + " for \"" + best + "\" but " + Math.round(runnerUp)
                    + " for the runner-up, too close to call; leaving it unidentified.");
            return null;
        }
        return best;
    }

    private Path calendarIconDir() {
        return WorkspacePaths.current().root().resolve("data").resolve("calendar-icons");
    }

    /**
     * The icon library, keyed by event name. Files are plain PNGs named after the event, so a name
     * can be corrected or a new icon added by editing the folder -- no rebuild, no code change.
     *
     * <p>Curated, never self-taught. Saving each readable bar's icon under its own OCR'd name was
     * tried and is actively harmful: a misread put "Fontns" and "aWork y" into the library as event
     * names, and once written they would have been matched against for good. Every entry here is a
     * name somebody established.</p>
     */
    private Map<String, byte[]> loadIconLibrary() {
        Map<String, byte[]> library = new LinkedHashMap<>();
        Path dir = calendarIconDir();
        if (!Files.isDirectory(dir)) {
            return library;
        }
        try (java.util.stream.Stream<Path> files = Files.list(dir)) {
            for (Path file : files.filter(p -> p.toString().toLowerCase().endsWith(".png")).sorted().toList()) {
                try {
                    String name = file.getFileName().toString();
                    name = name.substring(0, name.length() - 4).replace('_', ' ');
                    library.put(name, Files.readAllBytes(file));
                } catch (IOException unreadable) {
                    logWarning("bg_calendar | Calendar: could not read icon " + file.getFileName()
                            + ": " + unreadable.getMessage());
                }
            }
        } catch (IOException listFailed) {
            logWarning("bg_calendar | Calendar: could not list the icon library: " + listFailed.getMessage());
        }
        return library;
    }

    /**
     * Best library name for this icon, or null when nothing matches clearly enough to say.
     *
     * <p>Every candidate is scored against the same captured frame, inside a box a few pixels
     * larger than the icon, so the matcher absorbs scroll drift itself instead of the caller
     * trying to cancel it out.</p>
     */
    private String matchIcon(RawImageData capture, int barLeft, int barRight, int bandTop, int bandBottom) {
        if (iconLibrary.isEmpty()) {
            return null;
        }
        // Searched across the whole bar, not a box around where the icon was located: the located
        // edge moves by up to a dozen pixels between two reads of the same chart, and letting the
        // matcher find the icon itself removes that from the answer entirely. Measured on a real
        // before/after-scroll pair: same icon 98.4-99.4, best wrong icon 56.2.
        PointData topLeft = new PointData(barLeft, bandTop);
        PointData bottomRight = new PointData(barRight, bandBottom + 1);

        // Two stages. Curated names are scored among themselves first, and this pass's unidentified
        // identities are consulted only when no curated name wins. Scoring them together let an
        // identity -- which is only a crop of some bar -- stand as runner-up to that very bar's real
        // icon, and the margin rule then refused a correct match it should never have been compared to.
        String curated = bestIconMatch(capture, topLeft, bottomRight, false);
        if (curated != null) {
            return curated;
        }
        return bestIconMatch(capture, topLeft, bottomRight, true);
    }

    private String bestIconMatch(RawImageData capture, PointData topLeft, PointData bottomRight,
                                 boolean unidentifiedOnly) {
        String best = null;
        double bestScore = -1;
        double runnerUp = -1;
        for (Map.Entry<String, byte[]> candidate : iconLibrary.entrySet()) {
            if (candidate.getKey().startsWith(UNIDENTIFIED_ICON_PREFIX) != unidentifiedOnly) {
                continue;
            }
            double score;
            try {
                score = OpenCvPatternLocator.matchFromRawTemplate(
                        capture, candidate.getValue(), topLeft, bottomRight, 0).getMatchScore();
            } catch (RuntimeException matchFailed) {
                logWarning("bg_calendar | Calendar: icon \"" + candidate.getKey()
                        + "\" could not be matched: " + matchFailed.getMessage());
                continue;
            }
            if (score > bestScore) {
                runnerUp = bestScore;
                bestScore = score;
                best = candidate.getKey();
            } else if (score > runnerUp) {
                runnerUp = score;
            }
        }
        if (best == null || bestScore < ICON_MATCH_MIN_SCORE) {
            return null;
        }
        if (runnerUp >= 0 && bestScore - runnerUp < ICON_MATCH_MIN_MARGIN) {
            logInfo("bg_calendar | Calendar: icon scores " + Math.round(bestScore) + " for \"" + best
                    + "\" but " + Math.round(runnerUp) + " for the runner-up, too close to call;"
                    + " leaving it unidentified.");
            return null;
        }
        return best;
    }

    /** The game's current UTC date as the chart itself states it, or null when the banner does not
     *  read. Every column is dated as an offset from this, so it is worth reading rather than
     *  assuming the two clocks agree. */
    private LocalDate readGameUtcDate() {
        String banner = readPanelBlock(GAME_CLOCK_TOP_LEFT, GAME_CLOCK_BOTTOM_RIGHT);
        if (banner == null) {
            return null;
        }
        Matcher matcher = GAME_CLOCK_DATE.matcher(banner);
        if (!matcher.find()) {
            logInfo("bg_calendar | Calendar: clock banner read as \"" + banner.trim()
                    + "\", which carries no date.");
            return null;
        }
        try {
            return LocalDate.of(Integer.parseInt(matcher.group(1)),
                    Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(3)));
        } catch (RuntimeException notADate) {
            return null;
        }
    }

    /**
     * A coarse fingerprint of the scrolling chart area only, or 0 when no frame is available.
     *
     * <p>Used to tell when a step no longer moves the list, which is the bottom. The whole frame
     * cannot be compared: the banner above the chart is a ticking clock, so two captures of the same
     * resting chart are never identical and the bottom was never detected.</p>
     */
    private long chartFingerprint() {
        RawImageData capture = emuManager.captureScreen(EMULATOR_NUMBER);
        if (capture == null) {
            return 0;
        }
        BufferedImage frame;
        try {
            frame = ImageConverter.toBufferedImage(capture);
        } catch (RuntimeException unreadable) {
            return 0;
        }
        long hash = 1125899906842597L;
        for (int y = GANTT_CONTENT_TOP; y < GANTT_CONTENT_BOTTOM && y < frame.getHeight(); y += 7) {
            for (int x = GANTT_LEFT; x < GANTT_RIGHT && x < frame.getWidth(); x += 7) {
                hash = 31 * hash + (frame.getRGB(x, y) & 0xF0F0F0);
            }
        }
        return hash == 0 ? 1 : hash;
    }

    /** Prefix of the in-memory, never-saved icon entries that stand for a bar nobody could name. */
    private static final String UNIDENTIFIED_ICON_PREFIX = "__unidentified_";

    private int unidentifiedCount;

    private String unidentifiedIdentity;

    /**
     * Registers an unidentified bar's icon for the rest of this pass, and returns its identity.
     *
     * <p>Held in memory only, never written to the icon folder -- it has no real name to file under.
     * Its only job is to let {@code matchIcon} recognise the same bar on the next overlapping screen,
     * so one bar is not recorded twice and two different bars are not merged into one.</p>
     */
    private String rememberUnidentifiedIcon(BufferedImage frame, int[] iconBox) {
        String identity = UNIDENTIFIED_ICON_PREFIX + (++unidentifiedCount);
        if (iconBox == null) {
            return identity;
        }
        try {
            BufferedImage crop = frame.getSubimage(iconBox[0], iconBox[1],
                    iconBox[2] - iconBox[0], iconBox[3] - iconBox[1]);
            java.io.ByteArrayOutputStream png = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(crop, "png", png);
            iconLibrary.put(identity, png.toByteArray());
        } catch (IOException | RuntimeException unencodable) {
            logWarning("bg_calendar | Calendar: could not hold the icon of an unidentified bar: "
                    + unencodable.getMessage());
        }
        return identity;
    }

    /** What a bar's tooltip card says about its event. */
    private static final class Tooltip {
        private final String title;
        private final LocalDateTime start;
        private final LocalDateTime end;

        private Tooltip(String title, LocalDateTime start, LocalDateTime end) {
            this.title = title;
            this.start = start;
            this.end = end;
        }
    }

    private static final PointData POWER_BR = new PointData(272, 96);

    private static final PointData COAL_TL = new PointData(430, 0);

    private static final PointData COAL_BR = new PointData(515, 40);

    private static final PointData GEMS_BR = new PointData(675, 38);

    private static final PointData TASK_LIST_CLOSE_POINT = new PointData(660, 359);

    private static final PointData TASK_LIST_TITLE_TOP_LEFT = new PointData(20, 335);

    private static final PointData TASK_LIST_TITLE_BOTTOM_RIGHT = new PointData(220, 385);

    private static final PointData TASK_LIST_PANEL_TOP_LEFT = new PointData(10, 460);

    private static final PointData TASK_LIST_PANEL_BOTTOM_RIGHT = new PointData(710, 1270);

    /** The "Local Time"/"UTC" line, which sits above the list and outside the panel crop. */
    private static final PointData TASK_LIST_CLOCK_TOP_LEFT = new PointData(200, 408);

    private static final PointData TASK_LIST_CLOCK_BOTTOM_RIGHT = new PointData(560, 456);

    private static final PointData TASK_LIST_SCROLL_FROM = new PointData(360, 1100);

    private static final PointData TASK_LIST_SCROLL_TO = new PointData(360, 650);

    private static final PointData FORTRESS_PANEL_TOP_LEFT = new PointData(10, 580);

    private static final PointData FORTRESS_PANEL_BOTTOM_RIGHT = new PointData(710, 1270);

    private static final PointData FORTRESS_SCROLL_FROM = new PointData(360, 1100);

    private static final PointData FORTRESS_SCROLL_TO = new PointData(360, 650);

    private static final PointData CALENDAR_PANEL_TOP_LEFT = new PointData(10, 230);

    private static final PointData CALENDAR_PANEL_BOTTOM_RIGHT = new PointData(710, 1200);

    private static final PointData CALENDAR_TAB_STRIP_SWIPE_FROM = new PointData(150, 141);

    private static final PointData CALENDAR_TAB_STRIP_SWIPE_TO = new PointData(600, 141);

    /** One slow step down the chart. 250px against an ~800px viewport, so every step overlaps the
     *  last by more than two bar rows, and dragged over 1.5s so the list moves exactly that far
     *  instead of flinging. Measured on a live sweep: 16 bars in 6 steps, bottom found on the 7th. */
    private static final PointData CALENDAR_STEP_FROM = new PointData(360, 900);

    private static final PointData CALENDAR_STEP_TO = new PointData(360, 650);

    /** "UTC Time 2026-09-16 02:44:52" -- the banner directly above the day columns. */


    /** Tapping here closes a card and does nothing else: the footer note under the chart. Not the
     *  clock banner -- a card for a bar high on the chart opens above it and covers the banner, so the
     *  tap landed on the card and it stayed open. Some cards carry a Go button that would act on the
     *  event, so the close tap must never be anywhere a card can reach. */
    private static final PointData TOOLTIP_CLOSE_POINT = new PointData(360, 1234);

    /** Just below the panel's own white top band (rows 184-206), which would otherwise read as a card. */
    private static final int TOOLTIP_CARD_SEARCH_TOP = 208;

    private static final int TOOLTIP_SETTLE_MS = 1200;

    /** Upper bound on taps a single pass may make, so a chart full of unknowns cannot run away. */
    private static final int TOOLTIP_MAX_PER_PASS = 24;

    /** This pass's rows by key; a later screen's read of the same bar replaces an earlier one. */
    private final Map<String, Object[]> pendingCalendarRows = new LinkedHashMap<>();

    /** Windows read from cards this pass, by event name. A list per name: the same event can
     *  show twice, this week's run and next week's. */
    private final Map<String, java.util.List<LocalDateTime[]>> cardWindows = new LinkedHashMap<>();

    private int tooltipsRead;

    /** The last tap was answered with a readable frame and no card: the game says it is not an event. */
    private boolean lastTapOpenedNoCard;

    private boolean calendarPassAborted;

    /**
     * The card's near-white header block as {left, right, top}, or null when no card is open.
     *
     * <p>Found by colour rather than position: the card opens below the bar or above it depending on
     * where the bar sits, and moves sideways with it. Its fill is (243,253,255), brighter than
     * anything else on the chart, so the first rows dominated by that colour are its top edge.</p>
     */
    private static int[] findTooltipCard(BufferedImage frame) {
        int width = frame.getWidth();
        int top = -1;
        for (int y = TOOLTIP_CARD_SEARCH_TOP; y < GANTT_CONTENT_BOTTOM && y < frame.getHeight(); y++) {
            int bright = 0;
            for (int x = 0; x < width; x += 2) {
                if (isTooltipFill(frame.getRGB(x, y))) {
                    bright++;
                }
            }
            if (bright > 120) {
                top = y;
                break;
            }
        }
        if (top < 0 || top + 170 >= frame.getHeight()) {
            return null;
        }
        int probe = top + 6;
        int left = -1;
        int right = -1;
        for (int x = 0; x < width; x++) {
            if (isTooltipFill(frame.getRGB(x, probe))) {
                if (left < 0) {
                    left = x;
                }
                right = x;
            }
        }
        return left < 0 || right - left < 200 ? null : new int[]{left, right, top};
    }

    private static boolean isTooltipFill(int rgb) {
        return ((rgb >> 16) & 0xFF) >= 232 && ((rgb >> 8) & 0xFF) >= 244 && (rgb & 0xFF) >= 246;
    }

    /**
     * Taps a bar, reads the card it opens, and closes it again. Null when anything about that is not
     * exactly as expected -- no card, no window line, or a card that will not close -- because
     * each of those means the screen is no longer the one being read.
     */
    private Tooltip readBarTooltip(int x, int y) {
        lastTapOpenedNoCard = false;
        if (calendarPassAborted || tooltipsRead >= TOOLTIP_MAX_PER_PASS) {
            return null;
        }
        tooltipsRead++;

        tapNear(new PointData(x, y));
        sleepTask(TOOLTIP_SETTLE_MS);

        Tooltip result = null;
        BufferedImage frame = captureFrame();
        int[] card = frame == null ? null : findTooltipCard(frame);
        if (card == null) {
            lastTapOpenedNoCard = frame != null;
            logInfo("bg_calendar | Calendar: tapping the bar at " + x + "," + y + " opened no card.");
        } else {
            // Title is read to the right of the icon, the window across the full card below it. One
            // box for both let the icon leak in as a stray first word ("Sh City Development").
            int left = card[0];
            int right = card[1];
            int top = card[2];
            String titleText = readPanelBlock(new PointData(left + 100, top + 35),
                    new PointData(right - 8, top + 100));
            String windowText = readPanelBlock(new PointData(left + 8, top + 105),
                    new PointData(right - 8, top + 160));
            String title = cleanGanttLabel(titleText);
            LocalDateTime[] window = parseTooltipWindow(windowText);
            if (window == null) {
                logInfo("bg_calendar | Calendar: card opened but its window line read as \""
                        + (windowText == null ? "" : windowText.trim()) + "\"; not trusting it.");
            } else if (title.length() < MIN_CREDIBLE_LABEL_LENGTH) {
                logInfo("bg_calendar | Calendar: card opened but its title read as \""
                        + (titleText == null ? "" : titleText.trim()) + "\"; not trusting it.");
            } else {
                result = new Tooltip(title, window[0], window[1]);
            }
        }

        if (!closeTooltip()) {
            calendarPassAborted = true;
            logWarning("bg_calendar | Calendar: a tooltip card would not close; stopping this pass"
                    + " rather than keep tapping a screen that is no longer the chart.");
            return null;
        }
        // No check that the chart is pixel-identical afterwards: animated bar art changes the exact
        // fingerprint on its own, and that aborted a live pass on a card that had closed cleanly. A
        // small shift is harmless anyway -- every screen is dated from its own columns.
        return result;
    }

    private boolean closeTooltip() {
        for (int attempt = 0; attempt < 2; attempt++) {
            tapNear(TOOLTIP_CLOSE_POINT);
            sleepTask(TOOLTIP_SETTLE_MS);
            BufferedImage frame = captureFrame();
            if (frame != null && findTooltipCard(frame) == null) {
                return true;
            }
        }
        return false;
    }

    /** Parses the card's window line. "24:00" is the end of that day, stored as 23:59 like every
     *  other whole-day window here. */
    private static LocalDateTime[] parseTooltipWindow(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = TOOLTIP_WINDOW.matcher(text);
        if (!m.find()) {
            return null;
        }
        try {
            LocalDateTime from = tooltipTime(m.group(1), m.group(2), m.group(3), m.group(4), m.group(5));
            LocalDateTime to = tooltipTime(m.group(6), m.group(7), m.group(8), m.group(9), m.group(10));
            return to.isBefore(from) ? null : new LocalDateTime[]{from, to};
        } catch (RuntimeException notATime) {
            return null;
        }
    }

    private static LocalDateTime tooltipTime(String year, String month, String day, String hour, String minute) {
        LocalDate date = LocalDate.of(Integer.parseInt(year), Integer.parseInt(month), Integer.parseInt(day));
        int h = Integer.parseInt(hour);
        int mm = Integer.parseInt(minute);
        return h == 24 ? date.atTime(23, 59) : date.atTime(h, mm);
    }

    private BufferedImage captureFrame() {
        RawImageData capture = emuManager.captureScreen(EMULATOR_NUMBER);
        if (capture == null) {
            return null;
        }
        try {
            return ImageConverter.toBufferedImage(capture);
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    /** Saves the icon under a name the game itself printed, and holds it for the rest of this pass,
     *  so the same bar on the next overlapping screen is recognised without tapping it again. */
    private void learnIconNow(BufferedImage frame, int[] iconBox, String name) {
        learnIcon(frame, iconBox, name);
        try {
            BufferedImage crop = frame.getSubimage(iconBox[0], iconBox[1],
                    iconBox[2] - iconBox[0], iconBox[3] - iconBox[1]);
            java.io.ByteArrayOutputStream png = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(crop, "png", png);
            iconLibrary.put(name, png.toByteArray());
        } catch (IOException | RuntimeException unencodable) {
            logWarning("bg_calendar | Calendar: could not hold the new icon for \"" + name + "\": "
                    + unencodable.getMessage());
        }
    }

    private void rememberCardWindow(String name, Tooltip tooltip) {
        cardWindows.computeIfAbsent(name.toLowerCase(), k -> new java.util.ArrayList<>())
                .add(new LocalDateTime[]{tooltip.start, tooltip.end});
    }

    /** A window already read from this event's card that contains what the chart shows, if any. */
    private LocalDateTime[] cardWindowCovering(String name, LocalDateTime from, LocalDateTime to) {
        for (LocalDateTime[] window : cardWindows.getOrDefault(name.toLowerCase(), java.util.List.of())) {
            if (!window[0].isAfter(from) && !window[1].isBefore(to)) {
                return window;
            }
        }
        return null;
    }

    /** An icon's name comes from its file name, which cannot hold an apostrophe
     *  ("Mias_Fortune_Hut"); the list's own spelling is the one shown. */
    private String listSpelling(String iconName) {
        String bare = iconName.replaceAll("[^A-Za-z0-9]", "");
        for (String known : knownEventNames) {
            if (known.replaceAll("[^A-Za-z0-9]", "").equalsIgnoreCase(bare)) {
                return known;
            }
        }
        return iconName;
    }

    /** Adds a name read from a tooltip to the operator's list, unless it is already there. */
    private void rememberEventName(String name) {
        for (String known : knownEventNames) {
            if (known.equalsIgnoreCase(name)) {
                return;
            }
        }
        Path file = WorkspacePaths.current().root().resolve("data").resolve(CALENDAR_NAMES_FILE);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, name + "\n",
                    java.nio.charset.StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            java.util.List<String> updated = new java.util.ArrayList<>(knownEventNames);
            updated.add(name);
            knownEventNames = updated;
            logInfo("bg_calendar | Calendar: added \"" + name + "\" to " + CALENDAR_NAMES_FILE + ".");
        } catch (IOException unwritable) {
            logWarning("bg_calendar | Calendar: could not add \"" + name + "\" to "
                    + CALENDAR_NAMES_FILE + ": " + unwritable.getMessage());
        }
    }

    private int readGanttChart(boolean finalScreen) {
        RawImageData capture = emuManager.captureScreen(EMULATOR_NUMBER);
        if (capture == null) {
            logWarning("bg_calendar | Calendar: screen capture unavailable; skipping this pass.");
            return 0;
        }
        BufferedImage frame;
        try {
            frame = ImageConverter.toBufferedImage(capture);
        } catch (RuntimeException conversionFailed) {
            logWarning("bg_calendar | Calendar: could not decode the frame: " + conversionFailed.getMessage());
            return 0;
        }

        int todayColumn = ganttTodayColumn(frame);
        if (todayColumn < 0) {
            logWarning("bg_calendar | Calendar: today's column is not highlighted on this frame, so the "
                    + "day columns cannot be dated. Recording nothing rather than guessing dates.");
            return 0;
        }
        // Anchored to the game's own clock, printed in the banner above the chart, not to this
        // machine's. They are the same only while the PC clock is right, and the whole chart is
        // dated by offsets from this one value, so a drifting local clock would silently move every
        // bar. The machine clock stays as a fallback, and says so when it is used.
        LocalDate today = readGameUtcDate();
        if (today == null) {
            today = LocalDate.now(ZoneOffset.UTC);
            logWarning("bg_calendar | Calendar: could not read the game's UTC clock from the panel"
                    + " banner; dating the chart from this machine's clock instead.");
        }

        int recorded = 0;
        Integer bandStart = null;
        for (int y = GANTT_CONTENT_TOP; y < GANTT_CONTENT_BOTTOM; y++) {
            boolean isBarRow = false;
            for (int column = 0; column < GANTT_DAY_COLUMNS; column++) {
                if (ganttColumnCoverage(frame, column, y) > 0.6) {
                    isBarRow = true;
                    break;
                }
            }
            if (isBarRow && bandStart == null) {
                bandStart = y;
            } else if (!isBarRow && bandStart != null) {
                if (y - bandStart > GANTT_MIN_BAR_HEIGHT) {
                    recorded += recordGanttBar(capture, frame, bandStart, y - 1, todayColumn, today, finalScreen);
                }
                bandStart = null;
            }
        }
        if (bandStart != null && GANTT_CONTENT_BOTTOM - bandStart > GANTT_MIN_BAR_HEIGHT) {
            recorded += recordGanttBar(capture, frame, bandStart, GANTT_CONTENT_BOTTOM - 1, todayColumn, today, finalScreen);
        }
        return recorded;
    }

    private int recordGanttBar(RawImageData capture, BufferedImage frame, int bandTop, int bandBottom,
                               int todayColumn, LocalDate today, boolean finalScreen) {
        // A bar partly hidden under the day header or the footer is only half a bar: its icon crop
        // is partial and matches nothing. Recording it registered that half-icon as an unidentified
        // identity, and when the same bar appeared whole on the next screen it matched both its real
        // icon and that copy of itself -- too close to call -- so Foundry Battle went unrecorded and
        // left four phantom unknowns behind. The next overlapping screen always shows it whole, so
        // it is skipped here. Measured on a live sweep: whole bars 51-56px, clipped ones 21-34px.
        // On the last screen only the bar cut off by the footer is let through. One cut off by the day
        // header was seen whole a screen earlier; letting it through too recorded its half-icon as an
        // unidentified event, and a tap on its hidden half opened no card.
        boolean clippedByFooter = finalScreen && bandBottom >= GANTT_CONTENT_BOTTOM - 1;
        if (bandBottom - bandTop < GANTT_FULL_BAR_MIN_HEIGHT && !clippedByFooter) {
            return 0;
        }
        int centre = (bandTop + bandBottom) / 2;
        int firstColumn = -1;
        int lastColumn = -1;
        for (int column = 0; column < GANTT_DAY_COLUMNS; column++) {
            if (ganttColumnCoverage(frame, column, centre) > 0.5) {
                if (firstColumn < 0) {
                    firstColumn = column;
                }
                lastColumn = column;
            }
        }
        if (firstColumn < 0) {
            return 0;
        }

        LocalDate start = today.plusDays(firstColumn - todayColumn);
        LocalDate end = today.plusDays(lastColumn - todayColumn);

        // The icon and label are centred together inside the bar, so both are found relative to
        // where the bar's content actually begins rather than at a fixed offset from its left edge.
        int barLeft = ganttColumnStart(firstColumn);
        int barRight = ganttColumnStart(lastColumn + 1);
        int fill = ganttBarFill(frame, barLeft, barRight, centre);
        int iconLeft = ganttIconLeft(frame, barLeft, barRight, centre, fill);
        int[] iconBox = iconLeft < 0 ? null
                : ganttIconBox(iconLeft, bandTop, bandBottom, frame.getWidth());

        // Read across the whole bar and strip the icon's wreckage from the front of the text,
        // rather than starting the crop past the icon. Starting past it was tried and reads much
        // worse -- "Fortress Battles" came back as "Fontns" -- because the icon's right edge is not
        // where its box ends, so the crop clipped into the first letters. Cropping wide and cleaning
        // the string afterwards recovered every name the game printed in full.
        String label = readPanelBlock(
                new PointData(barLeft + ICON_LABEL_GAP, centre - 16),
                new PointData(barRight, centre + 16));
        // The game's own ellipsis has to be read off the raw text: cleaning strips punctuation, so
        // "Alliance..." and "Alliance" are indistinguishable afterwards.
        String raw = label == null ? "" : label.trim();
        String name = cleanGanttLabel(label);
        // A one or two character read is noise, not a name: the game has no such event. Treating it
        // as unreadable keeps "oe" out of the calendar instead of recording it as an event.
        if (name.length() < MIN_CREDIBLE_LABEL_LENGTH) {
            // Discarded outright rather than carried as a stub: showing "oe..." claims the game
            // printed a name beginning "oe", and it did not.
            name = "";
        }

        // Three sources, strongest first. The label text is never used as a name on its own: it is
        // a 12px string on a saturated bar, and the reads it produces -- "Whortress Battlessuy",
        // "aBizarne Bazaar", "Halllof Chiefs" -- are evidence, not answers. Something has to agree
        // with it before it becomes a name, and when nothing does the bar says so.
        String resolved = null;
        String how = null;

        String sameUnidentifiedAs = null;
        if (iconBox != null) {
            String matched = matchIcon(capture, barLeft, barRight, bandTop, bandBottom);
            if (matched != null && matched.startsWith(UNIDENTIFIED_ICON_PREFIX)) {
                // Not a name: this is a bar an earlier screen of this same pass already failed to
                // identify, seen again because the screens overlap. It keeps that bar's identity.
                sameUnidentifiedAs = matched;
            } else if (matched != null) {
                resolved = listSpelling(matched);
                how = "its icon";
            }
        }
        if (resolved == null && !name.isEmpty()) {
            String snapped = snapToKnownName(name);
            if (snapped != null) {
                resolved = snapped;
                how = "the event list";
                // Its icon is not in the library yet and the name is now established, so this is the
                // one moment it is safe to learn: the file is written under a curated name, never
                // under whatever the OCR happened to say.
                if (iconBox != null) {
                    learnIcon(frame, iconBox, snapped);
                }
            }
        }

        boolean identified = resolved != null;
        if (identified) {
            if (!resolved.equalsIgnoreCase(name)) {
                logInfo("bg_calendar | Calendar: bar " + start + ".." + end + " read as \""
                        + (name.isEmpty() ? "(nothing)" : name) + "\"; " + how + " says \""
                        + resolved + "\", which is the better evidence.");
            }
            name = resolved;
        }

        LocalDateTime startAt = start.atStartOfDay();
        LocalDateTime endAt = end.plusDays(1).atStartOfDay().minusMinutes(1);

        if (!identified && sameUnidentifiedAs == null) {
            // Nothing on the chart names it, and it has not been seen earlier in this pass. Ask the
            // game: tapping a bar opens a card with the event's full name and its exact window, in
            // large clean text. This is the only way to name an event the chart truncates -- "Mia's...",
            // "Stand...", a bar with no room for any text at all.
            Tooltip tooltip = readBarTooltip((barLeft + barRight) / 2, centre);
            if (tooltip != null) {
                String canonical = snapToKnownName(tooltip.title);
                String adopted = canonical != null ? canonical : tooltip.title;
                logInfo("bg_calendar | Calendar: bar " + start + ".." + end + " read as \""
                        + (name.isEmpty() ? "(icon only)" : name) + "\"; its tooltip names it \""
                        + tooltip.title + "\", " + tooltip.start + " -> " + tooltip.end + " UTC.");
                name = adopted;
                identified = true;
                // The tooltip's window is the real one, the chart's is only what fits in the week:
                // Grow your heroes runs 09/20-09/21 but the chart, ending on the 20th, shows one day.
                startAt = tooltip.start;
                endAt = tooltip.end;
                start = tooltip.start.toLocalDate();
                end = tooltip.end.toLocalDate();
                rememberCardWindow(adopted, tooltip);
                if (iconBox != null) {
                    learnIconNow(frame, iconBox, adopted);
                }
                rememberEventName(adopted);
            }
        } else if (identified && (firstColumn == 0 || lastColumn == GANTT_DAY_COLUMNS - 1)) {
            // The chart shows seven days, so a bar reaching its first or last column is cut to them:
            // King of Icefield runs 09/21-09/27 but the chart, ending on the 21st, drew one day, and
            // once its icon was learned that clipped read overwrote the card's window. The card for
            // such a bar is read once per pass and reused on every overlapping screen.
            LocalDateTime[] window = cardWindowCovering(name, startAt, endAt);
            if (window == null) {
                Tooltip tooltip = readBarTooltip((barLeft + barRight) / 2, centre);
                if (tooltip == null) {
                    logInfo("bg_calendar | Calendar: " + name + " runs off the chart edge and its card"
                            + " could not be read; keeping the visible " + start + ".." + end + ".");
                } else if (!name.equalsIgnoreCase(listSpelling(tooltip.title))
                        && !name.equalsIgnoreCase(snapToKnownName(tooltip.title))) {
                    logInfo("bg_calendar | Calendar: tapped " + name + " but its card says \""
                            + tooltip.title + "\"; keeping the visible " + start + ".." + end + ".");
                } else if (tooltip.start.isAfter(startAt) || tooltip.end.isBefore(endAt)) {
                    logInfo("bg_calendar | Calendar: " + name + "'s card window " + tooltip.start
                            + " -> " + tooltip.end + " does not cover the bar " + start + ".." + end
                            + "; keeping the visible dates.");
                } else {
                    rememberCardWindow(name, tooltip);
                    window = new LocalDateTime[]{tooltip.start, tooltip.end};
                }
            }
            if (window != null) {
                startAt = window[0];
                endAt = window[1];
                start = startAt.toLocalDate();
                end = endAt.toLocalDate();
            }
        }
        if (!identified && sameUnidentifiedAs == null && lastTapOpenedNoCard) {
            // No icon, no name, and the game opens no card for it: every event bar has one, so this
            // is chart furniture read as a bar, not an event nobody could name.
            lastTapOpenedNoCard = false;
            logInfo("bg_calendar | Calendar: band " + start + ".." + end + " at y=" + centre
                    + " has no icon, no name and no card; not an event, not recorded.");
            return 0;
        }
        if (!identified) {
            // Nothing agreed and the tooltip gave nothing either. The dates are real and are kept;
            // the name is not invented, and the raw read is logged.
            if (sameUnidentifiedAs == null) {
                logInfo("bg_calendar | Calendar: bar " + start + ".." + end + " read as \""
                        + (name.isEmpty() ? "(icon only)" : name) + "\" -- no icon, nothing in "
                        + "data/calendar-events.txt, and no tooltip. Recording it as unidentified.");
                sameUnidentifiedAs = rememberUnidentifiedIcon(frame, iconBox);
            }
            unidentifiedIdentity = sameUnidentifiedAs;
            name = "Unidentified - " + start;
        }
        boolean activeNow = !today.isBefore(start) && !today.isAfter(end);
        // Keyed by name and start for a named event. An unidentified one is keyed by the icon identity
        // instead: Wander Theater and Wanderer Missions share both dates exactly, so a date-based key
        // made the second overwrite the first and one of them silently vanished.
        String keyTail = unidentifiedIdentity != null
                ? unidentifiedIdentity + "_" + start + "_" + end
                : name.toUpperCase().replaceAll("[^A-Z0-9]+", "_") + "_" + start;
        String rowKey = STATE_GANTT_KEY_PREFIX + keyTail.toUpperCase().replaceAll("[^A-Z0-9_]+", "_");
        pendingCalendarRows.put(rowKey, new Object[]{rowKey, name, activeNow, startAt, endAt});
        unidentifiedIdentity = null;
        logInfo("bg_calendar | Calendar: " + name + " " + start + " -> " + end
                + (activeNow ? " (running now)" : ""));
        return 1;
    }

    /**
     * Strips the wreckage the bar's own icon leaves in front of the name. The crop starts past the
     * icon, but its right edge still lands in the block often enough to produce reads like
     * "&laquo;Hall of Chiefs", ": Vault of Enigma" and "=~ Hero)Rally". This drops everything before
     * the first letter, turns stray punctuation inside the name into spaces, and drops a leading
     * one- or two-character lowercase fragment. It only ever removes characters, so a name can come
     * out shortened or empty but never invented; an empty result falls through to the same
     * truncated-label handling as a blank read.
     */
    private String cleanGanttLabel(String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw.replaceAll("^[^\\p{L}]+", "");
        text = text.replaceAll("[^\\p{L}\\p{N}\\s'-]+", " ").replaceAll("\\s+", " ").trim();
        String[] words = text.split(" ");
        if (words.length > 1 && words[0].length() <= 2 && words[0].equals(words[0].toLowerCase())) {
            text = text.substring(words[0].length() + 1);
        }
        return text.trim();
    }

    private String readPanelBlock(PointData topLeft, PointData bottomRight) {
        try {
            return stringHelper.attemptRecognition(
                    topLeft, bottomRight, 2, 200L,
                    OcrSettingsData.forTextBlock(),
                    s -> s != null && !s.isBlank(),
                    String::trim);
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * A reading held back for one cycle, waiting to see whether the next one agrees with it.
     * Keyed "&lt;profileId&gt;:&lt;field&gt;".
     *
     * <p>In memory, so a restart forgets it and the confirmation starts over -- the same trade
     * ResourceStockpileRoutine makes for its own streak, and for the same reason: it costs one
     * extra sample after a restart, not a lasting fault.</p>
     */
    private static final Map<String, Long> AWAITING_CONFIRMATION = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Dave's #250 review: telemetry was resolving off {@code user.dir} (breaks the moment this is
     * an installed Stable/Nightly build, which resolves relative to the launch directory, not the
     * chosen workspace) and shared one {@code latest.json}/{@code history.jsonl} across every
     * profile (profile B's sample could satisfy profile A's sanity check, and concurrent writers
     * on different emulators could interleave into the same file). Fixed both by resolving under
     * {@code WorkspacePaths.current().root()} and partitioning by the profile's stable numeric ID
     * (never its name, which is mutable) -- same layout convention as
     * {@code GameAnalyticsHistoryService} (workspace root -> data/&lt;feature&gt;/profiles/&lt;id&gt;/).
     * Every profile now owns its own pair of files; there is nothing left to filter or race over.
     */
    private Path telemetryDir() {
        return WorkspacePaths.current().root()
                .resolve("data").resolve("telemetry")
                .resolve("profiles").resolve(String.valueOf(profile.getId()));
    }

    /**
     * Reads a resource stockpile value that ResourceStockpileRoutine stored in config.
     * Returns null (not 0) when it has never been scanned, so a graph shows a gap rather
     * than a misleading zero.
     */
    private Long readStockpile(ConfigurationKeyEnum key) {
        try {
            Long value = profile.getConfig(key, Long.class);
            return (value == null || value <= 0L) ? null : value;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Parses "11,914,539", "6.7M", "32784" and "1.2B" to a plain long.
     * Package-visible so the parsing rules can be exercised directly.
     */
    static Long parseScaled(String raw) {
        String s = raw.trim().replace(",", "").replace(" ", "");
        if (s.isEmpty()) {
            return null;
        }

        long multiplier = 1L;
        char last = s.charAt(s.length() - 1);
        boolean abbreviated = last == 'K' || last == 'M' || last == 'B';
        if (abbreviated) {
            multiplier = last == 'K' ? 1_000L : last == 'M' ? 1_000_000L : 1_000_000_000L;
            s = s.substring(0, s.length() - 1);
        } else {
            // Tesseract frequently reads the HUD's thousands commas as periods
            // ("12.552.372"). Only the abbreviated form has a real decimal
            // point, so on an un-abbreviated value a period is always a group
            // separator and is safe to drop. Without this, every full-precision
            // Power reading is discarded.
            s = s.replace(".", "");
        }

        if (s.isEmpty()) {
            return null;
        }
        try {
            // Parsed as a double because the abbreviated form carries a decimal
            // ("6.7M"); the un-abbreviated form never does, so this is lossless
            // for the values the HUD actually shows.
            return (long) (Double.parseDouble(s) * multiplier);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** One lock per JVM, shared by every profile's writer -- cheap, and removes any doubt about
     *  two runs (a slow one plus its on-time successor) interleaving the same profile's files. */
    private static final Object WRITE_LOCK = new Object();
}
