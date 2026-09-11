package dev.frostguard.tasks.economy;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.OcrSettingsData;
import dev.frostguard.api.domain.OcrSettingsData.TextLayout;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.vision.ocr.OcrEngine;
import dev.frostguard.vision.ocr.PanelRowIndex;
import dev.frostguard.vision.ocr.TextLine;

/**
 * Reads current Meat/Wood/Coal/Iron warehouse totals and caches them to profile
 * config for {@link GatherRoutine}'s Smart Gathering and for {@code bg_telemetry}
 * (which reads meat/wood/iron from these config keys).
 *
 * <p><b>Source screen (the operator-directed + live-verified 2026-08-10):</b> tapping the
 * top-bar resource counter (the pouch the HUD rotates to, e.g. "11.4M") opens the
 * <b>Overview → Resource Production</b> panel, which lists all four gather
 * resources with their real "Owned" total on ONE screen (no scrolling). This
 * screen is always available and never maxes out — unlike the prior tech-node
 * "Research Cost" hack, which on a mature account found every candidate node MAXED
 * and so left stale/garbage stockpile values frozen (meat cached at 235M while the
 * real Owned total was 72.3M).
 *
 * <p>The four "Owned" crops below were verified by cropping a live 720x1280 capture
 * of this panel and confirming they cleanly frame 72.3M / 79.8M / 11.4M / 4.3M.
 *
 * <p><b>Steel + speedups</b> live on the richer Backpack "Resource &amp; Speedup
 * Summary" popup (needs its own calibration pass); their config keys stay dormant
 * until that reader is calibrated, and their Statistics tiles simply don't render
 * while unpopulated.
 *
 * <p>Gated on {@code GATHER_SMART_PRIORITY_BOOL} (the "Smart Gathering" checkbox);
 * force-run at startup via {@code ScheduleService} so the stats have fresh totals
 * the moment the bot launches, then hourly.
 */
public class ResourceStockpileRoutine extends DelayedTask {

    private static final Duration DEFAULT_INTERVAL = Duration.ofHours(1);

    // ── Navigation (720x1280, live-verified 2026-08-10) ──
    /** Top-bar resource pouch counter; tapping it opens Overview → Resource Production. */
    private static final PointData RESOURCE_COUNTER = new PointData(392, 22);
    /** "Resource Production" tab at the bottom of the Overview panel (usually already active). */
    private static final PointData RESOURCE_PRODUCTION_TAB = new PointData(525, 1216);
    /** "X" that closes the Overview panel (top-right of its header). */
    private static final PointData CLOSE_OVERVIEW_X = new PointData(690, 358);

    // "Owned" total crops — the LARGE top number in each resource row (the smaller

    /**
     * The Overview's Owned column: each row's owned figure and the shielded one under it, and
     * nothing else.
     *
     * <p>This used to be the whole panel, and it missed 71 of 83 cycles. Not by misreading
     * the panel -- by failing to read it at all. A saved failing frame is perfectly clean, every
     * figure sharp, and the whole-panel pass still loses three of the four rows: Tesseract's
     * layout analysis merges meat's owned value, its shielded value and the shield icon into one
     * 125x58 "word" reading "orem", and drops wood's and coal's values altogether. Only iron, the
     * bottom row, survives -- which is exactly the pattern in the log, where 55 of 76 short
     * reads came back with one to three values and iron was nearly always one of them.</p>
     *
     * <p>It is deterministic. Replay the same frame and it fails the same way, which is why a
     * retry into the same values could not rescue it, and why every frame captured by hand read
     * cleanly: the fault turns on the particular glyphs on the panel, not on timing or screen
     * state. With the icons and the Output column in the page, some combinations of figures
     * tip the segmentation over; given only the column of numbers, it reads them as the column
     * of numbers they are.</p>
     *
     * <p>It is the other side of the lesson that moved these reads off per-value crop boxes in
     * the first place. A box too tight starved the reader of context and dropped decimal
     * points; the whole panel gives it so much that it misjudges the layout. A column is wide
     * enough for every decimal and narrow enough that there is nothing else to mistake. Checked
     * against every Overview frame on disk -- both captured failures, three from the World map,
     * two from the City view and the 9/02 fixture -- it reads all eight exactly, where the whole
     * panel read six. The top edge sits below the column header and the right edge short of the
     * + buttons, so neither turns up as a stray word.</p>
     */
    private static final PointData OVERVIEW_OWNED_TL = new PointData(430, 470);
    private static final PointData OVERVIEW_OWNED_BR = new PointData(600, 905);

    /**
     * The whole Summary popup, read in one pass rather than as a set of per-value boxes -- see
     * {@link PanelRowIndex} for why that turned out to matter more than any amount of crop
     * calibration.
     */
    private static final PointData SUMMARY_PANEL_TL = new PointData(60, 380);
    private static final PointData SUMMARY_PANEL_BR = new PointData(700, 980);

    /** Left edge of the Overview's "Owned" column, clear of the Output column beside it. */
    private static final int OWNED_COLUMN_X = 440;
    /** Left edge of the Summary's "Total Resources" column, clear of "Total Items". */
    private static final int TOTAL_RESOURCES_COLUMN_X = 460;
    /**
     * Left edge of the Speedup tab's duration column.
     *
     * <p>Was 400, which is inside the values. These strings are right-aligned, so the longer the
     * duration the further left it begins: "12 hr(s)10 min" starts at x=468 but "11 day(s)5
     * hr(s)45 min" starts at 425, and a bucket holding more than about ten days starts left of
     * 400 -- where the leading number was cut off and the row read as the remainder. The labels
     * end by roughly 340, so 370 sits in the gap with room on both sides.</p>
     */
    private static final int SPEEDUP_VALUE_COLUMN_X = 370;

    /**
     * How many times a panel is opened before the cycle gives up on it.
     *
     * <p>One in five cycles was coming back with nothing -- 17 of 83 in the log -- and every one
     * of those keeps the figures already cached. That is the safe answer to an unreadable panel,
     * but a fifth of the scans going stale is what lets a cached value drift away from the game
     * and then correct itself in a single step, which the Statistics tab reports as a spend on
     * whatever window the step lands in. The reads themselves are accurate; it is the misses that
     * age the cache.</p>
     *
     * <p>The misses are not OCR failing to read a panel. They are the panel not being there:
     * a cycle that opens while the game is on an unexpected screen taps into whatever is in front
     * of it and finds no rows at all. Opening it a second time costs a few seconds and turns most
     * of those into a reading.</p>
     */
    private static final int PANEL_OPEN_ATTEMPTS = 2;

    /**
     * Added to every settle on the second attempt.
     *
     * <p>When a first attempt fails on a screen that was other than expected, the retry follows a
     * screen re-establish and a fresh navigation, so it is already better placed. When it failed
     * because the panel was still animating, more time is the whole fix. Waiting longer costs a
     * few seconds on the one cycle in five that needed it.</p>
     */
    private static final long RETRY_EXTRA_SETTLE_MS = 1200;

    /** The Overview lists meat, wood, coal then iron, top to bottom, always. */
    private static final List<String> OVERVIEW_ROW_ORDER = List.of("meat", "wood", "coal", "iron");

    /** The Summary's Resources tab, in its fixed order. Only Steel is read from it. */
    private static final List<String> SUMMARY_ROW_ORDER =
            List.of("Gems", "Meat", "Wood", "Coal", "Iron", "Steel");

    /** The Speedup tab, in its fixed order. */
    private static final List<String> SPEEDUP_ROW_ORDER =
            List.of("General", "Training", "Construction", "Research", "Healing");

    /**
     * Reading a whole panel at once, so no colour isolation and no glyph filter.
     *
     * <p>Both were doing damage. Isolating on the digit colour washed the anti-aliased decimal
     * point out to background before Tesseract saw it -- the pixels of a one-pixel dot never sit
     * close enough to the pure text colour to survive the tolerance -- which is how "87.4M" became
     * "874M". And a whitelist cannot help a pass that has to read the labels too. Given the whole
     * panel and its natural contrast, the reader returns every decimal correctly.</p>
     */
    private static final OcrSettingsData PANEL_TEXT_SETTINGS =
            OcrSettingsData.assembler()
                    .textLayout(TextLayout.TEXT_BLOCK)
                    .stripBackground(false)
                    .build();

    // ── Steel + Speedups: Backpack → chart button → "Resource & Speedup Summary" (verified live) ──
    private static final PointData BACKPACK_NAV = new PointData(305, 1255);       // bottom-nav Backpack
    private static final PointData SUMMARY_CHART_BUTTON = new PointData(682, 40); // chart button (NOT 712 — dead corner)
    private static final PointData SPEEDUP_TAB = new PointData(547, 355);         // "Speedup" tab in the popup
    private static final PointData SUMMARY_CLOSE_X = new PointData(697, 258);     // popup close X
    // "fix all": Steel sat dormant (config key never written) because no reader was
    // ever calibrated for it. The popup's default landing tab is "Resources" (Meat/Wood/Coal/Iron/
    // Steel/Chief Stamina, one screen, no scroll) -- same popup the Speedup tab already reads, just
    // read BEFORE switching tabs. "Total Resources" column crop, verified against a live capture
    // showing "811.71K".
    //
    // First two live passes both misread this as "117MK" regardless of settle
    // delay. Root-caused by replaying TesseractOcrProvider's exact preprocessing in isolation
    // (color-distance-from-target -> grayscale, see cropAndPreprocess): the decimal point's pixels
    // are anti-aliased against the background, so their colour sits far enough from the pure text
    // colour (81,104,143) that CHANNEL_TOLERANCE washes the dot out to pure white before Tesseract
    // ever sees it -- "811.71K" becomes "811 71K" with no dot at all, and different OCR passes
    // segment/guess the two fragments differently. The raw (non-isolated) crop keeps the dot fine on
    // its own (dark digits on a light, fairly uniform blue popup background give plenty of natural
    // contrast) -- stripBackground(false) here, unlike the Speedup durations below which are larger
    // text with more spacing and read fine isolated. Widened a few px on every edge too so descenders
    // aren't clipped at the crop boundary.

    private Duration interval = DEFAULT_INTERVAL;

    public ResourceStockpileRoutine(AccountDescriptor profile, TpDailyTaskEnum tpTask) {
        super(profile, tpTask);
        reschedule(LocalDateTime.now());
    }

    // Intentionally does NOT override getDistinctKey(). A non-null distinct key made
    // execution write task-state under key "902:resource_stockpile_scan" while the Control→Tasks UI
    // reads plain "902" (written once at startup by the force-run), so the tile froze at "Ready, last
    // run 1h ago" forever even though the task ran. TIMER_SWEEP is immune precisely because it doesn't
    // override this. Nothing dequeues this task by that string, so dropping the override is safe.

    @Override
    protected LaunchPoint getRequiredStartLocation() {
        // WORLD, not HOME. The engine forces this screen before execute()
        // (DelayedTask.run → ensureCorrectScreenLocation). The top-bar resource counter + Overview
        // panel live on the World map; declaring HOME parked the game on Base City, so the (480,22)
        // tap missed the panel and the crops OCR'd city pixels (meat=1, coal=12). Every other
        // top-bar/HUD reader (bg_telemetry, GatherRoutine) pins to WORLD.
        return LaunchPoint.WORLD;
    }

    @Override
    protected void execute() {
        Boolean smartGatheringOn = profile.getConfig(ConfigurationKeyEnum.GATHER_SMART_PRIORITY_BOOL, Boolean.class);
        if (!Boolean.TRUE.equals(smartGatheringOn)) {
            reschedule(LocalDateTime.now().plus(interval));
            return;
        }

        logInfo("ResourceStockpileRoutine | Scanning Meat/Wood/Coal/Iron from the Overview panel.");

        Map<String, Long> read = readOverviewPanel();

        if (read != null) {
            // "check the last couple days" turned up meat/wood/iron swinging 10x
            // between consecutive hourly reads (e.g. 84.9M then 849M then back) while power/coal
            // trended smoothly the same hours -- an OCR misread of the "." in the abbreviated form
            // (Tesseract dropping the decimal point turns "84.9M" into "849M"), not a real stockpile
            // swing. That misread was going straight into config, which GatherRoutine's Smart
            // Gathering also reads -- so this wasn't just corrupting the stats graph, it was
            // corrupting live gather-priority decisions. Same "reject an implausible jump against the
            // last known-good value" guard used in bg_telemetry for power/gems, applied here against
            // the config value this routine itself last wrote (since this IS the writer).
            Long meat = sanityCheckAgainstCached("meat", read.get("meat"),
                    ConfigurationKeyEnum.RESOURCE_STOCKPILE_MEAT_LONG);
            Long wood = sanityCheckAgainstCached("wood", read.get("wood"),
                    ConfigurationKeyEnum.RESOURCE_STOCKPILE_WOOD_LONG);
            Long coal = sanityCheckAgainstCached("coal", read.get("coal"),
                    ConfigurationKeyEnum.RESOURCE_STOCKPILE_COAL_LONG);
            Long iron = sanityCheckAgainstCached("iron", read.get("iron"),
                    ConfigurationKeyEnum.RESOURCE_STOCKPILE_IRON_LONG);

            if (meat != null) profile.setConfig(ConfigurationKeyEnum.RESOURCE_STOCKPILE_MEAT_LONG, meat);
            if (wood != null) profile.setConfig(ConfigurationKeyEnum.RESOURCE_STOCKPILE_WOOD_LONG, wood);
            if (coal != null) profile.setConfig(ConfigurationKeyEnum.RESOURCE_STOCKPILE_COAL_LONG, coal);
            if (iron != null) profile.setConfig(ConfigurationKeyEnum.RESOURCE_STOCKPILE_IRON_LONG, iron);
            profile.setConfig(ConfigurationKeyEnum.RESOURCE_STOCKPILE_LAST_READ_STRING,
                    LocalDateTime.now().toString());
            setShouldUpdateConfig(true);
            logInfo("ResourceStockpileRoutine | Stockpiles cached: meat=" + meat + " wood=" + wood
                    + " coal=" + coal + " iron=" + iron + " (raw OCR: " + read + ")");
        } else {
            logWarning("ResourceStockpileRoutine | Overview panel unreadable this cycle. Leaving the "
                    + "last cached stockpile values in place (GatherRoutine falls back to blind rotation).");
        }

        // Speedups live on a different screen (Backpack → Summary → Speedup tab). Read them in the
        // same cycle so the stats track speedup gain/loss alongside resources.
        readAndCacheSpeedups();

        navigationHelper.ensureCorrectScreenLocation(LaunchPoint.WORLD);
        setRecurring(true);
        reschedule(LocalDateTime.now().plus(interval));
    }

    /** Opens Backpack → Resource &amp; Speedup Summary, OCRs Steel on the default "Resources" tab,
     *  then switches to the Speedup tab and OCRs the five speedup totals to minutes. */
    /**
     * What one open of the Resource &amp; Speedup Summary yielded, before any plausibility check.
     *
     * <p>Raw on purpose. Whether a reading is believable is a separate question from whether it was
     * read at all, and only the second one is worth reopening the panel over -- a figure the guard
     * holds back was read perfectly well.</p>
     */
    private record SummaryRead(Long steel, Long general, Long training,
                               Long construction, Long research, Long healing) {

        int resolved() {
            int n = 0;
            for (Long v : new Long[]{steel, general, training, construction, research, healing}) {
                if (v != null) n++;
            }
            return n;
        }

        boolean complete() {
            return resolved() == 6;
        }
    }

    /** Opens Backpack → Resource &amp; Speedup Summary, reads Steel and the five speedup totals,
     *  and caches whatever survives its plausibility check. */
    private void readAndCacheSpeedups() {
        try {
            // Keep the better of the attempts rather than the last one. A retry can come back
            // worse than what it is retrying -- the panel it reopens might be the one that is
            // covered this time -- and caching that would throw away figures the first attempt
            // had already read correctly.
            SummaryRead best = null;
            for (int attempt = 1; attempt <= PANEL_OPEN_ATTEMPTS; attempt++) {
                SummaryRead read = openAndReadSummary(attempt);
                if (best == null || read.resolved() > best.resolved()) {
                    best = read;
                }
                if (best.complete()) {
                    break;
                }
                if (attempt < PANEL_OPEN_ATTEMPTS) {
                    logInfo("ResourceStockpileRoutine | Summary read " + read.resolved()
                            + " of 6 values on attempt " + attempt
                            + "; re-establishing the screen and opening it again.");
                    navigationHelper.ensureCorrectScreenLocation(LaunchPoint.WORLD);
                }
            }
            cacheSummary(best);
        } catch (Exception e) {
            logWarning("ResourceStockpileRoutine | Speedup read failed this cycle: " + e.getMessage());
        }
    }

    /** One open of the Summary popup: Steel from the default tab, then the Speedup tab. */
    private SummaryRead openAndReadSummary(int attempt) {
        tapNear(BACKPACK_NAV);
        sleepTask(settleFor(1600, attempt));
        tapNear(SUMMARY_CHART_BUTTON);
        // First live pass at 1400ms caught the popup slide-in mid-animation and
        // OCR'd a garbled "117MK" for Steel (correctly rejected as unparseable, but still a wasted
        // cycle) -- the manual calibration pass that measured the crop used ~2s and read cleanly.
        sleepTask(settleFor(2200, attempt));

        // Lands on "Resources" by default -- read Steel here before switching tabs.
        //
        // By its label, not by a box. The box that used to do this was measured one row too
        // high and had been reading the IRON row for the life of this routine: every steel
        // figure in the telemetry history is iron's, inflated a hundredfold by the decimal
        // point the narrow crop also dropped ("4.39M" read as "439M"). Nothing about a fixed
        // rectangle can notice it is on the wrong row. A label can.
        PanelRowIndex resources = readPanelRows(SUMMARY_PANEL_TL, SUMMARY_PANEL_BR);
        Long steel = readTableValue(resources, "Steel", TOTAL_RESOURCES_COLUMN_X, SUMMARY_ROW_ORDER,
                ResourceStockpileRoutine::parseScaled);

        tapNear(SPEEDUP_TAB);
        // Was 1000ms. Every other panel in this routine settles for 2200-2600 because the
        // popup animates, and a tab switch is no different -- a frame caught mid-transition
        // reads whatever is halfway drawn.
        sleepTask(settleFor(2500, attempt));

        // Each bucket by the name printed beside it. The row heights vary -- two labels wrap
        // onto a second line -- so their values do not sit at a fixed offset from anything.
        PanelRowIndex speedups = readPanelRows(SUMMARY_PANEL_TL, SUMMARY_PANEL_BR);
        SummaryRead read = new SummaryRead(steel,
                readSpeedup(speedups, "General"),
                readSpeedup(speedups, "Training"),
                readSpeedup(speedups, "Construction"),
                readSpeedup(speedups, "Research"),
                readSpeedup(speedups, "Healing"));

        tapNear(SUMMARY_CLOSE_X);
        sleepTask(300);
        return read;
    }

    /** Puts a summary read through the plausibility guards and caches what survives. */
    private void cacheSummary(SummaryRead read) {
        Long steel = sanityCheckAgainstCached("steel", read.steel(),
                ConfigurationKeyEnum.RESOURCE_STOCKPILE_STEEL_LONG);
        if (steel != null) {
            profile.setConfig(ConfigurationKeyEnum.RESOURCE_STOCKPILE_STEEL_LONG, steel);
        }
        logInfo("ResourceStockpileRoutine | Steel cached: " + steel);

        // The five speedup buckets were the one OCR'd family with no plausibility guard
        // anywhere -- not here, and not in bg_telemetry either, which explicitly skips them on
        // the grounds that it reads them from config rather than OCR'ing them itself. Nobody
        // was checking them at all, and it showed: on 9/1 the general bucket read 30 minutes
        // between readings of 2434 and 2664, which the Statistics tab then reported as
        // "+1d 21h 21m gained" overnight because that misread happened to be the last reading
        // before the window opened. Same guard as the stockpiles now, on its own policy --
        // see SPEEDUP_GUARD for why the thresholds differ.
        Long gen  = sanityCheckAgainstCached("sp_general", read.general(),
                ConfigurationKeyEnum.SPEEDUP_GENERAL_MIN_LONG, SPEEDUP_GUARD);
        Long tr   = sanityCheckAgainstCached("sp_training", read.training(),
                ConfigurationKeyEnum.SPEEDUP_TRAINING_MIN_LONG, SPEEDUP_GUARD);
        Long con  = sanityCheckAgainstCached("sp_construction", read.construction(),
                ConfigurationKeyEnum.SPEEDUP_CONSTRUCTION_MIN_LONG, SPEEDUP_GUARD);
        Long res  = sanityCheckAgainstCached("sp_research", read.research(),
                ConfigurationKeyEnum.SPEEDUP_RESEARCH_MIN_LONG, SPEEDUP_GUARD);
        Long heal = sanityCheckAgainstCached("sp_healing", read.healing(),
                ConfigurationKeyEnum.SPEEDUP_HEALING_MIN_LONG, SPEEDUP_GUARD);

        if (gen != null)  profile.setConfig(ConfigurationKeyEnum.SPEEDUP_GENERAL_MIN_LONG, gen);
        if (tr != null)   profile.setConfig(ConfigurationKeyEnum.SPEEDUP_TRAINING_MIN_LONG, tr);
        if (con != null)  profile.setConfig(ConfigurationKeyEnum.SPEEDUP_CONSTRUCTION_MIN_LONG, con);
        if (res != null)  profile.setConfig(ConfigurationKeyEnum.SPEEDUP_RESEARCH_MIN_LONG, res);
        if (heal != null) profile.setConfig(ConfigurationKeyEnum.SPEEDUP_HEALING_MIN_LONG, heal);
        setShouldUpdateConfig(true);
        logInfo("ResourceStockpileRoutine | Speedups cached (min): general=" + gen + " training=" + tr
                + " construction=" + con + " research=" + res + " healing=" + heal);
    }

    private Map<String, Long> readOverviewPanel() {
        for (int attempt = 1; attempt <= PANEL_OPEN_ATTEMPTS; attempt++) {
            Map<String, Long> owned = openAndReadOverview(attempt);
            if (owned != null) {
                return owned;
            }
            if (attempt < PANEL_OPEN_ATTEMPTS) {
                logInfo("ResourceStockpileRoutine | Overview did not read on attempt " + attempt
                        + "; re-establishing the screen and opening it again.");
                // Not a second tap on the same spot. Whatever the first attempt was looking at, this
                // gets back to a known screen first -- it presses Back off an unexpected one and
                // dismisses the quit dialog that a bare Back pops -- so the retry starts from the
                // same place the first attempt was supposed to.
                navigationHelper.ensureCorrectScreenLocation(LaunchPoint.WORLD);
            }
        }
        return null;
    }

    /** One open of the Overview: tap, settle, read, close. Null when it did not come back whole. */
    private Map<String, Long> openAndReadOverview(int attempt) {
        // Tapping the resource ICON opens Overview directly on the Resource Production tab (verified
        // live) — no tab tap needed. One clean tap + settle matches the proven manual flow.
        tapNear(RESOURCE_COUNTER);
        sleepTask(settleFor(2600, attempt)); // let the panel fully slide+render before OCR

        // Captured here rather than inside readPanelRows so that, if this read comes back short,
        // the frame saved for diagnosis is the very one that was read -- not a second screenshot
        // taken a moment later that might show something else.
        RawImageData frame = emuManager.captureScreen(EMULATOR_NUMBER);
        PanelRowIndex panel = readPanelRows(frame, OVERVIEW_OWNED_TL, OVERVIEW_OWNED_BR);

        tapNear(CLOSE_OVERVIEW_X);
        sleepTask(300);

        // The rows carry no text label, only an icon, so they cannot be matched by name the way
        // the Summary's are. What they do have is a fixed order and a fixed count. Each row's
        // owned figure is the upper of the two numbers in the Owned column -- the shielded amount
        // sits beneath it -- and the panel is only accepted when all four resolve. A partial read
        // would shift the assignment and file wood's stockpile under coal, which is worse than
        // skipping an hourly cycle and keeping the values already cached.
        List<Long> values = new java.util.ArrayList<>();
        for (PanelRowIndex.Row row : panel.rows()) {
            Optional<TextLine> owned = row.topmostFrom(OWNED_COLUMN_X, t -> parseScaled(t) != null);
            if (owned.isEmpty()) continue;
            values.add(parseScaled(owned.get().text().trim()));
        }

        if (values.size() != OVERVIEW_ROW_ORDER.size()) {
            // At WARN, with the rows it saw and the frame it saw them in. Of the last 76 short reads,
            // 21 resolved nothing and 55 resolved one to three values -- usually just iron, the
            // bottom row -- on a panel that verifies 4/4 every time it is captured by hand. Whatever
            // separates those frames from the good ones is only visible in the frames themselves,
            // and until now none were kept.
            logWarning("ResourceStockpileRoutine | Owned read incomplete on attempt " + attempt
                    + ": resolved " + values.size() + " of " + OVERVIEW_ROW_ORDER.size() + " " + values
                    + "; rows seen " + describeRows(panel) + ". "
                    + saveFailedFrame(frame, "overview-attempt" + attempt + "-resolved" + values.size()));
            return null;
        }

        Map<String, Long> out = new LinkedHashMap<>();
        for (int i = 0; i < OVERVIEW_ROW_ORDER.size(); i++) {
            out.put(OVERVIEW_ROW_ORDER.get(i), values.get(i));
        }
        return out;
    }

    /** A settle time, lengthened on a retry. See {@link #RETRY_EXTRA_SETTLE_MS}. */
    private static long settleFor(long baseMs, int attempt) {
        return baseMs + (attempt - 1) * RETRY_EXTRA_SETTLE_MS;
    }

    /**
     * Reads one panel region as words and groups them into rows. Never throws -- an unreadable
     * frame comes back as an empty index, which every caller already treats as "skip this cycle".
     */
    private PanelRowIndex readPanelRows(PointData topLeft, PointData bottomRight) {
        return readPanelRows(emuManager.captureScreen(EMULATOR_NUMBER), topLeft, bottomRight);
    }

    /** The same read, over a frame the caller already holds. */
    private PanelRowIndex readPanelRows(RawImageData frame, PointData topLeft, PointData bottomRight) {
        try {
            if (frame == null || !frame.isValid()) {
                logWarning("ResourceStockpileRoutine | No usable frame to read the panel from.");
                return PanelRowIndex.of(List.of());
            }
            return PanelRowIndex.of(
                    OcrEngine.recognizeWords(frame, topLeft, bottomRight, PANEL_TEXT_SETTINGS));
        } catch (Exception e) {
            logWarning("ResourceStockpileRoutine | Panel read failed: " + e.getMessage());
            return PanelRowIndex.of(List.of());
        }
    }

    /** Each row's text, for a log line -- enough to see what the reader thought was there. */
    private static String describeRows(PanelRowIndex panel) {
        StringBuilder sb = new StringBuilder("[");
        for (PanelRowIndex.Row row : panel.rows()) {
            if (sb.length() > 1) sb.append(" | ");
            sb.append(row.text());
        }
        String text = sb.append(']').toString();
        return text.length() > 400 ? text.substring(0, 400) + "...]" : text;
    }

    /**
     * Saves a frame that failed to read, beside the installation under ocr-debug/.
     *
     * <p>Same convention as MonumentRoutine's diagnostic frames, and the same rule: a diagnostic
     * must never be able to break the routine it is diagnosing, so every failure here is
     * swallowed and reported inline in the caller's log line instead.</p>
     */
    private String saveFailedFrame(RawImageData frame, String tag) {
        try {
            if (frame == null) {
                return "(diagnostic frame unavailable: no frame was captured)";
            }
            java.io.File dir = new java.io.File(System.getProperty("user.dir"), "ocr-debug");
            if (!dir.isDirectory() && !dir.mkdirs()) {
                return "(diagnostic frame not saved: could not create " + dir + ")";
            }
            String stamp = LocalDateTime.now().toString().replace(':', '-').replace('.', '-');
            java.io.File out = new java.io.File(dir, "stockpile-" + tag + "-" + stamp + ".png");
            javax.imageio.ImageIO.write(
                    dev.frostguard.vision.convert.ImageConverter.toBufferedImage(frame), "png", out);
            return "Diagnostic frame saved: " + out.getAbsolutePath();
        } catch (Exception e) {
            return "(diagnostic frame not saved: " + e.getMessage() + ")";
        }
    }

    /**
     * One value out of a fixed-order table, by the name of its row.
     *
     * <p>The whole table has to line up before any single value is believed -- see
     * {@link PanelRowIndex#orderedValues}. Returns null when it does not, which every caller here
     * already treats as an unreadable cycle.</p>
     */
    private <T> T readTableValue(PanelRowIndex panel, String label, int columnX,
                                 List<String> rowOrder,
                                 java.util.function.Function<String, T> parse) {
        Optional<Map<String, String>> table = panel.orderedValues(columnX, rowOrder);
        if (table.isEmpty()) {
            logDebug("ResourceStockpileRoutine | Panel rows did not match the expected "
                    + rowOrder.size() + "-row layout " + rowOrder + "; skipping '" + label + "'.");
            return null;
        }
        String text = table.get().get(label);
        T parsed = (text == null || text.isBlank()) ? null : parse.apply(text.trim());
        if (parsed == null) {
            // At WARN, not DEBUG: a row that will not parse is the shape a clipped read takes, and
            // the text it choked on is the only evidence of which part went missing.
            logWarning("ResourceStockpileRoutine | '" + label + "' did not read as a whole value: '"
                    + text + "' -- keeping the cached figure for this cycle.");
        }
        return parsed;
    }

    /** One speedup bucket's total, in minutes, or null. */
    private Long readSpeedup(PanelRowIndex panel, String label) {
        return readTableValue(panel, label, SPEEDUP_VALUE_COLUMN_X, SPEEDUP_ROW_ORDER,
                ResourceStockpileRoutine::parseDurationMinutes);
    }

    /**
     * The whole duration, or nothing.
     *
     * <p>Each component used to be picked out of the string wherever it appeared, and any single
     * hit was enough to return a number. That turns a partly-read row into a plausible small
     * figure instead of a refusal, and it is how the speedup history got its cliffs. Drop the
     * leading number off "1 day(s)5 hr(s)2 min" -- which the column edge did, because these
     * strings are right-aligned and the long ones reach further left -- and "day(s)5 hr(s)2 min"
     * came back as 302 minutes rather than 1,742. On the tab that read as fifteen hours of
     * construction speedup spent overnight, on a night nothing was spent at all. The same clipping
     * gave the training bucket its run of 8s and 16s: "hr(s)16 min" parsed as sixteen minutes.</p>
     *
     * <p>So the string now has to be a duration end to end. A unit with no number in front of it,
     * or anything left over, means the row was not read properly and there is no answer to give --
     * the caller keeps the value it already had, which is a repeat in the history rather than a
     * phantom spend.</p>
     */
    private static final Pattern WHOLE_DURATION = Pattern.compile(
            "^(?:(\\d+)day\\(?s?\\)?)?(?:(\\d+)hr\\(?s?\\)?)?(?:(\\d+)min)?$",
            Pattern.CASE_INSENSITIVE);

    /** "1 day(s)10 hr(s)50 min" to 2090. Null unless the whole string is a duration. */
    static Long parseDurationMinutes(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        // The reader spaces these unpredictably ("day(s)5 hr(s)45 min"), so compare without spaces.
        Matcher m = WHOLE_DURATION.matcher(raw.replaceAll("\\s+", ""));
        if (!m.matches()) {
            return null;
        }
        long total = 0;
        boolean any = false;
        if (m.group(1) != null) { total += Long.parseLong(m.group(1)) * 1440L; any = true; }
        if (m.group(2) != null) { total += Long.parseLong(m.group(2)) * 60L;   any = true; }
        if (m.group(3) != null) { total += Long.parseLong(m.group(3));         any = true; }
        return any ? total : null;
    }

    /** Fraction outside of which a new resource reading is rejected as an implausible OCR misread
     *  rather than a real change. Same value/rationale as bg_telemetry's power/gems guard -- real
     *  stockpile change between hourly reads is gradual; the observed misreads (a dropped decimal
     *  point in the abbreviated form) jumped ~10x, far outside this band. */
    private static final double SANITY_BAND_MAX_RATIO = 1.5;
    private static final double SANITY_BAND_MIN_RATIO = 1.0 / SANITY_BAND_MAX_RATIO;

    /** "this is wrong" -- root-caused live against real telemetry history:
     *  meat froze at exactly 87,700,000 for 4 straight hourly cycles (6+ hours) while the real
     *  in-game HUD showed 5.7M. Unlike bg_telemetry's power/gems check (which compares against
     *  the PREVIOUS SAMPLE and self-heals after one skipped write, since a rejection writes null
     *  and breaks the chain), this routine only ever writes the CACHED CONFIG VALUE on accept --
     *  so once one reading locks in, every future correct reading that's far from it (whether a
     *  fixed misread finally correcting itself, or a real large legitimate change: a big Furnace
     *  upgrade spend, a big claim) gets rejected FOREVER, with no way to ever recover. A single
     *  old cached number was being trusted over an unlimited run of newer, mutually-consistent
     *  readings.
     *
     *  Fix: track how many CONSECUTIVE rejected readings agree with EACH OTHER (not with the
     *  stale cache). Several independent reads clustering together is stronger evidence than one
     *  old cached number, whatever caused the original divergence -- once REJECT_STREAK_TO_TRUST
     *  is reached, trust the new streak and let the cache catch up. In-memory (resets if
     *  Bearguard restarts) -- that only costs one extra recovery cycle, not a lasting bug. */
    private static final int REJECT_STREAK_TO_TRUST = 3;

    /** Observed live: steel cached at 839,000,000 against a last-known-good
     *  of 1,174,000 (ratio ~715x). The streak-to-trust escape hatch above has no upper bound: a
     *  gentle 1.5x drift and a physically-impossible 715x spike need the same 3 consistent reads to
     *  get auto-trusted, because {@link #inBand} only checks readings against EACH OTHER, never
     *  against how far they are from the cache in absolute terms. Three consecutive misreads of the
     *  same wrong screen region (an event overlay, a UI shift) agree with each other just as
     *  reliably as three genuine readings do. A hard ceiling here means an outlier past this ratio
     *  NEVER auto-trusts no matter how consistent the streak -- it stays rejected (falls back to
     *  the last known-good) until a human looks at it, instead of quietly overwriting the stockpile
     *  cache -- and by extension GatherRoutine's Smart Gathering priority and the Statistics tab --
     *  with a number that can't be real. */
    private static final double MAX_TRUSTABLE_STREAK_RATIO = 10.0;
    private final Map<String, Long> rejectStreakAnchor = new java.util.HashMap<>();
    private final Map<String, Integer> rejectStreakCount = new java.util.HashMap<>();

    /**
     * What "implausible" means for one family of fields. Stockpiles and speedups are both OCR'd
     * off the same panel and misread the same way, but they move differently enough that one set
     * of thresholds cannot serve both.
     *
     * @param decimalRepair     try the /10 dropped-decimal-point correction before rejecting.
     *                          Only the abbreviated stockpile forms ("87.4M") have a decimal point
     *                          to drop; a duration reads as "1d 22h 31m" and cannot fail this way.
     * @param absoluteTolerance a change no larger than this is always plausible, whatever the
     *                          ratio says. A ratio test alone is nonsense on a small pile -- five
     *                          minutes of speedup becoming forty-nine is a 9.8x "spike" and an
     *                          entirely ordinary morning's rewards.
     * @param streakToTrust     consecutive mutually-consistent rejected readings that beat a stale
     *                          cache.
     * @param maxTrustableRatio hard ceiling past which no streak is ever trusted.
     */
    record GuardPolicy(boolean decimalRepair, long absoluteTolerance,
                               int streakToTrust, double maxTrustableRatio) {}

    static final GuardPolicy STOCKPILE_GUARD =
            new GuardPolicy(true, 0L, REJECT_STREAK_TO_TRUST, MAX_TRUSTABLE_STREAK_RATIO);

    /**
     * Speedups need their own thresholds, because the stockpile ones would make the guard worse
     * than no guard at all here.
     *
     * <p>The 10x auto-trust ceiling is the dangerous one: emptying a speedup bucket into a single
     * Furnace upgrade is a completely normal thing to do and takes the total from 2268 minutes to
     * 155 -- a 14.6x drop, past the ceiling, so it would be rejected forever and the page would
     * report a stockpile the operator spent days ago. A hoard genuinely can go to almost nothing
     * in one action, so ratio alone cannot tell a real spend from a misread here. What separates
     * them is repetition: a real spend is confirmed by the very next reading, a misread never is.
     * So the ceiling comes off and the streak does the work, shortened to two because a bucket
     * that legitimately moves fast should not sit stale for three cycles waiting to be believed.
     *
     * <p>Four hours of absolute tolerance keeps small piles from tripping the ratio test at all.</p>
     */
    static final GuardPolicy SPEEDUP_GUARD =
            new GuardPolicy(false, 240L, 2, Double.MAX_VALUE);

    /**
     * Rejects a candidate reading that jumps implausibly far from the value this routine last
     * cached for the same field, returning null (config keeps its last known-good value, and
     * GatherRoutine/bg_telemetry both fall back the same way they do on any other unreadable
     * cycle) instead of overwriting a good cached value with a likely-wrong one -- UNLESS the
     * candidate is the latest in a streak of mutually-consistent rejected readings (see field
     * doc above), in which case the streak wins over the stale cache.
     *
     * <p>: live testing found this ISN'T random noise for Meat/Wood -- a live capture
     * showed a crisp, unambiguous "87.4M" in the crop, but OCR reliably read it as "874M" both times
     * (Coal "9.8M" and Iron "1.8M", one digit before the point, read correctly both times; Meat/Wood
     * always have two). Tesseract is dropping the decimal point itself when there are two leading
     * digits -- a specific, well-understood failure mode, not an arbitrary misread -- so a pure
     * reject would leave these two fields permanently stale (every future real reading would also
     * come out 10x high and get rejected forever). Before rejecting, check whether dividing by 10
     * repairs it back into the plausible band; if so that confirms the dropped-decimal pattern and
     * the corrected value is used. This is a narrow, specific correction for one identified failure
     * mode, not an open-ended guess -- anything that doesn't fit even after /10 still gets rejected
     * (and falls through to the streak-based recovery instead).
     */
    private Long sanityCheckAgainstCached(String field, Long candidate, ConfigurationKeyEnum cacheKey) {
        return sanityCheckAgainstCached(field, candidate, cacheKey, STOCKPILE_GUARD);
    }

    private Long sanityCheckAgainstCached(String field, Long candidate, ConfigurationKeyEnum cacheKey,
                                          GuardPolicy policy) {
        if (candidate == null) {
            return null;
        }
        Long cached;
        try {
            cached = profile.getConfig(cacheKey, Long.class);
        } catch (Exception e) {
            cached = null;
        }
        if (cached == null || cached <= 0L) {
            rejectStreakCount.remove(field);
            rejectStreakAnchor.remove(field);
            return candidate;
        }
        if (inBand(candidate, cached, policy.absoluteTolerance())) {
            rejectStreakCount.remove(field);
            rejectStreakAnchor.remove(field);
            return candidate;
        }
        long corrected = candidate / 10L;
        if (policy.decimalRepair() && inBand(corrected, cached, policy.absoluteTolerance())) {
            logWarning("ResourceStockpileRoutine | " + field + " reading " + candidate + " is implausibly "
                    + "far from the last cached " + cached + ", but /10 (" + corrected + ") fits -- this is "
                    + "the known dropped-decimal-point misread, using the corrected value.");
            rejectStreakCount.remove(field);
            rejectStreakAnchor.remove(field);
            return corrected;
        }

        Long anchor = rejectStreakAnchor.get(field);
        int streak = (anchor != null && inBand(candidate, anchor, policy.absoluteTolerance()))
                ? rejectStreakCount.getOrDefault(field, 0) + 1 : 1;
        rejectStreakAnchor.put(field, candidate);
        rejectStreakCount.put(field, streak);

        double cacheRatio = (double) candidate / cached;
        boolean withinTrustableCeiling = cacheRatio <= policy.maxTrustableRatio()
                && cacheRatio >= 1.0 / policy.maxTrustableRatio();

        if (streak >= policy.streakToTrust() && withinTrustableCeiling) {
            logWarning("ResourceStockpileRoutine | " + field + " has now read consistently near "
                    + candidate + " for " + streak + " consecutive cycles while cached stays at "
                    + cached + " -- trusting the consistent new readings over the stale cache.");
            rejectStreakCount.remove(field);
            rejectStreakAnchor.remove(field);
            return candidate;
        }
        if (streak >= policy.streakToTrust()) {
            logWarning("ResourceStockpileRoutine | " + field + " has read consistently near " + candidate
                    + " for " + streak + " consecutive cycles, but that is " + String.format("%.1f", cacheRatio)
                    + "x the last cached " + cached + " -- past the " + policy.maxTrustableRatio()
                    + "x ceiling for auto-trust, so this is treated as a persistent misread (e.g. a covering "
                    + "popup or wrong screen region) rather than a real change. Keeping the last known-good "
                    + "value; this needs a human look, not another cycle.");
            return null;
        }

        logWarning("ResourceStockpileRoutine | " + field + " reading " + candidate + " is implausibly "
                + "far from the last cached " + cached + " (ratio " + String.format("%.2f", cacheRatio)
                + ", /10 correction didn't fit either) -- rejecting for now, keeping the last known-good "
                + "value (streak toward trusting a consistent new reading: " + streak + "/"
                + policy.streakToTrust() + ").");
        return null;
    }

    /** Within the ratio band, or a small enough absolute move that the ratio is not meaningful.
     *  The absolute escape matters on small values: 5 minutes of speedup becoming 49 is a 9.8x
     *  ratio and 44 minutes of reality. */
    static boolean inBand(long candidate, long cached, long absoluteTolerance) {
        if (absoluteTolerance > 0 && Math.abs(candidate - cached) <= absoluteTolerance) {
            return true;
        }
        double ratio = (double) candidate / (double) cached;
        return ratio <= SANITY_BAND_MAX_RATIO && ratio >= SANITY_BAND_MIN_RATIO;
    }

    /** Parses "72.3M", "583,853", "1.2M" etc to a plain long. */
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
            s = s.replace(".", "");
        }
        if (s.isEmpty()) {
            return null;
        }
        try {
            return (long) (Double.parseDouble(s) * multiplier);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
