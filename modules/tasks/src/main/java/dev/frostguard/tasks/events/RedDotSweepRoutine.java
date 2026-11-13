package dev.frostguard.tasks.events;

import java.awt.image.BufferedImage;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.engine.helper.TemplateSearchHelper.SearchConfig;
import dev.frostguard.engine.nav.CommonGameAreas;
import dev.frostguard.engine.nav.CommonOCRSettings;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.engine.service.StatisticsService;
import dev.frostguard.tasks.diagnostics.TaskDiagnosticSnapshots;
import dev.frostguard.tasks.events.RedDotSweepPolicy.LabelVerdict;
import dev.frostguard.tasks.events.RedDotSweepPolicy.Outcome;
import dev.frostguard.tasks.events.RedDotSweepPolicy.Panel;
import dev.frostguard.tasks.events.RedDotVision.Blob;
import dev.frostguard.vision.convert.GameTimeUtils;
import dev.frostguard.vision.convert.ImageConverter;

/**
 * Walks the Events, Deals and Gem Shop tabs that show a red dot, shortly after the daily reset and again at
 * midday, and taps the free claims no dedicated task covers: green Claim, Claim All and Free buttons.
 *
 * <p>A candidate is tapped only when it is a green pill that also matches a claim template, so a priced
 * orange button, a blue Top Up Now or an item frame never qualifies. Tabs that belong to a dedicated task
 * (Hall of Chiefs, Dead Shot, Winter Siege and so on) are skipped while that task is enabled, and a page
 * whose banner names Chief Gear, or cannot be read at all, is left untouched. Anything unexpected is saved
 * as a snapshot, logged, and backed out of with a check after every Back; it is never tapped.
 */
public class RedDotSweepRoutine extends DelayedTask {

    static final AreaData HOME_RAIL_AREA = AreaData.of(540, 80, 719, 700);
    static final AreaData CONTENT_AREA = AreaData.of(0, 200, 719, 1245);
    static final AreaData STRIP_DOT_AREA = AreaData.of(0, 96, 719, 135);
    static final AreaData BANNER_AREA = AreaData.of(20, 195, 700, 335);
    static final AreaData STRIP_LABEL_AREA = AreaData.of(0, 140, 719, 185);
    static final AreaData CART_AREA = AreaData.of(380, 60, 719, 700);
    static final AreaData HEADER_AREA = CommonGameAreas.DEALS_HEADER;
    private static final PointData LIST_LOW = new PointData(360, 1120);
    private static final PointData LIST_HIGH = new PointData(360, 620);

    private static final int TEMPLATE_THRESHOLD = 85;
    private static final int PANEL_SETTLE_MS = 1800;
    private static final int PAGE_SETTLE_MS = 1300;
    private static final int CLAIM_SETTLE_MS = 1600;
    private static final int STRIP_SWIPE_MS = 600;
    private static final int STRIP_SETTLE_MS = 1100;
    private static final int LIST_SWIPE_MS = 500;
    private static final int LIST_SETTLE_MS = 1000;
    private static final int MAX_CLAIMS_PER_PAGE = 8;
    private static final int MAX_SCROLLS_PER_PAGE = 3;
    private static final int MAX_STRIP_SWIPES = 14;
    private static final int STILL_SWIPES_FOR_END = 2;
    private static final int MAX_TABS_PER_PANEL = 20;
    private static final double STILL_STRIP_DIFF = 3.0;
    private static final double STILL_LIST_DIFF = 2.0;
    private static final int BUTTON_BOX_MARGIN = 8;
    private static final int TIP_LABEL_REACH = 90;
    private static final int TIP_SETTLE_MS = 1200;
    private static final int TAB_BOX_HALF_WIDTH = 105;

    /** The templates that name a free claim. Anything else green is not tapped. */
    private static final List<TemplatesEnum> CLAIM_TEMPLATES = List.of(
            TemplatesEnum.EVENTS_CLAIM_BUTTON,
            TemplatesEnum.EVENTS_HERO_RALLY_CLAIM_ALL,
            TemplatesEnum.EVENTS_LUCKY_CHIP_FREE_BUTTON);

    /** A tab with its own task: left alone while that task is on. */
    private record CoveredTab(TemplatesEnum tab, TpDailyTaskEnum task) {
    }

    private static final List<CoveredTab> COVERED_TABS = List.of(
            new CoveredTab(TemplatesEnum.EVENTS_TAB_HALL_OF_CHIEFS, TpDailyTaskEnum.EVENT_HALL_OF_CHIEFS),
            new CoveredTab(TemplatesEnum.EVENTS_TAB_DEFEAT_BEASTS, TpDailyTaskEnum.EVENT_DEFEAT_BEASTS),
            new CoveredTab(TemplatesEnum.EVENTS_TAB_BROTHERS_IN_ARMS, TpDailyTaskEnum.EVENT_BROTHERS_IN_ARMS),
            new CoveredTab(TemplatesEnum.HERO_RALLY_DEALS_TAB, TpDailyTaskEnum.EVENT_HERO_RALLY_CLAIM),
            new CoveredTab(TemplatesEnum.EVENTS_TAB_LUCKY_WHEEL, TpDailyTaskEnum.EVENT_LUCKY_CHIP_SUPPLY),
            new CoveredTab(TemplatesEnum.DEAD_SHOT_TAB, TpDailyTaskEnum.EVENT_DEAD_SHOT_SIGN_IN),
            new CoveredTab(TemplatesEnum.FORTUNE_HUT_TAB, TpDailyTaskEnum.EVENT_FORTUNE_HUT),
            new CoveredTab(TemplatesEnum.WINTER_SIEGE_TAB, TpDailyTaskEnum.EVENT_WINTER_SIEGE));

    private int failedAttempts;
    private int claimed;
    private int skippedTabs;
    private int unknownDots;
    private int tipsCleared;
    private boolean snapshotTaken;
    private final List<int[]> visitedTabs = new ArrayList<>();

    public RedDotSweepRoutine(AccountDescriptor profile, TpDailyTaskEnum tpTask) {
        super(profile, tpTask);
    }

    @Override
    public LaunchPoint getRequiredStartLocation() {
        return LaunchPoint.HOME;
    }

    @Override
    protected void execute() {
        claimed = 0;
        skippedTabs = 0;
        unknownDots = 0;
        tipsCleared = 0;
        snapshotTaken = false;
        logDedicatedPanels();
        Outcome outcome = Outcome.CLEAN;
        for (Panel panel : Panel.values()) {
            visitedTabs.clear();
            if (sweep(panel) == Outcome.UNKNOWN) {
                outcome = Outcome.UNKNOWN;
            }
            leave(panel);
        }
        if (claimed > 0) {
            StatisticsService.obtain().addToCounter(profile, "Red-dot Sweep Claimed", claimed);
        }
        if (tipsCleared > 0) {
            StatisticsService.obtain().addToCounter(profile, "Red-dot Sweep Tips Cleared", tipsCleared);
        }
        failedAttempts = outcome == Outcome.UNKNOWN ? failedAttempts + 1 : 0;
        if (outcome == Outcome.UNKNOWN) {
            reportFailedAttempt("Red-dot sweep ended on a screen it did not expect");
        }
        LocalDateTime next = RedDotSweepPolicy.nextRun(LocalDateTime.now(), GameTimeUtils.dailyResetTime(),
                ThreadLocalRandom.current().nextInt(RedDotSweepPolicy.RESET_JITTER_MAX_MINUTES + 1),
                outcome, failedAttempts);
        logInfo("Red-dot sweep: " + outcome + ", claimed " + claimed + ", skipped " + skippedTabs
                + " covered or unnamed tab(s), unticked " + tipsCleared + " Tips box(es), " + unknownDots + " unexplained dot(s). Next pass at "
                + next.format(DATETIME_FORMATTER) + ".");
        rescheduleExact(next);
    }

    private void logDedicatedPanels() {
        for (RedDotSweepPolicy.Dedicated dedicated : RedDotSweepPolicy.DEDICATED_PANELS) {
            logInfo("Red-dot sweep leaves " + dedicated.panel() + " to " + dedicated.task().displayText()
                    + (taskEnabled(dedicated.task()) ? "." : " (that task is off, so nobody claims it)."));
        }
    }

    private boolean taskEnabled(TpDailyTaskEnum task) {
        return task.activationSwitch() != null
                && Boolean.TRUE.equals(profile.getConfig(task.activationSwitch(), Boolean.class));
    }

    // ---- one panel ----

    private Outcome sweep(Panel panel) {
        if (!open(panel)) {
            return Outcome.UNKNOWN;
        }
        Outcome outcome = claimPage(panel, "opening tab");
        if (rewindStrip(panel) == Outcome.UNKNOWN) {
            return Outcome.UNKNOWN;
        }
        int still = 0;
        int visits = 0;
        for (int swipe = 0; swipe <= MAX_STRIP_SWIPES && visits < MAX_TABS_PER_PANEL; swipe++) {
            Optional<BufferedImage> strip = frame();
            if (strip.isEmpty()) {
                return Outcome.UNKNOWN;
            }
            Optional<Blob> dot = nextDottedTab(panel, strip.get());
            while (dot.isPresent() && visits < MAX_TABS_PER_PANEL) {
                visits++;
                if (visitTab(panel, strip.get(), dot.get()) == Outcome.UNKNOWN) {
                    outcome = Outcome.UNKNOWN;
                    if (!panelOpen(panel)) {
                        return outcome;
                    }
                }
                strip = frame();
                if (strip.isEmpty()) {
                    return Outcome.UNKNOWN;
                }
                dot = nextDottedTab(panel, strip.get());
            }
            Optional<BufferedImage> before = frame();
            swipe(CommonGameAreas.DEALS_TAB_STRIP_SWIPE_FROM, CommonGameAreas.DEALS_TAB_STRIP_SWIPE_TO,
                    STRIP_SWIPE_MS);
            sleepTask(STRIP_SETTLE_MS);
            Optional<BufferedImage> after = frame();
            if (before.isEmpty() || after.isEmpty()) {
                return Outcome.UNKNOWN;
            }
            still = RedDotVision.meanDifference(before.get(), after.get(), CommonGameAreas.DEALS_TAB_STRIP)
                    >= STILL_STRIP_DIFF ? 0 : still + 1;
            if (still >= STILL_SWIPES_FOR_END) {
                break;
            }
        }
        return outcome;
    }

    private Outcome visitTab(Panel panel, BufferedImage strip, Blob dot) {
        visitedTabs.add(RedDotVision.tabSignature(strip, dot));
        tapNear(new PointData(RedDotVision.tabTapX(dot), dot.centerY() + 40), 6);
        sleepTask(PAGE_SETTLE_MS);
        if (!panelOpen(panel)) {
            logWarning("Tapped a " + panel.label() + " tab but the panel header is gone; "
                    + snapshot(panel, "tab-left-panel"));
            return Outcome.UNKNOWN;
        }
        return claimPage(panel, "tab at x=" + dot.centerX());
    }

    /** The first dotted, unselected, unvisited tab that no dedicated task owns. */
    private Optional<Blob> nextDottedTab(Panel panel, BufferedImage strip) {
        List<int[]> covered = coveredTabCentres();
        for (Blob dot : RedDotVision.redDots(strip, STRIP_DOT_AREA)) {
            if (RedDotVision.isSelectedTab(strip, dot)) {
                continue;
            }
            int[] signature = RedDotVision.tabSignature(strip, dot);
            if (visitedTabs.stream().anyMatch(seen -> RedDotVision.sameTab(seen, signature))) {
                continue;
            }
            String tabLabel = tabLabel(dot);
            if (RedDotSweepPolicy.judgeLabel(tabLabel) == LabelVerdict.EXCLUDED) {
                visitedTabs.add(signature);
                skippedTabs++;
                logInfo(panel.label() + " tab \"" + tabLabel.trim() + "\" is excluded by name; never entered.");
                continue;
            }
            int tabCentre = RedDotVision.tabTapX(dot);
            if (covered.stream().anyMatch(c -> Math.abs(c[0] - tabCentre) <= TAB_BOX_HALF_WIDTH)) {
                visitedTabs.add(signature);
                skippedTabs++;
                logInfo(panel.label() + " tab at x=" + dot.centerX()
                        + " has a red dot but a dedicated task owns it; skipped.");
                continue;
            }
            return Optional.of(dot);
        }
        return Optional.empty();
    }

    /** The label under an unselected tab's icon, read before the tab is tapped so an excluded tab is never entered. */
    private String tabLabel(Blob dot) {
        PointData topLeft = new PointData(Math.max(0, dot.left() - 195), STRIP_LABEL_AREA.topLeft().getY());
        PointData bottomRight = new PointData(Math.min(719, dot.left() + 5), STRIP_LABEL_AREA.bottomRight().getY());
        String text = readStringValue(topLeft, bottomRight, CommonOCRSettings.SHOP_TAB_TEXT_SETTINGS);
        return text == null ? "" : text;
    }

    /** Centres of the strip tabs that belong to an enabled dedicated task. */
    private List<int[]> coveredTabCentres() {
        List<int[]> centres = new ArrayList<>();
        for (CoveredTab covered : COVERED_TABS) {
            if (!taskEnabled(covered.task())) {
                continue;
            }
            ImageSearchResultData found = templateSearchHelper.locatePattern(covered.tab(),
                    search(CommonGameAreas.DEALS_TAB_STRIP, 1));
            if (found.isFound()) {
                centres.add(new int[] {found.getX()});
            }
        }
        return centres;
    }

    // ---- one page ----

    /**
     * Reads the page, claims every positively identified free button on it (scrolling a little), and reports
     * what else it could not explain. A page that names Chief Gear, or cannot be named, is not touched.
     */
    private Outcome claimPage(Panel panel, String where) {
        LabelVerdict verdict = RedDotSweepPolicy.judgeLabel(pageLabel());
        if (verdict != LabelVerdict.OK) {
            logInfo(panel.label() + " " + where + ": page label " + verdict + "; not touching it. "
                    + snapshot(panel, "label-" + verdict.name().toLowerCase()));
            return Outcome.CLEAN;
        }
        if (clearTips(panel, where) == Outcome.UNKNOWN) {
            return Outcome.UNKNOWN;
        }
        for (int scroll = 0; scroll <= MAX_SCROLLS_PER_PAGE; scroll++) {
            Outcome claims = claimVisibleButtons(panel, where);
            if (claims == Outcome.UNKNOWN) {
                return Outcome.UNKNOWN;
            }
            Optional<BufferedImage> before = frame();
            if (before.isEmpty() || scroll == MAX_SCROLLS_PER_PAGE) {
                reportUnexplainedDots(panel, before, where);
                break;
            }
            swipe(LIST_LOW, LIST_HIGH, LIST_SWIPE_MS);
            sleepTask(LIST_SETTLE_MS);
            Optional<BufferedImage> after = frame();
            if (after.isEmpty() || RedDotVision.meanDifference(before.get(), after.get(), CONTENT_AREA)
                    < STILL_LIST_DIFF) {
                reportUnexplainedDots(panel, after, where);
                break;
            }
        }
        return Outcome.CLEAN;
    }

    /**
     * A ticked "Tip" checkbox keeps a red dot on its page without any claim waiting. A tick counts only with
     * the Tip label template right beside it, and only that box is tapped: it is a reminder switch, not a
     * claim, and unticking it is what clears the dot. Any other ticked box is left alone.
     */
    private Outcome clearTips(Panel panel, String where) {
        Optional<BufferedImage> page = frame();
        if (page.isEmpty()) {
            return Outcome.UNKNOWN;
        }
        for (Blob tick : RedDotVision.ticks(page.get(), CONTENT_AREA)) {
            AreaData labelBox = AreaData.of(Math.max(0, tick.left() - TIP_LABEL_REACH), Math.max(0, tick.top() - 18),
                    Math.max(0, tick.left() - 2), Math.min(1279, tick.bottom() + 18));
            if (!templateSearchHelper.locatePattern(TemplatesEnum.TIPS_LABEL, search(labelBox, 1)).isFound()) {
                continue;
            }
            logInfo(panel.label() + " " + where + ": a ticked Tip box at (" + tick.centerX() + "," + tick.centerY()
                    + ") is keeping a red dot on; unticking it. It is a reminder switch, not a claim.");
            tapNear(new PointData(tick.centerX(), tick.centerY()), 4);
            sleepTask(TIP_SETTLE_MS);
            Optional<BufferedImage> after = frame();
            if (after.isPresent() && RedDotVision.ticks(after.get(), CONTENT_AREA).stream()
                    .anyMatch(t -> Math.abs(t.centerX() - tick.centerX()) <= 10
                            && Math.abs(t.centerY() - tick.centerY()) <= 10)) {
                logWarning(panel.label() + " " + where + ": the Tip box is still ticked after the tap; "
                        + snapshot(panel, "tip-still-ticked"));
                return Outcome.UNKNOWN;
            }
            tipsCleared++;
        }
        return Outcome.CLEAN;
    }

    private Outcome claimVisibleButtons(Panel panel, String where) {
        for (int i = 0; i < MAX_CLAIMS_PER_PAGE; i++) {
            Optional<BufferedImage> page = frame();
            if (page.isEmpty()) {
                return Outcome.UNKNOWN;
            }
            Optional<Blob> button = firstFreeClaim(page.get());
            if (button.isEmpty()) {
                return Outcome.CLEAN;
            }
            Blob target = button.get();
            tapNear(new PointData(target.centerX(), target.centerY()), 8);
            sleepTask(CLAIM_SETTLE_MS);
            if (!settledOnPanel(panel)) {
                logWarning(panel.label() + " " + where + ": a claim left the panel on an unknown screen; "
                        + snapshot(panel, "claim-popup"));
                return Outcome.UNKNOWN;
            }
            Optional<BufferedImage> after = frame();
            if (after.isPresent() && stillClaimable(after.get(), target)) {
                logWarning(panel.label() + " " + where + ": the claim at (" + target.centerX() + ","
                        + target.centerY() + ") is still green after the tap; " + snapshot(panel, "claim-unconfirmed"));
                return Outcome.UNKNOWN;
            }
            claimed++;
            logInfo(panel.label() + " " + where + ": claimed the free button at (" + target.centerX() + ","
                    + target.centerY() + ").");
        }
        logWarning(panel.label() + " " + where + ": hit the " + MAX_CLAIMS_PER_PAGE + "-claim cap on one page.");
        return Outcome.CLEAN;
    }

    /** A green pill that also matches a claim template inside its own box. */
    private Optional<Blob> firstFreeClaim(BufferedImage page) {
        for (Blob button : RedDotVision.greenButtons(page, CONTENT_AREA)) {
            AreaData box = AreaData.of(Math.max(0, button.left() - BUTTON_BOX_MARGIN),
                    Math.max(0, button.top() - BUTTON_BOX_MARGIN),
                    Math.min(719, button.right() + BUTTON_BOX_MARGIN),
                    Math.min(1279, button.bottom() + BUTTON_BOX_MARGIN));
            for (TemplatesEnum template : CLAIM_TEMPLATES) {
                if (templateSearchHelper.locatePattern(template, search(box, 1)).isFound()) {
                    return Optional.of(button);
                }
            }
        }
        return Optional.empty();
    }

    private static boolean stillClaimable(BufferedImage page, Blob target) {
        for (Blob button : RedDotVision.greenButtons(page, CONTENT_AREA)) {
            if (Math.abs(button.centerX() - target.centerX()) <= 12
                    && Math.abs(button.centerY() - target.centerY()) <= 12) {
                return true;
            }
        }
        return false;
    }

    /**
     * Back after a claim: the panel header should still be showing. A reward popup that covers it is closed
     * with one Back and checked again; if the panel is still not there, the caller stops.
     */
    private boolean settledOnPanel(Panel panel) {
        if (panelOpen(panel)) {
            return true;
        }
        pressBack();
        sleepTask(PAGE_SETTLE_MS);
        return panelOpen(panel);
    }

    private void reportUnexplainedDots(Panel panel, Optional<BufferedImage> page, String where) {
        if (page.isEmpty()) {
            return;
        }
        List<Blob> greens = RedDotVision.greenButtons(page.get(), CONTENT_AREA);
        for (Blob dot : RedDotVision.redDots(page.get(), CONTENT_AREA)) {
            if (greens.stream().noneMatch(g -> dot.near(g, 12))) {
                unknownDots++;
                logInfo(panel.label() + " " + where + ": red dot at (" + dot.centerX() + "," + dot.centerY()
                        + ") that is not on a claim button; left alone."
                        + (snapshotTaken ? "" : " " + snapshot(panel, "unexplained-dot")));
                snapshotTaken = true;
            }
        }
    }

    /** The banner and tab-label text, for the Chief Gear guard. */
    private String pageLabel() {
        String banner = readStringValue(BANNER_AREA.topLeft(), BANNER_AREA.bottomRight(),
                CommonOCRSettings.DEAL_PAGE_TEXT_SETTINGS);
        String strip = readStringValue(STRIP_LABEL_AREA.topLeft(), STRIP_LABEL_AREA.bottomRight(),
                CommonOCRSettings.DEAL_PAGE_TEXT_SETTINGS);
        return (banner == null ? "" : banner) + " " + (strip == null ? "" : strip);
    }

    // ---- opening, rewinding, leaving ----

    private boolean open(Panel panel) {
        ImageSearchResultData button = switch (panel) {
            case EVENTS -> templateSearchHelper.locatePattern(TemplatesEnum.HOME_EVENTS_BUTTON,
                    search(HOME_RAIL_AREA, 3));
            case DEALS -> templateSearchHelper.locatePattern(TemplatesEnum.HOME_DEALS_BUTTON,
                    search(HOME_RAIL_AREA, 3));
            case GEM_SHOP -> templateSearchHelper.locatePattern(TemplatesEnum.HOME_SHOP_CART_BUTTON,
                    search(CART_AREA, 3));
        };
        if (!button.isFound()) {
            logWarning(panel.label() + " button not found; " + snapshot(panel, "button-missing"));
            return false;
        }
        tapInside(button);
        sleepTask(PANEL_SETTLE_MS);
        if (!panelOpen(panel)) {
            logWarning("Tapped " + panel.label() + " but its header did not show; " + snapshot(panel, "not-open"));
            return false;
        }
        return true;
    }

    private boolean panelOpen(Panel panel) {
        TemplatesEnum header = switch (panel) {
            case EVENTS -> TemplatesEnum.EVENTS_HEADER;
            case DEALS -> TemplatesEnum.DEALS_TITLE;
            case GEM_SHOP -> TemplatesEnum.GEM_SHOP_HEADER;
        };
        AreaData area = panel == Panel.GEM_SHOP ? AreaData.of(0, 0, 330, 90) : HEADER_AREA;
        return templateSearchHelper.locatePattern(header, search(area, 3)).isFound();
    }

    /** Swipes the tab strip toward its start so the walk covers every tab from the left. */
    private Outcome rewindStrip(Panel panel) {
        int still = 0;
        for (int swipe = 0; swipe < MAX_STRIP_SWIPES && still < STILL_SWIPES_FOR_END; swipe++) {
            Optional<BufferedImage> before = frame();
            swipe(CommonGameAreas.DEALS_TAB_STRIP_SWIPE_TO, CommonGameAreas.DEALS_TAB_STRIP_SWIPE_FROM,
                    STRIP_SWIPE_MS);
            sleepTask(STRIP_SETTLE_MS);
            Optional<BufferedImage> after = frame();
            if (before.isEmpty() || after.isEmpty()) {
                return Outcome.UNKNOWN;
            }
            still = RedDotVision.meanDifference(before.get(), after.get(), CommonGameAreas.DEALS_TAB_STRIP)
                    >= STILL_STRIP_DIFF ? 0 : still + 1;
        }
        if (!panelOpen(panel)) {
            logWarning(panel.label() + " header lost while rewinding the strip; " + snapshot(panel, "rewind-lost"));
            return Outcome.UNKNOWN;
        }
        return Outcome.CLEAN;
    }

    /** Back arrow while the header shows, then a checked Back or two, then the framework's own recovery. */
    private void leave(Panel panel) {
        for (int attempt = 0; attempt < 3; attempt++) {
            if (!panelOpen(panel)) {
                break;
            }
            tapNear(CommonGameAreas.DEALS_BACK_ARROW, 4);
            sleepTask(PAGE_SETTLE_MS);
        }
        if (panelOpen(panel)) {
            logWarning(panel.label() + " still open after its back arrow; pressing Back.");
            pressBack();
            sleepTask(PAGE_SETTLE_MS);
        }
        if (panelOpen(panel)) {
            logWarning(panel.label() + " would not close; " + snapshot(panel, "will-not-close"));
        }
        navigationHelper.ensureCorrectScreenLocation(LaunchPoint.HOME);
    }

    // ---- helpers ----

    private static SearchConfig search(AreaData area, int attempts) {
        return SearchConfig.builder()
                .withArea(area)
                .withThreshold(TEMPLATE_THRESHOLD)
                .withMaxAttempts(attempts)
                .withDelay(400)
                .build();
    }

    private Optional<BufferedImage> frame() {
        RawImageData raw = emuManager.captureScreen(EMULATOR_NUMBER);
        if (raw == null) {
            return Optional.empty();
        }
        return Optional.of(ImageConverter.toBufferedImage(raw));
    }

    private String snapshot(Panel panel, String type) {
        return TaskDiagnosticSnapshots.captureAlways(emuManager, EMULATOR_NUMBER, "reddotsweep",
                panel.name().toLowerCase().replace('_', '-') + "-" + type);
    }
}
