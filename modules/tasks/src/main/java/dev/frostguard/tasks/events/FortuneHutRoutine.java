package dev.frostguard.tasks.events;

import java.awt.image.BufferedImage;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
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
import dev.frostguard.engine.service.EventScheduleService;
import dev.frostguard.engine.service.StatisticsService;
import dev.frostguard.tasks.diagnostics.TaskDiagnosticSnapshots;
import dev.frostguard.vision.convert.GameTimeUtils;
import dev.frostguard.vision.convert.ImageConverter;

/**
 * Mia's Fortune Hut, the crystal-ball tab of the Deals panel. It runs for about two days, about every
 * three to four weeks, and lives on one currency: Fortune Tokens. Free ones come once a day from the Free
 * Fortune Token Pack behind the "+" next to the count (the daily missions add more).
 *
 * <p>Each round the game asks for a Wish Reward (one of four items, Fire Crystals by default here) and
 * hides it in one of ten crystal balls beside nine side prizes. An orb costs more with every one opened
 * (1, 2, 3, 4, 6, 8, 12, 15, 20, 25 tokens as read live), and the wish is always in the tenth at the latest.
 * Tokens are free, carry over until the event ends, and are worth nothing afterwards, so the strategy is:
 * claim the free pack every day, bank the tokens, and spend all of them in one go near the end, so the
 * deepest, cheapest-per-orb run uses the biggest pool.
 *
 * <p>Never tapped: any pack with a price, a gem or top-up button, and Reset while it still costs tokens.
 * Only the green Free button of the free pack is ever pressed on the pack page, and the pack page is
 * left by its back arrow.
 *
 * <p>Gated on the State calendar's window for the event; see {@link EventCalendarGate}.
 */
public class FortuneHutRoutine extends DelayedTask {

    /** The name and key prefix the State calendar stores this event under. */
    static final String CALENDAR_EVENT_NAME = "Mia's Fortune Hut";
    private static final String CALENDAR_KEY_PREFIX = "STATE_GANTT_MIA_S_FORTUNE_HUT_";

    private static final PointData BACK_ARROW = CommonGameAreas.DEALS_BACK_ARROW;
    private static final PointData PACK_PLUS = new PointData(645, 283);
    private static final PointData FREE_PACK_BUTTON = new PointData(511, 356);
    static final PointData FREE_PACK_GREEN_SAMPLE = new PointData(450, 356);

    private static final AreaData HOME_RAIL_AREA = AreaData.of(540, 80, 719, 700);
    private static final PointData HUT_TITLE_TOP_LEFT = new PointData(30, 215);
    private static final PointData HUT_TITLE_BOTTOM_RIGHT = new PointData(420, 275);
    private static final PointData COST_LABEL_TOP_LEFT = new PointData(50, 730);
    private static final PointData COST_LABEL_BOTTOM_RIGHT = new PointData(260, 785);
    private static final PointData PACK_TITLE_TOP_LEFT = new PointData(60, 10);
    private static final PointData PACK_TITLE_BOTTOM_RIGHT = new PointData(460, 70);
    private static final PointData FREE_LABEL_TOP_LEFT = new PointData(120, 135);
    private static final PointData FREE_LABEL_BOTTOM_RIGHT = new PointData(600, 195);
    private static final PointData WISH_HINT_TOP_LEFT = new PointData(30, 885);
    private static final PointData WISH_HINT_BOTTOM_RIGHT = new PointData(690, 950);

    /** The four Wish Reward options, left to right, and the button under them. */
    static final int[] WISH_OPTION_X = {170, 297, 423, 549};
    static final int WISH_OPTION_Y = 723;
    private static final PointData WISH_CONFIRM = new PointData(360, 840);
    static final PointData WISH_GREEN_SAMPLE = new PointData(250, 845);

    /** The ten crystal balls: 3 over 4 over 3, centres from live frames. */
    static final int[][] ORB_CENTRES = {
            {197, 880}, {360, 880}, {522, 880},
            {115, 1020}, {277, 1020}, {440, 1020}, {605, 1020},
            {195, 1158}, {360, 1158}, {523, 1158}};
    /** The lilac highlight on top of an unopened ball, 30 px above its centre. */
    static final int ORB_HIGHLIGHT_DY = -30;
    /** The digit of "Current cost" turns red once the token count cannot pay it. */
    static final int[] COST_X = {255, 292};
    static final int[] COST_Y = {742, 772};
    static final int COST_RED_MIN_PIXELS = 3;

    private static final int TEMPLATE_THRESHOLD = 85;
    private static final int PANEL_SETTLE_MS = 1500;
    private static final int ORB_SETTLE_MS = 4000;
    private static final int MAX_ORB_STEPS = 12;
    private static final int MAX_STRIP_SWIPES = 6;
    private static final int STRIP_SWIPE_MS = 400;
    private static final int RESET_PAD_MIN_MINUTES = 3;
    private static final int RESET_PAD_MAX_MINUTES = 10;
    private static final Duration FAILURE_RETRY = Duration.ofHours(1);

    public FortuneHutRoutine(AccountDescriptor profile, TpDailyTaskEnum tpTask) {
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
        LocalDateTime end = eventEnd();
        boolean spendNow = playEnabled() && spendWindowOpen(end);

        if (!openHut()) {
            finish(failureRetry(end));
            return;
        }
        boolean packClaimed = claimFreePack();
        boolean unsure = false;
        int opened = 0;
        if (spendNow) {
            int result = playBoard();
            if (result < 0) {
                unsure = true;
            } else {
                opened = result;
            }
        } else {
            logInfo("Fortune Hut: banking tokens"
                    + (playEnabled() ? " until " + (end == null ? "the end" : spendFrom(end).format(DATETIME_FORMATTER)) : "")
                    + ".");
        }
        leave();
        if (packClaimed) {
            StatisticsService.obtain().addToCounter(profile, "Fortune Hut Free Packs", 1);
        }
        if (opened > 0) {
            StatisticsService.obtain().addToCounter(profile, "Fortune Hut Orbs Opened", opened);
        }

        if (unsure) {
            finish(failureRetry(end));
        } else if (spendNow) {
            // Spent what the tokens would pay for. The next pass is the following day's free pack, if the event is still on.
            finish(afterNextReset());
        } else {
            finish(nextPass(end));
        }
    }

    /** Waits for the event's State calendar window; with no clear verdict the pass goes ahead. */
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

    // ---- when to spend -----------------------------------------------------------------------------

    /** The window's end in local time, or null when the calendar has no bar for the running event. */
    private LocalDateTime eventEnd() {
        try {
            return EventScheduleService.obtain().windowFor(CALENDAR_KEY_PREFIX, LocalDateTime.now(ZoneOffset.UTC))
                    .filter(entry -> entry.getInactiveSince() != null)
                    .map(entry -> entry.getInactiveSince().atZone(ZoneOffset.UTC)
                            .withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime())
                    .orElse(null);
        } catch (RuntimeException e) {
            logWarning("Fortune Hut: could not read the event's end from the calendar: " + e.getMessage());
            return null;
        }
    }

    private boolean playEnabled() {
        return !Boolean.FALSE.equals(profile.getConfig(ConfigurationKeyEnum.FORTUNE_HUT_PLAY_BOOL, Boolean.class));
    }

    private int spendLeadHours() {
        Integer hours = profile.getConfig(ConfigurationKeyEnum.FORTUNE_HUT_SPEND_HOURS_BEFORE_END_INT, Integer.class);
        return hours == null ? 6 : Math.max(1, Math.min(36, hours));
    }

    private LocalDateTime spendFrom(LocalDateTime end) {
        return end.minusHours(spendLeadHours());
    }

    /** With no known end the tokens would otherwise go unspent, so the pass plays. */
    private boolean spendWindowOpen(LocalDateTime end) {
        return end == null || !LocalDateTime.now().isBefore(spendFrom(end));
    }

    /** The next free pack, or the start of the spend window if that comes first. */
    private LocalDateTime nextPass(LocalDateTime end) {
        LocalDateTime pack = afterNextReset();
        if (end == null || !playEnabled()) {
            return pack;
        }
        LocalDateTime spend = spendFrom(end);
        return spend.isAfter(LocalDateTime.now()) && spend.isBefore(pack) ? spend : pack;
    }

    private LocalDateTime failureRetry(LocalDateTime end) {
        LocalDateTime soon = LocalDateTime.now().plus(FAILURE_RETRY);
        return end != null && soon.isAfter(end) ? afterNextReset() : soon;
    }

    private LocalDateTime afterNextReset() {
        return GameTimeUtils.dailyResetTime().plusMinutes(
                ThreadLocalRandom.current().nextInt(RESET_PAD_MIN_MINUTES, RESET_PAD_MAX_MINUTES + 1));
    }

    // ---- navigation --------------------------------------------------------------------------------

    private boolean openHut() {
        ImageSearchResultData deals = templateSearchHelper.locatePattern(TemplatesEnum.HOME_DEALS_BUTTON,
                search(HOME_RAIL_AREA, 3));
        if (!deals.isFound()) {
            logWarning("Fortune Hut: Deals button not found on the City rail; " + snapshot("deals-button-missing"));
            return false;
        }
        tapNear(deals.getPoint(), 6);
        sleepTask(PANEL_SETTLE_MS);
        if (!dealsOpen()) {
            logWarning("Fortune Hut: tapped Deals but its header did not show; " + snapshot("deals-not-open"));
            return false;
        }
        if (onHutPage()) {
            return true;
        }
        ImageSearchResultData tab = findTab();
        if (!tab.isFound()) {
            logWarning("Fortune Hut: no Fortune Hut tab in the Deals strip although the calendar says it is on; "
                    + snapshot("tab-absent"));
            leave();
            return false;
        }
        tapNear(tab.getPoint(), 6);
        sleepTask(PANEL_SETTLE_MS);
        if (!onHutPage()) {
            logWarning("Fortune Hut: tapped the tab but the page did not open; " + snapshot("page-not-open"));
            leave();
            return false;
        }
        return true;
    }

    /** Looks where the strip opened, then walks it toward its right end and back toward its start. */
    private ImageSearchResultData findTab() {
        ImageSearchResultData tab = locateTab();
        if (tab.isFound()) {
            return tab;
        }
        for (boolean towardEnd : new boolean[] {true, false}) {
            for (int swipe = 0; swipe < MAX_STRIP_SWIPES; swipe++) {
                if (towardEnd) {
                    swipe(CommonGameAreas.DEALS_TAB_STRIP_SWIPE_FROM, CommonGameAreas.DEALS_TAB_STRIP_SWIPE_TO,
                            STRIP_SWIPE_MS);
                } else {
                    swipe(CommonGameAreas.DEALS_TAB_STRIP_SWIPE_TO, CommonGameAreas.DEALS_TAB_STRIP_SWIPE_FROM,
                            STRIP_SWIPE_MS);
                }
                sleepTask(900);
                tab = locateTab();
                if (tab.isFound()) {
                    return tab;
                }
            }
        }
        return tab;
    }

    private ImageSearchResultData locateTab() {
        return templateSearchHelper.locatePattern(TemplatesEnum.FORTUNE_HUT_TAB,
                search(CommonGameAreas.DEALS_TAB_STRIP, 1));
    }

    private boolean dealsOpen() {
        return templateSearchHelper.locatePattern(TemplatesEnum.DEALS_TITLE,
                search(CommonGameAreas.DEALS_HEADER, 3)).isFound();
    }

    private boolean onHutPage() {
        return found(TemplatesEnum.FORTUNE_HUT_TITLE, HUT_TITLE_TOP_LEFT, HUT_TITLE_BOTTOM_RIGHT);
    }

    private boolean boardShown() {
        return found(TemplatesEnum.FORTUNE_HUT_COST_LABEL, COST_LABEL_TOP_LEFT, COST_LABEL_BOTTOM_RIGHT);
    }

    private boolean wishPickerShown() {
        return found(TemplatesEnum.FORTUNE_HUT_WISH_HINT, WISH_HINT_TOP_LEFT, WISH_HINT_BOTTOM_RIGHT);
    }

    /** Leaves Deals through its back arrow, only while its header is on screen. */
    private void leave() {
        for (int i = 0; i < 3 && dealsOpen(); i++) {
            tapNear(BACK_ARROW, 4);
            sleepTask(1500);
        }
    }

    // ---- the free pack -----------------------------------------------------------------------------

    /**
     * The "+" beside the token count opens the pack page. The free pack sits on top of it while it is
     * unclaimed and vanishes once taken, which slides the $4.99 pack into the Free button's place: so the
     * tap needs both the "Free Fortune Token Pack" label and a green button, never one alone.
     */
    private boolean claimFreePack() {
        tapNear(PACK_PLUS, 6);
        sleepTask(PANEL_SETTLE_MS);
        if (!found(TemplatesEnum.FORTUNE_HUT_PACK_TITLE, PACK_TITLE_TOP_LEFT, PACK_TITLE_BOTTOM_RIGHT)) {
            logWarning("Fortune Hut: the \"+\" did not open the token pack page; " + snapshot("pack-page-not-open"));
            return false;
        }
        boolean claimed = false;
        boolean labelled = found(TemplatesEnum.FORTUNE_HUT_FREE_PACK_LABEL, FREE_LABEL_TOP_LEFT, FREE_LABEL_BOTTOM_RIGHT);
        Optional<BufferedImage> page = frame();
        if (labelled && page.isPresent() && freePackButtonIsGreen(page.get())) {
            tapNear(FREE_PACK_BUTTON, 6);
            sleepTask(2500);
            claimed = true;
            logInfo("Fortune Hut: claimed today's Free Fortune Token Pack.");
        } else {
            logInfo("Fortune Hut: no free pack waiting (claimed already today).");
        }
        tapNear(BACK_ARROW, 4);
        sleepTask(PANEL_SETTLE_MS);
        if (!onHutPage()) {
            logWarning("Fortune Hut: not back on the Hut page after the pack page; " + snapshot("after-pack"));
        }
        return claimed;
    }

    // ---- the board ---------------------------------------------------------------------------------

    /**
     * Opens crystal balls while the count can pay the next one (its cost reads red when it cannot).
     * Returns the number opened, or -1 when a screen was not recognised and the pass stopped.
     *
     * <p>Reset is never tapped here: before the wish is found it costs tokens and throws the board away.
     * The wish-found screen has not been captured yet, so anything that is neither the board nor the
     * wish picker is saved and left as it is.
     */
    private int playBoard() {
        int opened = 0;
        for (int step = 0; step < MAX_ORB_STEPS; step++) {
            if (wishPickerShown()) {
                if (!pickWish()) {
                    return -1;
                }
                continue;
            }
            if (!boardShown()) {
                logWarning("Fortune Hut: neither the board nor the wish picker is showing; " + snapshot("unknown-screen"));
                return opened > 0 ? opened : -1;
            }
            Optional<BufferedImage> board = frame();
            if (board.isEmpty()) {
                return -1;
            }
            if (costIsRed(board.get())) {
                logInfo("Fortune Hut: the next orb costs more tokens than are left; opened " + opened + " this pass.");
                return opened;
            }
            List<Integer> unopened = unopenedOrbs(board.get());
            if (unopened.isEmpty()) {
                logInfo("Fortune Hut: no unopened orb on the board; " + snapshot("board-complete"));
                return opened;
            }
            int[] centre = ORB_CENTRES[unopened.get(0)];
            tapNear(new PointData(centre[0], centre[1]), 8);
            sleepTask(ORB_SETTLE_MS);
            opened++;
            snapshot("orb-" + opened);
        }
        return opened;
    }

    /** Selects the configured Wish Reward and presses the green button under the four options. */
    private boolean pickWish() {
        Integer setting = profile.getConfig(ConfigurationKeyEnum.FORTUNE_HUT_WISH_INT, Integer.class);
        int option = Math.max(1, Math.min(4, setting == null ? 1 : setting));
        tapNear(new PointData(WISH_OPTION_X[option - 1], WISH_OPTION_Y), 8);
        sleepTask(900);
        Optional<BufferedImage> picker = frame();
        if (picker.isEmpty() || !wishConfirmIsGreen(picker.get())) {
            logWarning("Fortune Hut: selecting Wish Reward " + option + " did not turn the button green; "
                    + snapshot("wish-not-selected"));
            return false;
        }
        tapNear(WISH_CONFIRM, 6);
        sleepTask(3000);
        logInfo("Fortune Hut: Wish Reward " + option + " chosen for the round.");
        return true;
    }

    // ---- pixel reads (package-private for the frame tests) -----------------------------------------

    /** Indices of the balls still sealed: only they carry the lilac highlight above their centre. */
    static List<Integer> unopenedOrbs(BufferedImage img) {
        List<Integer> sealed = new ArrayList<>();
        for (int i = 0; i < ORB_CENTRES.length; i++) {
            int rgb = img.getRGB(ORB_CENTRES[i][0], ORB_CENTRES[i][1] + ORB_HIGHLIGHT_DY);
            int r = (rgb >> 16) & 0xFF;
            int g = (rgb >> 8) & 0xFF;
            int b = rgb & 0xFF;
            if (r >= 215 && g >= 135 && g <= 185 && b >= 230) {
                sealed.add(i);
            }
        }
        return sealed;
    }

    /** True when "Current cost" is drawn in red: the tokens left cannot pay for the next orb. */
    static boolean costIsRed(BufferedImage img) {
        int red = 0;
        for (int x = COST_X[0]; x <= COST_X[1]; x += 3) {
            for (int y = COST_Y[0]; y <= COST_Y[1]; y += 4) {
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                if (r > 200 && g < 90 && b < 90) {
                    red++;
                }
            }
        }
        return red >= COST_RED_MIN_PIXELS;
    }

    static boolean freePackButtonIsGreen(BufferedImage img) {
        return WanderTheaterRoutine.isClaimGreen(img.getRGB(FREE_PACK_GREEN_SAMPLE.getX(), FREE_PACK_GREEN_SAMPLE.getY()));
    }

    static boolean wishConfirmIsGreen(BufferedImage img) {
        return WanderTheaterRoutine.isClaimGreen(img.getRGB(WISH_GREEN_SAMPLE.getX(), WISH_GREEN_SAMPLE.getY()));
    }

    // ---- plumbing ----------------------------------------------------------------------------------

    private boolean found(TemplatesEnum template, PointData topLeft, PointData bottomRight) {
        return templateSearchHelper.locatePattern(template,
                SearchConfig.builder()
                        .withCoordinates(topLeft, bottomRight)
                        .withThreshold(TEMPLATE_THRESHOLD)
                        .withMaxAttempts(3)
                        .withDelay(400)
                        .build()).isFound();
    }

    private static SearchConfig search(AreaData area, int attempts) {
        return SearchConfig.builder()
                .withArea(area)
                .withThreshold(TEMPLATE_THRESHOLD)
                .withMaxAttempts(attempts)
                .withDelay(400)
                .build();
    }

    private void finish(LocalDateTime when) {
        logInfo("Next Fortune Hut pass at " + when.format(DATETIME_FORMATTER) + ".");
        rescheduleExact(when);
    }

    private Optional<BufferedImage> frame() {
        RawImageData raw = emuManager.captureScreen(EMULATOR_NUMBER);
        if (raw == null) {
            return Optional.empty();
        }
        return Optional.of(ImageConverter.toBufferedImage(raw));
    }

    private String snapshot(String type) {
        return TaskDiagnosticSnapshots.captureAlways(emuManager, EMULATOR_NUMBER, "fortunehut", type);
    }
}
