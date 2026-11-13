package dev.frostguard.tasks.events;

import java.awt.image.BufferedImage;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
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
import dev.frostguard.tasks.events.RedDotVision.Blob;
import dev.frostguard.vision.convert.ImageConverter;

/**
 * Hero Rally, a leveled reward track filled by normal play. It is a tab in the Deals panel, opened from the
 * Deals button on the City rail; the strip opens on whatever tab was last used, so the tab is found by its
 * picture with a walk along the strip both ways ({@link DealsTabStrip}).
 *
 * <p>Each level row has a Free reward, a locked Path of Honor reward and one button on the right. A ready
 * row shows a green Claim; a claimed row turns into an orange Continue, which is a Path of Honor upsell, and
 * a row the account has not reached shows a blue Obtain Points. Only a green pill that also matches the Claim
 * template is tapped, plus Claim All when the game batches several levels. Continue, Obtain Points, Unlock,
 * Purchase Level and the "+" are never tapped.
 *
 * <p>The unclaimed rows sit between the claimed ones (Continue) and the unreached ones (Obtain Points), so
 * after claiming what is on screen the list is scrolled toward that boundary until both kinds show at once,
 * the list stops moving, or the scroll would turn back.
 */
public class HeroRallyClaimRoutine extends DelayedTask {

    /** The name the State calendar stores this event under, for the gate to find its window. */
    private static final String CALENDAR_EVENT_NAME = "Hero Rally";

    static final AreaData HOME_RAIL_AREA = AreaData.of(540, 80, 719, 700);
    static final AreaData TITLE_AREA = AreaData.of(20, 195, 300, 275);
    static final AreaData CLAIM_ALL_AREA = AreaData.of(0, 195, 719, 1279);
    /** The right-hand button column of the level rows, below the sticky Free / Path of Honor header. */
    static final AreaData ROW_BUTTON_AREA = AreaData.of(500, 630, 700, 1262);
    static final AreaData LIST_AREA = AreaData.of(20, 630, 700, 1262);

    // Drags on the gap between the level line and the Free column, so a drag never starts on a reward.
    private static final PointData LIST_LOW = new PointData(115, 1150);
    private static final PointData LIST_HIGH = new PointData(115, 750);
    // The blank Deals header right of its title: closes a reward reveal, and does nothing on the page itself.
    private static final PointData REVEAL_DISMISS = new PointData(450, 40);

    // A pill in the row column, measured on live frames: Claim 148x52 of (37,183,86), Continue 146x53 of
    // (255,190,40), Obtain Points 146x54 of (79,165,252); a pill cut by the list edge keeps 30 px of height.
    static final int PILL_MIN_WIDTH = 110;
    static final int PILL_MAX_WIDTH = 180;
    static final int PILL_MIN_HEIGHT = 24;
    static final int PILL_MIN_PIXELS = 1500;
    static final int CLAIM_BOX_MARGIN = 8;
    private static final int ROW_BAND_REACH = 60;
    // A claim changes its row band by 9 or more; the same row a moment later differs by under 1.
    static final double STILL_ROW_DIFF = 3.0;
    private static final double STILL_LIST_DIFF = 2.0;

    private static final int TEMPLATE_THRESHOLD = 85;
    private static final int MAX_CLAIMS = 12;
    private static final int MAX_LIST_MOVES = 12;
    private static final int PANEL_SETTLE_MS = 2000;
    private static final int PAGE_SETTLE_MS = 1500;
    private static final int CLAIM_SETTLE_MS = 1500;
    private static final int ACTION_SETTLE_MS = 900;
    private static final int LIST_SWIPE_MS = 600;
    private static final int LIST_SETTLE_MS = 1000;

    enum Outcome { CLAIMED, NOTHING_TO_CLAIM, EVENT_ABSENT, UNKNOWN }

    enum ListMove { DONE, EARLIER, LATER }

    private int claimed;
    private boolean frameMissing;

    public HeroRallyClaimRoutine(AccountDescriptor profile, TpDailyTaskEnum tpDailyTask) {
        super(profile, tpDailyTask);
    }

    @Override
    protected LaunchPoint getRequiredStartLocation() {
        return LaunchPoint.HOME;
    }

    @Override
    public boolean provideDailyMissionProgress() {
        return true;
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
            StatisticsService.obtain().addToCounter(profile, "Hero Rally Claimed", claimed);
        }
        if (outcome == Outcome.UNKNOWN) {
            reportFailedAttempt("Hero Rally outcome unknown");
        }
        LocalDateTime next = nextSlot();
        logInfo(logLine(outcome + ", claimed " + claimed + ". Rechecking after the next reset: "
                + next.format(DATETIME_FORMATTER) + "."));
        reschedule(next);
    }

    /**
     * Waits for the event instead of walking the Deals panel when the calendar says it is not on.
     *
     * <p>There is nothing to claim while Hero Rally is not running, and the walk costs a panel open
     * and a strip walk every pass to find that out.</p>
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

    // Claimed shortly after the daily reset, in its own slot, so the late claims do not pile up.
    private LocalDateTime nextSlot() {
        return PostResetClaimSlots.next(PostResetClaimSlots.HERO_RALLY);
    }

    private Outcome visit() {
        ImageSearchResultData deals = locate(TemplatesEnum.HOME_DEALS_BUTTON, HOME_RAIL_AREA, 3);
        if (!deals.isFound()) {
            logWarning(logLine("Deals button not found on the City rail; " + snapshot("deals-button-missing")));
            return Outcome.UNKNOWN;
        }
        tapInside(deals);
        sleepTask(PANEL_SETTLE_MS);
        if (!dealsOpen()) {
            logWarning(logLine("Tapped Deals but its header did not show; " + snapshot("deals-not-open")));
            return Outcome.UNKNOWN;
        }
        if (!onHeroRallyPage()) {
            ImageSearchResultData tab = findTab();
            if (frameMissing) {
                logWarning(logLine("No screen capture while walking the Deals tab strip."));
                return Outcome.UNKNOWN;
            }
            if (!tab.isFound()) {
                logInfo(logLine("Hero Rally not in the Deals strip (event not running)."));
                return Outcome.EVENT_ABSENT;
            }
            tapInside(tab);
            sleepTask(PAGE_SETTLE_MS);
            if (!onHeroRallyPage()) {
                logWarning(logLine("Tapped the Hero Rally tab but the page did not open; " + snapshot("page-not-open")));
                return Outcome.UNKNOWN;
            }
        }
        claimAllIfOffered();
        return claimTrack();
    }

    /** Claim All shows only when several levels are ready at once and the game batches them. */
    private void claimAllIfOffered() {
        ImageSearchResultData claimAll = locate(TemplatesEnum.EVENTS_HERO_RALLY_CLAIM_ALL, CLAIM_ALL_AREA, 2);
        if (!claimAll.isFound()) {
            return;
        }
        logInfo(logLine("Claim All found. Claiming."));
        tapInside(claimAll);
        sleepTask(CLAIM_SETTLE_MS);
        tapNear(REVEAL_DISMISS, 4);
        sleepTask(ACTION_SETTLE_MS);
        claimed++;
    }

    /**
     * Claims what is on screen, then scrolls toward the unclaimed rows and claims again, until the boundary
     * between claimed and unreached rows is in view, the list stops, or the scroll would turn back.
     */
    private Outcome claimTrack() {
        ListMove previous = null;
        for (int step = 0; step <= MAX_LIST_MOVES; step++) {
            if (!claimVisibleRows()) {
                return Outcome.UNKNOWN;
            }
            Optional<BufferedImage> view = frame();
            if (view.isEmpty()) {
                logWarning(logLine("No screen capture while reading the level rows."));
                return Outcome.UNKNOWN;
            }
            ListMove move = nextMove(view.get());
            if (move == ListMove.DONE || (previous != null && move != previous) || step == MAX_LIST_MOVES) {
                break;
            }
            if (move == ListMove.LATER) {
                swipe(LIST_LOW, LIST_HIGH, LIST_SWIPE_MS);
            } else {
                swipe(LIST_HIGH, LIST_LOW, LIST_SWIPE_MS);
            }
            sleepTask(LIST_SETTLE_MS);
            Optional<BufferedImage> after = frame();
            if (after.isEmpty()) {
                logWarning(logLine("No screen capture after scrolling the level rows."));
                return Outcome.UNKNOWN;
            }
            if (RedDotVision.meanDifference(view.get(), after.get(), LIST_AREA) < STILL_LIST_DIFF) {
                break;
            }
            previous = move;
        }
        if (claimed == 0) {
            logInfo(logLine("Nothing ready to claim right now."));
            return Outcome.NOTHING_TO_CLAIM;
        }
        return Outcome.CLAIMED;
    }

    /** Taps each confirmed Claim on screen in turn. False when a claim left the page or changed nothing. */
    private boolean claimVisibleRows() {
        for (int i = 0; i < MAX_CLAIMS; i++) {
            Optional<BufferedImage> page = frame();
            if (page.isEmpty()) {
                logWarning(logLine("No screen capture while reading the level rows."));
                return false;
            }
            Optional<Blob> row = firstClaimRow(page.get());
            if (row.isEmpty()) {
                return true;
            }
            Blob pill = row.get();
            logInfo(logLine("Claim found at (" + pill.centerX() + "," + pill.centerY() + "). Claiming."));
            tapNear(new PointData(pill.centerX(), pill.centerY()), 6);
            sleepTask(CLAIM_SETTLE_MS);
            tapNear(REVEAL_DISMISS, 4);
            sleepTask(ACTION_SETTLE_MS);
            if (!onHeroRallyPage()) {
                logWarning(logLine("A claim left the Hero Rally page on an unknown screen; " + snapshot("claim-left-page")));
                return false;
            }
            Optional<BufferedImage> after = frame();
            if (after.isEmpty()) {
                logWarning(logLine("No screen capture after a claim."));
                return false;
            }
            if (!rowChanged(page.get(), after.get(), pill)) {
                logWarning(logLine("Tapped Claim at (" + pill.centerX() + "," + pill.centerY()
                        + ") but its row did not change; " + snapshot("claim-unconfirmed")));
                return false;
            }
            claimed++;
        }
        logWarning(logLine("Hit the " + MAX_CLAIMS + "-claim cap on one screen."));
        return true;
    }

    /** The top green pill in the row column that also reads "Claim". */
    private Optional<Blob> firstClaimRow(BufferedImage page) {
        for (Blob pill : greenPills(page)) {
            if (locate(TemplatesEnum.EVENTS_CLAIM_BUTTON, claimBox(pill), 1).isFound()) {
                return Optional.of(pill);
            }
        }
        return Optional.empty();
    }

    /** Green pills in the row column, top to bottom; Continue (orange) and Obtain Points (blue) never qualify. */
    static List<Blob> greenPills(BufferedImage img) {
        List<Blob> pills = new ArrayList<>();
        for (Blob blob : RedDotVision.greenButtons(img, ROW_BUTTON_AREA)) {
            if (blob.width() >= PILL_MIN_WIDTH && blob.width() <= PILL_MAX_WIDTH) {
                pills.add(blob);
            }
        }
        return pills;
    }

    /** Where the Claim template is looked for around a green pill. */
    static AreaData claimBox(Blob pill) {
        return AreaData.of(Math.max(0, pill.left() - CLAIM_BOX_MARGIN), Math.max(0, pill.top() - CLAIM_BOX_MARGIN),
                Math.min(719, pill.right() + CLAIM_BOX_MARGIN), Math.min(1279, pill.bottom() + CLAIM_BOX_MARGIN));
    }

    /** True when the band of rows around the tapped pill looks different afterwards. */
    static boolean rowChanged(BufferedImage before, BufferedImage after, Blob pill) {
        AreaData band = AreaData.of(LIST_AREA.topLeft().getX(), Math.max(0, pill.top() - ROW_BAND_REACH),
                LIST_AREA.bottomRight().getX(), Math.min(1279, pill.bottom() + ROW_BAND_REACH));
        return RedDotVision.meanDifference(before, after, band) >= STILL_ROW_DIFF;
    }

    /** Which way the unclaimed rows lie, judged from the pills on screen once its Claims are taken. */
    static ListMove nextMove(BufferedImage img) {
        return nextMove(hasPill(img, HeroRallyClaimRoutine::isContinueOrange),
                hasPill(img, HeroRallyClaimRoutine::isObtainBlue));
    }

    /**
     * Claimed rows (Continue) come before the unclaimed ones and unreached rows (Obtain Points) after them, so
     * seeing both means nothing is left in between. Seeing neither means the screen is not understood.
     */
    static ListMove nextMove(boolean continueSeen, boolean obtainSeen) {
        if (continueSeen == obtainSeen) {
            return ListMove.DONE;
        }
        return obtainSeen ? ListMove.EARLIER : ListMove.LATER;
    }

    static boolean hasPill(BufferedImage img, RedDotVision.PixelTest colour) {
        for (Blob blob : RedDotVision.components(img, ROW_BUTTON_AREA, colour)) {
            if (blob.width() >= PILL_MIN_WIDTH && blob.width() <= PILL_MAX_WIDTH
                    && blob.height() >= PILL_MIN_HEIGHT && blob.pixels() >= PILL_MIN_PIXELS) {
                return true;
            }
        }
        return false;
    }

    static boolean isContinueOrange(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        return r >= 235 && g >= 150 && g <= 215 && b <= 90 && r - b >= 170;
    }

    static boolean isObtainBlue(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        return b >= 225 && g >= 130 && g <= 200 && r <= 120 && b - r >= 120;
    }

    private ImageSearchResultData findTab() {
        return DealsTabStrip.find(DealsTabStrip.walker(this,
                () -> locate(TemplatesEnum.HERO_RALLY_DEALS_TAB, CommonGameAreas.DEALS_TAB_STRIP, 1),
                this::frame, () -> sleepTask(DealsTabStrip.SETTLE_MS)));
    }

    /** The page title, or the selected tab when the banner is out of view. */
    private boolean onHeroRallyPage() {
        return locate(TemplatesEnum.HERO_RALLY_TITLE, TITLE_AREA, 3).isFound()
                || locate(TemplatesEnum.EVENTS_TAB_HERO_RALLY, CommonGameAreas.DEALS_TAB_STRIP, 1).isFound();
    }

    private boolean dealsOpen() {
        return locate(TemplatesEnum.DEALS_TITLE, CommonGameAreas.DEALS_HEADER, 3).isFound();
    }

    /**
     * Leaves Deals through its back arrow while its header shows. The first tap also closes a reward reveal
     * left on top, so the arrow is checked and tapped at most twice.
     */
    private void leave() {
        for (int i = 0; i < 2 && dealsOpen(); i++) {
            tapNear(CommonGameAreas.DEALS_BACK_ARROW, 4);
            sleepTask(PAGE_SETTLE_MS);
        }
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
        return TaskDiagnosticSnapshots.capture(emuManager, EMULATOR_NUMBER, "herorally", type);
    }

    private String logLine(String note) {
        return "HeroRallyClaimRoutine | " + note;
    }
}
