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
    private static final DateTimeFormatter UTC_INPUT_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

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
        // "Upcoming Events" calendar rides this task's own schedule instead of needing its own.
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

        String combined = readPanelBlock(TASK_LIST_PANEL_TOP_LEFT, TASK_LIST_PANEL_BOTTOM_RIGHT);
        if (combined == null) {
            combined = "";
        }
        logDebug("bg_telemetry | Task List raw panel text (pass 0): " + combined);
        for (int i = 0; i < TASK_LIST_SCROLL_PASSES; i++) {
            swipe(TASK_LIST_SCROLL_FROM, TASK_LIST_SCROLL_TO);
            sleepTask(700);
            String more = readPanelBlock(TASK_LIST_PANEL_TOP_LEFT, TASK_LIST_PANEL_BOTTOM_RIGHT);
            logDebug("bg_telemetry | Task List raw panel text (pass " + (i + 1) + "): " + more);
            if (more != null && !more.isBlank()) {
                combined = combined + "\n" + more;
            }
        }

        if (combined.isBlank()) {
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
            int[] preview = recordTaskListFrom(combined, false);
            if (preview[0] == 0 || preview[1] > 0) {
                logWarning("bg_telemetry | Task List: this read found " + preview[0] + " dated and "
                        + preview[1] + " undated entr" + (preview[0] + preview[1] == 1 ? "y" : "ies")
                        + "; keeping the previous read rather than replace it with a partial one.");
            } else {
                int forgotten = EventScheduleService.obtain().forgetAll(TASK_LIST_KEY_PREFIX);
                if (forgotten > 0) {
                    logInfo("bg_telemetry | Task List: cleared " + forgotten + " row(s) from the previous read.");
                }
                recordTaskListFrom(combined, true);
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
    // pass (hourly); the state Calendar read is gated to the first pass of each UTC day (see
    // maybeScanStateCalendar) since it has nothing new to say between two game resets.

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

    private static final String STATE_GANTT_KEY_PREFIX = "STATE_GANTT_";
    /** Bump whenever a bar's span or name is read differently, to invalidate the stored scan. */
    private static final String SCAN_FORMAT_VERSION = "v11";
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
    private static final int DEFAULT_TRAP_NUMBER = 1;
    private static final int DEFAULT_TRAP_PREPARATION_MINUTES = 10;

    private void scanUpcomingEventsCalendar() {
        scanRotatingEvents();
        scanBearTrapWindow();
        scanAllianceFortress();
        scanAllianceTaskList();
        maybeScanStateCalendar();
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
        String lastScanned = profile.getConfig(
                ConfigurationKeyEnum.BG_TELEMETRY_LAST_STATE_CALENDAR_SCAN_DATE_STRING, String.class);
        if (gameDay.equals(lastScanned)) {
            return;
        }

        // The Events button lives on the base screen, and the scans before this one leave panels
        // open. Without this the tap lands on whatever is in front and the panel never opens.
        navigationHelper.ensureCorrectScreenLocation(LaunchPoint.WORLD);

        ImageSearchResultData eventsBtn = templateSearchHelper.locatePattern(
                TemplatesEnum.HOME_EVENTS_BUTTON, SearchConfigConstants.SINGLE_WITH_RETRIES);
        if (!eventsBtn.isFound()) {
            logInfo("bg_telemetry | Events icon not found; will retry the state calendar scan next run.");
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
            logWarning("bg_telemetry | Calendar tab not found after " + CALENDAR_TAB_SWIPE_ATTEMPTS
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
        logInfo("bg_telemetry | Calendar: icon library holds " + iconLibrary.size()
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
            logWarning("bg_telemetry | Calendar: still scrolling after " + CALENDAR_MAX_SCROLL_STEPS
                    + " steps; stopped there, so bars below that point were not read this pass.");
        }
        logInfo("bg_telemetry | State calendar: read " + (steps + 1) + " screen(s), recorded "
                + recorded + " bar reading(s).");
        // Written only once the whole chart has been read. A pass that stopped part-way used to have
        // cleared the previous read already, and left the calendar holding the six bars of its one
        // screen; the previous full read is the better answer until a new one completes.
        if (calendarPassAborted) {
            logWarning("bg_telemetry | Calendar: this pass stopped early; keeping the previous read"
                    + " and discarding " + pendingCalendarRows.size() + " partial row(s).");
        } else {
            int forgotten = EventScheduleService.obtain().forgetAll(STATE_GANTT_KEY_PREFIX);
            if (forgotten > 0) {
                logInfo("bg_telemetry | Calendar: cleared " + forgotten + " row(s) from the previous read.");
            }
            for (Object[] row : pendingCalendarRows.values()) {
                EventScheduleService.obtain().recordWindow((String) row[0], (String) row[1],
                        (Boolean) row[2], (LocalDateTime) row[3], (LocalDateTime) row[4]);
            }
            logInfo("bg_telemetry | Calendar: stored " + pendingCalendarRows.size() + " event(s).");
        }

        pressBack();
        sleepTask(600);
        pressBack();

        if (!calendarPassAborted) {
            profile.setConfig(ConfigurationKeyEnum.BG_TELEMETRY_LAST_STATE_CALENDAR_SCAN_DATE_STRING, gameDay);
        }
    }

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
    /** The steel-blue section divider ("Deals", "Events"): never a bar. */
    private static final int[] GANTT_SECTION_HEADER = {112, 184, 209};
    private static final int GANTT_COLOR_TOLERANCE = 28;

    /** One slow step down the chart. 250px against an ~800px viewport, so every step overlaps the
     *  last by more than two bar rows, and dragged over 1.5s so the list moves exactly that far
     *  instead of flinging. Measured on a live sweep: 16 bars in 6 steps, bottom found on the 7th. */
    private static final PointData CALENDAR_STEP_FROM = new PointData(360, 900);
    private static final PointData CALENDAR_STEP_TO = new PointData(360, 650);
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
            logInfo("bg_telemetry | Calendar: learned the icon for \"" + canonicalName
                    + "\", so it no longer depends on reading that label.");
        } catch (IOException | RuntimeException writeFailed) {
            logWarning("bg_telemetry | Calendar: could not save the icon for \"" + canonicalName
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
            logWarning("bg_telemetry | Calendar: could not read " + CALENDAR_NAMES_FILE + ": "
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
            logInfo("bg_telemetry | Calendar: \"" + observed + "\" scores " + Math.round(bestScore)
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
                    logWarning("bg_telemetry | Calendar: could not read icon " + file.getFileName()
                            + ": " + unreadable.getMessage());
                }
            }
        } catch (IOException listFailed) {
            logWarning("bg_telemetry | Calendar: could not list the icon library: " + listFailed.getMessage());
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
                logWarning("bg_telemetry | Calendar: icon \"" + candidate.getKey()
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
            logInfo("bg_telemetry | Calendar: icon scores " + Math.round(bestScore) + " for \"" + best
                    + "\" but " + Math.round(runnerUp) + " for the runner-up, too close to call;"
                    + " leaving it unidentified.");
            return null;
        }
        return best;
    }

    /** "UTC Time 2026-09-16 02:44:52" -- the banner directly above the day columns. */
    private static final PointData GAME_CLOCK_TOP_LEFT = new PointData(120, 226);
    private static final PointData GAME_CLOCK_BOTTOM_RIGHT = new PointData(604, 290);
    private static final Pattern GAME_CLOCK_DATE = Pattern.compile("([0-9]{4})[-/]([0-9]{2})[-/]([0-9]{2})");

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
            logInfo("bg_telemetry | Calendar: clock banner read as \"" + banner.trim()
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
            logWarning("bg_telemetry | Calendar: could not hold the icon of an unidentified bar: "
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

    /** "2026-09-18 00:00 - 2026-09-19 24:00" -- the card's own window line, always in UTC. */
    private static final Pattern TOOLTIP_WINDOW = Pattern.compile(
            "([0-9]{4})-([0-9]{2})-([0-9]{2}) +([0-9]{1,2}):([0-9]{2}) *- *"
                    + "([0-9]{4})-([0-9]{2})-([0-9]{2}) +([0-9]{1,2}):([0-9]{2})");
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
            logInfo("bg_telemetry | Calendar: tapping the bar at " + x + "," + y + " opened no card.");
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
                logInfo("bg_telemetry | Calendar: card opened but its window line read as \""
                        + (windowText == null ? "" : windowText.trim()) + "\"; not trusting it.");
            } else if (title.length() < MIN_CREDIBLE_LABEL_LENGTH) {
                logInfo("bg_telemetry | Calendar: card opened but its title read as \""
                        + (titleText == null ? "" : titleText.trim()) + "\"; not trusting it.");
            } else {
                result = new Tooltip(title, window[0], window[1]);
            }
        }

        if (!closeTooltip()) {
            calendarPassAborted = true;
            logWarning("bg_telemetry | Calendar: a tooltip card would not close; stopping this pass"
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
            logWarning("bg_telemetry | Calendar: could not hold the new icon for \"" + name + "\": "
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

    /** An icon's name comes from its file name, which cannot hold an apostrophe ("Mias_Fortune_Hut");
     *  the list's own spelling is the one shown. */
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
            logInfo("bg_telemetry | Calendar: added \"" + name + "\" to " + CALENDAR_NAMES_FILE + ".");
        } catch (IOException unwritable) {
            logWarning("bg_telemetry | Calendar: could not add \"" + name + "\" to "
                    + CALENDAR_NAMES_FILE + ": " + unwritable.getMessage());
        }
    }

    private int readGanttChart(boolean finalScreen) {
        RawImageData capture = emuManager.captureScreen(EMULATOR_NUMBER);
        if (capture == null) {
            logWarning("bg_telemetry | Calendar: screen capture unavailable; skipping this pass.");
            return 0;
        }
        BufferedImage frame;
        try {
            frame = ImageConverter.toBufferedImage(capture);
        } catch (RuntimeException conversionFailed) {
            logWarning("bg_telemetry | Calendar: could not decode the frame: " + conversionFailed.getMessage());
            return 0;
        }

        int todayColumn = ganttTodayColumn(frame);
        if (todayColumn < 0) {
            logWarning("bg_telemetry | Calendar: today's column is not highlighted on this frame, so the "
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
            logWarning("bg_telemetry | Calendar: could not read the game's UTC clock from the panel"
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
                logInfo("bg_telemetry | Calendar: bar " + start + ".." + end + " read as \""
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
                logInfo("bg_telemetry | Calendar: bar " + start + ".." + end + " read as \""
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
                    logInfo("bg_telemetry | Calendar: " + name + " runs off the chart edge and its card"
                            + " could not be read; keeping the visible " + start + ".." + end + ".");
                } else if (!name.equalsIgnoreCase(listSpelling(tooltip.title))
                        && !name.equalsIgnoreCase(snapToKnownName(tooltip.title))) {
                    logInfo("bg_telemetry | Calendar: tapped " + name + " but its card says \""
                            + tooltip.title + "\"; keeping the visible " + start + ".." + end + ".");
                } else if (tooltip.start.isAfter(startAt) || tooltip.end.isBefore(endAt)) {
                    logInfo("bg_telemetry | Calendar: " + name + "'s card window " + tooltip.start
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
            logInfo("bg_telemetry | Calendar: band " + start + ".." + end + " at y=" + centre
                    + " has no icon, no name and no card; not an event, not recorded.");
            return 0;
        }
        if (!identified) {
            // Nothing agreed and the tooltip gave nothing either. The dates are real and are kept;
            // the name is not invented, and the raw read is logged.
            if (sameUnidentifiedAs == null) {
                logInfo("bg_telemetry | Calendar: bar " + start + ".." + end + " read as \""
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
        logInfo("bg_telemetry | Calendar: " + name + " " + start + " -> " + end
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
