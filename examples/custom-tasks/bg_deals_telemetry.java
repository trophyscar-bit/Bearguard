package dev.frostguard.engine.listener.task.impl;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
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
import dev.frostguard.api.deals.DealPriceTracker;
import dev.frostguard.api.deals.DealScoring;
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
import dev.frostguard.vision.deals.PriceButtonLocator;
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
 * <p>City event icons are tapped by position rather than recognised, because the column changes with
 * live events and neither their pictures nor their labels can be relied on. Whatever opens is named by
 * its own panel header and read like any other surface, so an event nobody has seen before is still
 * covered.</p>
 */
public class bg_deals_telemetry extends DelayedTask implements CustomTaskConfigurable {

    /**
     * Two hours after the 00:00 UTC daily reset: 22:00 Eastern daylight time, 21:00 standard. Kept in UTC
     * so it follows the reset, not the clock change.
     */
    private static final LocalTime RUN_AT_UTC = LocalTime.of(2, 0);
    private static final int RUN_JITTER_MINUTES = 10;
    private static final int FRAME_RETENTION_DAYS = 7;
    /** History the nightly report scores against; the verdict bands compare a week. */
    private static final int REPORT_HISTORY_DAYS = 30;
    private static final List<String> GOOD_VERDICTS = List.of("Best this week", "Great");
    /** The item library's name for Fire Crystals; a pack's crystals are counted under this key. */
    private static final String FIRE_CRYSTAL = "Fire Crystal";
    /** Bands for dollars per crystal, against the cheapest crystal of the same day. */
    private static final double FC_GOOD_RATIO = 1.15;
    private static final double FC_OK_RATIO = 1.60;
    /** Cards grant their items on every day they stay valid, which no page states as a total. */
    private static final Pattern DAILY_REWARD_CARD = Pattern.compile("(?i)\\bcard\\b");
    private static final com.fasterxml.jackson.databind.ObjectMapper REPORT_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();
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
    /** Safety ceiling only: a page now ends when swiping stops finding offers, not at a fixed count. */
    private static final int MAX_PAGE_SCROLLS = 25;
    private static final int MAX_POPUP_SCROLLS = 10;
    /** Two swipes that add no offer nobody has seen means the list has ended. */
    private static final int BARREN_SWIPES_FOR_END = 2;
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
    /**
     * Panel pages are dragged by the cards' left margin. Card item rows scroll sideways, and a vertical drag
     * that starts on one moves the row, not the page: on 2026-09-19 the Regular Pack tab stopped at Mastery
     * Forging with more cards below, because x 360 landed on its item row; drags at x 34 went on to Pet
     * Food, Taming Manual and Pet Energy. Pop-ups are narrower than the panel and keep the centre line.
     */
    private static final PointData PANEL_SWIPE_FROM = new PointData(34, 1000);
    private static final PointData PANEL_SWIPE_TO = new PointData(34, 600);
    private static final PointData POPUP_SWIPE_FROM = new PointData(360, 1000);
    private static final PointData POPUP_SWIPE_TO = new PointData(360, 600);
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
     * Event icons on the city view. They fill up to two fixed columns on the right of the map, about 95 px
     * apart, and are tapped by position: their labels are cream on snow, which the white-text mask cannot
     * isolate, and OCR read nothing at all on the 2026-09-21 city frame, so the scan opened no icon on any
     * run. A slot that opens nothing is skipped, and whatever does open is named by its own header. The paw,
     * scales and mail buttons below y 760 are not deal surfaces.
     */
    private static final int MIN_HEADER_LETTERS = 3;
    /** Half an icon apart: a slot this close to a located shortcut is that shortcut. */
    private static final int SAME_ICON_RADIUS = 50;
    private static final int[] ICON_COLUMN_X = {557, 665};
    private static final int[] ICON_ROW_Y = {160, 260, 355, 455, 550};
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
    private final List<Map<String, Object>> coverage = new ArrayList<>();
    /** Tabs found to hold choose-your-own packs; nothing from them is kept. */
    private final Set<String> chooseYourOwnTabs = new HashSet<>();
    private DealsStore store;
    private DealFrameReader reader;
    private Path frameDir;
    private int frameNumber;

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
                    + RUN_AT_UTC + " UTC schedule.");
        }
    }

    @Override
    protected void execute() {
        offers.clear();
        problems.clear();
        coverage.clear();
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
            writeReport(day);
        } catch (IOException writeFailed) {
            logError("bg_deals_telemetry | Could not write the scan for " + day + ": " + writeFailed.getMessage());
        }
        pruneOldFrames(day);
        reschedule(nextRun(LocalDateTime.now()));
    }

    /**
     * Writes {@code data/deals/reports/<day>.json} beside the scan: today's offers with the verdict
     * {@link DealScoring} gave them, what changed since the previous scan, and the offers whose price or
     * items could not be read, so a reader does not have to re-score the history itself.
     */
    private void writeReport(LocalDate day) {
        try {
            DealsStore.Loaded loaded = store.readSince(day.minusDays(REPORT_HISTORY_DAYS));
            Map<String, Double> manual = store.readItemValues();
            List<DealScoring.ScoredOffer> today = DealScoring.score(loaded.scans(), manual).stream()
                    .filter(s -> day.equals(s.day()))
                    .toList();
            DealScan previous = loaded.scans().entrySet().stream()
                    .filter(e -> e.getKey().isBefore(day))
                    .max(Map.Entry.comparingByKey())
                    .map(Map.Entry::getValue)
                    .orElse(null);
            Map<String, DealOffer> before = new LinkedHashMap<>();
            if (previous != null) {
                previous.offers().forEach(o -> before.put(o.packKey() + "|" + o.priceText(), o));
            }

            List<Map<String, Object>> scoredRows = new ArrayList<>();
            List<Map<String, Object>> best = new ArrayList<>();
            List<Map<String, Object>> skip = new ArrayList<>();
            List<Map<String, Object>> changed = new ArrayList<>();
            List<Map<String, Object>> unreadable = new ArrayList<>();
            for (DealScoring.ScoredOffer scored : today) {
                DealOffer offer = scored.offer();
                if (offer.priceUsd() == null || offer.items().isEmpty()) {
                    unreadable.add(entry(scored, offer.priceUsd() == null
                            ? "price unreadable (" + offer.priceText() + ")" : "no items read"));
                    continue;
                }
                Map<String, Object> row = entry(scored, why(scored));
                // Every readable offer, so a reader showing one pack's verdict does not have to guess
                // from which list it is missing: an unchanged "Fair" pack is in none of the others.
                scoredRows.add(row);
                if (GOOD_VERDICTS.stream().anyMatch(v -> scored.verdict().contains(v))) {
                    best.add(row);
                } else if (scored.verdict().contains("Bad")) {
                    skip.add(row);
                }
                DealOffer was = before.get(offer.packKey() + "|" + offer.priceText());
                if (previous == null) {
                    continue;
                }
                if (was == null) {
                    changed.add(entry(scored, "new since " + previous.scannedAt().substring(0, 10)));
                } else if (!Objects.equals(was.priceUsd(), offer.priceUsd())) {
                    changed.add(entry(scored, "was " + was.priceText()));
                }
            }
            best.sort(Comparator.comparingDouble(r -> -((Number) r.get("score")).doubleValue()));
            skip.sort(Comparator.comparingDouble(r -> ((Number) r.get("score")).doubleValue()));

            Map<String, Object> report = new LinkedHashMap<>();
            report.put("day", day.toString());
            report.put("writtenAt", LocalDateTime.now().toString());
            report.put("offers", today.size());
            report.put("scored", scoredRows);
            report.put("best", best);
            report.put("skip", skip);
            report.put("changed", changed);
            report.put("unreadable", unreadable);
            report.put("fireCrystals", fireCrystals(today, DealPriceTracker.unitValues(loaded.scans(), manual)));
            report.put("coverage", coverageRows());
            report.put("problems", problems);
            Path dir = store.root().resolve("reports");
            Files.createDirectories(dir);
            Path file = dir.resolve(day + ".json");
            Path temporary = dir.resolve(day + ".json.tmp");
            REPORT_MAPPER.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), report);
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            logInfo("bg_deals_telemetry | Report " + file.getFileName() + ": " + scoredRows.size() + " scored, "
                    + best.size() + " best, "
                    + skip.size() + " to skip, " + changed.size() + " changed, " + unreadable.size() + " unreadable.");
        } catch (IOException | RuntimeException reportFailed) {
            problems.add("Report for " + day + " not written: " + reportFailed.getMessage());
            logWarning("bg_deals_telemetry | Could not write the report for " + day + ": " + reportFailed.getMessage());
        }
    }

    /**
     * Every priced offer holding Fire Crystals, cheapest crystal first. Two prices are given: the whole
     * pack's price over its crystals, and the price left after the rest of the pack is valued at the
     * fitted item prices, which is what the crystals themselves cost. A pack whose other items are worth
     * more than the asking price leaves nothing to charge the crystals, so its net price is zero.
     */
    private static List<Map<String, Object>> fireCrystals(List<DealScoring.ScoredOffer> today,
            Map<String, Double> unitValues) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (DealScoring.ScoredOffer scored : today) {
            DealOffer offer = scored.offer();
            long crystals = offer.items().stream()
                    .filter(i -> FIRE_CRYSTAL.equals(i.key()))
                    .mapToLong(DealItem::quantity)
                    .sum();
            if (crystals <= 0 || offer.priceUsd() == null) {
                continue;
            }
            double others = 0;
            int valued = 0;
            int priceable = 0;
            for (DealItem item : offer.items()) {
                if (FIRE_CRYSTAL.equals(item.key())) {
                    continue;
                }
                priceable++;
                Double unit = unitValues.get(item.key());
                if (unit != null) {
                    others += unit * item.quantity();
                    valued++;
                }
            }
            double net = Math.max(0, offer.priceUsd() - others);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("title", offer.title());
            row.put("surface", offer.surface());
            row.put("tab", offer.tab());
            row.put("priceUsd", offer.priceUsd());
            row.put("fireCrystals", crystals);
            row.put("usdPerCrystalWholePack", round(offer.priceUsd() / crystals));
            row.put("usdPerCrystalNet", round(net / crystals));
            row.put("otherItemsValuedUsd", round(others));
            row.put("otherItemsPriced", valued + " of " + priceable);
            row.put("dailyRewardsNotTotalled", DAILY_REWARD_CARD.matcher(offer.title() + " " + offer.tab()).find());
            row.put("remaining", offer.remaining());
            row.put("limitPeriod", offer.limitPeriod());
            row.put("purchasesPerMonth", offer.purchasesPerMonth());
            row.put("frame", offer.frame());
            rows.add(row);
        }
        // Ranked on the whole-pack price, not the net one. The fitted values routinely account for the
        // entire asking price (a $49.99 pack valued at $49.99 of gems, speedups and resources), which
        // leaves the crystals at $0.000 and makes every row look equally good. Net stays as a lower bound.
        rows.sort(Comparator.comparingDouble(r -> ((Number) r.get("usdPerCrystalWholePack")).doubleValue()));
        double best = rows.stream()
                .filter(r -> !(boolean) r.get("dailyRewardsNotTotalled"))
                .mapToDouble(r -> ((Number) r.get("usdPerCrystalWholePack")).doubleValue())
                .min()
                .orElse(0);
        boolean first = true;
        for (Map<String, Object> row : rows) {
            double whole = ((Number) row.get("usdPerCrystalWholePack")).doubleValue();
            double net = ((Number) row.get("usdPerCrystalNet")).doubleValue();
            if ((boolean) row.get("dailyRewardsNotTotalled")) {
                // A card grants crystals every day it is valid, and the page states neither the total nor
                // the up-front grant in a form this reader adds up. Its figure is one day's worth, so it
                // reads far too expensive: calling it "skip" would bury the cheapest crystals in the shop.
                row.put("verdict", "unknown");
                row.put("why", String.format(Locale.ROOT,
                        "card: $%.3f counts one day's %d crystals only, not the whole validity period",
                        whole, ((Number) row.get("fireCrystals")).longValue()));
                continue;
            }
            row.put("verdict", whole <= best * FC_GOOD_RATIO ? "good"
                    : whole <= best * FC_OK_RATIO ? "ok" : "skip");
            row.put("why", String.format(Locale.ROOT,
                    "$%.3f per crystal for the whole pack (at least $%.3f once its other items are valued"
                            + " at $%.2f)%s",
                    whole, net, ((Number) row.get("otherItemsValuedUsd")).doubleValue(),
                    first ? "; cheapest crystals today" : ""));
            first = false;
        }
        return rows;
    }

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    /**
     * The surfaces this run knew about, each with what it found there. A surface that was skipped, or that
     * stopped scrolling with more below, says so here rather than simply contributing fewer offers.
     */
    private List<Map<String, Object>> coverageRows() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> recorded : coverage) {
            String surface = (String) recorded.get("surface");
            List<DealOffer> mine = offers.values().stream()
                    .filter(o -> surface.equals(o.surface()))
                    .toList();
            Map<String, Object> row = new LinkedHashMap<>(recorded);
            row.put("offers", mine.size());
            row.put("unreadable", mine.stream()
                    .filter(o -> o.priceUsd() == null || o.items().isEmpty())
                    .count());
            row.put("fullyScrolled", problems.stream()
                    .noneMatch(problem -> problem.startsWith(surface + " ") && problem.contains("still scrolling")));
            rows.add(row);
        }
        return rows;
    }

    private static Map<String, Object> entry(DealScoring.ScoredOffer scored, String why) {
        DealOffer offer = scored.offer();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("title", offer.title());
        row.put("surface", offer.surface());
        row.put("tab", offer.tab());
        row.put("priceUsd", offer.priceUsd());
        row.put("priceText", offer.priceText());
        row.put("score", scored.score());
        row.put("verdict", scored.verdict());
        row.put("basis", scored.basis());
        row.put("daysSeen", scored.daysSeen());
        row.put("limitPeriod", offer.limitPeriod());
        row.put("items", offer.items().stream()
                .map(i -> Map.of("key", i.key(), "quantity", i.quantity()))
                .toList());
        row.put("frame", offer.frame());
        row.put("why", why);
        return row;
    }

    /** One line a reader can print: what the pack is worth per dollar, and where that number came from. */
    private static String why(DealScoring.ScoredOffer scored) {
        String worth = String.format(Locale.ROOT, "%.2fx the items' value for the price (%s)",
                scored.score(), scored.basis());
        if (scored.lowestEarlierPrice() != null && scored.offer().priceUsd() != null
                && scored.offer().priceUsd() < scored.lowestEarlierPrice()) {
            return worth + ", cheapest seen";
        }
        return worth;
    }

    static LocalDateTime nextRun(LocalDateTime now) {
        ZonedDateTime nowUtc = now.atZone(ZoneId.systemDefault()).withZoneSameInstant(ZoneOffset.UTC);
        ZonedDateTime today = nowUtc.toLocalDate().atTime(RUN_AT_UTC).atZone(ZoneOffset.UTC);
        ZonedDateTime next = nowUtc.isBefore(today) ? today : today.plusDays(1);
        return next.plusMinutes(ThreadLocalRandom.current().nextInt(RUN_JITTER_MINUTES + 1))
                .withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
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
                    recordCoverage(surface, false, null, null, "city view not confirmed");
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
                    recordCoverage(surface, false, null, null, "shortcut not found on the city view");
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
                    recordCoverage(surface, false, null, null, "no tabbed panel opened after "
                            + PANEL_OPEN_ATTEMPTS + " taps");
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
        Set<String> surveyed = new HashSet<>();
        // The Deals icon sits in the same column. Its panel header did not read on the 2026-09-22 run, so
        // the whole panel was surveyed a second time under a slot name, costing a quarter of the run and
        // filing its offers twice. Its own template says where it is, so that slot is left alone. It has to
        // be located on the city view: looked for while the previous surface was still open, it was never
        // found, and the guard never fired (City icon 665,260 duplicated Deals on 16 of 16 days).
        ImageSearchResultData dealsIcon;
        try {
            if (!returnToCity()) {
                problems.add("City icons: skipped because the city view could not be confirmed.");
                recordCoverage("City icons", false, null, null, "city view not confirmed");
                return;
            }
            dealsIcon = templateSearchHelper.locatePattern(TemplatesEnum.HOME_DEALS_BUTTON,
                    SearchConfigConstants.DEFAULT_SINGLE);
        } catch (RuntimeException failed) {
            recordFailure("City icons", failed);
            return;
        }
        if (!dealsIcon.isFound()) {
            problems.add("City icons: the Deals shortcut was not found on the city view, so its slot is not"
                    + " excluded and the Deals panel may be surveyed twice.");
        }
        int opened = 0;
        for (int x : ICON_COLUMN_X) {
            for (int y : ICON_ROW_Y) {
                if (opened >= MAX_SHORTCUTS) {
                    problems.add("City icons: stopped after " + MAX_SHORTCUTS + " panels; more may be unread.");
                    return;
                }
                String slot = x + "," + y;
                if (dealsIcon.isFound() && Math.abs(dealsIcon.getPoint().getX() - x) < SAME_ICON_RADIUS
                        && Math.abs(dealsIcon.getPoint().getY() - y) < SAME_ICON_RADIUS) {
                    logInfo("bg_deals_telemetry | " + slot + " is the Deals shortcut, already surveyed; skipping.");
                    continue;
                }
                try {
                    if (!returnToCity()) {
                        problems.add("City icons: skipped because the city view could not be confirmed.");
                        return;
                    }
                    RawImageData home = capture();
                    tapNear(new PointData(x, y));
                    sleepTask(PANEL_SETTLE_MS);
                    RawImageData frame = capture();
                    if (meanDiff(home, frame, WHOLE_FRAME) < OPENED_MEAN_DIFF) {
                        logDebug("bg_deals_telemetry | No icon at " + slot + "; nothing opened.");
                        continue;
                    }
                    opened++;
                    String header = panelHeader(frame);
                    // Panels with no header of their own give back their countdown or stray glyphs
                    // ("03:0232/32ee", "~"); those are not names.
                    if (header.chars().filter(Character::isLetter).count() < MIN_HEADER_LETTERS) {
                        header = "";
                    }
                    String name = header.isBlank() ? "City icon " + slot : header;
                    BufferedImage image = ImageConverter.toBufferedImage(frame);
                    boolean tabbed = TabStripCells.locate(image).size() >= MIN_TABS_FOR_STRIP;
                    // A city building opens a panel too: the infirmary's Heal Injured page was read as a
                    // surface and its "Finish - 94" button kept as an offer. A shop has tabs or a price button.
                    if (!tabbed && PriceButtonLocator.locate(image).isEmpty()) {
                        logInfo("bg_deals_telemetry | " + slot + " opened " + name
                                + " with no tabs or price buttons; not a deal surface.");
                        continue;
                    }
                    if (NOT_A_DEAL_SHORTCUT.matcher(header).find()) {
                        logInfo("bg_deals_telemetry | " + slot + " opened " + name + ", not a deal surface; skipping.");
                        continue;
                    }
                    // Pack pop-ups carry their name in the artwork, not a header, so an unreadable header
                    // is deduplicated by its slot: tabKey drops the digits and would fold every one of them
                    // into a single "cityicon" entry, skipping every icon after the first.
                    if (!surveyed.add(header.isBlank() ? "slot " + slot : tabKey(header))) {
                        logInfo("bg_deals_telemetry | " + slot + " opened " + name + " again; skipping.");
                        continue;
                    }
                    if (tabbed) {
                        surveyTabbedPanel(name);
                    } else {
                        surveyPage(name, name, MAX_POPUP_SCROLLS, false);
                        recordCoverage(name, true, null, null, null);
                    }
                } catch (RuntimeException failed) {
                    recordFailure("City icon " + slot, failed);
                }
            }
        }
        logInfo("bg_deals_telemetry | City icons: " + opened + " panel(s) opened, " + surveyed.size() + " surveyed.");
    }

    /** Label and countdown text in the column, top to bottom, each turned into its icon's tap point. */
    /** The panel's own name, from the white header beside its back arrow, or "" when it cannot be read. */
    private String panelHeader(RawImageData frame) {
        BufferedImage image = ImageConverter.toBufferedImage(frame);
        RawImageData mask = WhiteTextIsolator.isolate(image, 0, 0, image.getWidth(), image.getHeight(), 0);
        try {
            return OcrEngine.recognizeText(mask, HEADER_TOP_LEFT, HEADER_BOTTOM_RIGHT,
                    CommonOCRSettings.DEAL_PAGE_TEXT_SETTINGS).replaceAll("\\s+", " ").trim();
        } catch (OcrException unreadable) {
            return "";
        }
    }

    private boolean isAlreadySurveyedPanel(RawImageData frame) {
        return ALREADY_SURVEYED_HEADER.matcher(panelHeader(frame)).find();
    }

    /**
     * Tabs are tapped by their measured box, one whole tab at a time; the label read inside each box
     * only names the tab and remembers it across strip swipes.
     */
    private void surveyTabbedPanel(String surface) {
        Set<String> visited = new HashSet<>();
        Set<String> seen = new HashSet<>();
        String opening = surveyPage(surface, null, MAX_PAGE_SCROLLS, true);
        if (opening != null) {
            visited.add(tabKey(opening));
            seen.add(tabKey(opening));
        }
        int surveyed = 0;
        int strips = 0;
        while (strips++ < MAX_STRIP_SWIPES && surveyed < MAX_TABS_PER_PANEL) {
            RawImageData strip = capture();
            BufferedImage image = ImageConverter.toBufferedImage(strip);
            for (TabStripCells.Cell cell : TabStripCells.locate(image)) {
                String seenLabel = tabLabel(strip, cell);
                if (!seenLabel.isEmpty()) {
                    seen.add(tabKey(seenLabel));
                }
                if (TabStripCells.isSelected(image, cell)) {
                    continue;
                }
                String label = seenLabel;
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
        int found = Math.max(seen.size(), surveyed + 1);
        recordCoverage(surface, true, found, visited.size(), null);
        logInfo("bg_deals_telemetry | " + surface + ": " + surveyed + " tab(s) surveyed after the opening tab, "
                + found + " seen in the strip.");
        if (surveyed == 0) {
            problems.add(surface + ": no other tabs were found; only the opening tab was surveyed.");
        }
    }

    /** One row per surface the scan knows about, so a surface that vanishes reads as unvisited. */
    private void recordCoverage(String surface, boolean visited, Integer tabsFound, Integer tabsVisited,
            String reason) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("surface", surface);
        row.put("visited", visited);
        row.put("tabsFound", tabsFound);
        row.put("tabsVisited", tabsVisited);
        row.put("reason", reason);
        coverage.removeIf(existing -> surface.equals(existing.get("surface")));
        coverage.add(row);
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
        int barren = 0;
        // Offers already seen on this page, not in the whole run: the same Fire Crystal Pack sits under
        // both Fire Crystal Energy and Regular Pack, so measured against the run two screens of it would
        // read as the end of a list that goes on below them.
        Set<String> onThisPage = new HashSet<>();
        for (int scroll = 0; scroll <= maxScrolls; scroll++) {
            int before = onThisPage.size();
            DealFrameReader.Page page = readAndCollect(frame, surface, tab, tabbed);
            page.offers().forEach(o -> onThisPage.add(o.title() + "|" + o.priceText()));
            if (firstTitle == null) {
                firstTitle = page.title();
            }
            if (page.offers().size() == 1 && page.clippedTileRowY() != null) {
                frame = swipeTileRow(frame, surface, tab, tabbed, page.clippedTileRowY());
            }
            swipe(tabbed ? PANEL_SWIPE_FROM : POPUP_SWIPE_FROM, tabbed ? PANEL_SWIPE_TO : POPUP_SWIPE_TO, SWIPE_MS);
            sleepTask(SCROLL_SETTLE_MS);
            RawImageData next = capture();
            if (meanDiff(frame, next, CONTENT_REGION) < STILL_MEAN_DIFF) {
                return firstTitle;
            }
            // A page whose artwork animates never reads as still, so the old fixed cap cut long tabs
            // off mid-list (4 of them on 2026-10-22). The list has ended when swiping stops yielding
            // offers this page has not shown yet, whatever the pixels do.
            barren = onThisPage.size() > before ? 0 : barren + 1;
            if (barren >= BARREN_SWIPES_FOR_END) {
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
                    offer.frame(), offer.limitPeriod(), offer.limitCount(), offer.timeLeft(), offer.card());
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
        // An opening tab is named by its page title, which OCR sometimes takes from the line under it
        // ("Welcome to the Dawn Market," on 3 of 20 reads); one carousel is one tab.
        String tabName = tab != null ? tab : reads.stream()
                .collect(Collectors.groupingBy(read -> read.page().title(), Collectors.counting()))
                .entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
        for (CarouselRead read : reads) {
            String name = read.packName().isBlank() ? null : read.packName();
            if (name == null) {
                problems.add(surface + " / " + tabName + ": pack name unreadable on "
                        + read.page().offers().stream().map(DealOffer::frame).findFirst().orElse("a frame")
                        + "; kept the page title.");
            } else if (options.size() > 1) {
                name += " (option " + (options.indexOf(read.optionCentre()) + 1) + " of " + options.size() + ")";
            }
            collect(surface, tabName, read.page().offers(), name);
        }
        logInfo("bg_deals_telemetry | " + surface + " / " + (tab == null ? "opening tab" : tab) + ": carousel read "
                + reads.size() + " pack page(s) across " + options.size() + " option(s).");
        return tabName;
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
                first.limitPeriod() != null ? first.limitPeriod() : second.limitPeriod(),
                first.limitCount() != null ? first.limitCount() : second.limitCount(),
                first.timeLeft() != null ? first.timeLeft() : second.timeLeft(),
                first.card() != null ? first.card() : second.card());
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
