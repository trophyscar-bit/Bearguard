package dev.frostguard.tasks.city;

import java.awt.Color;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.regex.Pattern;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.OcrSettingsData;
import dev.frostguard.api.domain.OcrSettingsData.TextLayout;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.nav.SidebarDestination;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.engine.service.StatisticsService;
import dev.frostguard.vision.convert.GameTimeUtils;
import dev.frostguard.vision.convert.RegexNumberParser;

/**
 * Task responsible for auto-healing injured troops.
 *
 * <p>
 * On the World map, an icon over "My City" shows a healing-progress
 * percentage whenever troops are injured. Tapping it opens the "Heal Injured" panel,
 * which lists severely-injured troops by type with per-type heal queues (up to a few
 * running in parallel, mirroring how Training splits by camp). "Quick Select" + "Heal"
 * starts the highest-priority queue; once started, the same button becomes "Help" --
 * tapping it requests alliance assistance, which measured live cut a real 14:21 timer
 * down to 1:45 (roughly 90% reduction). The World-map badge percentage itself ("1%",
 * sometimes "2%" or higher by design) is NOT used for the actual decision here -- the
 * panel's own "Severely Injured X/Y" count is read instead, since that's the number
 * that actually determines whether there's anything to heal.
 *
 * <p>
 * <b>Known gap :</b> a real backpack-item Speedup ("if needed") is not
 * wired up yet -- Help alone handled the observed case (90% reduction), so this was left
 * as a manual the operator action for now rather than adding a whole Backpack-inventory read for
 * a case that may not come up often. Flagged as a clean follow-up, not forgotten.
 *
 * <p>
 * <b>Live-tested 2026-08-12</b> up through Quick Select -> Heal -> Help, all three
 * confirmed live in that exact session. The OCR reads (Severely Injured count, Healing
 * countdown) and the idle/no-injuries path were written from the same session's
 * screenshots but have NOT been separately live-verified -- flagged honestly, not
 * assumed correct.
 */
public class HealInjuredRoutine extends DelayedTask {

    // ========== Navigation ==========
    // The Infirmary has NO fixed screen position: city view keeps whatever pan/zoom it
    // was last left on, so nothing can be tapped by coordinate from a cold start. The
    // sidebar's Research Center row lands an identical frame every time, and in that
    // frame the Infirmary sits in the upper right with its injured-troops bubble at a
    // known point. That bubble opens the Heal Injured panel DIRECTLY -- it skips the
    // building's Details/Upgrade/Heal ring.
    //
    // This replaces an earlier approach that tapped a World-map healing icon over My
    // City. That icon is a progress badge, not a route into the panel.
    /** Injured-troops bubble over the Infirmary, in the Research Center anchor frame. */
    private static final PointData INJURED_BUBBLE_POINT = new PointData(561, 318);
    private static final int ANCHOR_SETTLE_MS = 1200;

    // ========== Heal Injured panel (full-screen, fixed layout) ==========
    private static final PointData PANEL_TITLE_TOP_LEFT = new PointData(80, 18);
    private static final PointData PANEL_TITLE_BOTTOM_RIGHT = new PointData(340, 64);

    private static final PointData SEVERELY_INJURED_TOP_LEFT = new PointData(360, 130);
    private static final PointData SEVERELY_INJURED_BOTTOM_RIGHT = new PointData(670, 210);

    /** Mandatory before healing: the panel reopens with whatever partial selection it
     *  had last time, NOT with every slider maxed, so healing without this silently
     *  leaves wounded troops in the beds. */
    private static final PointData QUICK_SELECT_BTN = new PointData(79, 1207);
    /** Same screen position serves as "Heal" (before starting) and "Help" (after
     *  starting) -- the panel swaps the button's label/action in place. Note the gem
     *  "Finish" button sits immediately left of it; this one spends resources only. */
    private static final PointData PRIMARY_ACTION_BTN = new PointData(571, 1207);
    private static final PointData PANEL_CLOSE_BTN = new PointData(44, 40);

    private static final PointData HEALING_TIMER_TOP_LEFT = new PointData(500, 1195);
    private static final PointData HEALING_TIMER_BOTTOM_RIGHT = new PointData(700, 1240);

    // ========== Constants ==========
    /** Fallback idle cadence when the configured value is missing or nonsensical. */
    private static final int DEFAULT_IDLE_RECHECK_MINUTES = 5;
    private static final int MIN_IDLE_RECHECK_MINUTES = 1;
    private static final int MAX_IDLE_RECHECK_MINUTES = 24 * 60;
    private static final int PANEL_SETTLE_MS = 1500;
    private static final int ACTION_SETTLE_MS = 1000;
    private static final int MAX_TIMER_HOURS_SANITY = 12;

    private static final OcrSettingsData WHITE_TEXT_SETTINGS = OcrSettingsData.assembler()
            .stripBackground(true)
            .setTextColor(new Color(255, 255, 255))
            .charWhitelist("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ ")
            .textLayout(OcrSettingsData.TextLayout.SINGLE_LINE)
            .build();

    private static final OcrSettingsData INJURED_COUNT_SETTINGS = OcrSettingsData.assembler()
            .stripBackground(true)
            .setTextColor(new Color(255, 255, 255))
            .charWhitelist("0123456789,/")
            .textLayout(OcrSettingsData.TextLayout.SINGLE_LINE)
            .build();

    private static final OcrSettingsData TIMER_SETTINGS = OcrSettingsData.assembler()
            .stripBackground(true)
            .setTextColor(new Color(255, 255, 255))
            .charWhitelist("0123456789:")
            .textLayout(OcrSettingsData.TextLayout.SINGLE_LINE)
            .build();

    public HealInjuredRoutine(AccountDescriptor profile, TpDailyTaskEnum tpDailyTask) {
        super(profile, tpDailyTask);
    }

    @Override
    protected LaunchPoint getRequiredStartLocation() {
        return LaunchPoint.ANY;
    }

    @Override
    public boolean provideDailyMissionProgress() {
        return true;
    }

    /**
     * How long to wait before looking again when nothing is currently injured,
     * from Troops -> Healing. Clamped rather than trusted: this value drives a
     * reschedule, and a zero or negative one would spin the task.
     */
    private int idleRecheckMinutes() {
        Integer configured = profile.getConfig(ConfigurationKeyEnum.HEAL_INJURED_IDLE_RECHECK_INT, Integer.class);
        if (configured == null) {
            return DEFAULT_IDLE_RECHECK_MINUTES;
        }
        return Math.min(MAX_IDLE_RECHECK_MINUTES, Math.max(MIN_IDLE_RECHECK_MINUTES, configured));
    }

    @Override
    protected void execute() {
        final int idleRecheckMinutes = idleRecheckMinutes();

        if (!navigationHelper.navigateToSidebarDestination(SidebarDestination.RESEARCH_CENTER)) {
            logWarning("Could not reach the Research Center anchor frame, so the Infirmary's "
                    + "position is unknown. Rechecking in " + idleRecheckMinutes + " minutes "
                    + "rather than tapping blind.");
            reschedule(LocalDateTime.now().plusMinutes(idleRecheckMinutes));
            return;
        }
        sleepTask(ANCHOR_SETTLE_MS);

        tapNear(INJURED_BUBBLE_POINT);
        sleepTask(PANEL_SETTLE_MS);

        if (!isHealPanelOpen()) {
            logInfo("Heal Injured panel didn't open -- no injured-troops bubble over the "
                    + "Infirmary, so nothing is currently injured. Rechecking in "
                    + idleRecheckMinutes + " minutes.");
            reschedule(LocalDateTime.now().plusMinutes(idleRecheckMinutes));
            return;
        }

        Integer injuredCount = readSeverelyInjuredCount();
        if (injuredCount == null) {
            logWarning("Could not OCR the Severely Injured count. Closing panel and "
                    + "rechecking in " + idleRecheckMinutes + " minutes rather than guessing.");
            closePanel();
            reschedule(LocalDateTime.now().plusMinutes(idleRecheckMinutes));
            return;
        }

        if (injuredCount == 0) {
            logInfo("Zero injured troops. Rechecking in " + idleRecheckMinutes + " minutes.");
            closePanel();
            reschedule(LocalDateTime.now().plusMinutes(idleRecheckMinutes));
            return;
        }

        logInfo("Severely Injured: " + injuredCount + ". Starting heal + requesting help.");

        tapNear(QUICK_SELECT_BTN);
        sleepTask(ACTION_SETTLE_MS);

        // First tap on the shared button position: starts healing ("Heal" -> queue begins).
        tapNear(PRIMARY_ACTION_BTN);
        sleepTask(ACTION_SETTLE_MS);

        StatisticsService.obtain().addToCounter(profile, "Heal Injured Started", 1);

        // The second tap is NOT unconditional. A small batch finishes in seconds and the
        // game dismisses the whole panel on its own -- tapping that same coordinate again
        // would land on the city behind it and select a building. Only when the panel is
        // still up has the button swapped to "Help" (alliance assistance on the timer,
        // measured live cutting 14:21 down to 1:45).
        Duration remaining = null;
        if (isHealPanelOpen()) {
            tapNear(PRIMARY_ACTION_BTN);
            sleepTask(ACTION_SETTLE_MS);
            remaining = readHealingTimer();
            closePanel();
        } else {
            logInfo("Panel closed straight after healing -- the queue finished immediately, "
                    + "so there was no running timer to request Help on.");
            reschedule(LocalDateTime.now().plusMinutes(idleRecheckMinutes));
            return;
        }

        if (remaining == null) {
            logWarning("Could not OCR the healing countdown after starting. Falling back "
                    + "to the " + idleRecheckMinutes + "-minute idle recheck.");
            reschedule(LocalDateTime.now().plusMinutes(idleRecheckMinutes));
            return;
        }

        LocalDateTime nextCheck = LocalDateTime.now().plus(remaining);
        logInfo("Healing queue running, ready at " + nextCheck.format(DATETIME_FORMATTER)
                + ". Rescheduling to that time (more injured troops may be queued after).");
        reschedule(nextCheck);
    }

    private boolean isHealPanelOpen() {
        String title = stringHelper.attemptRecognition(
                PANEL_TITLE_TOP_LEFT, PANEL_TITLE_BOTTOM_RIGHT,
                3, 200L, WHITE_TEXT_SETTINGS,
                s -> s != null && !s.isBlank(),
                s -> s);
        return title != null && title.toLowerCase().contains("heal");
    }

    private Integer readSeverelyInjuredCount() {
        String text = stringHelper.attemptRecognition(
                SEVERELY_INJURED_TOP_LEFT, SEVERELY_INJURED_BOTTOM_RIGHT,
                3, 200L, INJURED_COUNT_SETTINGS,
                s -> s != null && s.matches(".*\\d.*"),
                s -> s);

        if (text == null) {
            return null;
        }

        // Format observed live: "382/79,300" -- the count BEFORE the slash is what's
        // actually queued/needing heal right now.
        Pattern beforeSlash = Pattern.compile("(\\d[\\d,]*)\\s*/");
        String digits = RegexNumberParser.extractByPattern(text, beforeSlash) != null
                ? String.valueOf(RegexNumberParser.extractByPattern(text, beforeSlash))
                : null;

        if (digits == null) {
            logWarning("Severely Injured text didn't match the expected 'N/total' shape: '"
                    + text + "'");
            return null;
        }

        try {
            return Integer.parseInt(digits.replace(",", ""));
        } catch (NumberFormatException e) {
            logWarning("Could not parse injured count from: '" + text + "'");
            return null;
        }
    }

    private Duration readHealingTimer() {
        String text = stringHelper.attemptRecognition(
                HEALING_TIMER_TOP_LEFT, HEALING_TIMER_BOTTOM_RIGHT,
                3, 200L, TIMER_SETTINGS,
                GameTimeUtils::isAcceptedFormat,
                s -> s);

        if (text == null) {
            return null;
        }

        try {
            Duration parsed = GameTimeUtils.parseDuration(text);
            if (parsed.toHours() > MAX_TIMER_HOURS_SANITY) {
                logWarning("Healing timer read implausibly long (" + parsed
                        + "), discarding rather than trusting it.");
                return null;
            }
            return parsed;
        } catch (Exception e) {
            logWarning("Could not parse healing timer from: '" + text + "'");
            return null;
        }
    }

    private void closePanel() {
        tapNear(PANEL_CLOSE_BTN);
        sleepTask(ACTION_SETTLE_MS);
    }
}
