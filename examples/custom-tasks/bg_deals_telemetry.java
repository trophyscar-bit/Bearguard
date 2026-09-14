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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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
 * tab strip band, swiping, and back.</p>
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
    private static final int TAB_SETTLE_MS = 2000;
    private static final int SCROLL_SETTLE_MS = 1600;
    private static final int BACK_SETTLE_MS = 1200;

    private static final int MAX_TABS_PER_PANEL = 40;
    private static final int MAX_STRIP_SWIPES = 20;
    private static final int MAX_PAGE_SCROLLS = 8;
    private static final int MAX_POPUP_SCROLLS = 3;
    private static final int MAX_ROW_SWIPES = 3;
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
            if (!returnToCity()) {
                problems.add(surface + ": skipped because the city view could not be confirmed first.");
                return;
            }
            ImageSearchResultData button = templateSearchHelper.locatePattern(
                    shortcut, SearchConfigConstants.SINGLE_WITH_RETRIES);
            if (button.isFound()) {
                tapNear(button.getPoint());
            } else if (hudFallback != null) {
                logWarning("bg_deals_telemetry | " + surface + " shortcut template did not match; trying its fixed "
                        + "top-bar position " + hudFallback.getX() + "," + hudFallback.getY() + ".");
                tapNear(hudFallback);
            } else {
                problems.add(surface + ": shortcut not found on the city view; surface skipped.");
                logWarning("bg_deals_telemetry | " + surface + " shortcut not found; skipping it.");
                return;
            }
            sleepTask(PANEL_SETTLE_MS);
            if (TabStripCells.locate(ImageConverter.toBufferedImage(capture())).size() < MIN_TABS_FOR_STRIP) {
                problems.add(surface + ": no tabbed panel opened from its shortcut; surface skipped.");
                logWarning("bg_deals_telemetry | " + surface + " did not open a tabbed panel; skipping it.");
                return;
            }
            surveyTabbedPanel(surface);
        } catch (RuntimeException failed) {
            recordFailure(surface, failed);
        }
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
        String file = saveFrame(frame);
        DealFrameReader.Page page = reader.read(frame, surface, tab == null ? "" : tab, file, tabbed);
        String tabName = tab == null ? page.title() : tab;
        String tabKey = surface + "|" + tabName;
        if (page.chooseYourOwn()) {
            if (chooseYourOwnTabs.add(tabKey)) {
                // The pick slots glow on and off, so earlier frames of this tab may not have been flagged.
                offers.values().removeIf(o -> o.surface().equals(surface) && Objects.equals(o.tab(), tabName));
                logInfo("bg_deals_telemetry | " + surface + " / " + tabName + " holds choose-your-own packs; left out.");
            }
        } else if (!chooseYourOwnTabs.contains(tabKey)) {
            for (DealOffer read : page.offers()) {
                DealOffer offer = new DealOffer(read.surface(), tabName, read.title(), read.priceUsd(), read.priceText(),
                        read.remaining(), read.purchased(), read.items(), read.frame(), read.limitPeriod());
                offers.merge(offer.packKey() + "|" + offer.priceText(), offer, bg_deals_telemetry::mergeReads);
            }
        }
        problems.addAll(page.problems());
        logInfo("bg_deals_telemetry | " + surface + " / " + tabName + " (" + file + "): " + page.offers().size()
                + " offer(s)" + (page.problems().isEmpty() ? "" : ", " + page.problems().size() + " problem(s)"));
        return page;
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
