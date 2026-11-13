package dev.frostguard.tasks.events;

import java.awt.image.BufferedImage;
import java.time.Duration;
import java.time.LocalDateTime;
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
import dev.frostguard.engine.service.StatisticsService;
import dev.frostguard.tasks.diagnostics.TaskDiagnosticSnapshots;
import dev.frostguard.vision.convert.GameTimeUtils;
import dev.frostguard.vision.convert.ImageConverter;

/**
 * Dead Shot's free Sign-in Rewards. Dead Shot is a tab in Deals; its page has a Sign-in Rewards button on
 * the left that opens a 12-day grid, and the day waiting to be claimed is the tile with the glowing
 * pale-yellow frame. Once a day, an hour after the 00:00 UTC reset, the routine opens Deals, finds the tab
 * by its picture (the strip opens wherever it was left), taps the glowing tile and confirms the tile turned
 * into a claimed one with a green check.
 *
 * <p>Taps are limited to the Deals button, the detected Dead Shot tab, the detected Sign-in Rewards button,
 * a glowing tile, the popup's X while the popup title is on screen, and the Deals back arrow. Shoot!, x5,
 * the ticket "+", History and the "!" button are never tapped.
 */
public class DeadShotSignInRoutine extends DelayedTask {

    static final AreaData DEAD_SHOT_TITLE_AREA = AreaData.of(20, 190, 300, 275);
    static final AreaData SIGN_IN_BUTTON_AREA = AreaData.of(0, 300, 220, 700);
    static final AreaData SIGN_IN_TITLE_AREA = AreaData.of(150, 180, 570, 280);
    static final AreaData HOME_RAIL_AREA = AreaData.of(540, 80, 719, 700);
    private static final int TEMPLATE_THRESHOLD = 85;
    // The X sits on the popup's title banner; on the Dead Shot page the same spot is the "!" button,
    // so it is tapped only while the popup title is visible.
    private static final PointData POPUP_CLOSE = new PointData(665, 222);

    // The Sign-in Rewards red dot, measured on 2026-11-10 at (96-110, 435-450): about 29 px right of and
    // 58 px above the centre of the button template.
    private static final int DOT_LEFT = 15;
    private static final int DOT_RIGHT = 45;
    private static final int DOT_TOP = -75;
    private static final int DOT_BOTTOM = -40;
    private static final int DOT_MIN_PIXELS = 60;

    // The 12-day grid inside the popup. A claimable tile has a pale-yellow frame (255,255,152) where every
    // other tile has a cream one (254,210,152); only the green channel tells them apart, so the frame is
    // proven by shape: two long vertical edges facing each other, joined by a horizontal edge.
    static final int GRID_LEFT = 40;
    static final int GRID_RIGHT = 680;
    static final int GRID_TOP = 330;
    static final int GRID_BOTTOM = 1095;
    private static final int EDGE_MIN_LENGTH = 100;
    private static final int EDGE_JOIN_GAP = 3;
    private static final int TILE_MIN_WIDTH = 100;
    private static final double EDGE_MIN_OVERLAP = 0.7;
    private static final int HORIZONTAL_EDGE_SLACK = 8;
    private static final double HORIZONTAL_EDGE_FRACTION = 0.6;
    // A claimed tile carries a green check: 631 such pixels on the claimed Day 2, none on unclaimed tiles.
    private static final int CHECK_MIN_PIXELS = 300;
    private static final int GLOW_LOOKS = 3;
    private static final int GLOW_LOOK_DELAY_MS = 500;
    private static final int MAX_CLAIMS = 12;

    private static final int PANEL_SETTLE_MS = 2000;
    private static final int PAGE_SETTLE_MS = 1500;
    private static final int CLAIM_SETTLE_MS = 2500;
    private static final int CLAIM_LOOKS = 3;
    private static final int CLAIM_LOOK_DELAY_MS = 1000;

    static final Duration RUN_AFTER_RESET = Duration.ofHours(1);
    static final Duration FAILURE_RETRY = Duration.ofMinutes(30);
    static final int MAX_FAILED_ATTEMPTS = 4;

    enum Outcome { CLAIMED, NOTHING_TO_CLAIM, EVENT_ABSENT, UNKNOWN }

    record Tile(int left, int top, int right, int bottom) {
        PointData center() {
            return new PointData((left + right) / 2, (top + bottom) / 2);
        }

        boolean overlaps(Tile other) {
            return left < other.right && other.left < right && top < other.bottom && other.top < bottom;
        }
    }

    private record Edge(int x, int top, int bottom) {
    }

    private int failedAttempts;
    private int claimed;
    private boolean frameMissing;

    public DeadShotSignInRoutine(AccountDescriptor profile, TpDailyTaskEnum tpTask) {
        super(profile, tpTask);
    }

    @Override
    public LaunchPoint getRequiredStartLocation() {
        return LaunchPoint.HOME;
    }

    @Override
    protected void execute() {
        claimed = 0;
        frameMissing = false;
        Outcome outcome = visit();
        leave();
        if (claimed > 0) {
            StatisticsService.obtain().addToCounter(profile, "Dead Shot Sign-in Claimed", claimed);
        }
        failedAttempts = outcome == Outcome.UNKNOWN ? failedAttempts + 1 : 0;
        LocalDateTime next = nextRun(outcome, failedAttempts, LocalDateTime.now(), GameTimeUtils.dailyResetTime());
        if (outcome == Outcome.UNKNOWN) {
            reportFailedAttempt("Dead Shot sign-in outcome unknown");
            if (failedAttempts >= MAX_FAILED_ATTEMPTS) {
                logWarning("Dead Shot sign-in still unknown after " + failedAttempts
                        + " attempts; giving up until the next daily run.");
                failedAttempts = 0;
            }
        }
        logInfo("Dead Shot sign-in: " + outcome + ". Next pass at " + next.format(DATETIME_FORMATTER) + ".");
        rescheduleExact(next);
    }

    /**
     * An hour after the next reset, except that an unknown outcome is retried after 30 minutes, at most
     * {@link #MAX_FAILED_ATTEMPTS} times, and never later than that daily run.
     */
    static LocalDateTime nextRun(Outcome outcome, int failedAttempts, LocalDateTime now, LocalDateTime nextReset) {
        LocalDateTime daily = nextReset.plus(RUN_AFTER_RESET);
        if (outcome != Outcome.UNKNOWN || failedAttempts >= MAX_FAILED_ATTEMPTS) {
            return daily;
        }
        LocalDateTime retry = now.plus(FAILURE_RETRY);
        return retry.isBefore(daily) ? retry : daily;
    }

    private Outcome visit() {
        ImageSearchResultData deals = templateSearchHelper.locatePattern(TemplatesEnum.HOME_DEALS_BUTTON,
                search(HOME_RAIL_AREA, 3));
        if (!deals.isFound()) {
            logWarning("Deals button not found on the City rail; " + snapshot("deals-button-missing"));
            return Outcome.UNKNOWN;
        }
        tapInside(deals);
        sleepTask(PANEL_SETTLE_MS);
        if (!dealsOpen()) {
            logWarning("Tapped Deals but its header did not show; " + snapshot("deals-not-open"));
            return Outcome.UNKNOWN;
        }
        if (!onDeadShotPage()) {
            ImageSearchResultData tab = findTab();
            if (frameMissing) {
                logWarning("No screen capture while walking the Deals tab strip.");
                return Outcome.UNKNOWN;
            }
            if (!tab.isFound()) {
                logInfo("No Dead Shot tab anywhere in the Deals strip: the event is not running; "
                        + snapshot("tab-absent"));
                return Outcome.EVENT_ABSENT;
            }
            tapInside(tab);
            sleepTask(PAGE_SETTLE_MS);
            if (!onDeadShotPage()) {
                logWarning("Tapped the Dead Shot tab but the page did not open; " + snapshot("page-not-open"));
                return Outcome.UNKNOWN;
            }
        }
        return claimFromPage();
    }

    private Outcome claimFromPage() {
        ImageSearchResultData button = templateSearchHelper.locatePattern(TemplatesEnum.DEAD_SHOT_SIGN_IN_BUTTON,
                search(SIGN_IN_BUTTON_AREA, 3));
        if (!button.isFound()) {
            logWarning("Dead Shot page open but no Sign-in Rewards button; " + snapshot("signin-button-missing"));
            return Outcome.UNKNOWN;
        }
        Optional<BufferedImage> page = frame();
        boolean redDot = page.isPresent() && hasRedDot(page.get(), button.getPoint());
        tapInside(button);
        sleepTask(PAGE_SETTLE_MS);
        if (!signInPopupShown()) {
            logWarning("Tapped Sign-in Rewards but its popup did not open; " + snapshot("popup-not-open"));
            return Outcome.UNKNOWN;
        }
        for (int i = 0; i < MAX_CLAIMS; i++) {
            Optional<Tile> tile = lookForGlow();
            if (frameMissing) {
                logWarning("No screen capture while reading the Sign-in Rewards grid.");
                return Outcome.UNKNOWN;
            }
            if (tile.isEmpty()) {
                break;
            }
            tapNear(tile.get().center(), 10);
            sleepTask(CLAIM_SETTLE_MS);
            if (!tileClaimed(tile.get())) {
                logWarning("Tapped the glowing Sign-in Rewards tile at " + tile.get()
                        + " but it did not turn claimed; " + snapshot("claim-unconfirmed"));
                return Outcome.UNKNOWN;
            }
            claimed++;
            logInfo("Claimed the Dead Shot Sign-in Rewards tile at " + tile.get() + ".");
        }
        if (claimed > 0) {
            return Outcome.CLAIMED;
        }
        if (redDot) {
            logWarning("Sign-in Rewards shows a red dot but no tile glows; " + snapshot("dot-without-glow"));
            return Outcome.UNKNOWN;
        }
        logInfo("Nothing to claim in Dead Shot Sign-in Rewards today.");
        return Outcome.NOTHING_TO_CLAIM;
    }

    /** Closes the popup with its X and leaves Deals through its back arrow, each only when it is on screen. */
    private void leave() {
        if (signInPopupShown()) {
            tapNear(POPUP_CLOSE, 4);
            sleepTask(PAGE_SETTLE_MS);
            if (signInPopupShown()) {
                logWarning("Sign-in Rewards popup still open after its X; pressing Back.");
                pressBack();
                sleepTask(PAGE_SETTLE_MS);
            }
        }
        if (dealsOpen()) {
            tapNear(CommonGameAreas.DEALS_BACK_ARROW, 4);
            sleepTask(PAGE_SETTLE_MS);
        }
    }

    /** Walks the Deals strip both ways for the Dead Shot tab; see {@link DealsTabStrip}. */
    private ImageSearchResultData findTab() {
        return DealsTabStrip.find(DealsTabStrip.walker(this, this::locateTab, this::frame,
                () -> sleepTask(DealsTabStrip.SETTLE_MS)));
    }

    private ImageSearchResultData locateTab() {
        return templateSearchHelper.locatePattern(TemplatesEnum.DEAD_SHOT_TAB,
                search(CommonGameAreas.DEALS_TAB_STRIP, 1));
    }

    private boolean dealsOpen() {
        return templateSearchHelper.locatePattern(TemplatesEnum.DEALS_TITLE,
                search(CommonGameAreas.DEALS_HEADER, 3)).isFound();
    }

    /** The page title, or the selected tab when the page art has moved under the title. */
    private boolean onDeadShotPage() {
        return templateSearchHelper.locatePattern(TemplatesEnum.DEAD_SHOT_TITLE,
                search(DEAD_SHOT_TITLE_AREA, 3)).isFound()
                || templateSearchHelper.locatePattern(TemplatesEnum.DEAD_SHOT_TAB_SELECTED,
                search(CommonGameAreas.DEALS_TAB_STRIP, 1)).isFound();
    }

    private boolean signInPopupShown() {
        return templateSearchHelper.locatePattern(TemplatesEnum.DEAD_SHOT_SIGN_IN_TITLE,
                search(SIGN_IN_TITLE_AREA, 3)).isFound();
    }

    private Optional<Tile> lookForGlow() {
        for (int look = 0; look < GLOW_LOOKS; look++) {
            Optional<BufferedImage> img = frame();
            if (img.isEmpty()) {
                return Optional.empty();
            }
            Optional<Tile> tile = claimableTile(img.get());
            if (tile.isPresent()) {
                return tile;
            }
            sleepTask(GLOW_LOOK_DELAY_MS);
        }
        return Optional.empty();
    }

    private boolean tileClaimed(Tile tile) {
        for (int look = 0; look < CLAIM_LOOKS; look++) {
            if (look > 0) {
                sleepTask(CLAIM_LOOK_DELAY_MS);
            }
            if (!signInPopupShown()) {
                continue;
            }
            Optional<BufferedImage> img = frame();
            if (img.isPresent() && isClaimed(img.get(), tile)) {
                return true;
            }
        }
        return false;
    }

    /** No glow left on the tile, and the tile now carries the green claimed check. */
    static boolean isClaimed(BufferedImage img, Tile tile) {
        boolean stillGlows = claimableTile(img).map(tile::overlaps).orElse(false);
        return !stillGlows && greenCheckPixels(img, tile) >= CHECK_MIN_PIXELS;
    }

    /** The first tile in the grid whose frame glows, or empty when nothing is waiting. */
    static Optional<Tile> claimableTile(BufferedImage img) {
        List<Edge> edges = verticalEdges(img);
        for (int i = 0; i < edges.size(); i++) {
            Edge left = edges.get(i);
            for (int j = i + 1; j < edges.size(); j++) {
                Edge right = edges.get(j);
                if (right.x() - left.x() < TILE_MIN_WIDTH || !facing(left, right)) {
                    continue;
                }
                Tile tile = new Tile(left.x(), Math.min(left.top(), right.top()),
                        right.x(), Math.max(left.bottom(), right.bottom()));
                if (hasHorizontalEdge(img, tile)) {
                    return Optional.of(tile);
                }
                break;
            }
        }
        return Optional.empty();
    }

    private static List<Edge> verticalEdges(BufferedImage img) {
        List<Edge> edges = new ArrayList<>();
        int startX = -1;
        int lastX = -1;
        int top = 0;
        int bottom = 0;
        for (int x = GRID_LEFT; x < GRID_RIGHT; x++) {
            int[] run = longestColumnRun(img, x);
            if (run[1] - run[0] < EDGE_MIN_LENGTH) {
                continue;
            }
            if (startX >= 0 && x - lastX > EDGE_JOIN_GAP) {
                edges.add(new Edge(startX, top, bottom));
                startX = -1;
            }
            if (startX < 0) {
                startX = x;
                top = run[0];
                bottom = run[1];
            } else {
                top = Math.min(top, run[0]);
                bottom = Math.max(bottom, run[1]);
            }
            lastX = x;
        }
        if (startX >= 0) {
            edges.add(new Edge(startX, top, bottom));
        }
        return edges;
    }

    private static int[] longestColumnRun(BufferedImage img, int x) {
        int bestStart = 0;
        int bestEnd = 0;
        int start = -1;
        for (int y = GRID_TOP; y <= GRID_BOTTOM; y++) {
            boolean glow = y < GRID_BOTTOM && isGlow(img.getRGB(x, y));
            if (glow && start < 0) {
                start = y;
            } else if (!glow && start >= 0) {
                if (y - start > bestEnd - bestStart) {
                    bestStart = start;
                    bestEnd = y;
                }
                start = -1;
            }
        }
        return new int[] {bestStart, bestEnd};
    }

    private static boolean facing(Edge left, Edge right) {
        int overlap = Math.min(left.bottom(), right.bottom()) - Math.max(left.top(), right.top());
        int shorter = Math.min(left.bottom() - left.top(), right.bottom() - right.top());
        return overlap >= EDGE_MIN_OVERLAP * shorter;
    }

    private static boolean hasHorizontalEdge(BufferedImage img, Tile tile) {
        int needed = (int) (HORIZONTAL_EDGE_FRACTION * (tile.right() - tile.left()));
        for (int edgeY : new int[] {tile.top(), tile.bottom()}) {
            for (int y = Math.max(GRID_TOP, edgeY - HORIZONTAL_EDGE_SLACK);
                    y <= Math.min(GRID_BOTTOM - 1, edgeY + HORIZONTAL_EDGE_SLACK); y++) {
                int run = 0;
                for (int x = tile.left(); x <= tile.right(); x++) {
                    run = isGlow(img.getRGB(x, y)) ? run + 1 : 0;
                    if (run >= needed) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean isGlow(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        return r >= 240 && g >= 225 && b >= 100 && b <= 200;
    }

    private static int greenCheckPixels(BufferedImage img, Tile tile) {
        int count = 0;
        for (int y = tile.top(); y < tile.bottom(); y++) {
            for (int x = tile.left(); x < tile.right(); x++) {
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                if (g >= 170 && r <= 140 && b <= 120 && g - r >= 60) {
                    count++;
                }
            }
        }
        return count;
    }

    /** The red dot over the Sign-in Rewards button, located from the button's detected centre. */
    static boolean hasRedDot(BufferedImage img, PointData buttonCenter) {
        int count = 0;
        for (int y = Math.max(0, buttonCenter.getY() + DOT_TOP); y < buttonCenter.getY() + DOT_BOTTOM; y++) {
            for (int x = buttonCenter.getX() + DOT_LEFT; x < Math.min(img.getWidth(), buttonCenter.getX() + DOT_RIGHT); x++) {
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                if (r >= 200 && g <= 70 && b <= 70) {
                    count++;
                }
            }
        }
        return count >= DOT_MIN_PIXELS;
    }

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
            frameMissing = true;
            return Optional.empty();
        }
        return Optional.of(ImageConverter.toBufferedImage(raw));
    }

    private String snapshot(String type) {
        return TaskDiagnosticSnapshots.capture(emuManager, EMULATOR_NUMBER, "deadshot", type);
    }
}
