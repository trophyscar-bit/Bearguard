package dev.frostguard.tasks.events;

import java.awt.image.BufferedImage;
import java.io.File;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.OcrSettingsData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.engine.helper.TemplateSearchHelper.SearchConfig;
import dev.frostguard.engine.nav.SearchConfigConstants;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.vision.color.GameColors;
import dev.frostguard.vision.color.PixelStats;
import dev.frostguard.vision.convert.GameTimeUtils;
import dev.frostguard.vision.convert.ImageConverter;
import dev.frostguard.vision.ocr.ResilientOcrExecutor;

/**
 * Journey of Light: keep every expedition team flying, take the free daily Pocket Watch, claim
 * milestone rewards, and optionally assemble Radiance Treasures.
 *
 * <p>Pocket Watches are never spent. They carry over to the next Journey of Light, so spending one
 * now and spending it next month are worth the same, and the in-game Auto Expedition button burns
 * all of them at once.
 *
 * <p>Treasures need no deadline handling. The event's own rules give a one-day collection window
 * after it closes and then open anything left automatically, so a treasure is never lost; the only
 * decision is whether to assemble. Assembling as treasures arrive gives the same result as
 * assembling once at the end (counts only grow), and does not depend on the bot being up during
 * the collection day.
 *
 * <p>Every coordinate below was read off live 720x1280 frames; the frame tests
 * check each one against those captures.
 */
public class JourneyofLightRoutine extends DelayedTask {

    // Expedition slots, left to right. Slot 4 is the one bought with a pack; while locked it shows
    // "Employ Expedition Teams" and neither line reads.
    //
    // Each timer crop is centred on its slot (x 114, 278, 441, 604). The inherited crops for slots
    // 2-4 sat 8px right of centre, clipping the leading "0" of "07:56:09" into a 9 or a 1 -- a
    // reading the format check rejects, so a flying team looked like it had no timer at all.
    static final AreaData[] SLOT_TIMERS = {
            AreaData.of(58, 1034, 170, 1060),
            AreaData.of(222, 1034, 334, 1060),
            AreaData.of(385, 1034, 497, 1060),
            AreaData.of(548, 1034, 660, 1060),
    };
    /** "Expedition" while a team is idle, "Finish" while it is flying. */
    static final AreaData[] SLOT_LABELS = {
            AreaData.of(45, 1070, 185, 1102),
            AreaData.of(208, 1070, 348, 1102),
            AreaData.of(371, 1070, 511, 1102),
            AreaData.of(534, 1070, 674, 1102),
    };

    static final AreaData DISPATCH_ALL = AreaData.of(50, 1150, 290, 1230);
    static final AreaData EXPEDITIONS_TAB = AreaData.of(50, 220, 350, 260);
    static final AreaData TREASURES_TAB = AreaData.of(400, 220, 660, 260);
    static final AreaData EVENT_HEADER = AreaData.of(50, 300, 400, 400);

    /** "8/10" under the milestone chest: completed expeditions over the next target. */
    static final AreaData FLIGHT_COUNTER = AreaData.of(40, 455, 112, 485);
    static final PointData MILESTONE_CHEST = new PointData(75, 435);
    static final PointData MILESTONE_CLOSE = new PointData(664, 155);
    static final int MILESTONE_ROWS = 5;
    static final int MILESTONE_ROW_PITCH = 180;

    static final PointData WATCH_PLUS = new PointData(660, 368);
    static final AreaData PACK_HEADER = AreaData.of(90, 20, 450, 62);
    static final AreaData GIFT_TITLE = AreaData.of(230, 140, 480, 185);
    static final AreaData GIFT_BUTTON = AreaData.of(425, 252, 600, 298);
    /** "Event ends in" line: harmless to tap when no reward overlay is up. */
    static final PointData PACK_NEUTRAL = new PointData(360, 105);

    // Assemble buttons on the My Treasures cards. Sampled on a strip above the white label, where
    // the enabled button is saturated blue and the disabled one grey.
    static final AreaData COMMON_ASSEMBLE_BODY = AreaData.of(212, 729, 332, 738);
    static final AreaData PREMIUM_ASSEMBLE_BODY = AreaData.of(535, 729, 655, 738);
    static final PointData COMMON_ASSEMBLE = new PointData(270, 753);
    static final PointData PREMIUM_ASSEMBLE = new PointData(594, 753);
    static final AreaData ASSEMBLE_DIALOG = AreaData.of(40, 980, 690, 1130);
    /** Gap between the lower two cards: clears "Tap anywhere to exit" without touching a button. */
    static final PointData TREASURES_NEUTRAL = new PointData(360, 1183);

    static final Duration NO_TIMER_RETRY = Duration.ofMinutes(30);
    static final Duration ENDED_RECHECK = Duration.ofHours(6);
    static final Duration LANDING_MARGIN = Duration.ofMinutes(1);
    static final Duration RESET_MARGIN = Duration.ofMinutes(5);
    static final Duration EVIDENCE_INTERVAL = Duration.ofHours(3);

    private static final Pattern FRACTION = Pattern.compile("(\\d+)\\s*/\\s*(\\d+)");
    private static final Pattern TARGET = Pattern.compile("Target\\D*(\\d+)", Pattern.CASE_INSENSITIVE);

    static final OcrSettingsData TIMER_SETTINGS = OcrSettingsData.assembler()
            .textLayout(OcrSettingsData.TextLayout.SINGLE_LINE)
            .charWhitelist("0123456789:")
            .build();
    static final OcrSettingsData FRACTION_SETTINGS = OcrSettingsData.assembler()
            .textLayout(OcrSettingsData.TextLayout.SINGLE_LINE)
            .charWhitelist("0123456789/")
            .build();
    static final OcrSettingsData LINE_SETTINGS = OcrSettingsData.assembler()
            .textLayout(OcrSettingsData.TextLayout.SINGLE_LINE)
            .build();

    /** Last time each kind of evidence frame was written, so a stuck state cannot fill the disk. */
    private static final Map<String, LocalDateTime> LAST_EVIDENCE = new ConcurrentHashMap<>();

    enum GiftAction { CLAIM, ALREADY_CLAIMED, UNRECOGNISED }

    private ResilientOcrExecutor<LocalDateTime> timerHelper;

    public JourneyofLightRoutine(AccountDescriptor profile, TpDailyTaskEnum tpTask) {
        super(profile, tpTask);
    }

    @Override
    protected void execute() {
        this.timerHelper = new ResilientOcrExecutor<>(provider);

        if (!openEvent()) {
            return;
        }

        boolean ended = eventHasEnded();
        Optional<LocalDateTime> soonestReturn = Optional.empty();
        if (!ended) {
            // The repeated taps also clear the "Tap anywhere to exit" overlay that returning
            // teams raise, so collecting and re-dispatching happen in the same press.
            tapInside(DISPATCH_ALL.topLeft(), DISPATCH_ALL.bottomRight(), 5, 200);
            sleepTask(1000);
            soonestReturn = readSoonestReturn();
        }

        claimMilestones(readCompletedFlights());
        if (!ended) {
            claimFreeWatch();
        }
        assembleTreasures();

        LocalDateTime next = nextRun(LocalDateTime.now(), ended, soonestReturn, GameTimeUtils.dailyResetTime());
        logInfo("Journey of Light: next check " + next.format(DATETIME_FORMATTER)
                + (soonestReturn.isPresent() ? " (first team lands " + soonestReturn.get().format(DATETIME_FORMATTER) + ")" : ""));
        reschedule(next);

        for (int i = 0; i < 3; i++) {
            sleepTask(500);
            pressBack();
        }
    }

    /**
     * Next run: when the first team lands, but no later than just after the daily reset, which is
     * when the free Pocket Watch comes back.
     */
    static LocalDateTime nextRun(LocalDateTime now, boolean ended, Optional<LocalDateTime> soonestReturn,
            LocalDateTime nextReset) {
        if (ended) {
            return now.plus(ENDED_RECHECK);
        }
        if (soonestReturn.isEmpty()) {
            return now.plus(NO_TIMER_RETRY);
        }
        LocalDateTime landing = soonestReturn.get().plus(LANDING_MARGIN);
        LocalDateTime afterReset = nextReset.plus(RESET_MARGIN);
        return landing.isBefore(afterReset) ? landing : afterReset;
    }

    private boolean openEvent() {
        ImageSearchResultData deals = templateSearchHelper.locatePattern(
                TemplatesEnum.HOME_DEALS_BUTTON, SearchConfigConstants.DEFAULT_SINGLE);
        if (!deals.isFound()) {
            logWarning("The 'Deals' button was not found. Retrying in 5 minutes.");
            reschedule(LocalDateTime.now().plusMinutes(5));
            return false;
        }
        tapInside(deals);
        sleepTask(1500);

        boolean navigated = navigateToEventScreen();
        for (int i = 0; i < 3 && !navigated; i++) {
            logDebug("Retrying navigation to the Journey of Light event screen. Attempt " + (i + 1) + " of 3.");
            sleepTask(1000);
            navigated = navigateToEventScreen();
        }
        if (!navigated) {
            logInfo("Journey of Light is not in Deals; it is probably not running. Checking again after reset.");
            reschedule(GameTimeUtils.dailyResetTime());
            return false;
        }
        return true;
    }

    private boolean navigateToEventScreen() {
        // Close any windows that may be open
        tapInside(new PointData(529, 27), new PointData(635, 63), 5, 300);

        ImageSearchResultData selected = templateSearchHelper.locatePattern(
                TemplatesEnum.JOURNEY_OF_LIGHT_TAB, SearchConfigConstants.DEFAULT_SINGLE);
        ImageSearchResultData unselected = templateSearchHelper.locatePattern(
                TemplatesEnum.JOURNEY_OF_LIGHT_UNSELECTED_TAB, SearchConfigConstants.DEFAULT_SINGLE);

        if (selected.isFound() || unselected.isFound()) {
            logInfo("Successfully navigated to the Journey of Light event.");
            sleepTask(500);
            tapInside(selected.isFound() ? selected : unselected);
            sleepTask(1000);

            // The event remembers its inner tab; make sure it is on expeditions, not My Treasures.
            tapInside(EXPEDITIONS_TAB.topLeft(), EXPEDITIONS_TAB.bottomRight());
            sleepTask(500);
            return true;
        }
        return false;
    }

    private boolean eventHasEnded() {
        String header = stringHelper.attemptRecognition(EVENT_HEADER.topLeft(), EVENT_HEADER.bottomRight(),
                1, 300L, null, s -> !s.isEmpty(), s -> s);
        return header != null && header.toLowerCase(Locale.ROOT).contains("collect");
    }

    /**
     * Earliest landing among the teams in the air. Empty when no timer reads at all, which after a
     * Dispatch All means something on screen is not what this routine expects; the frame is kept.
     */
    private Optional<LocalDateTime> readSoonestReturn() {
        LocalDateTime soonest = null;
        int idle = 0;
        for (int slot = 0; slot < SLOT_TIMERS.length; slot++) {
            AreaData area = SLOT_TIMERS[slot];
            LocalDateTime landing = timerHelper.attemptRecognition(area.topLeft(), area.bottomRight(), 3, 200L,
                    TIMER_SETTINGS, GameTimeUtils::isAcceptedFormat,
                    text -> LocalDateTime.now().plus(GameTimeUtils.parseDuration(text)));
            if (landing != null) {
                logInfo("Expedition team " + (slot + 1) + " lands in " + GameTimeUtils.formatCountdown(landing));
                if (soonest == null || landing.isBefore(soonest)) {
                    soonest = landing;
                }
                continue;
            }
            String label = readStringValue(SLOT_LABELS[slot].topLeft(), SLOT_LABELS[slot].bottomRight(), LINE_SETTINGS);
            if (label != null && label.toLowerCase(Locale.ROOT).contains("expedition")) {
                idle++;
                logWarning("Expedition team " + (slot + 1) + " is still idle after Dispatch All.");
            } else {
                logDebug("Expedition slot " + (slot + 1) + " has no timer and no idle label; treating it as locked.");
            }
        }
        if (soonest == null || idle > 0) {
            saveEvidence(idle > 0 ? "jol-team-left-idle" : "jol-no-timers");
        }
        return Optional.ofNullable(soonest);
    }

    private int readCompletedFlights() {
        String counter = readStringValue(FLIGHT_COUNTER.topLeft(), FLIGHT_COUNTER.bottomRight(), FRACTION_SETTINGS);
        int flights = completedFlights(counter);
        if (flights < 0) {
            logWarning("Could not read the expedition counter ('" + counter + "'); skipping milestones this run.");
        }
        return flights;
    }

    /** Numerator of "8/10", or -1. */
    static int completedFlights(String counter) {
        if (counter == null) {
            return -1;
        }
        Matcher m = FRACTION.matcher(counter);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    /** N from "Target: N", or -1. */
    static int milestoneTarget(String header) {
        if (header == null) {
            return -1;
        }
        Matcher m = TARGET.matcher(header);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    static AreaData milestoneHeader(int row) {
        int y = 215 + MILESTONE_ROW_PITCH * row;
        return AreaData.of(60, y, 260, y + 34);
    }

    static AreaData milestoneButton(int row) {
        int y = 271 + MILESTONE_ROW_PITCH * row;
        return AreaData.of(500, y, 650, y + 70);
    }

    /**
     * The progress figure inside each row is drawn red until it is reached and OCR drops the red
     * digits, so the count comes from the main-screen counter and each row is judged on its target.
     * A row is claimable when its target is reached and it does not already carry the green tick.
     */
    private void claimMilestones(int flights) {
        if (flights < 0) {
            return;
        }
        tapNear(MILESTONE_CHEST);
        sleepTask(1500);

        for (int pass = 0; pass < MILESTONE_ROWS; pass++) {
            boolean claimed = false;
            for (int row = 0; row < MILESTONE_ROWS; row++) {
                AreaData header = milestoneHeader(row);
                int target = milestoneTarget(readStringValue(header.topLeft(), header.bottomRight(), LINE_SETTINGS));
                if (target < 0 || target > flights) {
                    continue;
                }
                AreaData button = milestoneButton(row);
                ImageSearchResultData tick = templateSearchHelper.locatePattern(
                        TemplatesEnum.JOURNEY_OF_LIGHT_MILESTONE_CLAIMED,
                        SearchConfig.builder().withMaxAttempts(1).withThreshold(90)
                                .withCoordinates(button.topLeft(), button.bottomRight()).build());
                if (tick.isFound()) {
                    continue;
                }
                saveEvidence("jol-milestone-claim");
                tapInside(button.topLeft(), button.bottomRight());
                sleepTask(1500);
                tapNear(new PointData(360, 155)); // popup title: clears the reward overlay if one opened
                sleepTask(800);
                logInfo("Claimed the " + target + "-expedition milestone (" + flights + " completed).");
                claimed = true;
                break; // claimed rows may move; read the list again
            }
            if (!claimed) {
                break;
            }
        }
        tapNear(MILESTONE_CLOSE);
        sleepTask(1000);
    }

    static GiftAction giftAction(String title, String button) {
        if (title == null || !title.toLowerCase(Locale.ROOT).contains("expedition gift")) {
            return GiftAction.UNRECOGNISED;
        }
        String b = button == null ? "" : button.toLowerCase(Locale.ROOT);
        if (b.contains("claimed")) {
            return GiftAction.ALREADY_CLAIMED;
        }
        if (b.contains("claim") || b.contains("free")) {
            return GiftAction.CLAIM;
        }
        return GiftAction.UNRECOGNISED;
    }

    /**
     * The free watch is the "Expedition Gift" card at the top of the Quick Adventure Pack screen,
     * behind the + beside the watch count. Everything else on that screen is a paid pack, so the
     * button is only pressed when its own label says it is the free claim.
     */
    private void claimFreeWatch() {
        tapNear(WATCH_PLUS);
        sleepTask(1500);

        String header = readStringValue(PACK_HEADER.topLeft(), PACK_HEADER.bottomRight(), LINE_SETTINGS);
        if (header == null || !header.toLowerCase(Locale.ROOT).contains("adventure")) {
            logWarning("Quick Adventure Pack screen did not open (read '" + header + "'); skipping the free watch.");
            saveEvidence("jol-watch-screen-unrecognised");
            return;
        }

        String title = readStringValue(GIFT_TITLE.topLeft(), GIFT_TITLE.bottomRight(), LINE_SETTINGS);
        String button = readStringValue(GIFT_BUTTON.topLeft(), GIFT_BUTTON.bottomRight(), LINE_SETTINGS);
        switch (giftAction(title, button)) {
            case CLAIM -> {
                tapInside(GIFT_BUTTON.topLeft(), GIFT_BUTTON.bottomRight());
                sleepTask(1500);
                tapNear(PACK_NEUTRAL);
                sleepTask(800);
                logInfo("Claimed the free daily Pocket Watch.");
            }
            case ALREADY_CLAIMED -> logDebug("Free daily Pocket Watch already claimed.");
            case UNRECOGNISED -> {
                logWarning("Expedition Gift read as '" + title + "' / '" + button + "'; not pressing it.");
                saveEvidence("jol-watch-gift-unrecognised");
            }
        }
        pressBack();
        sleepTask(1200);
    }

    private void assembleTreasures() {
        boolean common = enabled(ConfigurationKeyEnum.JOURNEY_OF_LIGHT_ASSEMBLE_COMMON_BOOL);
        boolean premium = enabled(ConfigurationKeyEnum.JOURNEY_OF_LIGHT_ASSEMBLE_PREMIUM_BOOL);
        if (!common && !premium) {
            return;
        }
        tapInside(TREASURES_TAB.topLeft(), TREASURES_TAB.bottomRight());
        sleepTask(1500);

        // Common first: it is what produces Premium.
        if (common) {
            assembleIfReady("Common", COMMON_ASSEMBLE_BODY, COMMON_ASSEMBLE);
        }
        if (premium) {
            assembleIfReady("Premium", PREMIUM_ASSEMBLE_BODY, PREMIUM_ASSEMBLE);
        }
    }

    /** Enabled Assemble buttons are saturated blue; disabled ones grey. More than half must be blue. */
    static boolean assembleReady(BufferedImage frame, AreaData body) {
        int width = body.bottomRight().getX() - body.topLeft().getX() + 1;
        int height = body.bottomRight().getY() - body.topLeft().getY() + 1;
        return PixelStats.count(frame, body, GameColors::isActionBlue) * 2 > width * height;
    }

    private void assembleIfReady(String kind, AreaData body, PointData button) {
        BufferedImage frame = capture();
        if (frame == null || !assembleReady(frame, body)) {
            logDebug(kind + " Radiance Treasures: not enough to assemble.");
            return;
        }
        tapNear(button);
        sleepTask(1500);

        // The dialog's quantity slider opens at the maximum, so one confirm assembles everything.
        ImageSearchResultData confirm = templateSearchHelper.locatePattern(
                TemplatesEnum.JOURNEY_OF_LIGHT_ASSEMBLE_CONFIRM,
                SearchConfig.builder().withMaxAttempts(2).withDelay(400L).withThreshold(90)
                        .withCoordinates(ASSEMBLE_DIALOG.topLeft(), ASSEMBLE_DIALOG.bottomRight()).build());
        if (!confirm.isFound()) {
            logWarning(kind + " assemble dialog not recognised; backing out without assembling.");
            saveEvidence("jol-assemble-dialog-" + kind.toLowerCase(Locale.ROOT));
            pressBack();
            sleepTask(800);
            return;
        }
        tapInside(confirm);
        sleepTask(2500);
        tapNear(TREASURES_NEUTRAL);
        sleepTask(1200);
        logInfo("Assembled " + kind + " Radiance Treasures.");
    }

    private boolean enabled(ConfigurationKeyEnum key) {
        return Boolean.TRUE.equals(profile.getConfig(key, Boolean.class));
    }

    private BufferedImage capture() {
        try {
            RawImageData raw = emuManager.captureScreen(EMULATOR_NUMBER);
            return raw == null ? null : ImageConverter.toBufferedImage(raw);
        } catch (Exception ex) {
            logWarning("Screen capture failed: " + ex.getMessage());
            return null;
        }
    }

    /**
     * Keeps the screen for a state this routine has not been verified against, at most once per
     * kind every few hours. Best effort: a failed write must never replace the real outcome.
     */
    private void saveEvidence(String kind) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime last = LAST_EVIDENCE.get(kind);
        if (last != null && last.plus(EVIDENCE_INTERVAL).isAfter(now)) {
            return;
        }
        try {
            BufferedImage frame = capture();
            if (frame == null) {
                return;
            }
            File dir = new File(System.getProperty("user.dir"), "ocr-debug");
            dir.mkdirs();
            String name = kind + "-" + now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss")) + ".png";
            ImageIO.write(frame, "png", new File(dir, name));
            LAST_EVIDENCE.put(kind, now);
            logInfo("Saved the screen for diagnosis: ocr-debug/" + name);
        } catch (Exception ex) {
            logWarning("Could not save the " + kind + " screen: " + ex.getMessage());
        }
    }
}
