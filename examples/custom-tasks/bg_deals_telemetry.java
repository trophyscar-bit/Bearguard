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
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;
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
import dev.frostguard.vision.ocr.OcrEngine;
import dev.frostguard.vision.ocr.OcrException;
import dev.frostguard.vision.ocr.TextLine;

/**
 * Daily read-only survey of every paid offer: the gem shop, the Deals panel, and every shortcut
 * icon in the city view's right-hand column. Writes one scan per day under {@code data/deals},
 * which the Deal Tracker page scores.
 *
 * <p>Never taps a price button. The only interactions are opening a surface, tapping a tab label
 * in the tab strip band, swiping, and back.</p>
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

    private static final int MAX_TABS_PER_PANEL = 40;
    private static final int MAX_STRIP_SWIPES = 20;
    private static final int MAX_PAGE_SCROLLS = 8;
    private static final int MAX_POPUP_SCROLLS = 3;
    private static final int MAX_ROW_SWIPES = 3;
    private static final int MAX_SHORTCUTS = 12;

    /** Tab labels sit in this band on both the gem shop and the Deals panel. */
    private static final PointData TAB_LABEL_TOP_LEFT = new PointData(30, 140);
    private static final PointData TAB_LABEL_BOTTOM_RIGHT = new PointData(700, 190);
    private static final int TAB_TAP_Y = 150;
    /** Labels clipped by the strip edges are read again after the next swipe instead. */
    private static final int TAB_EDGE_MARGIN = 12;
    /** A panel is tabbed when its strip shows at least this many labels. */
    private static final int MIN_TABS_FOR_STRIP = 2;
    /** About one 198 px tab per gesture: 500 px swipes skipped whole labels between frames. */
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
     * print 28-42 px below their icon's centre, and the column's lowest deal icon label sat at y 582;
     * the paw, scales and mail buttons below it are not deal surfaces.
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

    private final Map<String, DealOffer> offers = new LinkedHashMap<>();
    private final List<String> problems = new ArrayList<>();
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

        surveyTemplatePanel("Gem Shop", TemplatesEnum.HOME_SHOP_CART_BUTTON);
        surveyTemplatePanel("Deals", TemplatesEnum.HOME_DEALS_BUTTON);
        surveyShortcutColumn();

        if (offers.isEmpty()) {
            problems.add("No offers were read at all.");
            logWarning("bg_deals_telemetry | Scan finished with no offers read; the scan file records it as a failure.");
        }
        try {
            store.write(day, new DealScan(LocalDateTime.now().toString(), new ArrayList<>(offers.values()), problems));
            logInfo("bg_deals_telemetry | Wrote " + offers.size() + " offer(s) and " + problems.size() + " problem(s) for "
                    + day + ".");
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

    private void surveyTemplatePanel(String surface, TemplatesEnum shortcut) {
        try {
            navigationHelper.ensureCorrectScreenLocation(LaunchPoint.HOME);
            ImageSearchResultData button = templateSearchHelper.locatePattern(
                    shortcut, SearchConfigConstants.SINGLE_WITH_RETRIES);
            if (!button.isFound()) {
                problems.add(surface + ": shortcut not found on the city view; surface skipped.");
                logWarning("bg_deals_telemetry | " + surface + " shortcut not found; skipping it.");
                return;
            }
            tapNear(button.getPoint());
            sleepTask(PANEL_SETTLE_MS);
            surveyTabbedPanel(surface);
        } catch (RuntimeException failed) {
            recordFailure(surface, failed);
        } finally {
            returnHome();
        }
    }

    /**
     * Opens every labelled icon in the right-hand column once. The column is re-read before each
     * icon because closing a pop-up can remove or reorder icons (an expired offer drops out).
     */
    private void surveyShortcutColumn() {
        List<PointData> visited = new ArrayList<>();
        for (int opened = 0; opened < MAX_SHORTCUTS; opened++) {
            Shortcut next;
            RawImageData home;
            try {
                navigationHelper.ensureCorrectScreenLocation(LaunchPoint.HOME);
                home = capture();
                next = shortcuts(home).stream()
                        .filter(s -> visited.stream().noneMatch(v -> distance(v, s.icon()) < SAME_ICON_DISTANCE))
                        .findFirst()
                        .orElse(null);
            } catch (RuntimeException failed) {
                recordFailure("Shortcut column", failed);
                return;
            }
            if (next == null) {
                logInfo("bg_deals_telemetry | Shortcut column: " + visited.size() + " icon(s) opened.");
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
                if (tabLabels(openedFrame).size() >= MIN_TABS_FOR_STRIP) {
                    surveyTabbedPanel(next.name());
                } else {
                    surveyPage(next.name(), next.name(), MAX_POPUP_SCROLLS, false);
                }
            } catch (RuntimeException failed) {
                recordFailure(next.name(), failed);
            } finally {
                returnHome();
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
            String name = countdown ? "Timed offer" : text.replaceAll("[^A-Za-z' -]", "").trim();
            shortcuts.add(new Shortcut(name, icon));
        }
        return shortcuts;
    }

    private void surveyTabbedPanel(String surface) {
        Set<String> visited = new HashSet<>();
        surveyPage(surface, null, MAX_PAGE_SCROLLS, true);
        int strips = 0;
        while (strips++ < MAX_STRIP_SWIPES && visited.size() < MAX_TABS_PER_PANEL) {
            for (TextLine label : tabLabels(capture())) {
                String name = label.text().replaceAll("\\s+", " ").trim();
                if (!visited.add(name.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                tapNear(new PointData(label.left() + label.width() / 2, TAB_TAP_Y));
                sleepTask(TAB_SETTLE_MS);
                surveyPage(surface, name, MAX_PAGE_SCROLLS, true);
            }
            RawImageData before = capture();
            swipe(STRIP_SWIPE_FROM, STRIP_SWIPE_TO, SWIPE_MS);
            sleepTask(SCROLL_SETTLE_MS);
            if (meanDiff(before, capture(), STRIP_REGION) < STILL_MEAN_DIFF) {
                break;
            }
        }
        logInfo("bg_deals_telemetry | " + surface + ": " + visited.size() + " labelled tab(s) surveyed.");
        if (visited.isEmpty()) {
            problems.add(surface + ": no tab labels were readable; only the opening tab was surveyed.");
        }
    }

    // ── pages ───────────────────────────────────────────────────────

    /** Reads a page, then scrolls it until the content stops moving. */
    private void surveyPage(String surface, String tab, int maxScrolls, boolean tabbed) {
        RawImageData frame = capture();
        for (int scroll = 0; scroll <= maxScrolls; scroll++) {
            DealFrameReader.Page page = readAndCollect(frame, surface, tab, tabbed);
            if (page.offers().size() == 1 && page.clippedTileRowY() != null) {
                frame = swipeTileRow(frame, surface, tab, tabbed, page.clippedTileRowY());
            }
            swipe(CONTENT_SWIPE_FROM, CONTENT_SWIPE_TO, SWIPE_MS);
            sleepTask(SCROLL_SETTLE_MS);
            RawImageData next = capture();
            if (meanDiff(frame, next, CONTENT_REGION) < STILL_MEAN_DIFF) {
                return;
            }
            frame = next;
        }
        problems.add(surface + " / " + (tab == null ? "opening tab" : tab) + ": still scrolling after "
                + maxScrolls + " swipes; lower offers may be unread.");
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
        for (DealOffer read : page.offers()) {
            DealOffer offer = new DealOffer(read.surface(), tabName, read.title(), read.priceUsd(), read.priceText(),
                    read.remaining(), read.purchased(), read.items(), read.frame());
            offers.merge(offer.packKey() + "|" + offer.priceText(), offer, bg_deals_telemetry::mergeReads);
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
                first.purchased() || second.purchased(), new ArrayList<>(items.values()), first.frame());
    }

    private List<TextLine> tabLabels(RawImageData frame) {
        try {
            return OcrEngine.recognizeLines(frame, TAB_LABEL_TOP_LEFT, TAB_LABEL_BOTTOM_RIGHT,
                            CommonOCRSettings.DEAL_TAB_LABEL_SETTINGS).stream()
                    .filter(l -> l.text().chars().filter(Character::isLetter).count() >= 3)
                    .filter(l -> l.left() > TAB_LABEL_TOP_LEFT.getX() + TAB_EDGE_MARGIN
                            && l.left() + l.width() < TAB_LABEL_BOTTOM_RIGHT.getX() - TAB_EDGE_MARGIN)
                    .sorted(Comparator.comparingInt(TextLine::left))
                    .toList();
        } catch (OcrException unreadable) {
            problems.add("Tab strip unreadable: " + unreadable.getMessage());
            return List.of();
        }
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
        logError("bg_deals_telemetry | " + surface + " failed with " + failed.getClass().getName() + ": " + failed.getMessage());
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

    private void returnHome() {
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                navigationHelper.ensureCorrectScreenLocation(LaunchPoint.HOME);
                return;
            } catch (RuntimeException notYet) {
                pressBack();
                sleepTask(800);
            }
        }
    }
}
