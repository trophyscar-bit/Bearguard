package dev.frostguard.engine.listener.task.impl;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.imageio.ImageIO;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.deals.DealItem;
import dev.frostguard.api.deals.DealOffer;
import dev.frostguard.api.deals.DealScan;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.api.runtime.WorkspacePaths;
import dev.frostguard.engine.deals.DealCarousel;
import dev.frostguard.engine.deals.DealFrameReader;
import dev.frostguard.engine.deals.DealItemLibrary;
import dev.frostguard.engine.deals.DealsStore;
import dev.frostguard.engine.nav.CommonOCRSettings;
import dev.frostguard.engine.nav.SearchConfigConstants;
import dev.frostguard.engine.schedule.CustomTaskConfigurable;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.engine.service.CustomTaskService;
import dev.frostguard.vision.convert.ImageConverter;
import dev.frostguard.vision.convert.WhiteTextIsolator;
import dev.frostguard.vision.deals.TabStripCells;
import dev.frostguard.vision.ocr.OcrEngine;
import dev.frostguard.vision.ocr.OcrException;
import dev.frostguard.vision.ocr.TextLine;

/**
 * Daily read-only survey of every paid offer: the gem shop, the Deals panel, and every shortcut
 * icon in the city view's right-hand column. Writes one scan per day under {@code data/deals},
 * which the Deal Tracker page scores.
 *
 * <p>Never taps a price button. The only interactions are opening a surface, tapping a tab in the
 * tab strip band, a pack carousel's right arrow and its option row above the item panel, swiping,
 * and back.</p>
 *
 * <p>Shortcut icons are found by the label or countdown printed under each one, not by a picture
 * of the icon, because the column changes with live events: an icon nobody has seen before still
 * carries a label, and gets opened and read like the rest.</p>
 */
public class bg_deals_telemetry extends DelayedTask implements CustomTaskConfigurable {

    /** Shortly after the 00:00 UTC daily reset (20:00 Eastern daylight time). */
    private static final LocalTime RUN_AT = LocalTime.of(20, 30);
    private static final int RUN_JITTER_MINUTES = 10;
    private static final int FRAME_RETENTION_DAYS = 7;
    private static final DateTimeFormatter UTC_INPUT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private static final int PANEL_SETTLE_MS = 2500;
    private static final int PANEL_RECHECK_MS = 1500;
    private static final int PANEL_OPEN_CHECKS = 4;
    private static final int PANEL_OPEN_ATTEMPTS = 2;
    private static final int TAB_SETTLE_MS = 2000;
    private static final int SCROLL_SETTLE_MS = 1600;
    private static final int BACK_SETTLE_MS = 1200;

    private static final int MAX_TABS_PER_PANEL = 40;
    private static final int MAX_STRIP_SWIPES = 20;
    private static final int MAX_PAGE_SCROLLS = 8;
    private static final int MAX_POPUP_SCROLLS = 3;
    private static final int MAX_ROW_SWIPES = 3;
    /** Dawn Market has 5 tiers and 4 options; a carousel past these limits is reported, not looped. */
    private static final int MAX_CAROUSEL_TIERS = 10;
    private static final int MAX_CAROUSEL_OPTIONS = 8;
    private static final int CAROUSEL_SETTLE_MS = 2000;
    private static final int MAX_SHORTCUTS = 12;
    private static final int MAX_BACKS_TO_CITY = 6;

    /** Tab labels sit in this band on both the gem shop and the Deals panel. */
    private static final int TAB_LABEL_TOP = 140;
    private static final int TAB_LABEL_BOTTOM = 190;
    private static final int TAB_TAP_Y = 150;
    /** A panel is tabbed when its strip shows at least this many whole tabs. */
    private static final int MIN_TABS_FOR_STRIP = 2;
    /** About one 199 px tab per gesture: 500 px swipes skipped whole tabs between frames. */
    private static final PointData STRIP_SWIPE_FROM = new PointData(560, 150);
    private static final PointData STRIP_SWIPE_TO = new PointData(340, 150);
    private static final PointData CONTENT_SWIPE_FROM = new PointData(360, 1000);
    private static final PointData CONTENT_SWIPE_TO = new PointData(360, 600);
    private static final int ROW_SWIPE_FROM_X = 560;
    private static final int ROW_SWIPE_TO_X = 140;
    private static final int SWIPE_MS = 800;

    /** Countdown timers sit above y 450; comparing below them makes "nothing moved" measurable. */
    private static final int[] CONTENT_REGION = {40, 450, 680, 1240};
    private static final int[] STRIP_REGION = {30, 100, 700, 190};
    private static final int[] WHOLE_FRAME = {0, 0, 720, 1280};
    private static final double STILL_MEAN_DIFF = 3.0;
    /** A shortcut tap that changes less than this did not open anything. */
    private static final double OPENED_MEAN_DIFF = 12.0;

    /**
     * Right-hand shortcut column on the city view. Measured on 2026-09-13: labels and countdowns
     * print 28-42 px below their icon's centre; the paw, scales and mail buttons below y 760 are not
     * deal surfaces.
     */
    private static final PointData SHORTCUT_TOP_LEFT = new PointData(500, 90);
    private static final PointData SHORTCUT_BOTTOM_RIGHT = new PointData(720, 760);
    private static final int ICON_ABOVE_LABEL = 32;
    private static final int LABEL_MIN_HEIGHT = 8;
    private static final int LABEL_MAX_HEIGHT = 24;
    private static final int LABEL_WORD_GAP = 22;
    private static final int SAME_LABEL_ROW = 10;
    /** Two labels closer than this are the same icon seen again after the column shifted. */
    private static final int SAME_ICON_DISTANCE = 40;
    private static final Pattern COUNTDOWN = Pattern.compile("\\d{1,2}:\\d{2}:\\d{2}|\\d+d\\s?\\d{1,2}:\\d{2}");
    /** Surfaces this task already reads through their own templates, or that hold no offers. */
    private static final Pattern NOT_A_DEAL_SHORTCUT = Pattern.compile("(?i)event|^[bdo]eals?$");
    /**
     * The gem shop's gem-bag button in the top bar. Its template missed on a city frame where the button
     * was plainly visible (the icon sparkles), while the top bar itself never moves: the button spans
     * x 580-715, y 45-95 on every city frame captured. A tap there is only trusted once a tabbed panel opens.
     */
    private static final PointData GEM_SHOP_HUD_BUTTON = new PointData(648, 70);
    /** Panel headers such as "Deals" sit at the top left, beside the back arrow. */
    private static final PointData HEADER_TOP_LEFT = new PointData(90, 12);
    private static final PointData HEADER_BOTTOM_RIGHT = new PointData(420, 70);
    private static final Pattern ALREADY_SURVEYED_HEADER = Pattern.compile("(?i)\\bdeals\\b");

    private final Map<String, DealOffer> offers = new LinkedHashMap<>();
    private final List<String> problems = new ArrayList<>();
    /** Tabs found to hold choose-your-own packs; nothing from them is kept. */
    private final Set<String> chooseYourOwnTabs = new HashSet<>();
    private DealsStore store;
    private DealFrameReader reader;
    private Path frameDir;
    private int frameNumber;

    private record Shortcut(String name, PointData icon) {
    }

    public bg_deals_telemetry(AccountDescriptor profile, TpDailyTaskEnum tpTask) {
        super(profile, tpTask);
        reschedule(nextRun(LocalDateTime.now()));
    }

    @Override
    protected Object getDistinctKey() {
        return "bg_deals_telemetry";
    }

    @Override
    protected LaunchPoint getRequiredStartLocation() {
        return LaunchPoint.HOME;
    }

    @Override
    public void applyCustomTaskSettings(CustomTaskService.CustomTaskSettings settings) {
        String first = settings == null ? null : settings.getFirstExecutionUtc();
        if (first == null || first.isBlank()) {
            return;
        }
        try {
            reschedule(LocalDateTime.parse(first, UTC_INPUT).atOffset(ZoneOffset.UTC)
                    .atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime());
        } catch (RuntimeException unparseable) {
            logWarning("bg_deals_telemetry | Unparseable first-execution time '" + first + "'; keeping the daily "
                    + RUN_AT + " schedule.");
        }
    }

    @Override
    protected void execute() {
        offers.clear();
        problems.clear();
        chooseYourOwnTabs.clear();
        frameNumber = 0;
        LocalDate day = LocalDate.now();
        store = DealsStore.forWorkspace(WorkspacePaths.current().root());
        frameDir = store.frameDir(day);
        try {
            Files.createDirectories(frameDir);
            DealItemLibrary library = DealItemLibrary.load(store.itemIconsDir());
            if (library.size() == 0) {
                problems.add("No item icons in " + store.itemIconsDir() + "; tile items were not read.");
            }
            reader = new DealFrameReader(library);
        } catch (IOException setupFailed) {
            logError("bg_deals_telemetry | Cannot prepare " + store.root() + ": " + setupFailed.getMessage()
                    + ". Nothing scanned; retrying at the next daily run.");
            reschedule(nextRun(LocalDateTime.now()));
            return;
        }
        logInfo("bg_deals_telemetry | Starting deal scan into " + frameDir);

        surveyTemplatePanel("Gem Shop", TemplatesEnum.HOME_SHOP_CART_BUTTON, GEM_SHOP_HUD_BUTTON);
        surveyTemplatePanel("Deals", TemplatesEnum.HOME_DEALS_BUTTON, null);
        surveyShortcutColumn();
        if (!returnToCity()) {
            problems.add("Scan ended without confirming the city view.");
        }

        if (offers.isEmpty()) {
            problems.add("No offers were read at all.");
            logWarning("bg_deals_telemetry | Scan finished with no offers read; the scan file records it as a failure.");
        }
        try {
            store.write(day, new DealScan(LocalDateTime.now().toString(), new ArrayList<>(offers.values()), problems));
            logInfo("bg_deals_telemetry | Wrote " + offers.size() + " offer(s) and " + problems.size()
                    + " problem(s) for " + day + ".");
        } catch (IOException writeFailed) {
            logError("bg_deals_telemetry | Could not write the scan for " + day + ": " + writeFailed.getMessage());
        }
        pruneOldFrames(day);
        reschedule(nextRun(LocalDateTime.now()));
    }

    static LocalDateTime nextRun(LocalDateTime now) {
        LocalDateTime today = now.toLocalDate().atTime(RUN_AT);
        LocalDateTime next = now.isBefore(today) ? today : today.plusDays(1);
        return next.plusMinutes(ThreadLocalRandom.current().nextInt(RUN_JITTER_MINUTES + 1));
    }

    // ── surfaces ────────────────────────────────────────────────────

    /**
     * @param hudFallback fixed top-bar position of the shortcut, tried when its template does not match,
     *                    or {@code null} when the shortcut has no fixed position
     */
    private void surveyTemplatePanel(String surface, TemplatesEnum shortcut, PointData hudFallback) {
        try {
            for (int attempt = 1; attempt <= PANEL_OPEN_ATTEMPTS; attempt++) {
                if (!returnToCity()) {
                    problems.add(surface + ": skipped because the city view could not be confirmed first.");
                    return;
                }
                ImageSearchResultData button = templateSearchHelper.locatePattern(
                        shortcut, SearchConfigConstants.SINGLE_WITH_RETRIES);
                if (button.isFound()) {
                    tapNear(button.getPoint());
                } else if (hudFallback != null) {
                    logWarning("bg_deals_telemetry | " + surface + " shortcut template did not match; trying its "
                            + "fixed top-bar position " + hudFallback.getX() + "," + hudFallback.getY() + ".");
                    tapNear(hudFallback);
                } else {
                    problems.add(surface + ": shortcut not found on the city view; surface skipped.");
                    logWarning("bg_deals_telemetry | " + surface + " shortcut not found; skipping it.");
                    return;
                }
                RawImageData opened = awaitTabbedPanel();
                if (opened == null) {
                    surveyTabbedPanel(surface);
                    return;
                }
                // The frame the check rejected is the only evidence of what covered or replaced the panel.
                String evidence = saveFrame(opened);
                if (attempt < PANEL_OPEN_ATTEMPTS) {
                    logWarning("bg_deals_telemetry | " + surface + " showed no tab strip after tapping its shortcut ("
                            + evidence + "); returning to the city and tapping it again.");
                } else {
                    problems.add(surface + ": no tabbed panel opened after " + PANEL_OPEN_ATTEMPTS
                            + " taps (last screen " + evidence + "); surface skipped.");
                    logWarning("bg_deals_telemetry | " + surface + " did not open a tabbed panel after "
                            + PANEL_OPEN_ATTEMPTS + " taps (last screen " + evidence + "); skipping it.");
                }
            }
        } catch (RuntimeException failed) {
            recordFailure(surface, failed);
        }
    }

    /**
     * Waits for a panel that is still sliding in or loading. Returns {@code null} once a tab strip is
     * visible, otherwise the last frame checked.
     */
    private RawImageData awaitTabbedPanel() {
        sleepTask(PANEL_SETTLE_MS);
        RawImageData frame = capture();
        for (int check = 1; check < PANEL_OPEN_CHECKS; check++) {
            if (TabStripCells.locate(ImageConverter.toBufferedImage(frame)).size() >= MIN_TABS_FOR_STRIP) {
                return null;
            }
            sleepTask(PANEL_RECHECK_MS);
            frame = capture();
        }
        return TabStripCells.locate(ImageConverter.toBufferedImage(frame)).size() >= MIN_TABS_FOR_STRIP
                ? null : frame;
    }

    /**
     * Opens every labelled icon in the right-hand column once. The column is re-read before each
     * icon because closing a pop-up can remove or reorder icons (an expired offer drops out).
     */
    private void surveyShortcutColumn() {
        List<PointData> visited = new ArrayList<>();
        // Every icon any read of the column found. The column is re-read after each pop-up closes, but
        // OCR over the animated city misses labels on some reads: the second read on 2026-09-13 lost two of
        // the three icons the first read had found, and the scan stopped.
        List<Shortcut> known = new ArrayList<>();
        for (int opened = 0; opened < MAX_SHORTCUTS; opened++) {
            Shortcut next;
            RawImageData home;
            try {
                if (!returnToCity()) {
                    problems.add("Shortcut column: skipped because the city view could not be confirmed.");
                    return;
                }
                home = capture();
                List<Shortcut> column = shortcuts(home);
                if (opened == 0) {
                    logInfo("bg_deals_telemetry | Shortcut column (" + saveFrame(home) + "): "
                            + column.stream().map(Shortcut::name).collect(Collectors.joining(", ")));
                }
                for (Shortcut found : column) {
                    if (known.stream().noneMatch(k -> distance(k.icon(), found.icon()) < SAME_ICON_DISTANCE)) {
                        known.add(found);
                    }
                }
                next = known.stream()
                        .filter(s -> visited.stream().noneMatch(v -> distance(v, s.icon()) < SAME_ICON_DISTANCE))
                        .findFirst()
                        .orElse(null);
            } catch (RuntimeException failed) {
                recordFailure("Shortcut column", failed);
                return;
            }
            if (next == null) {
                logInfo("bg_deals_telemetry | Shortcut column: " + visited.size() + " icon(s) tried.");
                return;
            }
            visited.add(next.icon());
            try {
                tapNear(next.icon());
                sleepTask(PANEL_SETTLE_MS);
                RawImageData openedFrame = capture();
                if (meanDiff(home, openedFrame, WHOLE_FRAME) < OPENED_MEAN_DIFF) {
                    problems.add(next.name() + ": icon tapped but nothing opened.");
                    continue;
                }
                if (isAlreadySurveyedPanel(openedFrame)) {
                    logInfo("bg_deals_telemetry | " + next.name() + " opened the Deals panel, already surveyed; skipping.");
                    continue;
                }
                if (TabStripCells.locate(ImageConverter.toBufferedImage(openedFrame)).size() >= MIN_TABS_FOR_STRIP) {
                    surveyTabbedPanel(next.name());
                } else {
                    surveyPage(next.name(), next.name(), MAX_POPUP_SCROLLS, false);
                }
            } catch (RuntimeException failed) {
                recordFailure(next.name(), failed);
            }
        }
        problems.add("Shortcut column: stopped after " + MAX_SHORTCUTS + " icons; more may be unread.");
    }

    /** Label and countdown text in the column, top to bottom, each turned into its icon's tap point. */
    private List<Shortcut> shortcuts(RawImageData home) {
        BufferedImage image = ImageConverter.toBufferedImage(home);
        RawImageData mask = WhiteTextIsolator.isolate(image, 0, 0, image.getWidth(), image.getHeight(), 0);
        List<TextLine> words;
        try {
            words = OcrEngine.recognizeWords(mask, SHORTCUT_TOP_LEFT, SHORTCUT_BOTTOM_RIGHT,
                    CommonOCRSettings.DEAL_PAGE_TEXT_SETTINGS);
        } catch (OcrException unreadable) {
            problems.add("Shortcut column unreadable: " + unreadable.getMessage());
            return List.of();
        }
        List<List<TextLine>> labels = new ArrayList<>();
        words.stream()
                .filter(w -> w.height() >= LABEL_MIN_HEIGHT && w.height() <= LABEL_MAX_HEIGHT)
                .sorted(Comparator.comparingInt(TextLine::top).thenComparingInt(TextLine::left))
                .forEach(word -> labels.stream()
                        .filter(label -> {
                            TextLine last = label.get(label.size() - 1);
                            return Math.abs(last.top() - word.top()) <= SAME_LABEL_ROW
                                    && word.left() - (last.left() + last.width()) <= LABEL_WORD_GAP;
                        })
                        .findFirst()
                        .ifPresentOrElse(label -> label.add(word), () -> labels.add(new ArrayList<>(List.of(word)))));

        List<Shortcut> shortcuts = new ArrayList<>();
        for (List<TextLine> label : labels) {
            String text = label.stream().map(TextLine::text).reduce((a, b) -> a + " " + b).orElse("").trim();
            boolean countdown = COUNTDOWN.matcher(text).find();
            long letters = text.chars().filter(Character::isLetter).count();
            if (!countdown && letters < 4) {
                continue;
            }
            if (!countdown && NOT_A_DEAL_SHORTCUT.matcher(text.replaceAll("[^A-Za-z]", "")).find()) {
                continue;
            }
            int left = label.get(0).left();
            int right = label.get(label.size() - 1).left() + label.get(label.size() - 1).width();
            int top = label.stream().mapToInt(TextLine::top).min().orElse(0);
            PointData icon = new PointData((left + right) / 2, Math.max(SHORTCUT_TOP_LEFT.getY(), top - ICON_ABOVE_LABEL));
            String name = countdown ? "Timed offer" : text.replaceAll("[^A-Za-z' -]", "").replaceAll("^[ '-]+", "").trim();
            shortcuts.add(new Shortcut(name, icon));
        }
        return shortcuts;
    }

    private boolean isAlreadySurveyedPanel(RawImageData frame) {
        BufferedImage image = ImageConverter.toBufferedImage(frame);
        RawImageData mask = WhiteTextIsolator.isolate(image, 0, 0, image.getWidth(), image.getHeight(), 0);
        try {
            String header = OcrEngine.recognizeText(mask, HEADER_TOP_LEFT, HEADER_BOTTOM_RIGHT,
                    CommonOCRSettings.DEAL_PAGE_TEXT_SETTINGS);
            return ALREADY_SURVEYED_HEADER.matcher(header).find();
        } catch (OcrException unreadable) {
            return false;
        }
    }

    /**
     * Tabs are tapped by their measured box, one whole tab at a time; the label read inside each box
     * only names the tab and remembers it across strip swipes.
     */
    private void surveyTabbedPanel(String surface) {
        Set<String> visited = new HashSet<>();
        String opening = surveyPage(surface, null, MAX_PAGE_SCROLLS, true);
        if (opening != null) {
            visited.add(tabKey(opening));
        }
        int surveyed = 0;
        int strips = 0;
        while (strips++ < MAX_STRIP_SWIPES && surveyed < MAX_TABS_PER_PANEL) {
            RawImageData strip = capture();
            BufferedImage image = ImageConverter.toBufferedImage(strip);
            for (TabStripCells.Cell cell : TabStripCells.locate(image)) {
                if (TabStripCells.isSelected(image, cell)) {
                    continue;
                }
                String label = tabLabel(strip, cell);
                if (!label.isEmpty() && visited.contains(tabKey(label))) {
                    continue;
                }
                tapNear(new PointData(cell.centre(), TAB_TAP_Y));
                sleepTask(TAB_SETTLE_MS);
                String title = surveyPage(surface, label.isEmpty() ? null : label, MAX_PAGE_SCROLLS, true);
                visited.add(tabKey(label.isEmpty() && title != null ? title : label));
                surveyed++;
            }
            RawImageData before = capture();
            swipe(STRIP_SWIPE_FROM, STRIP_SWIPE_TO, SWIPE_MS);
            sleepTask(SCROLL_SETTLE_MS);
            if (meanDiff(before, capture(), STRIP_REGION) < STILL_MEAN_DIFF) {
                break;
            }
        }
        logInfo("bg_deals_telemetry | " + surface + ": " + surveyed + " tab(s) surveyed after the opening tab.");
        if (surveyed == 0) {
            problems.add(surface + ": no other tabs were found; only the opening tab was surveyed.");
        }
    }

    private String tabLabel(RawImageData frame, TabStripCells.Cell cell) {
        try {
            return OcrEngine.recognizeLines(frame, new PointData(cell.left() + 4, TAB_LABEL_TOP),
                            new PointData(cell.right() - 4, TAB_LABEL_BOTTOM), CommonOCRSettings.DEAL_TAB_LABEL_SETTINGS)
                    .stream()
                    .map(TextLine::text)
                    .collect(Collectors.joining(" "))
                    .replaceAll("\\s+", " ")
                    .trim();
        } catch (OcrException unreadable) {
            return "";
        }
    }

    /** Letters only, so "Hall of'Heroes" and "Hall of Heroes" are the same tab. */
    private static String tabKey(String label) {
        return label.replaceAll("[^A-Za-z]", "").toLowerCase(Locale.ROOT);
    }

    // ── pages ───────────────────────────────────────────────────────

    /** Reads a page, then scrolls it until the content stops moving. Returns the first read's page title. */
    private String surveyPage(String surface, String tab, int maxScrolls, boolean tabbed) {
        RawImageData frame = capture();
        Optional<DealCarousel.Layout> carousel = DealCarousel.locate(frame);
        if (carousel.isPresent()) {
            return surveyCarousel(frame, carousel.get(), surface, tab, tabbed);
        }
        String firstTitle = null;
        for (int scroll = 0; scroll <= maxScrolls; scroll++) {
            DealFrameReader.Page page = readAndCollect(frame, surface, tab, tabbed);
            if (firstTitle == null) {
                firstTitle = page.title();
            }
            if (page.offers().size() == 1 && page.clippedTileRowY() != null) {
                frame = swipeTileRow(frame, surface, tab, tabbed, page.clippedTileRowY());
            }
            swipe(CONTENT_SWIPE_FROM, CONTENT_SWIPE_TO, SWIPE_MS);
            sleepTask(SCROLL_SETTLE_MS);
            RawImageData next = capture();
            if (meanDiff(frame, next, CONTENT_REGION) < STILL_MEAN_DIFF) {
                return firstTitle;
            }
            frame = next;
        }
        problems.add(surface + " / " + (tab == null ? "opening tab" : tab) + ": still scrolling after "
                + maxScrolls + " swipes; lower offers may be unread.");
        return firstTitle;
    }

    /** Item rows wider than their card scroll sideways; the fifth tile is always clipped. */
    private RawImageData swipeTileRow(RawImageData frame, String surface, String tab, boolean tabbed, int rowY) {
        RawImageData current = frame;
        for (int swipes = 0; swipes < MAX_ROW_SWIPES; swipes++) {
            swipe(new PointData(ROW_SWIPE_FROM_X, rowY), new PointData(ROW_SWIPE_TO_X, rowY), SWIPE_MS);
            sleepTask(SCROLL_SETTLE_MS);
            RawImageData next = capture();
            if (meanDiff(current, next, CONTENT_REGION) < STILL_MEAN_DIFF) {
                break;
            }
            current = next;
            readAndCollect(current, surface, tab, tabbed);
        }
        return current;
    }

    private DealFrameReader.Page readAndCollect(RawImageData frame, String surface, String tab, boolean tabbed) {
        DealFrameReader.Page page = readPage(frame, surface, tab, tabbed);
        collect(surface, tabName(tab, page), page.offers(), null);
        return page;
    }

    /** Reads and logs one frame, and drops its tab if it turns out to hold choose-your-own packs. */
    private DealFrameReader.Page readPage(RawImageData frame, String surface, String tab, boolean tabbed) {
        String file = saveFrame(frame);
        DealFrameReader.Page page = reader.read(frame, surface, tab == null ? "" : tab, file, tabbed);
        String tabName = tabName(tab, page);
        if (page.chooseYourOwn() && chooseYourOwnTabs.add(surface + "|" + tabName)) {
            // The pick slots glow on and off, so earlier frames of this tab may not have been flagged.
            offers.values().removeIf(o -> o.surface().equals(surface) && Objects.equals(o.tab(), tabName));
            logInfo("bg_deals_telemetry | " + surface + " / " + tabName + " holds choose-your-own packs; left out.");
        }
        problems.addAll(page.problems());
        logInfo("bg_deals_telemetry | " + surface + " / " + tabName + " (" + file + "): " + page.offers().size()
                + " offer(s)" + (page.problems().isEmpty() ? "" : ", " + page.problems().size() + " problem(s)"));
        return page;
    }

    /** @param title replaces the title the reader gave each offer, or {@code null} to keep it */
    private void collect(String surface, String tabName, List<DealOffer> read, String title) {
        if (chooseYourOwnTabs.contains(surface + "|" + tabName)) {
            return;
        }
        for (DealOffer offer : read) {
            DealOffer kept = new DealOffer(offer.surface(), tabName, title == null ? offer.title() : title,
                    offer.priceUsd(), offer.priceText(), offer.remaining(), offer.purchased(), offer.items(),
                    offer.frame(), offer.limitPeriod());
            offers.merge(kept.packKey() + "|" + kept.priceText(), kept, bg_deals_telemetry::mergeReads);
        }
    }

    private static String tabName(String tab, DealFrameReader.Page page) {
        return tab == null ? page.title() : tab;
    }

    // ── carousels ───────────────────────────────────────────────────

    /** One read of a carousel pack, kept until every option is known so each can be numbered. */
    private record CarouselRead(int optionCentre, String packName, DealFrameReader.Page page) {
    }

    /**
     * Reads every pack behind a carousel: each option in its row, when it has one, and for each option
     * every tier behind the arrows. A tap on an option keeps the tier, so each option's tiers are stepped
     * until the banner comes round to the one it started on. Every option tap is confirmed by the option
     * frame moving there. Returns the page title of the first read.
     */
    private String surveyCarousel(RawImageData frame, DealCarousel.Layout layout, String surface, String tab,
            boolean tabbed) {
        String where = surface + " / " + (tab == null ? "opening tab" : tab);
        List<CarouselRead> reads = new ArrayList<>();
        Optional<DealCarousel.Box> selected = DealCarousel.selectedOption(frame, layout);
        List<Integer> visited = new ArrayList<>();
        Deque<Integer> pending = new ArrayDeque<>();
        RawImageData current = frame;
        int option = selected.map(DealCarousel.Box::centreX).orElse(0);
        for (int round = 0; round < MAX_CAROUSEL_OPTIONS && current != null; round++) {
            visited.add(option);
            current = surveyTiers(current, layout, option, where, surface, tab, tabbed, reads);
            if (current == null || selected.isEmpty()) {
                break;
            }
            Optional<DealCarousel.Box> shown = DealCarousel.selectedOption(current, layout);
            if (shown.isEmpty()) {
                problems.add(where + ": the option frame vanished; remaining options unread.");
                break;
            }
            int halfWidth = shown.get().width() / 2;
            for (int centre : DealCarousel.otherOptionCentres(current, layout, shown.get())) {
                if (!isNear(visited, centre, halfWidth) && !isNear(pending, centre, halfWidth)) {
                    pending.add(centre);
                }
            }
            Integer next = pending.poll();
            if (next == null) {
                break;
            }
            tapNear(new PointData(next, shown.get().centreY()));
            sleepTask(CAROUSEL_SETTLE_MS);
            current = capture();
            Optional<DealCarousel.Box> moved = DealCarousel.selectedOption(current, layout);
            if (moved.isEmpty() || Math.abs(moved.get().centreX() - next) > halfWidth) {
                problems.add(where + ": tapping the option at x=" + next + " did not select it ("
                        + saveFrame(current) + "); remaining options unread.");
                break;
            }
            option = moved.get().centreX();
        }
        if (!pending.isEmpty()) {
            problems.add(where + ": " + pending.size() + " option(s) left unread after " + MAX_CAROUSEL_OPTIONS
                    + " options.");
        }
        return collectCarousel(reads, surface, tab);
    }

    /**
     * Steps one option's tiers with the right arrow, reading each, until the banner shows the first tier
     * again (Dawn Market wraps round) or stops changing. Returns the last frame, or {@code null} when the
     * carousel is no longer on screen.
     */
    private RawImageData surveyTiers(RawImageData frame, DealCarousel.Layout layout, int option, String where,
            String surface, String tab, boolean tabbed, List<CarouselRead> reads) {
        DealCarousel.Box banner = layout.banner();
        int[] bannerRegion = {banner.x(), banner.y(), banner.right(), banner.bottom()};
        PointData rightArrow = new PointData(layout.rightArrow().centreX(), layout.rightArrow().centreY());
        RawImageData current = frame;
        for (int tier = 0; tier < MAX_CAROUSEL_TIERS; tier++) {
            reads.add(new CarouselRead(option, DealCarousel.bannerTitle(current, layout),
                    readPage(current, surface, tab, tabbed)));
            tapNear(rightArrow);
            sleepTask(CAROUSEL_SETTLE_MS);
            RawImageData next = capture();
            if (DealCarousel.locate(next).isEmpty()) {
                problems.add(where + ": the pack arrows vanished after a tier tap (" + saveFrame(next)
                        + "); remaining packs unread.");
                return null;
            }
            if (meanDiff(next, frame, bannerRegion) < STILL_MEAN_DIFF
                    || meanDiff(next, current, bannerRegion) < STILL_MEAN_DIFF) {
                return next;
            }
            current = next;
        }
        problems.add(where + ": still finding new tiers after " + MAX_CAROUSEL_TIERS + "; later tiers unread.");
        return current;
    }

    /** Names each read by its banner and, when there is an option row, by the option's place in it. */
    private String collectCarousel(List<CarouselRead> reads, String surface, String tab) {
        List<Integer> options = reads.stream().map(CarouselRead::optionCentre).distinct().sorted().toList();
        for (CarouselRead read : reads) {
            String name = read.packName().isBlank() ? null : read.packName();
            if (name == null) {
                problems.add(surface + " / " + tabName(tab, read.page()) + ": pack name unreadable on "
                        + read.page().offers().stream().map(DealOffer::frame).findFirst().orElse("a frame")
                        + "; kept the page title.");
            } else if (options.size() > 1) {
                name += " (option " + (options.indexOf(read.optionCentre()) + 1) + " of " + options.size() + ")";
            }
            collect(surface, tabName(tab, read.page()), read.page().offers(), name);
        }
        logInfo("bg_deals_telemetry | " + surface + " / " + (tab == null ? "opening tab" : tab) + ": carousel read "
                + reads.size() + " pack page(s) across " + options.size() + " option(s).");
        return reads.isEmpty() ? null : reads.get(0).page().title();
    }

    private static boolean isNear(Collection<Integer> known, int centre, int tolerance) {
        return known.stream().anyMatch(k -> Math.abs(k - centre) <= tolerance);
    }

    /** Two reads of the same pack: keep every item, and the larger quantity when they disagree. */
    private static DealOffer mergeReads(DealOffer first, DealOffer second) {
        Map<String, DealItem> items = new LinkedHashMap<>();
        Stream.concat(first.items().stream(), second.items().stream())
                .forEach(item -> items.merge(item.key(), item, (a, b) -> a.quantity() >= b.quantity() ? a : b));
        return new DealOffer(first.surface(), first.tab(), first.title(),
                first.priceUsd() != null ? first.priceUsd() : second.priceUsd(), first.priceText(),
                first.remaining() != null ? first.remaining() : second.remaining(),
                first.purchased() || second.purchased(), new ArrayList<>(items.values()), first.frame(),
                first.limitPeriod() != null ? first.limitPeriod() : second.limitPeriod());
    }

    // ── navigation ──────────────────────────────────────────────────

    /**
     * Presses back until the city view is confirmed by its Deals shortcut. The engine's home check
     * alone is not enough here: on 2026-09-13 it matched the furnace template while the Deals panel was
     * still open, so the shortcut scan read and tapped the panel instead of the city. The shortcut is
     * covered by every panel and pop-up this task opens.
     */
    private boolean returnToCity() {
        for (int attempt = 0; attempt <= MAX_BACKS_TO_CITY; attempt++) {
            try {
                navigationHelper.ensureCorrectScreenLocation(LaunchPoint.HOME);
            } catch (RuntimeException notYet) {
                logDebug("bg_deals_telemetry | Home check failed on attempt " + attempt + ": " + notYet.getMessage());
            }
            if (templateSearchHelper.locatePattern(TemplatesEnum.HOME_DEALS_BUTTON,
                    SearchConfigConstants.DEFAULT_SINGLE).isFound()) {
                return true;
            }
            pressBack();
            sleepTask(BACK_SETTLE_MS);
        }
        logWarning("bg_deals_telemetry | City view not confirmed after " + MAX_BACKS_TO_CITY + " back presses.");
        return false;
    }

    // ── frames ──────────────────────────────────────────────────────

    private RawImageData capture() {
        RawImageData frame = emuManager.captureScreen(EMULATOR_NUMBER);
        if (frame == null) {
            throw new IllegalStateException("screen capture returned nothing");
        }
        return frame;
    }

    private String saveFrame(RawImageData frame) {
        String name = String.format("%03d.png", ++frameNumber);
        try {
            ImageIO.write(ImageConverter.toBufferedImage(frame), "png", frameDir.resolve(name).toFile());
        } catch (IOException | RuntimeException saveFailed) {
            problems.add("Frame " + name + " not saved: " + saveFailed.getMessage());
        }
        return name;
    }

    private static double meanDiff(RawImageData a, RawImageData b, int[] region) {
        BufferedImage left = ImageConverter.toBufferedImage(a);
        BufferedImage right = ImageConverter.toBufferedImage(b);
        int x1 = Math.min(region[2], Math.min(left.getWidth(), right.getWidth()));
        int y1 = Math.min(region[3], Math.min(left.getHeight(), right.getHeight()));
        long total = 0;
        long samples = 0;
        for (int y = region[1]; y < y1; y += 3) {
            for (int x = region[0]; x < x1; x += 3) {
                int p = left.getRGB(x, y);
                int q = right.getRGB(x, y);
                total += Math.abs(((p >> 16) & 0xFF) - ((q >> 16) & 0xFF))
                        + Math.abs(((p >> 8) & 0xFF) - ((q >> 8) & 0xFF))
                        + Math.abs((p & 0xFF) - (q & 0xFF));
                samples += 3;
            }
        }
        return samples == 0 ? 0 : (double) total / samples;
    }

    private static double distance(PointData a, PointData b) {
        return Math.hypot(a.getX() - b.getX(), a.getY() - b.getY());
    }

    private void recordFailure(String surface, RuntimeException failed) {
        problems.add(surface + ": " + failed.getClass().getSimpleName() + " " + failed.getMessage());
        logError("bg_deals_telemetry | " + surface + " failed with " + failed.getClass().getName() + ": "
                + failed.getMessage());
    }

    private void pruneOldFrames(LocalDate today) {
        LocalDate keepFrom = today.minusDays(FRAME_RETENTION_DAYS - 1L);
        try (Stream<Path> dirs = Files.list(store.scansDir())) {
            for (Path dir : dirs.filter(Files::isDirectory).toList()) {
                LocalDate day;
                try {
                    day = LocalDate.parse(dir.getFileName().toString());
                } catch (RuntimeException notAFrameDir) {
                    continue;
                }
                if (day.isBefore(keepFrom)) {
                    try (Stream<Path> frames = Files.list(dir)) {
                        for (Path frame : frames.toList()) {
                            Files.deleteIfExists(frame);
                        }
                    }
                    Files.deleteIfExists(dir);
                }
            }
        } catch (IOException pruneFailed) {
            logWarning("bg_deals_telemetry | Could not prune old frames: " + pruneFailed.getMessage());
        }
    }
}
