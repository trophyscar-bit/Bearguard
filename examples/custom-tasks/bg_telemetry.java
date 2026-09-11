package dev.frostguard.engine.listener.task.impl;

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
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
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

/**
 * Bearguard telemetry: samples the top HUD on a schedule and appends the result
 * to a JSON history the Whiteout dashboard reads.
 *
 * <p>This exists because the external Node scraper that used to do this drove
 * ADB itself, so it could not run while the bot was running — the two fought
 * over the same device. Running the capture as a task inside the bot's own
 * queue removes that conflict by construction, and inherits the engine's
 * screen-verification and retry behaviour for free.
 *
 * <p>Deliberately additive: a new file under custom_tasks/, no upstream source
 * touched, so merges from Shederator/wosbot stay clean.
 */
public class bg_telemetry extends DelayedTask implements CustomTaskConfigurable {

    // matt/2026-08-09: hourly, so "last night" (23:00→08:00) and every window has fine-grained data.
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
     */
    private static final OcrSettingsData HUD_FULL_NUMBER_SETTINGS =
            OcrSettingsData.assembler()
                    .charWhitelist("0123456789,")
                    .textLayout(TextLayout.SINGLE_LINE)
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
        // from the values ResourceStockpileRoutine last scanned (stored in config) —
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

    // ── Upcoming Events calendar scans ──────────────────────────────────────────────
    //
    // Read-only: never taps Claim, Enable, Occupation Income, or anything else on these screens.
    // Hall of Chiefs / Brothers in Arms / Defeat Nearby Beasts and the Fortress read run every
    // pass (hourly); the state Calendar read is gated to once a day near 8:05 PM EST (see
    // maybeScanStateCalendar) since it has nothing new to say more often than that.

    private static final TemplatesEnum[] ROTATING_EVENT_TABS = {
            TemplatesEnum.EVENTS_TAB_HALL_OF_CHIEFS,
            TemplatesEnum.EVENTS_TAB_BROTHERS_IN_ARMS,
            TemplatesEnum.EVENTS_TAB_DEFEAT_BEASTS,
    };
    private static final String[] ROTATING_EVENT_LABELS = {
            "Hall of Chiefs", "Brothers in Arms", "Defeat Nearby Beasts",
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
    private static final ZoneId EST = ZoneId.of("America/New_York");
    private static final LocalTime CALENDAR_SCAN_TIME = LocalTime.of(20, 5);
    private static final Pattern DAY_LABEL = Pattern.compile("(Mon|Tue|Wed|Thu|Fri|Sat|Sun)\\s*(\\d{2}/\\d{2})");
    private static final Pattern TIME_RANGE = Pattern.compile("(\\d{1,2}:\\d{2})\\s*[-–]\\s*(\\d{1,2}:\\d{2})");

    private static final int PANEL_SETTLE_MS = 1200;
    private static final int DEFAULT_TRAP_NUMBER = 1;
    private static final int DEFAULT_TRAP_PREPARATION_MINUTES = 10;

    private void scanUpcomingEventsCalendar() {
        scanRotatingEvents();
        scanBearTrapWindow();
        scanAllianceFortress();
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
                    ROTATING_EVENT_TABS[i].name(), ROTATING_EVENT_LABELS[i], tab.isFound());
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

    /** Gated to once a day: the first hourly bg_telemetry run at/after 8:05 PM EST that has not
     *  already scanned today (tracked in BG_TELEMETRY_LAST_STATE_CALENDAR_SCAN_DATE_STRING). This
     *  week's grid had no event bars posted, so the format of a populated day is not yet observed
     *  -- this looks for an HH:mm-HH:mm range near each day label as a best-effort first pass and
     *  logs the raw panel text at DEBUG every run specifically so the real format can be confirmed
     *  and this parser tightened the next time a state event is actually scheduled. */
    private void maybeScanStateCalendar() {
        ZonedDateTime nowEst = ZonedDateTime.now(EST);
        if (nowEst.toLocalTime().isBefore(CALENDAR_SCAN_TIME)) {
            return;
        }
        String todayEst = nowEst.toLocalDate().toString();
        String lastScanned = profile.getConfig(
                ConfigurationKeyEnum.BG_TELEMETRY_LAST_STATE_CALENDAR_SCAN_DATE_STRING, String.class);
        if (todayEst.equals(lastScanned)) {
            return;
        }

        ImageSearchResultData eventsBtn = templateSearchHelper.locatePattern(
                TemplatesEnum.HOME_EVENTS_BUTTON, SearchConfigConstants.SINGLE_WITH_RETRIES);
        if (!eventsBtn.isFound()) {
            logInfo("bg_telemetry | Events icon not found; will retry the state calendar scan next run.");
            return;
        }
        tapNear(eventsBtn.getPoint());
        sleepTask(PANEL_SETTLE_MS);

        ImageSearchResultData calendarTab = templateSearchHelper.locatePattern(
                TemplatesEnum.EVENTS_CALENDAR_TAB, SearchConfigConstants.SINGLE_WITH_RETRIES);
        if (!calendarTab.isFound()) {
            // Calendar is the leftmost tab; one swipe toward the start recovers it if a prior
            // session left the strip scrolled away.
            swipe(CALENDAR_TAB_STRIP_SWIPE_FROM, CALENDAR_TAB_STRIP_SWIPE_TO);
            sleepTask(600);
            calendarTab = templateSearchHelper.locatePattern(
                    TemplatesEnum.EVENTS_CALENDAR_TAB, SearchConfigConstants.QUICK_SEARCH);
        }
        if (!calendarTab.isFound()) {
            logWarning("bg_telemetry | Calendar tab not found even after scrolling; will retry next run.");
            pressBack();
            return;
        }
        tapNear(calendarTab.getPoint());
        sleepTask(PANEL_SETTLE_MS);

        String panelText = readPanelBlock(CALENDAR_PANEL_TOP_LEFT, CALENDAR_PANEL_BOTTOM_RIGHT);
        logDebug("bg_telemetry | Calendar panel raw text: " + panelText);
        recordStateEventsFrom(panelText);

        pressBack();
        sleepTask(600);
        pressBack();

        profile.setConfig(ConfigurationKeyEnum.BG_TELEMETRY_LAST_STATE_CALENDAR_SCAN_DATE_STRING, todayEst);
    }

    private void recordStateEventsFrom(String panelText) {
        if (panelText == null || panelText.isBlank()) {
            return;
        }
        Matcher dayMatcher = DAY_LABEL.matcher(panelText);
        int previousEnd = -1;
        String previousDay = null;
        while (dayMatcher.find()) {
            if (previousDay != null) {
                recordOneDay(previousDay, panelText.substring(previousEnd, dayMatcher.start()));
            }
            previousDay = dayMatcher.group(1) + " " + dayMatcher.group(2);
            previousEnd = dayMatcher.end();
        }
        if (previousDay != null) {
            recordOneDay(previousDay, panelText.substring(previousEnd));
        }
    }

    private void recordOneDay(String dayLabel, String followingText) {
        Matcher range = TIME_RANGE.matcher(followingText);
        if (!range.find()) {
            return;
        }
        LocalDate date = parseDayLabelDate(dayLabel);
        if (date == null) {
            return;
        }

        LocalDateTime start = date.atTime(parseHm(range.group(1)));
        LocalDateTime end = date.atTime(parseHm(range.group(2)));
        if (end.isBefore(start)) {
            end = end.plusDays(1);
        }

        EventScheduleService.obtain().recordWindow(
                "STATE_CALENDAR_" + date, "State Event (" + dayLabel + ")", false, start, end);
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
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            LocalDate candidate = LocalDate.of(today.getYear(), month, day);
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
     * rather than a guess when OCR gives nothing usable — a wrong number is
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
        Path file = telemetryDir().resolve("history.jsonl");
        try {
            if (!Files.exists(file)) {
                return null;
            }
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            Map<String, Object> result = new LinkedHashMap<>();
            java.util.regex.Pattern pattern =
                    java.util.regex.Pattern.compile("\"(\\w+)\":(-?\\d+)(?!\\.)");
            for (int i = lines.size() - 1; i >= 0; i--) {
                String line = lines.get(i);
                if (line == null || line.isBlank()) {
                    continue;
                }
                java.util.regex.Matcher m = pattern.matcher(line);
                while (m.find()) {
                    // putIfAbsent: walking backwards, the first value seen for a field is the
                    // most recent one. Nulls do not match the pattern at all, so a rejected
                    // reading is skipped rather than recorded as the last known-good.
                    result.putIfAbsent(m.group(1), Long.parseLong(m.group(2)));
                }
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
     * flat number fields. Reads them straight from {@link StatisticsService} — no JSON parsing —
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

    /** One lock per JVM, shared by every profile's writer -- cheap, and removes any doubt about
     *  two runs (a slow one plus its on-time successor) interleaving the same profile's files. */
    private static final Object WRITE_LOCK = new Object();

    /**
     * Appends to a JSON Lines history and atomically replaces the latest-sample file.
     * JSONL is used for the history so a run can never corrupt earlier samples by rewriting a
     * whole document, which matters for something appending unattended overnight. latest.json
     * itself is written to a temp file and moved into place (atomically where the filesystem
     * supports it) rather than truncate-written in place, so a reader can never observe a
     * half-written file.
     */
    private void writeSample(String json) {
        Path dir = telemetryDir();
        synchronized (WRITE_LOCK) {
            try {
                Files.createDirectories(dir);
                Files.write(dir.resolve("history.jsonl"),
                        (json + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
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

    /** Minimal serializer — the project ships no JSON binding usable from here. */
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
