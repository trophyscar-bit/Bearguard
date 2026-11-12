package dev.frostguard.tasks.events;

import java.awt.image.BufferedImage;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.engine.helper.TemplateSearchHelper.SearchConfig;
import dev.frostguard.engine.nav.CommonGameAreas;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.engine.service.EventCalendarGate;
import dev.frostguard.engine.service.StatisticsService;
import dev.frostguard.tasks.diagnostics.TaskDiagnosticSnapshots;
import dev.frostguard.vision.convert.GameTimeUtils;
import dev.frostguard.vision.convert.ImageConverter;

/**
 * Winter Siege's Event Mission Rewards. While the event runs, every few hours, the routine opens Events, finds
 * the Winter Siege tab, goes through the page's main button to the stronghold map, opens Missions and claims
 * every green Claim. Attack missions finish on Battle day, so the passes keep going through every phase.
 *
 * <p>The routine stays away from the game's garrison and combat. Taps are limited to the Events button, the
 * detected tab, the detected Go Prepare, the detected Missions button, a detected Claim inside the mission
 * list, the popup's X while its title is on screen, and the back arrow.
 *
 * <p>Go Prepare is left alone while the page reads Garrisoned 0/2: the first Go Prepare on an account starts
 * a tutorial that locks the screen on Go Garrison, and it can only be finished by garrisoning a squad. Outside
 * the Preparation Phase the button has another label; the routine finds it by its blue fill, saves the page,
 * and goes ahead only when the map's Missions button appears behind it.
 */
public class WinterSiegeRoutine extends DelayedTask {

    /** The name the State calendar stores this event under, for the gate to find its window. */
    private static final String CALENDAR_EVENT_NAME = "Winter Siege";

    /**
     * Waits for the event instead of visiting its screen when the calendar says it is not on.
     *
     * <p>Winter Siege runs for a few days and is then off for weeks, so outside its window every
     * pass is a navigation that can only come back empty.</p>
     */
    private boolean parkedUntilTheEventOpens() {
        EventCalendarGate.Verdict verdict =
                EventCalendarGate.check(profile, CALENDAR_EVENT_NAME, LocalDateTime.now(ZoneOffset.UTC));
        if (verdict.shouldRun()) {
            verdict.note().ifPresent(this::logInfo);
            return false;
        }
        LocalDateTime nextStart = verdict.nextStartLocal().orElseThrow();
        logInfo(verdict.reason() + " Waiting until " + nextStart.format(DATETIME_FORMATTER) + ".");
        reschedule(nextStart);
        return true;
    }


    static final AreaData HOME_RAIL_AREA = AreaData.of(540, 80, 719, 700);
    static final AreaData EVENTS_HEADER_AREA = AreaData.of(70, 0, 300, 80);
    static final AreaData TITLE_AREA = AreaData.of(20, 195, 320, 270);
    static final AreaData GO_PREPARE_AREA = AreaData.of(180, 1060, 540, 1180);
    static final AreaData NOT_GARRISONED_AREA = AreaData.of(350, 1165, 480, 1215);
    static final AreaData MISSIONS_BUTTON_AREA = AreaData.of(330, 780, 719, 1000);
    static final AreaData MISSIONS_TITLE_AREA = AreaData.of(150, 125, 570, 195);
    static final AreaData CLAIM_AREA = AreaData.of(470, 205, 665, 1140);

    private static final PointData POPUP_CLOSE = new PointData(665, 158);
    private static final PointData REWARD_REVEAL_TAP = new PointData(358, 1182);
    private static final PointData LIST_SWIPE_FROM = new PointData(300, 1050);
    private static final PointData LIST_SWIPE_TO = new PointData(300, 350);
    private static final int LIST_SWIPE_MS = 2000;

    private static final int TEMPLATE_THRESHOLD = 85;
    private static final int MAX_CLAIMS_PER_PASS = 8;
    private static final int CLAIM_HALF_WIDTH = 60;
    private static final int CLAIM_HALF_HEIGHT = 22;
    private static final int CLAIM_MIN_GREEN_PIXELS = 1500;
    private static final int MAIN_BUTTON_MIN_BLUE_PIXELS = 10000;
    private static final int MAX_STRIP_SWIPES = 20;
    private static final int STRIP_SWIPE_MS = 600;
    private static final int STILL_SWIPES_FOR_END = 2;
    private static final int PANEL_SETTLE_MS = 2000;
    private static final int MAP_SETTLE_MS = 3500;
    private static final int STRIP_SETTLE_MS = 1200;
    private static final int CLAIM_SETTLE_MS = 2000;

    static final Duration RUN_AFTER_RESET = Duration.ofMinutes(70);
    static final Duration EVENT_PASS_INTERVAL = Duration.ofHours(3);
    static final Duration FAILURE_RETRY = Duration.ofMinutes(30);
    static final int MAX_FAILED_ATTEMPTS = 4;

    enum Outcome { CLAIMED, NOTHING_TO_CLAIM, NOT_GARRISONED, NO_MAIN_BUTTON, NO_MISSIONS, EVENT_ABSENT, UNKNOWN }

    private int failedAttempts;
    private int claimed;
    private boolean frameMissing;

    public WinterSiegeRoutine(AccountDescriptor profile, TpDailyTaskEnum tpTask) {
        super(profile, tpTask);
    }

    @Override
    public LaunchPoint getRequiredStartLocation() {
        return LaunchPoint.HOME;
    }

    @Override
    protected void execute() {
        if (parkedUntilTheEventOpens()) {
            return;
        }
        claimed = 0;
        frameMissing = false;
        Outcome outcome = visit();
        leave();
        if (claimed > 0) {
            StatisticsService.obtain().addToCounter(profile, "Winter Siege Missions Claimed", claimed);
        }
        failedAttempts = outcome == Outcome.UNKNOWN ? failedAttempts + 1 : 0;
        LocalDateTime next = nextRun(outcome, failedAttempts, LocalDateTime.now(), GameTimeUtils.dailyResetTime());
        if (outcome == Outcome.UNKNOWN) {
            reportFailedAttempt("Winter Siege outcome unknown");
            if (failedAttempts >= MAX_FAILED_ATTEMPTS) {
                logWarning("Winter Siege still unknown after " + failedAttempts
                        + " attempts; giving up until the next daily run.");
                failedAttempts = 0;
            }
        }
        logInfo("Winter Siege: " + outcome + ", claimed " + claimed + ". Next pass at "
                + next.format(DATETIME_FORMATTER) + ".");
        rescheduleExact(next);
    }

    /**
     * Shortly after the next reset when the event is not in the Events strip. While it runs, every
     * {@link #EVENT_PASS_INTERVAL}; an unknown outcome is retried after 30 minutes, at most
     * {@link #MAX_FAILED_ATTEMPTS} times. Never later than that daily run.
     */
    static LocalDateTime nextRun(Outcome outcome, int failedAttempts, LocalDateTime now, LocalDateTime nextReset) {
        LocalDateTime daily = nextReset.plus(RUN_AFTER_RESET);
        if (outcome == Outcome.EVENT_ABSENT) {
            return daily;
        }
        LocalDateTime next = outcome == Outcome.UNKNOWN && failedAttempts < MAX_FAILED_ATTEMPTS
                ? now.plus(FAILURE_RETRY)
                : now.plus(EVENT_PASS_INTERVAL);
        return next.isBefore(daily) ? next : daily;
    }

    private Outcome visit() {
        ImageSearchResultData events = locate(TemplatesEnum.HOME_EVENTS_BUTTON, HOME_RAIL_AREA, 3);
        if (!events.isFound()) {
            logWarning("Events button not found on the City rail; " + snapshot("events-button-missing"));
            return Outcome.UNKNOWN;
        }
        tapInside(events);
        sleepTask(PANEL_SETTLE_MS);
        if (!eventsOpen()) {
            logWarning("Tapped Events but its header did not show; " + snapshot("events-not-open"));
            return Outcome.UNKNOWN;
        }
        if (!onEventPage()) {
            ImageSearchResultData tab = findTab();
            if (frameMissing) {
                logWarning("No screen capture while walking the Events tab strip.");
                return Outcome.UNKNOWN;
            }
            if (!tab.isFound()) {
                logInfo("No Winter Siege tab in the Events strip: the event is not running.");
                return Outcome.EVENT_ABSENT;
            }
            tapInside(tab);
            sleepTask(PANEL_SETTLE_MS);
            if (!onEventPage()) {
                logWarning("Tapped the Winter Siege tab but the page did not open; " + snapshot("page-not-open"));
                return Outcome.UNKNOWN;
            }
        }
        return openMissions();
    }

    private Outcome openMissions() {
        ImageSearchResultData goPrepare = locate(TemplatesEnum.WINTER_SIEGE_GO_PREPARE, GO_PREPARE_AREA, 3);
        boolean preparation = goPrepare.isFound();
        PointData mainButton = preparation ? goPrepare.getPoint() : null;
        if (!preparation) {
            Optional<BufferedImage> page = frame();
            if (page.isEmpty()) {
                logWarning("No screen capture of the Winter Siege page.");
                return Outcome.UNKNOWN;
            }
            mainButton = blueButtonCenter(page.get(), GO_PREPARE_AREA).orElse(null);
            if (mainButton == null) {
                logInfo("Winter Siege page has no main button; " + snapshot("no-main-button"));
                return Outcome.NO_MAIN_BUTTON;
            }
        }
        if (locate(TemplatesEnum.WINTER_SIEGE_NOT_GARRISONED, NOT_GARRISONED_AREA, 2).isFound()) {
            logWarning("Winter Siege reads Garrisoned 0/2: no squads placed yet, so the main button is left alone.");
            return Outcome.NOT_GARRISONED;
        }
        if (!preparation) {
            logInfo("Winter Siege main button is not Go Prepare; trying it. " + snapshot("other-phase-page"));
        }
        tapNear(mainButton, 6);
        sleepTask(MAP_SETTLE_MS);
        ImageSearchResultData missions = locate(TemplatesEnum.WINTER_SIEGE_MISSIONS, MISSIONS_BUTTON_AREA, 3);
        if (!missions.isFound()) {
            if (preparation) {
                logWarning("Winter Siege map opened without a Missions button; "
                        + snapshot("missions-button-missing"));
                return Outcome.UNKNOWN;
            }
            logInfo("No Missions button behind this phase's main button; " + snapshot("other-phase-no-missions"));
            if (!eventsOpen()) {
                tapNear(CommonGameAreas.DEALS_BACK_ARROW, 4);
                sleepTask(PANEL_SETTLE_MS);
            }
            return Outcome.NO_MISSIONS;
        }
        tapInside(missions);
        sleepTask(PANEL_SETTLE_MS);
        if (!missionsShown()) {
            logWarning("Tapped Missions but its popup did not open; " + snapshot("missions-not-open"));
            return Outcome.UNKNOWN;
        }
        if (!claimVisibleRows()) {
            return Outcome.UNKNOWN;
        }
        swipe(LIST_SWIPE_FROM, LIST_SWIPE_TO, LIST_SWIPE_MS);
        sleepTask(STRIP_SETTLE_MS);
        if (!claimVisibleRows()) {
            return Outcome.UNKNOWN;
        }
        return claimed > 0 ? Outcome.CLAIMED : Outcome.NOTHING_TO_CLAIM;
    }

    /** Claims the green rows on screen. False when the popup did not come back after a claim. */
    private boolean claimVisibleRows() {
        for (int i = 0; i < MAX_CLAIMS_PER_PASS; i++) {
            ImageSearchResultData claim = locate(TemplatesEnum.EVENTS_CLAIM_BUTTON, CLAIM_AREA, 2);
            if (!claim.isFound()) {
                return true;
            }
            Optional<BufferedImage> rows = frame();
            if (rows.isEmpty()) {
                logWarning("No screen capture while reading the mission rows.");
                return false;
            }
            if (!isGreenClaim(rows.get(), claim.getPoint())) {
                return true;
            }
            tapInside(claim);
            sleepTask(CLAIM_SETTLE_MS);
            if (!missionsShown()) {
                tapNear(REWARD_REVEAL_TAP, 4);
                sleepTask(CLAIM_SETTLE_MS);
            }
            if (!missionsShown()) {
                logWarning("Missions popup did not come back after a claim; " + snapshot("claim-no-popup"));
                return false;
            }
            claimed++;
        }
        logWarning("Hit the claim cap of " + MAX_CLAIMS_PER_PASS + " on one pass; " + snapshot("claim-cap"));
        return true;
    }

    /** An unfinished row's Claim is the same shape in grey, so a match only counts when the button is green. */
    static boolean isGreenClaim(BufferedImage img, PointData center) {
        int green = 0;
        for (int y = center.getY() - CLAIM_HALF_HEIGHT; y <= center.getY() + CLAIM_HALF_HEIGHT; y++) {
            for (int x = center.getX() - CLAIM_HALF_WIDTH; x <= center.getX() + CLAIM_HALF_WIDTH; x++) {
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                if (g >= 140 && g - r >= 60 && g - b >= 60) {
                    green++;
                }
            }
        }
        return green >= CLAIM_MIN_GREEN_PIXELS;
    }

    /** Centre of the page's big blue button, found by its fill whatever its label says. */
    static Optional<PointData> blueButtonCenter(BufferedImage img, AreaData area) {
        long sumX = 0;
        long sumY = 0;
        int blue = 0;
        for (int y = area.topLeft().getY(); y <= area.bottomRight().getY(); y++) {
            for (int x = area.topLeft().getX(); x <= area.bottomRight().getX(); x++) {
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                if (b >= 200 && b - r >= 100 && g >= 120) {
                    sumX += x;
                    sumY += y;
                    blue++;
                }
            }
        }
        if (blue < MAIN_BUTTON_MIN_BLUE_PIXELS) {
            return Optional.empty();
        }
        return Optional.of(new PointData((int) (sumX / blue), (int) (sumY / blue)));
    }

    /** Closes Missions with its X, then steps back through the map and Events, each only when it is on screen. */
    private void leave() {
        if (missionsShown()) {
            tapNear(POPUP_CLOSE, 4);
            sleepTask(PANEL_SETTLE_MS);
        }
        if (locate(TemplatesEnum.WINTER_SIEGE_MISSIONS, MISSIONS_BUTTON_AREA, 1).isFound()) {
            tapNear(CommonGameAreas.DEALS_BACK_ARROW, 4);
            sleepTask(PANEL_SETTLE_MS);
        }
        if (eventsOpen()) {
            tapNear(CommonGameAreas.DEALS_BACK_ARROW, 4);
            sleepTask(PANEL_SETTLE_MS);
        }
    }

    /** Walks the strip toward its right end and back; a direction ends after two swipes that move nothing. */
    private ImageSearchResultData findTab() {
        ImageSearchResultData tab = locateTab();
        if (tab.isFound()) {
            return tab;
        }
        for (boolean towardEnd : new boolean[] {true, false}) {
            int still = 0;
            for (int swipe = 0; swipe < MAX_STRIP_SWIPES && still < STILL_SWIPES_FOR_END; swipe++) {
                Optional<BufferedImage> before = frame();
                if (towardEnd) {
                    swipe(CommonGameAreas.DEALS_TAB_STRIP_SWIPE_FROM, CommonGameAreas.DEALS_TAB_STRIP_SWIPE_TO,
                            STRIP_SWIPE_MS);
                } else {
                    swipe(CommonGameAreas.DEALS_TAB_STRIP_SWIPE_TO, CommonGameAreas.DEALS_TAB_STRIP_SWIPE_FROM,
                            STRIP_SWIPE_MS);
                }
                sleepTask(STRIP_SETTLE_MS);
                tab = locateTab();
                if (tab.isFound()) {
                    return tab;
                }
                Optional<BufferedImage> after = frame();
                if (before.isEmpty() || after.isEmpty()) {
                    return tab;
                }
                still = DeadShotSignInRoutine.stripMoved(before.get(), after.get()) ? 0 : still + 1;
            }
        }
        return tab;
    }

    private ImageSearchResultData locateTab() {
        return locate(TemplatesEnum.WINTER_SIEGE_TAB, CommonGameAreas.DEALS_TAB_STRIP, 1);
    }

    private boolean eventsOpen() {
        return locate(TemplatesEnum.EVENTS_HEADER, EVENTS_HEADER_AREA, 3).isFound();
    }

    private boolean onEventPage() {
        return locate(TemplatesEnum.WINTER_SIEGE_TITLE, TITLE_AREA, 2).isFound();
    }

    private boolean missionsShown() {
        return locate(TemplatesEnum.WINTER_SIEGE_MISSIONS_TITLE, MISSIONS_TITLE_AREA, 2).isFound();
    }

    private ImageSearchResultData locate(TemplatesEnum template, AreaData area, int attempts) {
        return templateSearchHelper.locatePattern(template, SearchConfig.builder()
                .withArea(area)
                .withThreshold(TEMPLATE_THRESHOLD)
                .withMaxAttempts(attempts)
                .withDelay(400)
                .build());
    }

    private Optional<BufferedImage> frame() {
        RawImageData raw = emuManager.captureScreen(EMULATOR_NUMBER);
        if (raw == null) {
            frameMissing = true;
            return Optional.empty();
        }
        return Optional.of(ImageConverter.toBufferedImage(raw));
    }

    private String snapshot(String type) {
        return TaskDiagnosticSnapshots.capture(emuManager, EMULATOR_NUMBER, "wintersiege", type);
    }
}
