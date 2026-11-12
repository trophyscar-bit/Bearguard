package dev.frostguard.engine.listener.task.impl;

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

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.api.domain.OcrSettingsData;
import dev.frostguard.api.domain.OcrSettingsData.TextLayout;
import dev.frostguard.api.domain.JobMetrics;
import dev.frostguard.api.domain.ProfilesData;
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

/**
 * Bearguard telemetry: samples the top HUD on a schedule and appends the result
 * to a JSON history the Whiteout dashboard reads.
 *
 * <p>This exists because the external Node scraper that used to do this drove
 * ADB itself, so it could not run while the bot was running â€” the two fought
 * over the same device. Running the capture as a task inside the bot's own
 * queue removes that conflict by construction, and inherits the engine's
 * screen-verification and retry behaviour for free.
 *
 * <p>Deliberately additive: a new file under custom_tasks/, no upstream source
 * touched, so merges from Shederator/wosbot stay clean.
 */
public class bg_telemetry extends DelayedTask implements CustomTaskConfigurable {
    // matt/2026-08-09: hourly, so "last night" (23:00â†’08:00) and every window has fine-grained data.
    private static final Duration DEFAULT_INTERVAL = Duration.ofHours(1);

    /**
     * HUD regions, in the required 720x1280 frame. Measured against live
     * captures rather than guessed. The slot left of the temperature readout is
     * deliberately unmapped: it shows population on the City view and a UTC
     * clock on the World view, so it cannot be trusted as a single field.
     */
    // Each crop starts AFTER its icon. Verified against a live frame: the coal
    // slot (the only one with no icon inside the crop) read correctly first
    // time, while power and gems both had their icon in-frame and OCR folded
    // its edges into the digits - the diamond turned 56,112 into 596,256.
    private static final PointData POWER_TL = new PointData(130, 48);

    private static final PointData POWER_BR = new PointData(272, 96);

    private static final PointData COAL_TL = new PointData(430, 0);

    private static final PointData COAL_BR = new PointData(515, 40);

    // Measured on a magnified frame: the diamond icon ends at x=572, the digits
    // run 591-667, and the green "+" starts at 688. 578 sits in the clean gap.
    // Both earlier attempts failed by landing on a glyph edge rather than in the
    // gap - 590 clipped the leading "5" (read as 596,256) and 608 cut it off
    // entirely (read as 5,256).
    private static final PointData GEMS_TL = new PointData(578, 2);

    private static final PointData GEMS_BR = new PointData(675, 38);

    /**
     * The HUD renders white text over a busy scene. Whitelisting the separator
     * and magnitude characters matters: the game abbreviates large values
     * ("6.7M") but prints others in full ("11,914,539"), and a digits-only
     * whitelist silently turns the former into 67.
     */
    private static final OcrSettingsData HUD_NUMBER_SETTINGS =
            OcrSettingsData.assembler()
                    .charWhitelist("0123456789.,KMB")
                    .textLayout(TextLayout.SINGLE_LINE)
                    .stripBackground(true)
                    .setTextColor(new java.awt.Color(255, 255, 255))
                    .build();

    /**
     * Digits and comma only, for the slots that always show a full number
     * (Power, Gems). Allowing K/M/B there costs accuracy for no benefit: with
     * the letters in the whitelist Tesseract read a clean "56,256" crop as
     * "596,256", inventing a digit. Only Coal actually abbreviates.
     *
     * <p>Read as a single WORD rather than a single line, because narrowing the whitelist only
     * reduced that fault and did not remove it: on 9/12 at 23:11 a clean "53,893" gems crop came
     * back as "593,893" -- the same invented 9 after the first digit -- and the plausibility guard
     * had to hold the reading back at a ratio of 16.8. Line segmentation is what admits the extra
     * glyph; these crops are one number and nothing else, so telling Tesseract that is both true
     * and stricter. Checked over 30 saved full-screen frames: identical on 28, correct instead of
     * wrong on the two that carried the fault, and no change to Power on any of them. Three frames
     * gain a trailing comma, which parseScaled strips.</p>
     */
    private static final OcrSettingsData HUD_FULL_NUMBER_SETTINGS =
            OcrSettingsData.assembler()
                    .charWhitelist("0123456789,")
                    .textLayout(TextLayout.SINGLE_WORD)
                    .stripBackground(true)
                    .setTextColor(new java.awt.Color(255, 255, 255))
                    .build();

    private static final DateTimeFormatter UTC_INPUT_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private Duration interval = DEFAULT_INTERVAL;

    public bg_telemetry(AccountDescriptor profile, TpDailyTaskEnum tpTask) {
        super(profile, tpTask);
        // Scheduling is in LOCAL time: TaskQueue compares against
        // LocalDateTime.now(). Passing a UTC instant here silently pushes the
        // first run forward by the machine's UTC offset, so the task sits in
        // the queue looking healthy and simply never becomes due.
        // (shield.java uses UTC because it targets a fixed UTC window - that is
        // a different intent from "run now".)
        reschedule(LocalDateTime.now());
    }

    @Override
    protected Object getDistinctKey() {
        return "bg_telemetry";
    }

    @Override
    protected LaunchPoint getRequiredStartLocation() {
        // The resource HUD is identical on City and World, but pinning to WORLD
        // gives the engine one deterministic screen to return to.
        return LaunchPoint.WORLD;
    }

    @Override
    public void applyCustomTaskSettings(CustomTaskService.CustomTaskSettings settings) {
        if (settings == null) {
            return;
        }
        Integer hours = settings.getFollowUpDelayHours();
        interval = hours != null && hours > 0 ? Duration.ofHours(hours) : DEFAULT_INTERVAL;

        String first = settings.getFirstExecutionUtc();
        if (first != null && !first.isBlank()) {
            try {
                // The setting is expressed in UTC but the scheduler works in
                // local time, so convert rather than passing it through.
                LocalDateTime localStart = LocalDateTime.parse(first, UTC_INPUT_FORMATTER)
                        .atOffset(ZoneOffset.UTC)
                        .atZoneSameInstant(ZoneId.systemDefault())
                        .toLocalDateTime();
                reschedule(localStart);
            } catch (RuntimeException e) {
                logWarning("bg_telemetry | Unparseable first-execution time '" + first + "', starting immediately.");
            }
        }
    }

    @Override
    protected void execute() {
        logInfo("bg_telemetry | Sampling HUD.");

        // matt/2026-08-15: "I lost forty thousand gems... I don't trust these statistics."
        // Root-caused against real history.jsonl data: gems was intermittently flip-flopping
        // between two distinct bands roughly 40,000 apart sample to sample (e.g. 90,629 then
        // 50,739 then back to 90,499) while power trended smoothly the whole time -- a real
        // OCR misread, not an actual gem swing (confirmed: a live screenshot mid-session showed
        // the true value sitting in the lower band). The crop itself checked out clean against a
        // fresh screenshot, so this is intermittent (a different screen state at capture time,
        // not a static coordinate bug) and not worth chasing blind. Same "decline rather than
        // guess" pattern used throughout this codebase: reject a reading that jumps implausibly
        // from the last known-good sample instead of trusting it, so a bad OCR frame produces a
        // gap in the graph, never a fake spike/drop. The comparison value is the last non-null
        // reading for the field, not the last row -- see readLastKnownGood for why that
        // distinction is the whole guard.
        Map<String, Object> lastKnownGood = readLastKnownGood();

        // Dave's #250 review: sanity-checking only power/gems left coal's live OCR read
        // unvalidated even though it goes through the exact same misread-prone path. Applied to
        // all three fields this task actually reads via OCR itself (meat/wood/iron/steel/speedups
        // below are cached config values from a different task's scan, not read here, so there's
        // no fresh OCR result for this check to validate against).
        Long power = sanityCheckAgainstLastKnown("power",
                readScaledNumber(POWER_TL, POWER_BR, HUD_FULL_NUMBER_SETTINGS, "power"), lastKnownGood);
        Long coal = sanityCheckAgainstLastKnown("coal",
                readScaledNumber(COAL_TL, COAL_BR, HUD_NUMBER_SETTINGS, "coal"), lastKnownGood);
        Long gems = sanityCheckAgainstLastKnown("gems",
                readScaledNumber(GEMS_TL, GEMS_BR, HUD_FULL_NUMBER_SETTINGS, "gems"), lastKnownGood);

        // A frame where nothing at all resolved almost always means we are not
        // actually on the HUD (a popup, an event takeover). Recording that as a
        // row of nulls would poison the history the dashboard graphs, so skip
        // the write and let the next run pick it up.
        if (power == null && coal == null && gems == null) {
            logWarning("bg_telemetry | No HUD values resolved - not on the expected screen. Skipping this sample.");
            scheduleNext();
            return;
        }

        // All four resources for the Statistics tab's "resources earned over time"
        // reports. The top HUD only ever shows one resource, so meat/wood/iron come
        // from the values ResourceStockpileRoutine last scanned (stored in config) â€”
        // no extra navigation, and they change slowly enough that the last scan is
        // fine for a graph. Coal stays the live HUD read (same resource, fresher).
        Long meat = readStockpile(ConfigurationKeyEnum.RESOURCE_STOCKPILE_MEAT_LONG);
        Long wood = readStockpile(ConfigurationKeyEnum.RESOURCE_STOCKPILE_WOOD_LONG);
        Long iron = readStockpile(ConfigurationKeyEnum.RESOURCE_STOCKPILE_IRON_LONG);
        if (coal == null) {
            coal = readStockpile(ConfigurationKeyEnum.RESOURCE_STOCKPILE_COAL_LONG);
        }

        // Steel + the five speedup buckets, all sourced from the config keys the
        // Resource & Speedup Summary reader (ResourceStockpileRoutine) last cached.
        // Speedups are stored/logged in MINUTES; the Statistics tab formats them back to durations.
        Long steel = readStockpile(ConfigurationKeyEnum.RESOURCE_STOCKPILE_STEEL_LONG);
        Long spGeneral = readStockpile(ConfigurationKeyEnum.SPEEDUP_GENERAL_MIN_LONG);
        Long spTraining = readStockpile(ConfigurationKeyEnum.SPEEDUP_TRAINING_MIN_LONG);
        Long spConstruction = readStockpile(ConfigurationKeyEnum.SPEEDUP_CONSTRUCTION_MIN_LONG);
        Long spResearch = readStockpile(ConfigurationKeyEnum.SPEEDUP_RESEARCH_MIN_LONG);
        Long spHealing = readStockpile(ConfigurationKeyEnum.SPEEDUP_HEALING_MIN_LONG);

        Map<String, Object> sample = new LinkedHashMap<>();
        sample.put("capturedAt", LocalDateTime.now(ZoneOffset.UTC).toString() + "Z");
        sample.put("profile", profile.getName());
        sample.put("power", power);
        sample.put("gems", gems);
        sample.put("meat", meat);
        sample.put("wood", wood);
        sample.put("coal", coal);
        sample.put("iron", iron);
        sample.put("steel", steel);
        sample.put("sp_general", spGeneral);
        sample.put("sp_training", spTraining);
        sample.put("sp_construction", spConstruction);
        sample.put("sp_research", spResearch);
        sample.put("sp_healing", spHealing);

        // Activity snapshot: the running task-run counts and action counters, flattened as
        // "run.<Task>" / "ctr.<Counter>" number fields. The Statistics tab diffs these between two
        // snapshots to show "what the bot DID" over a window (27 intel runs, 6 pet adventures, ...).
        appendActivitySnapshot(sample);

        // The readings this task took itself, plus the accomplishment counters, recorded as
        // observations at the moment they were taken. The JSON Lines file is still written below,
        // but it is a log of two-hourly copies of whatever the cache held; the store is a log of
        // readings. Only the second can answer "what changed between 11pm and 8am" by subtracting
        // two numbers, which is the whole point of it.
        recordObservations(power, gems, coal, sample);

        String json = toJson(sample);
        writeSample(json);

        logInfo("bg_telemetry | power=" + power + " gems=" + gems
                + " meat=" + meat + " wood=" + wood + " coal=" + coal + " iron=" + iron
                + " steel=" + steel + " sp(gen/tr/con/res/heal)=" + spGeneral + "/" + spTraining
                + "/" + spConstruction + "/" + spResearch + "/" + spHealing);

        // matt/2026-09-11: folded in here rather than as separate built-in tasks, so the sidebar's
        // Event-tab presence and the Bear Trap window; the Calendar chart belongs to bg_calendar.
        scanUpcomingEventsCalendar();

        scheduleNext();
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

    private static final int TASK_LIST_SCROLL_PASSES = 3;

    private static final String TASK_LIST_KEY_PREFIX = "TASKLIST_";

    /** Rows are classified down this column, inside every row's rounded fill. */
    private static final int TASK_LIST_ROW_PROBE_X = 34;

    private static final int TASK_LIST_ROW_MIN_HEIGHT = 25;

    /** Measured fills: "Today" heading, dated heading, entry card. */
    private static final int[] TASK_LIST_TODAY_FILL = {211, 157, 99};

    private static final int[] TASK_LIST_DATE_FILL = {143, 173, 221};

    private static final int[] TASK_LIST_ENTRY_FILL = {168, 188, 227};

    /** 'T' today heading, 'D' dated heading, 'E' entry, or 0 for background. */
    private static char taskListRowKind(int rgb) {
        if (nearFill(rgb, TASK_LIST_TODAY_FILL)) {
            return 'T';
        }
        if (nearFill(rgb, TASK_LIST_DATE_FILL)) {
            return 'D';
        }
        return nearFill(rgb, TASK_LIST_ENTRY_FILL) ? 'E' : 0;
    }

    private static boolean nearFill(int rgb, int[] fill) {
        return Math.abs(((rgb >> 16) & 0xFF) - fill[0]) <= 10
                && Math.abs(((rgb >> 8) & 0xFF) - fill[1]) <= 10
                && Math.abs((rgb & 0xFF) - fill[2]) <= 10;
    }

    /**
     * The whole rows on one screen of the panel, top to bottom, as clean text lines: a heading reads
     * as "Today" or its date, an entry as its title and subtitle followed by its time or "Ended".
     * Rows cut off by the panel's top or bottom edge are left for the screen that shows them whole.
     */
    private java.util.List<String> readTaskListRows(BufferedImage frame) {
        java.util.List<String> rows = new java.util.ArrayList<>();
        int top = TASK_LIST_PANEL_TOP_LEFT.getY();
        int bottom = Math.min(TASK_LIST_PANEL_BOTTOM_RIGHT.getY(), frame.getHeight());
        char kind = 0;
        int start = top;
        for (int y = top; y <= bottom; y++) {
            char here = y < bottom ? taskListRowKind(frame.getRGB(TASK_LIST_ROW_PROBE_X, y)) : 0;
            if (here == kind) {
                continue;
            }
            if (kind != 0 && y - start >= TASK_LIST_ROW_MIN_HEIGHT && start > top && y < bottom) {
                String row = readTaskListRow(kind, start, y - 1);
                if (row != null) {
                    rows.add(row);
                }
            }
            kind = here;
            start = y;
        }
        return rows;
    }

    private String readTaskListRow(char kind, int rowTop, int rowBottom) {
        if (kind == 'T') {
            return "Today";
        }
        if (kind == 'D') {
            String date = readPanelBlock(new PointData(40, rowTop), new PointData(680, rowBottom));
            return date == null ? null : date.replaceAll("\\s+", "");
        }
        String title = readPanelBlock(new PointData(150, rowTop + 4), new PointData(550, rowBottom - 4));
        if (title == null || title.isBlank()) {
            return null;
        }
        String status = readPanelBlock(new PointData(550, rowTop + 4), new PointData(690, rowBottom - 4));
        return title.replaceAll("\\s+", " ").trim() + " " + (status == null ? "" : status.trim());
    }

    /** Coarse hash of the panel, to notice a scroll that did not move it. */
    private static long taskListFingerprint(BufferedImage frame) {
        long hash = 1125899906842597L;
        int bottom = Math.min(TASK_LIST_PANEL_BOTTOM_RIGHT.getY(), frame.getHeight());
        for (int y = TASK_LIST_PANEL_TOP_LEFT.getY(); y < bottom; y += 9) {
            hash = 31 * hash + (frame.getRGB(TASK_LIST_ROW_PROBE_X, y) & 0xF0F0F0);
            hash = 31 * hash + (frame.getRGB(360, y) & 0xF0F0F0);
        }
        return hash;
    }

    /** Which clock the panel is currently printing; null until a pass reads it. */
    private Boolean taskListClockIsUtc;

    /** The panel's own "Local Time"/"UTC" line, or null when neither word read. */
    private static Boolean readTaskListClockMode(String panelText) {
        if (panelText == null) {
            return null;
        }
        String text = panelText.toLowerCase();
        boolean local = text.contains("local");
        boolean utc = text.contains("utc");
        if (local == utc) {
            return null;
        }
        return utc;
    }

    private static final Pattern TASK_LIST_DATE_MARKER = Pattern.compile("(\\d{4}/\\d{2}/\\d{2})|\\bToday\\b");

    private static final Pattern TASK_LIST_ENTRY_TITLE = Pattern.compile(
            "(Fortress Battles|Castle Battle|Bear Hunt\\s*-\\s*Trap\\s*\\d+)", Pattern.CASE_INSENSITIVE);

    private static final Pattern TASK_LIST_FACILITY_REF = Pattern.compile(
            "(Fortress No\\.?\\s*\\d+|Stronghold No\\.?\\s*\\d+)", Pattern.CASE_INSENSITIVE);

    private static final Pattern TASK_LIST_TIME = Pattern.compile("(\\d{1,2}):(\\d{2})\\b");

    private static final Pattern TASK_LIST_ENDED = Pattern.compile("\\bEnded\\b", Pattern.CASE_INSENSITIVE);

    private void scanAllianceTaskList() {
        // matt: the header clock only opens the Task List from the World map -- not City, and
        // not on every tap even there (only when some alliance event is close). The scans before
        // this one (Fortress) can leave the screen on City, so this must force World explicitly
        // rather than assume execute()'s start-of-run WORLD requirement still holds mid-run.
        navigationHelper.ensureCorrectScreenLocation(LaunchPoint.WORLD);

        // A positive anchor (the panel's own "Task List" title) decides whether anything actually
        // opened, rather than assuming a tap that did nothing was a panel to read; without this,
        // the blind close-tap below would land on whatever else is on screen.
        tapNear(HEADER_CLOCK_POINT);
        sleepTask(PANEL_SETTLE_MS);

        String title = readPanelBlock(TASK_LIST_TITLE_TOP_LEFT, TASK_LIST_TITLE_BOTTOM_RIGHT);
        if (title == null || !title.toLowerCase().contains("task")) {
            logInfo("bg_telemetry | Task List not open (no event close enough right now) -- skipping.");
            return;
        }

        // Read row by row, not as one block. One OCR pass over the whole panel mixed the white bear
        // icons into the text and dropped the date heading or the times at random -- the same
        // screen read "2026/09/17Osae ineOseo" on one pass and lost the heading on the next, and
        // every entry under it was then skipped. Each row is found by its fill colour and read on
        // its own, as the calendar's cards are.
        java.util.List<String> seen = new java.util.ArrayList<>();
        StringBuilder combined = new StringBuilder();
        long previousScreen = 0;
        for (int pass = 0; pass <= TASK_LIST_SCROLL_PASSES; pass++) {
            if (pass > 0) {
                swipe(TASK_LIST_SCROLL_FROM, TASK_LIST_SCROLL_TO);
                sleepTask(700);
            }
            BufferedImage screen = captureFrame();
            if (screen == null) {
                break;
            }
            long fingerprint = taskListFingerprint(screen);
            if (pass > 0 && fingerprint == previousScreen) {
                break;
            }
            previousScreen = fingerprint;
            java.util.List<String> rows = readTaskListRows(screen);
            logDebug("bg_telemetry | Task List rows (screen " + pass + "): " + rows);
            for (String row : rows) {
                // A row already read on the screen before is the overlap, not a new entry; carrying it
                // again would place it under whichever heading the earlier screen ended on.
                boolean heading = TASK_LIST_DATE_MARKER.matcher(row).find();
                if (!heading && seen.contains(row)) {
                    continue;
                }
                seen.add(row);
                combined.append(row).append('\n');
            }
        }

        if (combined.toString().isBlank()) {
            logInfo("bg_telemetry | Task List panel was empty; skipping this pass.");
        } else {
            // The panel lists every alliance fight the game is currently advertising, so it is a
            // snapshot and this pass replaces the last one. Cleared only once the panel has actually
            // been read: on the passes where it does not open, the previous read is all there is.
            taskListClockIsUtc = readTaskListClockMode(
                    readPanelBlock(TASK_LIST_CLOCK_TOP_LEFT, TASK_LIST_CLOCK_BOTTOM_RIGHT));
            if (taskListClockIsUtc == null) {
                logWarning("bg_telemetry | Task List: the panel's clock-mode line did not read, so its"
                        + " times cannot be placed on a clock; recording those entries by date only.");
            } else {
                logInfo("bg_telemetry | Task List: times are shown in "
                        + (taskListClockIsUtc ? "UTC" : "local time") + ".");
            }

            // The previous read is replaced only by a read at least as trustworthy. The date heading
            // is small text in a large OCR block and sometimes drops out; the entries under it are
            // then undated and skipped, and clearing first had emptied the Bear Traps from the
            // calendar on every such pass. Unknown contributes nothing -- it does not erase either.
            int[] preview = recordTaskListFrom(combined.toString(), false);
            if (preview[0] == 0 || preview[1] > 0) {
                logWarning("bg_telemetry | Task List: this read found " + preview[0] + " dated and "
                        + preview[1] + " undated entr" + (preview[0] + preview[1] == 1 ? "y" : "ies")
                        + "; keeping the previous read rather than replace it with a partial one.");
            } else {
                int forgotten = EventScheduleService.obtain().forgetAll(TASK_LIST_KEY_PREFIX);
                if (forgotten > 0) {
                    logInfo("bg_telemetry | Task List: cleared " + forgotten + " row(s) from the previous read.");
                }
                recordTaskListFrom(combined.toString(), true);
            }
        }

        tapNear(TASK_LIST_CLOSE_POINT);
        sleepTask(600);
    }

    /** Returns {dated, undated}. With {@code write} false nothing is stored, only counted. */
    private int[] recordTaskListFrom(String panelText, boolean write) {
        // Walk the flattened text once, tracking the most recent date marker seen so each entry
        // inherits the section it actually appeared under.
        //
        // Starts unknown, not "Today". The panel opens on a past section whose entries carry no
        // date header at all -- the ended Battles above the first "2026/09/13" -- and defaulting
        // those to today stamped them with whatever day the scan happened to run. Rows are keyed by
        // date, so a week of scanning turned one recurring fight into one row per day and Bear Hunt
        // appeared to run daily. An entry with no date above it is simply not recorded.
        String currentDateMarker = null;
        int cursor = 0;
        int recorded = 0;
        int undated = 0;

        while (cursor < panelText.length()) {
            Matcher dateMatcher = TASK_LIST_DATE_MARKER.matcher(panelText);
            Matcher titleMatcher = TASK_LIST_ENTRY_TITLE.matcher(panelText);
            boolean dateFound = dateMatcher.find(cursor);
            boolean titleFound = titleMatcher.find(cursor);

            if (dateFound && (!titleFound || dateMatcher.start() < titleMatcher.start())) {
                currentDateMarker = dateMatcher.group();
                cursor = dateMatcher.end();
                continue;
            }
            if (!titleFound) {
                break;
            }

            int entryEnd = findNextEntryBoundary(panelText, titleMatcher.end());
            String entryText = panelText.substring(titleMatcher.end(), entryEnd);
            if (currentDateMarker == null) {
                undated++;
            } else if (write
                    ? recordOneTaskListEntry(titleMatcher.group(1), currentDateMarker, entryText)
                    : resolveTaskListDate(currentDateMarker) != null) {
                recorded++;
            }
            cursor = titleMatcher.end();
        }

        if (write) {
            logInfo("bg_telemetry | Task List: recorded " + recorded + " entr" + (recorded == 1 ? "y" : "ies")
                    + (undated == 0 ? "." : ", and skipped " + undated + " with no date heading above them."));
        }
        return new int[]{recorded, undated};
    }

    /** An entry's own text runs until the next title or date marker, whichever comes first. */
    private int findNextEntryBoundary(String panelText, int from) {
        Matcher nextTitle = TASK_LIST_ENTRY_TITLE.matcher(panelText);
        Matcher nextDate = TASK_LIST_DATE_MARKER.matcher(panelText);
        int titleEnd = nextTitle.find(from) ? nextTitle.start() : panelText.length();
        int dateEnd = nextDate.find(from) ? nextDate.start() : panelText.length();
        return Math.min(titleEnd, dateEnd);
    }

    private boolean recordOneTaskListEntry(String title, String dateMarker, String entryText) {
        LocalDate date = resolveTaskListDate(dateMarker);
        if (date == null) {
            return false;
        }

        Matcher facility = TASK_LIST_FACILITY_REF.matcher(entryText);
        String facilityRef = facility.find() ? facility.group(1).replaceAll("\\s+", " ").trim() : null;

        String label = facilityRef != null ? title + " (" + facilityRef + ")" : title;
        String key = TASK_LIST_KEY_PREFIX + (facilityRef != null ? facilityRef : title).toUpperCase()
                .replaceAll("[^A-Z0-9]+", "_") + "_" + date;

        boolean ended = TASK_LIST_ENDED.matcher(entryText).find();
        Matcher time = TASK_LIST_TIME.matcher(entryText);

        if (ended) {
            // A past section entry -- record it as a closed window so it stops showing as
            // "upcoming" without inventing a start time nothing on screen actually gave.
            LocalDateTime end = date.atStartOfDay();
            EventScheduleService.obtain().recordWindow(key, label, false, end, end);
            return true;
        }
        if (time.find()) {
            LocalDateTime shown = date.atTime(Integer.parseInt(time.group(1)), Integer.parseInt(time.group(2)));
            LocalDateTime start = toUtcFromPanel(shown);
            if (start == null) {
                // The panel's own clock-mode line did not read. Everything here is stored as UTC,
                // so a local time written straight in would be out by the whole offset. The date is
                // still known, so it is kept as a whole-day entry rather than a wrong o'clock.
                EventScheduleService.obtain().recordWindow(key, label, false,
                        date.atStartOfDay(), date.plusDays(1).atStartOfDay().minusMinutes(1));
                return true;
            }
            EventScheduleService.obtain().recordWindow(key, label, false, start, null);
            return true;
        }
        // Neither "Ended" nor a time -- the highlighted/in-progress entry (an arrow icon, not
        // text). Recorded as currently active with an unknown end.
        EventScheduleService.obtain().recordWindow(key, label, true, date.atStartOfDay(), null);
        return true;
    }

    /**
     * Converts a time the Task List printed into UTC, or null when the panel never said which clock
     * it was using.
     *
     * <p>The panel carries a "Local Time"/"UTC" toggle and remembers whichever the operator last
     * chose, so the numbers on it are not always UTC. Everything in this cache is stored as UTC, so
     * reading a local time straight in put every alliance fight out by the whole offset -- four
     * hours, in this account's case.</p>
     */
    private LocalDateTime toUtcFromPanel(LocalDateTime shown) {
        if (taskListClockIsUtc == null) {
            return null;
        }
        if (taskListClockIsUtc) {
            return shown;
        }
        return shown.atZone(ZoneId.systemDefault())
                .withZoneSameInstant(ZoneOffset.UTC)
                .toLocalDateTime();
    }

    private LocalDate resolveTaskListDate(String marker) {
        if ("Today".equalsIgnoreCase(marker)) {
            return LocalDate.now(ZoneOffset.UTC);
        }
        try {
            String[] parts = marker.split("/");
            return LocalDate.of(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
        } catch (Exception ex) {
            return null;
        }
    }

    // â”€â”€ Upcoming Events calendar scans â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    //
    // Read-only: never taps Claim, Enable, Occupation Income, or anything else on these screens.
    // Hall of Chiefs / Brothers in Arms / Defeat Nearby Beasts and the Fortress read run every
    // pass (hourly). The Events -> Calendar chart is read by the separate bg_calendar task, once a
    // game day -- it has nothing new to say between two resets.

    private static final TemplatesEnum[] ROTATING_EVENT_TABS = {
            TemplatesEnum.EVENTS_TAB_HALL_OF_CHIEFS,
            TemplatesEnum.EVENTS_TAB_BROTHERS_IN_ARMS,
            TemplatesEnum.EVENTS_TAB_DEFEAT_BEASTS,
    };

    private static final String[] ROTATING_EVENT_LABELS = {
            "Hall of Chiefs", "Brothers in Arms", "Defeat Nearby Beasts",
    };

    /** Stable keys matching the original EventKind enum names -- NOT TemplatesEnum.name()
     *  (EVENTS_TAB_HALL_OF_CHIEFS, ...), which briefly diverged and produced duplicate rows for
     *  the same event under two different keys. */
    private static final String[] ROTATING_EVENT_KEYS = {
            "HALL_OF_CHIEFS", "BROTHERS_IN_ARMS", "DEFEAT_NEARBY_BEASTS",
    };

    private static final PointData FORTRESS_PANEL_TOP_LEFT = new PointData(10, 580);

    private static final PointData FORTRESS_PANEL_BOTTOM_RIGHT = new PointData(710, 1270);

    private static final PointData FORTRESS_SCROLL_FROM = new PointData(360, 1100);

    private static final PointData FORTRESS_SCROLL_TO = new PointData(360, 650);

    private static final Pattern FACILITY_NAME = Pattern.compile(
            "(Fortress No\\.?\\s*\\d+|Stronghold No\\.?\\s*\\d+)", Pattern.CASE_INSENSITIVE);

    private static final Pattern CONTROLLED_BY = Pattern.compile(
            "Controlled by\\s*([^\\n]{1,40})", Pattern.CASE_INSENSITIVE);

    private static final Pattern REWARD_EXPIRES = Pattern.compile(
            "Reward expires in\\s*(?:(\\d+)\\s*d\\s*)?(\\d{1,2}):(\\d{2}):(\\d{2})", Pattern.CASE_INSENSITIVE);

    private static final PointData CALENDAR_PANEL_TOP_LEFT = new PointData(10, 230);

    private static final PointData CALENDAR_PANEL_BOTTOM_RIGHT = new PointData(710, 1200);

    private static final PointData CALENDAR_TAB_STRIP_SWIPE_FROM = new PointData(150, 141);

    private static final PointData CALENDAR_TAB_STRIP_SWIPE_TO = new PointData(600, 141);

    private static final int PANEL_SETTLE_MS = 1200;

    private static final int DEFAULT_TRAP_NUMBER = 1;

    private static final int DEFAULT_TRAP_PREPARATION_MINUTES = 10;

    private void scanUpcomingEventsCalendar() {
        scanRotatingEvents();
        scanBearTrapWindow();
        scanAllianceFortress();
        scanAllianceTaskList();
    }

    /** Presence/absence of each rotating Events-tab entry -- no on-screen countdown exists to
     *  read there, so this is a transition timestamp accurate to this task's own interval. */
    private void scanRotatingEvents() {
        ImageSearchResultData eventsBtn = templateSearchHelper.locatePattern(
                TemplatesEnum.HOME_EVENTS_BUTTON, SearchConfigConstants.SINGLE_WITH_RETRIES);
        if (!eventsBtn.isFound()) {
            logInfo("bg_telemetry | Events icon not found; skipping the rotating-event scan this pass.");
            return;
        }
        tapNear(eventsBtn.getPoint());
        sleepTask(PANEL_SETTLE_MS);

        for (int i = 0; i < ROTATING_EVENT_TABS.length; i++) {
            ImageSearchResultData tab = templateSearchHelper.locatePattern(
                    ROTATING_EVENT_TABS[i], SearchConfigConstants.QUICK_SEARCH);
            EventScheduleService.obtain().recordObservation(
                    ROTATING_EVENT_KEYS[i], ROTATING_EVENT_LABELS[i], tab.isFound());
        }

        pressBack();
        sleepTask(600);
    }

    /** Bear Trap's window is already fully deterministic from its configured anchor -- no
     *  navigation needed, just the same math BearTrapRoutine itself uses. */
    private void scanBearTrapWindow() {
        try {
            Integer trapNumber = profile.getConfig(ConfigurationKeyEnum.BEAR_TRAP_NUMBER_INT, Integer.class);
            int resolvedTrapNumber = trapNumber == null ? DEFAULT_TRAP_NUMBER : trapNumber;
            LocalDateTime referenceTrapTime = profile.getConfig(
                    BearTrapParticipationSchedule.scheduleKey(resolvedTrapNumber), LocalDateTime.class);
            if (referenceTrapTime == null) {
                logInfo("bg_telemetry | Bear Trap reference time not configured; skipping its calendar entry.");
                return;
            }
            Integer prepMinutes = profile.getConfig(ConfigurationKeyEnum.BEAR_TRAP_PREPARATION_TIME_INT, Integer.class);
            int resolvedPrepMinutes = prepMinutes == null ? DEFAULT_TRAP_PREPARATION_MINUTES : prepMinutes;

            Instant referenceUtc = referenceTrapTime.atZone(ZoneOffset.UTC).toInstant();
            TimeWindowHelper.WindowResult window = BearTrapHelper.calculateWindow(referenceUtc, resolvedPrepMinutes);

            boolean active = window.getState() == TimeWindowHelper.WindowState.INSIDE;
            Instant windowStart = active ? window.getCurrentWindowStart() : window.getNextWindowStart();
            Instant windowEnd = active
                    ? window.getCurrentWindowEnd()
                    : window.getNextWindowStart().plusSeconds(window.getCurrentWindowDurationMinutes() * 60L);

            EventScheduleService.obtain().recordWindow("BEAR_TRAP", "Bear Trap", active,
                    LocalDateTime.ofInstant(windowStart, ZoneOffset.UTC),
                    LocalDateTime.ofInstant(windowEnd, ZoneOffset.UTC));
        } catch (Exception ex) {
            logWarning("bg_telemetry | Could not compute Bear Trap's window this scan: " + ex.getMessage());
        }
    }

    /** Live-verified 2026-09-11 against Alliance -> Battle -> Fortress: repeating cards each
     *  reading a facility name, "Controlled by [alliance]", and "Reward expires in HH:MM:SS".
     *  OCR-reads the whole panel as one block and pairs each name with the next expiry that
     *  follows it, since card order/count is however many facilities the alliance currently holds. */
    private void scanAllianceFortress() {
        if (!navigationHelper.navigateToAllianceMenu(NavigationHelper.AllianceMenu.BATTLE)) {
            logInfo("bg_telemetry | Could not open Alliance > Battle; skipping the Fortress scan this pass.");
            return;
        }
        sleepTask(PANEL_SETTLE_MS);

        ImageSearchResultData fortressTab = templateSearchHelper.locatePattern(
                TemplatesEnum.ALLIANCE_BATTLE_FORTRESS_TAB, SearchConfigConstants.SINGLE_WITH_RETRIES);
        if (fortressTab.isFound()) {
            tapNear(fortressTab.getPoint());
            sleepTask(PANEL_SETTLE_MS);
        }

        recordFacilitiesFrom(readPanelBlock(FORTRESS_PANEL_TOP_LEFT, FORTRESS_PANEL_BOTTOM_RIGHT));
        // One scroll to pick up facilities beyond the first screenful; a re-seen card just
        // overwrites itself since recordOneFacility writes by facility-name key.
        swipe(FORTRESS_SCROLL_FROM, FORTRESS_SCROLL_TO);
        sleepTask(PANEL_SETTLE_MS);
        recordFacilitiesFrom(readPanelBlock(FORTRESS_PANEL_TOP_LEFT, FORTRESS_PANEL_BOTTOM_RIGHT));

        pressBack();
        sleepTask(600);
        pressBack();
    }

    private void recordFacilitiesFrom(String panelText) {
        if (panelText == null || panelText.isBlank()) {
            return;
        }
        Matcher nameMatcher = FACILITY_NAME.matcher(panelText);
        int previousEnd = -1;
        String previousName = null;
        while (nameMatcher.find()) {
            if (previousName != null) {
                recordOneFacility(previousName, panelText.substring(previousEnd, nameMatcher.start()));
            }
            previousName = nameMatcher.group(1).replaceAll("\\s+", " ").trim();
            previousEnd = nameMatcher.end();
        }
        if (previousName != null) {
            recordOneFacility(previousName, panelText.substring(previousEnd));
        }
    }

    private void recordOneFacility(String facilityName, String followingText) {
        Matcher expires = REWARD_EXPIRES.matcher(followingText);
        if (!expires.find()) {
            return; // occupiable/unoccupied cards show no "Reward expires in" line
        }

        long days = expires.group(1) == null ? 0 : Long.parseLong(expires.group(1));
        int hours = Integer.parseInt(expires.group(2));
        int minutes = Integer.parseInt(expires.group(3));
        int seconds = Integer.parseInt(expires.group(4));
        Duration remaining = Duration.ofDays(days).plusHours(hours).plusMinutes(minutes).plusSeconds(seconds);

        Matcher controller = CONTROLLED_BY.matcher(followingText);
        String controlledBy = controller.find() ? controller.group(1).trim() : null;
        String label = controlledBy == null ? facilityName : facilityName + " (" + controlledBy + ")";

        LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC);
        EventScheduleService.obtain().recordWindow(
                "FORTRESS_" + facilityName.toUpperCase().replaceAll("[^A-Z0-9]+", "_"),
                label, true, nowUtc, nowUtc.plus(remaining));
    }

    /** One slow step down the chart. 250px against an ~800px viewport, so every step overlaps the
     *  last by more than two bar rows, and dragged over 1.5s so the list moves exactly that far
     *  instead of flinging. Measured on a live sweep: 16 bars in 6 steps, bottom found on the 7th. */
    private static final PointData CALENDAR_STEP_FROM = new PointData(360, 900);

    private static final PointData CALENDAR_STEP_TO = new PointData(360, 650);


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
     * The "Last night" report needs clean bookends, so always take an inventory
     * snapshot exactly at 23:00 (bedtime) and 08:30 (wake) local, on top of the normal interval.
     * Next run is the soonest of those two anchors or now+interval.
     *
     * <p>Dave's #250 review, 2026-08-18: this anchor had drifted to 08:00 while TelemetryReport's
     * WAKE_ANCHOR_GRACE_MINUTES comment documents an observed real capture at 08:30:43 -- the two
     * had gone out of sync. Restored to 08:30 to match the documented, actually-observed behavior.</p>
     */
    private void scheduleNext() {
        setRecurring(true);
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        java.time.LocalDateTime next = now.plus(interval);
        for (java.time.LocalTime anchor : new java.time.LocalTime[]{
                java.time.LocalTime.of(23, 0), java.time.LocalTime.of(8, 30)}) {
            java.time.LocalDateTime a = now.with(anchor);
            if (!a.isAfter(now)) a = a.plusDays(1);
            if (a.isBefore(next)) next = a;
        }
        reschedule(next);
    }

    /**
     * Reads a HUD number, resolving the game's abbreviated form. Returns null
     * rather than a guess when OCR gives nothing usable â€” a wrong number is
     * worse than a missing one in a history meant for graphing.
     */
    private Long readScaledNumber(PointData tl, PointData br, OcrSettingsData settings, String label) {
        String raw = readStringValue(tl, br, settings);
        if (raw == null || raw.isBlank()) {
            logWarning("bg_telemetry | No OCR text for " + label + ".");
            return null;
        }
        Long parsed = parseScaled(raw);
        if (parsed == null) {
            logWarning("bg_telemetry | Unparseable " + label + " reading: '" + raw.trim() + "'");
        }
        return parsed;
    }

    /** Fraction outside of which a new power/gems reading is rejected as an implausible OCR
     *  misread rather than a real change -- see the execute() call site's header note. Real
     *  changes between 2-hour samples are gradual; the actual misreads observed live jumped
     *  ~1.7-1.8x, well outside this band. */
    private static final double SANITY_BAND_MAX_RATIO = 1.5;

    private static final double SANITY_BAND_MIN_RATIO = 1.0 / SANITY_BAND_MAX_RATIO;

    /**
     * A reading held back for one cycle, waiting to see whether the next one agrees with it.
     * Keyed "&lt;profileId&gt;:&lt;field&gt;".
     *
     * <p>In memory, so a restart forgets it and the confirmation starts over -- the same trade
     * ResourceStockpileRoutine makes for its own streak, and for the same reason: it costs one
     * extra sample after a restart, not a lasting fault.</p>
     */
    private static final Map<String, Long> AWAITING_CONFIRMATION = new java.util.concurrent.ConcurrentHashMap<>();

    private static boolean withinBand(long candidate, long reference) {
        if (reference <= 0) {
            return true;
        }
        double ratio = (double) candidate / (double) reference;
        return ratio <= SANITY_BAND_MAX_RATIO && ratio >= SANITY_BAND_MIN_RATIO;
    }

    /**
     * Rejects a candidate reading that jumps implausibly far from the last known-good value for
     * the same field -- unless the previous cycle saw the same jump, in which case it is real.
     *
     * <p>Comparing against the last known-good rather than the previous row is what stopped
     * misreads slipping in one sample after every rejection. On its own, though, it cannot ever
     * change its mind: a genuine large move is outside the band, gets rejected, and the value it
     * is compared against never moves, so it is rejected again forever. That is not hypothetical.
     * Gems went from 123,911 to 455 -- spent, in one go -- and every reading afterwards was
     * refused against a figure the account had not held for half a day, so the tab simply stopped
     * tracking gems.</p>
     *
     * <p>What separates the two cases is repetition. A misread is a single bad frame and the next
     * reading disagrees with it; a real change is still there two hours later. So an implausible
     * reading is now held back rather than thrown away, and accepted when the following one lands
     * near it. A real move costs one skipped sample; a misread still never gets in.</p>
     */
    private Long sanityCheckAgainstLastKnown(String field, Long candidate, Map<String, Object> lastKnownGood) {
        if (candidate == null || lastKnownGood == null) {
            return candidate;
        }
        String key = profile.getId() + ":" + field;
        Object prevObj = lastKnownGood.get(field);
        if (!(prevObj instanceof Long) || (Long) prevObj <= 0) {
            AWAITING_CONFIRMATION.remove(key);
            return candidate;
        }
        long prev = (Long) prevObj;
        if (withinBand(candidate, prev)) {
            AWAITING_CONFIRMATION.remove(key);
            return candidate;
        }

        Long held = AWAITING_CONFIRMATION.get(key);
        if (held != null && withinBand(candidate, held)) {
            logWarning("bg_telemetry | " + field + " has now read near " + candidate + " twice running,"
                    + " so the move away from " + prev + " is real rather than a misread -- accepting it.");
            AWAITING_CONFIRMATION.remove(key);
            return candidate;
        }

        AWAITING_CONFIRMATION.put(key, candidate);
        logWarning("bg_telemetry | " + field + " reading " + candidate + " is implausibly far from the "
                + "last known-good " + prev + " (ratio " + String.format("%.2f", (double) candidate / prev)
                + ") -- holding it back for now. If the next reading agrees with it, it will be taken as"
                + " a real change; if not, it was a misread and nothing was recorded.");
        return null;
    }

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
     * The most recent NON-NULL value for each field, walking back through history as far as it
     * takes -- not simply the previous row.
     *
     * <p>This used to read latest.json, i.e. the last row written, and that made the guard blind
     * itself. Rejecting a reading writes null for that field; the next run then found null in
     * latest.json, had nothing to compare against, and passed the next candidate through
     * unchecked. So misreads got in one sample after every rejection, which for an intermittent
     * fault is most of them. The gems series shows it exactly:</p>
     *
     * <pre>
     *   8/23 19:34  47,635   good
     *   8/23 22:36  null     rejected -- guard working
     *   8/23 23:18  94,523   accepted, because the row above it was null
     *   8/24 01:20  null     rejected
     *   8/24 03:23  56,755   good
     * </pre>
     *
     * <p>A rejection is precisely when the guard needs to keep holding the last good value, so it
     * now does. Fields still absent everywhere in history simply have nothing to compare against,
     * which callers already handle.</p>
     */
    private Map<String, Object> readLastKnownGood() {
        try {
            if (profile.getId() == null) {
                return null;
            }
            // Asked of the observation store rather than by re-reading and regex-scanning a
            // growing text file on every run. Same question, same answer -- the last recorded
            // reading of each field -- but by index, and over everything recorded rather than
            // only what this task happened to write.
            dev.frostguard.data.metrics.MetricStore store =
                    dev.frostguard.data.metrics.MetricStore.forCurrentWorkspace();
            java.time.Instant now = java.time.Instant.now();
            Map<String, Object> result = new LinkedHashMap<>();
            for (String metric : store.metrics(profile.getId())) {
                // A rejected reading is never recorded, so the newest row for a field is by
                // construction its last known-good value.
                store.lastAtOrBefore(profile.getId(), metric, now)
                        .ifPresent(o -> result.put(metric, o.value()));
            }
            return result.isEmpty() ? null : result;
        } catch (Exception e) {
            return null;
        }
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

    /**
     * Adds the current activity totals (per-task run counts and action counters) to the sample as
     * flat number fields. Reads them straight from {@link StatisticsService} â€” no JSON parsing â€”
     * so the Statistics tab can diff two snapshots into "what the bot did" for a window.
     */
    private void appendActivitySnapshot(Map<String, Object> sample) {
        try {
            ProfilesData stats = StatisticsService.obtain().loadMetrics(profile);
            if (stats == null) return;
            if (stats.getTaskStatistics() != null) {
                for (Map.Entry<String, JobMetrics> e : stats.getTaskStatistics().entrySet()) {
                    if (e.getValue() != null) {
                        sample.put("run." + e.getKey(), e.getValue().getNumberOfRuns());
                    }
                }
            }
            if (stats.getCustomCounters() != null) {
                for (Map.Entry<String, Integer> e : stats.getCustomCounters().entrySet()) {
                    sample.put("ctr." + e.getKey(), e.getValue());
                }
            }
        } catch (Exception e) {
            logWarning("bg_telemetry | Could not snapshot activity stats: " + e.getMessage());
        }
    }

    /**
     * Records this task's own live HUD readings and the accomplishment counters.
     *
     * <p>Only the three HUD figures are this task's own readings; meat, wood, iron, steel and the
     * speedups are recorded by ResourceStockpileRoutine when it reads them, so they are not copied
     * here. The counters are the application's own running totals rather than anything OCR'd, so
     * they are as good at this moment as at any other.</p>
     *
     * <p>Best-effort: recording a statistic must never break a sample.</p>
     */
    private void recordObservations(Long power, Long gems, Long coal, Map<String, Object> sample) {
        try {
            if (profile.getId() == null) {
                return;
            }
            Map<String, Long> seen = new LinkedHashMap<>();
            if (power != null) seen.put("power", power);
            if (gems != null) seen.put("gems", gems);
            if (coal != null) seen.put("coal", coal);
            for (Map.Entry<String, Object> e : sample.entrySet()) {
                if ((e.getKey().startsWith("run.") || e.getKey().startsWith("ctr."))
                        && e.getValue() instanceof Number n) {
                    seen.put(e.getKey(), n.longValue());
                }
            }
            if (!seen.isEmpty()) {
                dev.frostguard.data.metrics.MetricStore.forCurrentWorkspace()
                        .recordAll(profile.getId(), java.time.Instant.now(), seen);
            }
        } catch (Exception e) {
            logWarning("bg_telemetry | Could not record observations: " + e.getMessage());
        }
    }

    /** One lock per JVM, shared by every profile's writer -- cheap, and removes any doubt about
     *  two runs (a slow one plus its on-time successor) interleaving the same profile's files. */
    private static final Object WRITE_LOCK = new Object();

    /**
     * Atomically replaces the latest-sample file.
     *
     * <p>A glanceable snapshot of the most recent sample, and nothing reads it to answer a
     * question about a time span -- the observation store does that. It is written to a temp file
     * and moved into place (atomically where the filesystem supports it) rather than
     * truncate-written, so a reader can never observe a half-written file.</p>
     */
    private void writeSample(String json) {
        Path dir = telemetryDir();
        synchronized (WRITE_LOCK) {
            try {
                Files.createDirectories(dir);
                // history.jsonl is no longer appended. The observation store is the record of
                // what was read and when; a second parallel copy of the same numbers in a text
                // file is what made "what changed overnight" ambiguous in the first place. The
                // existing file is left alone -- it is what the store was seeded from.
                writeAtomically(dir.resolve("latest.json"), json.getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                logError("bg_telemetry | Could not write telemetry to " + dir + ": " + e.getMessage());
            }
        }
    }

    /** Write-to-temp-then-move, matching {@code GameAnalyticsHistoryService}'s convention --
     *  falls back to a non-atomic replace only when the filesystem genuinely can't do better. */
    private static void writeAtomically(Path target, byte[] content) throws IOException {
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(temporary, content);
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Minimal serializer â€” the project ships no JSON binding usable from here. */
    private static String toJson(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(e.getKey()).append("\":");
            Object v = e.getValue();
            if (v == null) {
                sb.append("null");
            } else if (v instanceof Number) {
                sb.append(v);
            } else {
                sb.append('"').append(String.valueOf(v).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
            }
        }
        return sb.append('}').toString();
    }
}

