package dev.frostguard.tasks.events;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.OcrSettingsData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.engine.helper.TemplateSearchHelper.SearchConfig;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.engine.service.StatisticsService;
import dev.frostguard.vision.convert.GameTimeUtils;
import dev.frostguard.vision.convert.ImageConverter;

import java.awt.image.BufferedImage;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mergeventure, the merge-board event opened from the City's right-hand icon rail, in one pass:
 * <ol>
 * <li>Board: drains the storage bubble, uses every max-level (crowned) piece - coin bags, energy
 * crates, chest generators, fruit baskets for Jessie - merges every identical pair, unburies dirt
 * tiles, and spends Merge Energy on the wheat field until the game asks for more.</li>
 * <li>Daily Missions: claims the five "Spend N Merge Energy" tiers.</li>
 * <li>Atlas and Path: banks the star cards of newly discovered pieces and claims Path milestones.</li>
 * <li>Harvest Rhapsody: claims the free reward track.</li>
 * <li>Caravan: buys the Sojourner Caravan once 10,000 coins are banked, and in the board's last
 * hours spends the rest on Charm Designs, Charm Guides, Wild Marks, pet chests and speedups.</li>
 * </ol>
 * Merging energy or coin pieces before use is never worse than using them early (energy doubles
 * per level, the Lv. 6 coin bag pays 1,000 against 800 for two Lv. 5 stacks), so only crowned
 * pieces are used. Nothing that costs gems or money is ever tapped: the "Obtain more" pack popup
 * that opens at zero energy is closed, and the Boost Pack tab and the coin "+" are never touched.
 */
public class MergeventureRoutine extends DelayedTask {

    private static final OcrSettingsData WHITE_TEXT = OcrSettingsData.forWhiteTextOnDark();
    static final OcrSettingsData DARK_TITLE = OcrSettingsData.builder()
            .textLayout(OcrSettingsData.TextLayout.SINGLE_LINE).build();
    /** "Recovers In" is light grey on the dirt, which white-text isolation wipes out. */
    static final OcrSettingsData RECOVERY_TEXT = OcrSettingsData.builder()
            .textLayout(OcrSettingsData.TextLayout.SINGLE_LINE).allowedGlyphs("0123456789:").build();
    private static final int RED_DOT_PIXELS = 40;
    private static final Pattern NUMBER = Pattern.compile("(\\d[\\d,.]*)");

    // City
    private static final PointData ICON_AREA_TOP_LEFT = new PointData(380, 80);
    private static final PointData ICON_AREA_BOTTOM_RIGHT = new PointData(720, 640);

    // Event page (720x1280)
    private static final PointData BACK_ARROW = new PointData(40, 40);
    private static final PointData TAB_STRIP_LEFT = new PointData(100, 150);
    private static final PointData TAB_STRIP_RIGHT = new PointData(700, 150);
    private static final PointData MAIN_TAB = new PointData(130, 150);
    private static final PointData HARVEST_TAB = new PointData(330, 150);
    private static final PointData PATH_BUTTON = new PointData(638, 340);
    private static final PointData CARAVAN_BUTTON = new PointData(638, 460);
    private static final PointData MISSIONS_BUTTON = new PointData(638, 575);
    private static final PointData ATLAS_BUTTON = new PointData(638, 695);
    private static final PointData GO_MERGE = new PointData(360, 1175);
    static final PointData EVENT_TIMER_TOP_LEFT = new PointData(82, 338);
    static final PointData EVENT_TIMER_BOTTOM_RIGHT = new PointData(240, 370);
    static final PointData RECOVERY_TOP_LEFT = new PointData(366, 1090);
    static final PointData RECOVERY_BOTTOM_RIGHT = new PointData(482, 1120);

    // Daily Missions popup
    private static final int[] MISSION_ROW_Y = {355, 527, 700, 872, 1043};
    private static final int MISSION_CLAIM_X = 582;
    private static final PointData MISSIONS_CLOSE = new PointData(660, 155);

    // Board
    private static final PointData FIELD = new PointData(520, 1150);
    private static final PointData JESSIE = new PointData(150, 1110);
    private static final PointData STORAGE_BUBBLE = new PointData(360, 985);
    private static final PointData OBTAIN_MORE_CLOSE = new PointData(660, 153);
    private static final PointData DIALOG_CANCEL_OR_BLANK = new PointData(200, 787);
    private static final PointData BOARD_BACK = new PointData(45, 45);
    private static final int DRAG_MS = 450;
    private static final int MAX_BOARD_PASSES = 60;
    private static final int MAX_MERGES_PER_SWEEP = 80;
    private static final int JESSIE_TAPS = 10;

    // Atlas, Path and Harvest Rhapsody
    private static final PointData ATLAS_CLOSE = new PointData(620, 190);
    private static final PointData REWARD_BLANK = new PointData(360, 1190);
    private static final int HARVEST_FREE_X = 200;

    // Caravan
    static final PointData CARAVAN_TIMER_TOP_LEFT = new PointData(90, 272);
    static final PointData CARAVAN_TIMER_BOTTOM_RIGHT = new PointData(245, 302);
    static final PointData COINS_TOP_LEFT = new PointData(85, 478);
    static final PointData COINS_BOTTOM_RIGHT = new PointData(205, 515);
    private static final PointData[] CARAVAN_PRICES_TOP = {
        new PointData(140, 770), new PointData(360, 770), new PointData(580, 770),
        new PointData(140, 1043), new PointData(360, 1043), new PointData(580, 1043)};
    private static final PointData[] CARAVAN_PRICES_SCROLLED = {new PointData(140, 1203), new PointData(360, 1203)};
    static final PointData BUY_TITLE_TOP_LEFT = new PointData(188, 495);
    static final PointData BUY_TITLE_BOTTOM_RIGHT = new PointData(600, 532);
    static final PointData BUY_TOTAL_TOP_LEFT = new PointData(345, 808);
    static final PointData BUY_TOTAL_BOTTOM_RIGHT = new PointData(470, 850);
    private static final PointData BUY_MAX = new PointData(612, 703);
    private static final PointData BUY_BUTTON = new PointData(360, 828);
    private static final PointData BUY_CLOSE = new PointData(652, 432);
    private static final PointData CARAVAN_BLANK = new PointData(360, 260);
    private static final PointData CARAVAN_TIP = new PointData(657, 496);
    static final int SOJOURNER_PRICE = 10_000;

    /** Caravan wares worth coins, best first. Matched against the Buy dialog title. */
    static final List<String> CARAVAN_WANTED = List.of(
            "sojourner", "charm design", "charm guide", "wild mark", "pet advancement", "speedup");

    private static final Duration MIN_RESCHEDULE = Duration.ofMinutes(20);
    private static final Duration MAX_RESCHEDULE = Duration.ofHours(3);
    private static final Duration INACTIVE_RECHECK = Duration.ofHours(6);

    private final Set<MergeventureBoard.Move> refusedMoves = new HashSet<>();
    private final Set<String> refusedTaps = new HashSet<>();
    private int merges;
    private int used;
    private int generated;

    public MergeventureRoutine(AccountDescriptor profile, TpDailyTaskEnum tpTask) {
        super(profile, tpTask);
    }

    @Override
    public LaunchPoint getRequiredStartLocation() {
        return LaunchPoint.HOME;
    }

    @Override
    protected void execute() {
        refusedMoves.clear();
        refusedTaps.clear();
        merges = 0;
        used = 0;
        generated = 0;

        if (!openEvent()) {
            logInfo("Mergeventure icon not on the City rail; the event is not running.");
            reschedule(LocalDateTime.now().plus(INACTIVE_RECHECK));
            return;
        }
        Duration eventLeft = readDuration(EVENT_TIMER_TOP_LEFT, EVENT_TIMER_BOTTOM_RIGHT, WHITE_TEXT);
        logInfo("Mergeventure open" + (eventLeft == null ? "" : ", board closes in " + format(eventLeft)) + ".");

        if (enabled(ConfigurationKeyEnum.MERGEVENTURE_PLAY_BOARD_BOOL)) {
            playBoard();
        }
        Duration recovery = showMainTab() ? readDuration(RECOVERY_TOP_LEFT, RECOVERY_BOTTOM_RIGHT, RECOVERY_TEXT) : null;
        // A red dot on a side button or tab is the game saying something there is waiting.
        if (enabled(ConfigurationKeyEnum.MERGEVENTURE_MISSIONS_BOOL) && showMainTab()
                && sideButtonFlagged(frame(), MISSIONS_BUTTON)) {
            claimMissions();
        }
        if (enabled(ConfigurationKeyEnum.MERGEVENTURE_ATLAS_PATH_BOOL)) {
            if (showMainTab() && sideButtonFlagged(frame(), ATLAS_BUTTON)) {
                claimAtlas();
            }
            if (showMainTab() && sideButtonFlagged(frame(), PATH_BUTTON)) {
                claimPath();
            }
        }
        if (enabled(ConfigurationKeyEnum.MERGEVENTURE_HARVEST_BOOL) && showMainTab()
                && tabFlagged(frame(), HARVEST_TAB)) {
            claimHarvest();
        }
        Duration caravanLeft = null;
        if (enabled(ConfigurationKeyEnum.MERGEVENTURE_CARAVAN_BOOL) && showMainTab()) {
            caravanLeft = runCaravan(eventLeft);
        }
        boolean finalHours = inFinalHours(eventLeft) || inFinalHours(caravanLeft);
        if (showMainTab()) {
            reportLeftoverFlags(frame());
        }
        tapNear(BACK_ARROW, 4);
        sleepTask(2000);

        StatisticsService.obtain().addToCounter(profile, "Mergeventure Merges", merges);
        StatisticsService.obtain().addToCounter(profile, "Mergeventure Pieces Used", used);
        logInfo("Mergeventure pass done: " + generated + " field taps, " + merges + " merges, "
                + used + " max-level pieces used.");
        finish(nextRun(recovery, eventLeft != null ? eventLeft : caravanLeft, finalHours));
    }

    /** Logs every side button and tab still showing a red dot, so anything unhandled shows up in the log. */
    private void reportLeftoverFlags(BufferedImage img) {
        List<String> left = new ArrayList<>();
        if (sideButtonFlagged(img, PATH_BUTTON)) left.add("Path");
        if (sideButtonFlagged(img, CARAVAN_BUTTON)) left.add("Caravan");
        if (sideButtonFlagged(img, MISSIONS_BUTTON)) left.add("Missions");
        if (sideButtonFlagged(img, ATLAS_BUTTON)) left.add("Atlas");
        if (tabFlagged(img, HARVEST_TAB)) left.add("Harvest Rhapsody");
        if (!left.isEmpty()) {
            logInfo("Still flagged after this pass: " + String.join(", ", left)
                    + (left.equals(List.of("Caravan")) ? " (coins are being banked)." : "."));
        }
    }

    /** The red dot on the top-right corner of a side button (Path, Caravan, Missions, Atlas). */
    static boolean sideButtonFlagged(BufferedImage img, PointData button) {
        return redPixels(img, 650, button.getY() - 75, 710, button.getY() - 20) >= RED_DOT_PIXELS;
    }

    /** The red dot on the top-right corner of a tab, with the tab strip scrolled to its start. */
    static boolean tabFlagged(BufferedImage img, PointData tab) {
        return redPixels(img, tab.getX() + 50, 90, tab.getX() + 100, 130) >= RED_DOT_PIXELS;
    }

    private static int redPixels(BufferedImage img, int x0, int y0, int x1, int y1) {
        int n = 0;
        for (int y = Math.max(0, y0); y < Math.min(img.getHeight(), y1); y++) {
            for (int x = Math.max(0, x0); x < Math.min(img.getWidth(), x1); x++) {
                if (red(img.getRGB(x, y))) {
                    n++;
                }
            }
        }
        return n;
    }

    // ------------------------------------------------------------------ navigation

    private boolean openEvent() {
        ImageSearchResultData icon = templateSearchHelper.locatePattern(TemplatesEnum.MERGEVENTURE_CITY_ICON,
                SearchConfig.builder()
                        .withCoordinates(ICON_AREA_TOP_LEFT, ICON_AREA_BOTTOM_RIGHT)
                        .withMaxAttempts(3)
                        .withDelay(400)
                        .build());
        if (!icon.isFound()) {
            return false;
        }
        tapNear(icon.getPoint(), 6);
        sleepTask(3000);
        return showMainTab();
    }

    /** Selects the event's first tab, scrolling the tab strip back to its start first. */
    private boolean showMainTab() {
        if (onMainTab()) {
            return true;
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            swipe(TAB_STRIP_LEFT, TAB_STRIP_RIGHT, 400);
            sleepTask(600);
            tapNear(MAIN_TAB, 4);
            sleepTask(1500);
            if (onMainTab()) {
                return true;
            }
        }
        logWarning("Could not get to the Mergeventure main tab.");
        return false;
    }

    private boolean onMainTab() {
        return templateSearchHelper.locatePattern(TemplatesEnum.MERGEVENTURE_GO_MERGE_BUTTON,
                SearchConfig.builder()
                        .withCoordinates(new PointData(200, 1100), new PointData(520, 1250))
                        .withMaxAttempts(2)
                        .build()).isFound();
    }

    // ------------------------------------------------------------------ board

    private void playBoard() {
        tapNear(GO_MERGE, 6);
        sleepTask(3500);
        BufferedImage frame = frame();
        for (int attempt = 0; attempt < 3 && !MergeventureBoard.isBoardScreen(frame); attempt++) {
            sleepTask(1500);
            frame = frame();
        }
        if (!MergeventureBoard.isBoardScreen(frame)) {
            logWarning("Go Merge did not open the board.");
            return;
        }
        boolean outOfEnergy = false;
        boolean useMax = enabled(ConfigurationKeyEnum.MERGEVENTURE_USE_MAX_PIECES_BOOL);
        boolean jessieFed = true;
        for (int pass = 0; pass < MAX_BOARD_PASSES; pass++) {
            boolean progress = drainStorage();
            if (useMax && useMaxPieces()) {
                progress = true;
                jessieFed = true;
            }
            if (jessieFed) {
                tapJessie();
                jessieFed = false;
            }
            progress |= mergeSweep() > 0;
            if (!outOfEnergy) {
                BufferedImage now = frame();
                if (!MergeventureBoard.isBoardScreen(now) && !dismissBoardPopup()) {
                    break;
                }
                long empty = MergeventureBoard.count(MergeventureBoard.read(frame()), MergeventureBoard.Kind.EMPTY);
                if (empty >= 3) {
                    outOfEnergy = !generate();
                    progress |= !outOfEnergy;
                } else if (!progress) {
                    logInfo("Board is full and nothing merges; leaving the remaining energy.");
                    break;
                }
            }
            if (outOfEnergy && !progress) {
                break;
            }
        }
        tapNear(BOARD_BACK, 4);
        sleepTask(2500);
    }

    /** One field tap. False when the game answers with the "Obtain more" pack popup instead. */
    private boolean generate() {
        tapNear(FIELD, 8);
        sleepTask(1600);
        if (obtainMoreShown()) {
            // Owned Merge Energy Drinks (+5 each, from Harvest Rhapsody) list with a green Use
            // button; the pack row is yellow and the Harvest row blue, so green is only ever free.
            Optional<PointData> use = useButton(frame());
            if (use.isPresent()) {
                logInfo("Out of Merge Energy; drinking a Merge Energy Drink.");
                tapNear(use.get(), 4);
                sleepTask(1500);
                if (obtainMoreShown()) {
                    tapNear(OBTAIN_MORE_CLOSE, 4);
                    sleepTask(1200);
                }
                return true;
            }
            logInfo("Out of Merge Energy.");
            tapNear(OBTAIN_MORE_CLOSE, 4);
            sleepTask(1200);
            return false;
        }
        generated++;
        return true;
    }

    private boolean obtainMoreShown() {
        return templateSearchHelper.locatePattern(TemplatesEnum.MERGEVENTURE_OBTAIN_MORE_TITLE,
                SearchConfig.builder()
                        .withCoordinates(new PointData(200, 100), new PointData(520, 210))
                        .withMaxAttempts(1)
                        .build()).isFound();
    }

    /**
     * Closes whatever covers the board: the pack popup, a "use anyway" confirmation (its Cancel),
     * or a reward card (any blank tap). True when the board is visible again.
     */
    private boolean dismissBoardPopup() {
        for (int attempt = 0; attempt < 3; attempt++) {
            if (MergeventureBoard.isBoardScreen(frame())) {
                return true;
            }
            if (obtainMoreShown()) {
                tapNear(OBTAIN_MORE_CLOSE, 4);
            } else {
                tapNear(DIALOG_CANCEL_OR_BLANK, 4);
            }
            sleepTask(1300);
        }
        boolean back = MergeventureBoard.isBoardScreen(frame());
        if (!back) {
            logWarning("Something is covering the Mergeventure board; stopping board play.");
        }
        return back;
    }

    /** Merges until no identical pair is left. Returns how many drags changed the board. */
    private int mergeSweep() {
        int done = 0;
        for (int i = 0; i < MAX_MERGES_PER_SWEEP; i++) {
            BufferedImage before = frame();
            if (!MergeventureBoard.isBoardScreen(before) && !dismissBoardPopup()) {
                break;
            }
            before = frame();
            Optional<MergeventureBoard.Move> move =
                    MergeventureBoard.nextMove(before, MergeventureBoard.read(before), refusedMoves);
            if (move.isEmpty()) {
                break;
            }
            swipe(move.get().from(), move.get().to(), DRAG_MS);
            sleepTask(900);
            BufferedImage after = frame();
            if (MergeventureBoard.isBoardScreen(after) && MergeventureBoard.unchanged(before, after, move.get())) {
                logDebug("Drag " + move.get() + " changed nothing; not trying it again this pass.");
                refusedMoves.add(move.get());
            } else {
                done++;
            }
        }
        merges += done;
        return done;
    }

    /**
     * Taps each crowned (max-level) piece twice: the first tap selects, the second uses it. A
     * generator chest drops one piece per tap and disappears when spent, so it comes round again.
     */
    private boolean useMaxPieces() {
        boolean any = false;
        for (int i = 0; i < 30; i++) {
            BufferedImage before = frame();
            if (!MergeventureBoard.isBoardScreen(before)) {
                break;
            }
            List<MergeventureBoard.Cell> cells = MergeventureBoard.read(before);
            if (MergeventureBoard.count(cells, MergeventureBoard.Kind.EMPTY) == 0) {
                break;
            }
            Optional<MergeventureBoard.Cell> target = MergeventureBoard.crowned(cells).stream()
                    .filter(c -> !refusedTaps.contains(c.col() + "," + c.row()))
                    .findFirst();
            if (target.isEmpty()) {
                break;
            }
            PointData at = target.get().center();
            tapNear(at, 3);
            sleepTask(900);
            tapNear(at, 3);
            sleepTask(1500);
            if (!MergeventureBoard.isBoardScreen(frame())) {
                dismissBoardPopup();
            }
            BufferedImage after = frame();
            MergeventureBoard.Move self = new MergeventureBoard.Move(
                    target.get().col(), target.get().row(), target.get().col(), target.get().row(), false);
            if (MergeventureBoard.isBoardScreen(after) && MergeventureBoard.unchanged(before, after, self)) {
                refusedTaps.add(target.get().col() + "," + target.get().row());
            } else {
                any = true;
                used++;
            }
        }
        return any;
    }

    /** Jessie turns delivered fruit baskets into jam pieces, one per tap; idle taps are harmless. */
    private void tapJessie() {
        for (int i = 0; i < JESSIE_TAPS; i++) {
            tapNear(JESSIE, 6);
            sleepTask(1000);
        }
    }

    /** Pulls pieces out of the storage bubble one at a time while there is room. */
    private boolean drainStorage() {
        boolean any = false;
        for (int i = 0; i < 15; i++) {
            BufferedImage now = frame();
            if (!MergeventureBoard.isBoardScreen(now) || !MergeventureBoard.hasStoredPieces(now)
                    || MergeventureBoard.count(MergeventureBoard.read(now), MergeventureBoard.Kind.EMPTY) == 0) {
                break;
            }
            tapNear(STORAGE_BUBBLE, 4);
            sleepTask(1200);
            any = true;
        }
        return any;
    }

    // ------------------------------------------------------------------ missions

    private void claimMissions() {
        tapNear(MISSIONS_BUTTON, 6);
        sleepTask(1800);
        for (int attempt = 0; attempt < MISSION_ROW_Y.length; attempt++) {
            BufferedImage now = frame();
            Integer row = null;
            for (int y : MISSION_ROW_Y) {
                if (greenButton(now, MISSION_CLAIM_X, y)) {
                    row = y;
                    break;
                }
            }
            if (row == null) {
                break;
            }
            logInfo("Claiming Mergeventure daily missions.");
            tapNear(new PointData(MISSION_CLAIM_X, row), 6);
            sleepTask(1800);
        }
        tapNear(MISSIONS_CLOSE, 4);
        sleepTask(1200);
    }

    /** A green Use button in the Obtain-more list, below the pack banner. */
    static Optional<PointData> useButton(BufferedImage img) {
        for (int y = 460; y <= 1100; y += 10) {
            if (greenButton(img, 575, y)) {
                return Optional.of(new PointData(575, y));
            }
        }
        return Optional.empty();
    }

    static boolean greenButton(BufferedImage img, int cx, int cy) {
        int hits = 0;
        int total = 0;
        for (int y = cy - 18; y < cy + 18; y++) {
            for (int x = cx - 60; x < cx + 60; x++) {
                int p = img.getRGB(x, y);
                int r = (p >> 16) & 0xff;
                int g = (p >> 8) & 0xff;
                int b = p & 0xff;
                if (g > 170 && r < 140 && b < 140) {
                    hits++;
                }
                total++;
            }
        }
        return (double) hits / total > 0.35;
    }

    // ------------------------------------------------------------------ atlas, path, harvest

    /** Taps every new-discovery star card (a yellow card with a red dot); the list scrolls itself. */
    private void claimAtlas() {
        tapNear(ATLAS_BUTTON, 6);
        sleepTask(2200);
        int claimed = 0;
        int scrolls = 0;
        for (int i = 0; i < 40; i++) {
            BufferedImage now = frame();
            if (!isAtlas(now)) {
                tapNear(REWARD_BLANK, 4);
                sleepTask(1500);
                continue;
            }
            Optional<PointData> card = starCard(now);
            if (card.isEmpty()) {
                if (scrolls++ >= 8) {
                    break;
                }
                swipe(new PointData(360, 950), new PointData(360, 550), 700);
                sleepTask(1200);
                continue;
            }
            tapNear(card.get(), 3);
            sleepTask(2000);
            claimed++;
        }
        if (claimed > 0) {
            logInfo("Banked " + claimed + " Atlas star card(s).");
        }
        if (isAtlas(frame())) {
            tapNear(ATLAS_CLOSE, 4);
            sleepTask(1500);
        }
    }

    /** The Atlas book: its brown title ribbon and the pale page beside the ring binding. */
    static boolean isAtlas(BufferedImage img) {
        int ribbon = img.getRGB(250, 190);
        int page = img.getRGB(60, 660);
        int rr = (ribbon >> 16) & 0xff;
        int rg = (ribbon >> 8) & 0xff;
        int rb = ribbon & 0xff;
        int pr = (page >> 16) & 0xff;
        int pg = (page >> 8) & 0xff;
        int pb = page & 0xff;
        return rr > 100 && rr - rb > 60 && rg < 110 && pr > 200 && pg > 190 && pb > 160;
    }

    /** A star card waiting to be banked: a red dot on the top-right corner of a yellow card. */
    static Optional<PointData> starCard(BufferedImage img) {
        for (int y = 340; y < 1040; y += 3) {
            for (int x = 120; x < 650; x += 3) {
                if (!red(img.getRGB(x, y))) {
                    continue;
                }
                int cardX = x - 45;
                int cardY = y + 45;
                int card = img.getRGB(cardX, cardY);
                int r = (card >> 16) & 0xff;
                int g = (card >> 8) & 0xff;
                int b = card & 0xff;
                if (r > 235 && g > 200 && b < 170) {
                    return Optional.of(new PointData(cardX, cardY));
                }
            }
        }
        return Optional.empty();
    }

    private static boolean red(int p) {
        return ((p >> 16) & 0xff) > 215 && ((p >> 8) & 0xff) < 60 && (p & 0xff) < 60;
    }

    /** Path milestones show a red dot once reached; tapping the bubble claims it. */
    private void claimPath() {
        tapNear(PATH_BUTTON, 6);
        sleepTask(2000);
        for (int i = 0; i < 5; i++) {
            Optional<PointData> dot = redDot(frame(), 30, 220, 690, 1250);
            if (dot.isEmpty()) {
                break;
            }
            logInfo("Claiming a Mergeventure Path milestone.");
            tapNear(new PointData(dot.get().getX() - 25, dot.get().getY() + 25), 3);
            sleepTask(2000);
            tapNear(REWARD_BLANK, 4);
            sleepTask(1500);
        }
    }

    static Optional<PointData> redDot(BufferedImage img, int x0, int y0, int x1, int y1) {
        for (int y = y0; y < y1; y += 3) {
            for (int x = x0; x < x1; x += 3) {
                if (red(img.getRGB(x, y)) && red(img.getRGB(x + 3, y)) && red(img.getRGB(x, y + 3))) {
                    return Optional.of(new PointData(x, y));
                }
            }
        }
        return Optional.empty();
    }

    private void claimHarvest() {
        if (!showMainTab()) {
            return;
        }
        tapNear(HARVEST_TAB, 6);
        sleepTask(2000);
        int claimed = 0;
        for (int i = 0; i < 25; i++) {
            Optional<Integer> row = glowingFreeReward(frame());
            if (row.isEmpty()) {
                if (i > 0 && claimed == 0) {
                    break;
                }
                swipe(new PointData(360, 800), new PointData(360, 1150), 600);
                sleepTask(1200);
                row = glowingFreeReward(frame());
                if (row.isEmpty()) {
                    break;
                }
            }
            tapNear(new PointData(HARVEST_FREE_X, row.get()), 4);
            sleepTask(1500);
            claimed++;
        }
        if (claimed > 0) {
            logInfo("Claimed " + claimed + " free Harvest Rhapsody reward(s).");
        }
    }

    /** The y of a free-track reward whose card glows (claimable), within the fully visible rows. */
    static Optional<Integer> glowingFreeReward(BufferedImage img) {
        int bestY = -1;
        double best = 0.08;
        for (int y = 770; y <= 1180; y += 6) {
            int hits = 0;
            int total = 0;
            for (int yy = y - 58; yy < y + 58; yy += 2) {
                for (int x = 140; x < 262; x += 2) {
                    int p = img.getRGB(x, yy);
                    int r = (p >> 16) & 0xff;
                    int g = (p >> 8) & 0xff;
                    int b = p & 0xff;
                    if (r > 245 && g > 215 && b > 120 && b < 215) {
                        hits++;
                    }
                    total++;
                }
            }
            double glow = (double) hits / total;
            if (glow > best) {
                best = glow;
                bestY = y;
            }
        }
        return bestY < 0 ? Optional.empty() : Optional.of(bestY);
    }

    // ------------------------------------------------------------------ caravan

    /**
     * The Caravan is its own tab and stays open about a day after the board closes. Coins only come
     * from the board and are lost when the Caravan closes, so leftovers are spent in the board's
     * final hours, or the Caravan's if the board timer was not read. Returns the Caravan timer.
     */
    private Duration runCaravan(Duration boardLeft) {
        tapNear(CARAVAN_BUTTON, 6);
        sleepTask(2200);
        Duration caravanLeft = readDuration(CARAVAN_TIMER_TOP_LEFT, CARAVAN_TIMER_BOTTOM_RIGHT, WHITE_TEXT);
        if (tipTicked(frame())) {
            // "Tip" keeps a red dot on the Caravan (and the main tab and City icon) whenever any
            // ware is affordable; spending is decided here, so the reminder is switched off.
            logInfo("Caravan: switching off the Tip reminder.");
            tapNear(CARAVAN_TIP, 2);
            sleepTask(1200);
        }
        boolean finalHours = inFinalHours(boardLeft) || inFinalHours(caravanLeft);
        Integer coins = readCoins();
        if (coins == null) {
            logWarning("Could not read the Caravan coin balance; skipping purchases.");
            return caravanLeft;
        }
        boolean saving = enabled(ConfigurationKeyEnum.MERGEVENTURE_SAVE_FOR_SOJOURNER_BOOL);
        boolean spendAll = finalHours || !saving;
        if (coins < SOJOURNER_PRICE && !spendAll) {
            logInfo("Caravan: " + coins + " coins banked toward the Sojourner Caravan (" + SOJOURNER_PRICE + ").");
            return caravanLeft;
        }

        Map<String, Integer> wares = surveyCaravan();
        for (String wanted : CARAVAN_WANTED) {
            Integer slot = wares.get(wanted);
            if (slot == null) {
                continue;
            }
            boolean sojourner = wanted.equals("sojourner");
            if (!sojourner && !spendAll) {
                continue;
            }
            if (sojourner && coins < SOJOURNER_PRICE) {
                continue;
            }
            Integer left = buy(slot, wanted, coins);
            if (left != null) {
                coins = left;
            }
        }
        return caravanLeft;
    }

    /** Opens each ware's Buy dialog to read its name, then closes it. Name keyword to slot. */
    private Map<String, Integer> surveyCaravan() {
        Map<String, Integer> wares = new LinkedHashMap<>();
        PointData[] slots = allSlots();
        for (int slot = 0; slot < slots.length; slot++) {
            String title = openBuyDialog(slot);
            if (title == null) {
                continue;
            }
            String wanted = wantedKeyword(title);
            if (wanted != null && !wares.containsKey(wanted)) {
                wares.put(wanted, slot);
            }
            tapNear(BUY_CLOSE, 3);
            sleepTask(1000);
        }
        scrollCaravanTop();
        logInfo("Caravan wares found: " + wares.keySet() + ".");
        return wares;
    }

    private static PointData[] allSlots() {
        PointData[] all = new PointData[CARAVAN_PRICES_TOP.length + CARAVAN_PRICES_SCROLLED.length];
        System.arraycopy(CARAVAN_PRICES_TOP, 0, all, 0, CARAVAN_PRICES_TOP.length);
        System.arraycopy(CARAVAN_PRICES_SCROLLED, 0, all, CARAVAN_PRICES_TOP.length, CARAVAN_PRICES_SCROLLED.length);
        return all;
    }

    /** Taps a ware's price button and returns the Buy dialog title, or null when none opened. */
    private String openBuyDialog(int slot) {
        if (slot < CARAVAN_PRICES_TOP.length) {
            scrollCaravanTop();
            tapNear(CARAVAN_PRICES_TOP[slot], 4);
        } else {
            swipe(new PointData(360, 1100), new PointData(360, 500), 700);
            sleepTask(1200);
            tapNear(CARAVAN_PRICES_SCROLLED[slot - CARAVAN_PRICES_TOP.length], 4);
        }
        sleepTask(1400);
        String title = stringHelper.attemptRecognition(BUY_TITLE_TOP_LEFT, BUY_TITLE_BOTTOM_RIGHT, 2, 200L, DARK_TITLE,
                text -> text != null && text.trim().length() >= 4, String::trim);
        if (title == null || wantedKeyword(title) == null && !title.toLowerCase(Locale.ROOT).matches(".*[a-z]{4}.*")) {
            // No dialog: a sold-out ware, or the tap landed on the list. Clear any tooltip.
            tapNear(CARAVAN_BLANK, 3);
            sleepTask(800);
            return null;
        }
        return title;
    }

    private void scrollCaravanTop() {
        swipe(new PointData(360, 600), new PointData(360, 1150), 500);
        sleepTask(1000);
    }

    /** Buys as many of one ware as the dialog's max allows. Returns the coins left, or null. */
    private Integer buy(int slot, String wanted, int coins) {
        String title = openBuyDialog(slot);
        if (title == null || !wanted.equals(wantedKeyword(title))) {
            if (title != null) {
                tapNear(BUY_CLOSE, 3);
                sleepTask(1000);
            }
            return null;
        }
        tapNear(BUY_MAX, 3);
        sleepTask(700);
        Integer total = integerHelper.attemptRecognition(BUY_TOTAL_TOP_LEFT, BUY_TOTAL_BOTTOM_RIGHT, 3, 200L,
                WHITE_TEXT, text -> parseNumber(text) != null, MergeventureRoutine::parseNumber);
        if (total == null || total <= 0 || total > coins) {
            logInfo("Caravan: not buying " + title + " (total " + total + ", coins " + coins + ").");
            tapNear(BUY_CLOSE, 3);
            sleepTask(1000);
            return null;
        }
        logInfo("Caravan: buying " + title + " for " + total + " coins.");
        tapNear(BUY_BUTTON, 6);
        sleepTask(2000);
        tapNear(CARAVAN_BLANK, 3);
        sleepTask(1200);
        Integer left = readCoins();
        StatisticsService.obtain().addToCounter(profile, "Mergeventure Caravan Coins Spent", total);
        return left != null ? left : coins - total;
    }

    /** The Caravan's "Tip" checkbox shows a green tick while its reminder dot is on. */
    static boolean tipTicked(BufferedImage img) {
        int green = 0;
        for (int y = 480; y < 512; y++) {
            for (int x = 640; x < 676; x++) {
                int p = img.getRGB(x, y);
                if (((p >> 8) & 0xff) > 200 && ((p >> 16) & 0xff) < 160 && (p & 0xff) < 130) {
                    green++;
                }
            }
        }
        return green >= 40;
    }

    static String wantedKeyword(String title) {
        if (title == null) {
            return null;
        }
        String lower = title.toLowerCase(Locale.ROOT);
        for (String wanted : CARAVAN_WANTED) {
            if (lower.contains(wanted)) {
                return wanted;
            }
        }
        return null;
    }

    private Integer readCoins() {
        return integerHelper.attemptRecognition(COINS_TOP_LEFT, COINS_BOTTOM_RIGHT, 3, 200L, WHITE_TEXT,
                text -> parseNumber(text) != null, MergeventureRoutine::parseNumber);
    }

    static Integer parseNumber(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = NUMBER.matcher(text);
        if (!m.find()) {
            return null;
        }
        try {
            return Integer.parseInt(m.group(1).replace(",", "").replace(".", ""));
        } catch (NumberFormatException unreadable) {
            return null;
        }
    }

    // ------------------------------------------------------------------ scheduling

    private boolean inFinalHours(Duration eventLeft) {
        Integer hours = profile.getConfig(ConfigurationKeyEnum.MERGEVENTURE_SPEND_HOURS_INT, Integer.class);
        return eventLeft != null && eventLeft.compareTo(Duration.ofHours(hours == null ? 4 : hours)) <= 0;
    }

    /**
     * Back when energy next recovers, at most three hours out, and never later than the start of
     * the final-hours spending window so the Caravan pass is not missed.
     */
    private LocalDateTime nextRun(Duration recovery, Duration eventLeft, boolean finalHours) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime next = now.plus(MAX_RESCHEDULE);
        if (recovery != null && !recovery.isZero()) {
            LocalDateTime recovered = now.plus(recovery).plusMinutes(2);
            if (recovered.isBefore(next)) {
                next = recovered;
            }
        }
        if (eventLeft != null && !finalHours) {
            Integer hours = profile.getConfig(ConfigurationKeyEnum.MERGEVENTURE_SPEND_HOURS_INT, Integer.class);
            LocalDateTime window = now.plus(eventLeft).minusHours(hours == null ? 4 : hours).plusMinutes(5);
            if (window.isAfter(now) && window.isBefore(next)) {
                next = window;
            }
        }
        return next;
    }

    private void finish(LocalDateTime when) {
        LocalDateTime earliest = LocalDateTime.now().plus(MIN_RESCHEDULE);
        LocalDateTime at = when.isBefore(earliest) ? earliest : when;
        logInfo("Next Mergeventure pass at " + at.format(DATETIME_FORMATTER) + ".");
        reschedule(at);
    }

    // ------------------------------------------------------------------ shared

    private Duration readDuration(PointData topLeft, PointData bottomRight, OcrSettingsData settings) {
        return durationHelper.attemptRecognition(topLeft, bottomRight, 3, 200L, settings,
                text -> GameTimeUtils.isAcceptedFormat(timerText(text)),
                text -> GameTimeUtils.parseDuration(timerText(text)));
    }

    /**
     * The event font's "1" is a bare stroke: OCR reads "1d 20:53:14" as "Id" or "ld", and a digit
     * whitelist drops it altogether ("d 21:38:48"), so the letter is mapped back instead.
     */
    static String timerText(String text) {
        return text == null ? "" : text.trim().replaceFirst("^[Il|]d", "1d");
    }

    private BufferedImage frame() {
        RawImageData raw = emuManager.captureScreen(EMULATOR_NUMBER);
        return raw == null ? new BufferedImage(720, 1280, BufferedImage.TYPE_INT_RGB) : ImageConverter.toBufferedImage(raw);
    }

    private boolean enabled(ConfigurationKeyEnum key) {
        Boolean value = profile.getConfig(key, Boolean.class);
        return value != null && value;
    }

    private static String format(Duration d) {
        long h = d.toHours();
        return h >= 24 ? (h / 24) + "d " + (h % 24) + "h" : h + "h " + d.toMinutesPart() + "m";
    }
}
