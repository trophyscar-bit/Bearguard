package dev.frostguard.engine.deals;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import dev.frostguard.api.deals.CardTerms;
import dev.frostguard.api.deals.DealItem;
import dev.frostguard.api.deals.DealOffer;
import dev.frostguard.api.deals.DealPriceTracker;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.OcrSettingsData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.engine.nav.CommonOCRSettings;
import dev.frostguard.vision.convert.ImageConverter;
import dev.frostguard.vision.convert.WhiteTextIsolator;
import dev.frostguard.vision.deals.PriceButtonLocator;
import dev.frostguard.vision.deals.TileCountReader;
import dev.frostguard.vision.match.OpenCvPatternLocator;
import dev.frostguard.vision.ocr.OcrEngine;
import dev.frostguard.vision.ocr.OcrException;
import dev.frostguard.vision.ocr.TextLine;

/**
 * Reads every offer visible on one frame of a shop, deal panel or deal pop-up.
 *
 * <p>Anchored on the detected purchase buttons rather than fixed card geometry, because panels
 * stack one, two or three cards at arbitrary scroll offsets. Each button owns the text between
 * the previous card and itself.</p>
 *
 * <p>Two readings of the frame are combined. Titles, prices, listed reward rows and tile
 * quantities are white glyphs, read from a {@link WhiteTextIsolator} mask of the whole frame;
 * {@code Remaining}, purchase limits, {@code Purchased} and top-up requirements are dark text, read
 * from the plain frame. On the saved frames the plain reading alone missed every card title and listed
 * row, and the mask alone misses dark text.</p>
 *
 * <p>Choose-your-own packs (Mix &amp; Match, Custom Pet Chest), whose rewards are partly picked by the
 * player in plus-sign slots, are left out entirely: their price does not buy a fixed set of items.</p>
 *
 * <p>Nothing is guessed: an unreadable price stays {@code null} with its raw text kept, a tile
 * whose quantity is not read is left out, and a card title that cannot be read becomes the page
 * title plus the price, so different price tiers never merge in history.</p>
 */
public final class DealFrameReader {

    /**
     * @param clippedTileRowY centre y of a single-card tile row whose last tile runs into the card
     *                        edge, meaning more tiles are hidden to the right; {@code null} otherwise
     * @param chooseYourOwn   the frame shows a choose-your-own page; it yields no offers, and callers
     *                        should drop what they already collected from the same tab
     */
    public record Page(String title, List<DealOffer> offers, List<String> problems, Integer clippedTileRowY,
            boolean chooseYourOwn) {
    }

    private record Text(List<TextLine> plainLines, List<TextLine> whiteLines, List<TextLine> whiteWords,
            RawImageData capture, RawImageData whiteMask, OptionalDouble tileScale) {

        Text withTileScale(OptionalDouble scale) {
            return new Text(plainLines, whiteLines, whiteWords, capture, whiteMask, scale);
        }
    }

    private static final PointData FRAME_TOP_LEFT = new PointData(0, 80);
    private static final PointData FRAME_BOTTOM_RIGHT = new PointData(720, 1280);
    /** Page titles on every measured panel and pop-up sit between the header and the artwork. */
    private static final int PAGE_TITLE_TOP = 90;
    private static final int PAGE_TITLE_BOTTOM = 330;
    /** Tab labels on the gem shop and Deals panels end above this line. */
    private static final int TAB_STRIP_BOTTOM = 190;
    /** Card titles on stacked cards always sit below the panel's sticky header. */
    private static final int CARD_TITLE_MIN_TOP = 330;
    /**
     * A card's title banner ends 153-175 px above its price button on every stacked card measured
     * (Custom Chest tiers, Regular Pack cards). Anything further up is the sticky header's description,
     * which a scrolled page otherwise offers as the tallest nearby line.
     */
    private static final int CARD_TITLE_MAX_ABOVE_BUTTON = 230;
    private static final int MIN_TITLE_LETTERS = 5;
    /**
     * Item tiles on stacked cards sit beside and just below their button, so a card's items and
     * "Remaining" are searched this far below it. Titles are not: on tightly stacked Regular Pack cards
     * the next card's title starts within this band, so title search starts at the button's own bottom.
     */
    private static final int CARD_BELOW_BUTTON = 90;
    /** Purchase buttons carry their label on the left; the right edge holds a badge. */
    private static final double PRICE_TEXT_WIDTH_SHARE = 0.85;
    /** Height a button crop is normalised to before its fallback price read. */
    private static final int PRICE_CROP_HEIGHT = 40;
    /** "Remaining" and per-card limit lines sit within this distance of their own button's top. */
    private static final int REMAINING_WINDOW = 110;
    /**
     * When those lines are missing from the page reads, they are read again from the strip directly
     * under the button: "Remaining: 1", "Remaining: 5" and "Lifetime Limit: 1" print 13-38 px below the
     * bottom edge of their buttons on the Regular Pack cards.
     */
    private static final int UNDER_BUTTON_HEIGHT = 44;
    private static final int UNDER_BUTTON_MARGIN = 50;
    /**
     * Quantity band of a full-size tile, relative to the icon match (the template starts 6,4 into the tile).
     * Scaled by the match's tile size, since layouts draw tiles at 0.67-1.0 of full size.
     */
    private static final int TILE_LEFT = -6;
    private static final int TILE_RIGHT = 89;
    private static final int QUANTITY_TOP = 46;
    private static final int QUANTITY_BOTTOM = 92;
    /** Heights a tile's number band is scaled to for its reads, the two most reliable first. */
    private static final int[] CONSENSUS_CROP_HEIGHTS = {40, 56, 48, 32};
    /**
     * Tile counts are pure white: their pixels measured saturation 0-19 (of 255) on the 2026-10-22 chief
     * gear frame. The lilac floor of a purple tile is bright too, at 50-79, so the general cap of 70 keeps it
     * as a blob joined to the digits and no read of the Polishing Solution counts agreed. Read magnified, one
     * line at a time, under this cap, that frame's 15, 9 and 6 read correctly at three or four of the four
     * crop sizes and no tile read a wrong number; it is only a second opinion, because the sparse read is
     * what handles multi-digit counts under busy artwork (130, 1,500, 900 all abstained here).
     */
    private static final float PURE_WHITE_SATURATION = 35f / 255f;
    /** How far right of a grant icon its "xN" count may print. */
    private static final int SIDE_COUNT_REACH = 160;
    /** A printed price within this of a detected button is that button's own label or its struck-out price. */
    private static final int PRINTED_PRICE_CLEARANCE = 60;
    private static final int PRINTED_PRICE_PADDING = 18;
    /** Tiles on one row share a top within this many pixels. */
    private static final int SAME_ROW_TOLERANCE = 20;
    /** A row whose last tile ends past this x is cut off by the card edge (inner edge ~640). */
    private static final int CLIPPED_TILE_MIN_RIGHT = 620;

    /**
     * The plus sign inside a choose-your-own slot, cropped to the glyph so the card's silver, gold or red
     * background stays out of the match. Measured on twelve saved frames: rows of three slots score 91-100,
     * the nine other layouts (including the orange slots of the Weekly Benefits Card) score at most 78.
     *
     * <p>The glow pulses, so one card's slots can dip under the floor on a given frame while another
     * card's pass. The decision is therefore made for the whole frame: any row of three slots makes it a
     * choose-your-own page, since every tier on the Mix &amp; Match and Custom Pet Chest pages is one.</p>
     */
    private static final String PICK_SLOT_RESOURCE = "/deals/pick-slot.png";
    private static final double PICK_SLOT_MIN_SCORE = 90.0;
    private static final int PICK_SLOTS_IN_A_ROW = 3;
    /** Bounds the search for a full row when partial rows are found first. */
    private static final int MAX_SLOT_ROWS = 6;
    private static final byte[] PICK_SLOT = load(PICK_SLOT_RESOURCE);
    /**
     * The diamond of the gem banner on Regular Pack cards ("2,500" in large type beside it), its interior only
     * because the banner behind it changes colour per card. Alone it does not separate -- banner diamonds scored
     * 82-100 on the 2026-10-22 frames and gem tiles up to 81 -- so it only confirms a large number beside it.
     */
    private static final byte[] BANNER_DIAMOND = load("/deals/gem-banner-diamond.png");
    private static final double BANNER_DIAMOND_MIN_SCORE = 80.0;
    /** Lines of one wrapped title: letters within this ratio in width, starting at the same margin. */
    private static final double TITLE_TYPE_RATIO = 1.35;
    private static final int TITLE_LEFT_TOLERANCE = 12;
    /** Banner numbers stand about 32 px tall; tile counts about 14. */
    private static final int BANNER_MIN_HEIGHT = 24;
    /**
     * How far from the banner number its diamond sits: before it on Regular Pack cards, after it on the
     * one-pack event pages ("2,500" then the diamond, beside a sack of gems).
     */
    private static final int BANNER_DIAMOND_REACH = 80;
    /** How far from the diamond the banner number may run, and the half-height of its row. */
    private static final int BANNER_NUMBER_REACH = 220;
    private static final int BANNER_ROW_HALF_HEIGHT = 30;
    private static final ExecutorService OCR_PASSES = Executors.newFixedThreadPool(3, runnable -> {
        Thread thread = new Thread(runnable, "deal-page-ocr");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * A price inside a confirmed orange purchase button. The dollar sign is optional because the
     * Regular Pack card read as "4.99"; the digit guards on both sides keep thousands figures such as
     * the top-up badge's "2,500" from matching as "2,50".
     */
    private static final Pattern PRICE = Pattern.compile("(?<!\\d)\\$?\\s?(\\d{1,4})[.°,:](\\d{2})(?!\\d)");
    private static final Pattern REMAINING = Pattern.compile("(?i)remaining\\W*(\\d+)");
    private static final Pattern LIMIT = Pattern.compile("(?i)\\b(daily|weekly|monthly|lifetime)\\s*limit\\W*(\\d+)");
    private static final Pattern TIMER = Pattern.compile("\\d{1,2}:\\d{2}(:\\d{2})?");
    private static final Pattern VALID_DAYS = Pattern.compile("(?i)valid\\W*(\\d{1,3})\\s*day");
    private static final Pattern LOGIN_DAILY = Pattern.compile("(?i)login\\s*daily");
    private static final Pattern BUY_TO_GET = Pattern.compile("(?i)buy\\s*to\\s*get");
    /** A count printed beside an icon: "x10", "x5,000", "x1". */
    private static final Pattern SIDE_COUNT = Pattern.compile("^[xX](\\d[\\d,.]*[KkMm]?)$");
    private static final Pattern PERIOD_REMAINING = Pattern.compile("(?i)\\b(daily|weekly|monthly|event)\\s*remaining\\W*(\\d+)");
    /** The count a tile read ends with, in any form {@link #QUANTITY} accepts. */
    private static final Pattern TRAILING_QUANTITY = Pattern.compile(
            "(?<![\\d.,])(\\d{1,3}(?:[.,]\\d{3})+|\\d{1,4}(?:[.,]\\d{1,2})?[KkMm]|\\d{1,7})$");
    /** A line that is nothing but a dollar price, as printed on a buy button: "$9.99". */
    private static final Pattern PRICE_ONLY = Pattern.compile("^\\$\\d{1,3}[.°,:]\\d{2}$");
    /** "2d 01:48:15", "13d 20:45:42" or "05:45:35". */
    private static final Pattern COUNTDOWN = Pattern.compile("(?:\\d{1,3}d\\s*)?\\d{1,2}:\\d{2}:\\d{2}");
    private static final Pattern LISTED_ROW = Pattern.compile("^(.*[A-Za-z].*?)\\s+[xX]\\s?(\\d[\\d,]*)\\b.*$");
    private static final Pattern PURCHASED = Pattern.compile("(?i)\\bpurchased\\b");
    private static final Pattern POINTS_NEEDED = Pattern.compile("(?i)top.?up.*?([\\d,]{3,})\\s*more\\s*points");

    /**
     * A tile's printed count: "960", "2,000", "2.400" (the comma read as a point), or "10K", "2.5K", "1M".
     * Gems on every Fire Crystal Leap Pack print as 10K, 25K and 50K, which the digits-only form rejected,
     * so no pack's gems ever reached a scan.
     */
    private static final Pattern QUANTITY =
            Pattern.compile("^\\d{1,3}([.,]\\d{3})*$|^\\d{1,7}$|^\\d{1,4}([.,]\\d{1,2})?[KkMm]$");
    private static final Pattern SUFFIXED = Pattern.compile("^(\\d{1,4})(?:[.,](\\d{1,2}))?([KkMm])$");
    private static final Pattern SHORT_SPEEDUP = Pattern.compile("^[lIi|]([hm])$");
    private static final Pattern LISTED_ITEM_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9 '-]*");
    private static final List<String> NOT_A_TITLE = List.of("remaining", "purchase", "select your", "best deal",
            "claimed", "top up", "one available", "resources", "traveling", "brilliant design", "utilize", "limit");

    private final DealItemLibrary library;

    public DealFrameReader(DealItemLibrary library) {
        this.library = library;
    }

    /**
     * @param tabbed the frame belongs to a panel with a tab strip (gem shop, Deals), whose labels sit
     *               in the title band and are taller than some page titles
     */
    public Page read(RawImageData capture, String surface, String tab, String frame, boolean tabbed) {
        List<String> problems = new ArrayList<>();
        // The Get Gems store prints white prices on blue cards with no orange button, so the button-led read
        // below finds nothing on it; it has a reader of its own.
        Optional<List<GemStoreReader.GemBundle>> gemStore = GemStoreReader.read(capture, problems);
        if (gemStore.isPresent()) {
            List<DealOffer> bundles = gemStore.get().stream()
                    .map(b -> new DealOffer(surface, tab,
                            String.format(Locale.US, "%,d Gems", b.gems()) + (b.bonusGems() == null ? ""
                                    : String.format(Locale.US, " (first purchase +%,d)", b.bonusGems())),
                            b.priceUsd(), String.format(Locale.US, "$%.2f", b.priceUsd()), null, false,
                            List.of(new DealItem(DealPriceTracker.GEMS, b.gems(), "gem store")), frame, null))
                    .toList();
            return new Page("Get Gems", bundles, problems, null, false);
        }
        BufferedImage image = ImageConverter.toBufferedImage(capture);
        Text text = readText(capture, image, surface, tab, problems)
                .withTileScale(library.estimateScale(capture, FRAME_TOP_LEFT, FRAME_BOTTOM_RIGHT));
        List<PriceButtonLocator.Box> coloured = PriceButtonLocator.locate(image).stream()
                .filter(button -> !isActionButton(text.whiteWords(), button))
                .toList();
        List<PriceButtonLocator.Box> buttons = Stream.concat(coloured.stream(),
                        printedPriceButtons(text.whiteLines(), coloured).stream())
                .sorted(Comparator.comparingInt(PriceButtonLocator.Box::y).thenComparingInt(PriceButtonLocator.Box::x))
                .toList();

        TextLine pageTitleLine = pageTitle(text.whiteLines(), tabbed);
        if (pageTitleLine == null) {
            pageTitleLine = pageTitle(text.plainLines(), tabbed);
        }
        String pageTitle = pageTitleLine == null ? tab : cleanTitle(pageTitleLine.text());
        if (isChooseYourOwn(capture, FRAME_TOP_LEFT.getY(), FRAME_BOTTOM_RIGHT.getY(), 0)) {
            return new Page(pageTitle, List.of(), problems, null, true);
        }
        List<TextLine> allLines = Stream.concat(text.plainLines().stream(), text.whiteLines().stream()).toList();
        String pageLimit = pageLimit(allLines, buttons);
        List<DealOffer> offers = new ArrayList<>();
        List<DealItemLibrary.Match> tiles = new ArrayList<>();

        int windowTop = FRAME_TOP_LEFT.getY();
        int previousButtonBottom = FRAME_TOP_LEFT.getY();
        for (PriceButtonLocator.Box button : buttons) {
            int top = windowTop;
            int titleTop = previousButtonBottom;
            int bottom = button.bottom() + CARD_BELOW_BUTTON;
            windowTop = bottom;
            previousButtonBottom = button.bottom();

            String priceText = readPrice(image, text.whiteWords(), button);
            Double priceUsd = parsePrice(priceText);

            String title;
            int cardTop;
            if (buttons.size() == 1) {
                title = pageTitle;
                cardTop = pageTitleLine == null ? top : pageTitleLine.bottom();
            } else {
                TextLine cardTitle = cardTitle(text.whiteLines(), titleTop, button);
                title = cardTitle != null ? cleanTitle(cardTitle.text())
                        : pageTitle + (priceUsd == null ? "" : " - $" + String.format(Locale.US, "%.2f", priceUsd));
                cardTop = cardTitle == null ? Math.max(top, CARD_TITLE_MIN_TOP) : cardTitle.bottom();
            }
            if (priceUsd == null) {
                problems.add(surface + " / " + tab + " / " + title + ": price unreadable (\"" + priceText + "\")");
            }

            List<TextLine> window = allLines.stream()
                    .filter(l -> l.top() >= top && l.bottom() <= bottom)
                    .toList();
            CardTerms card = cardTerms(text, window, cardTop, button, tiles);
            List<DealItem> items = new ArrayList<>();
            if (card != null) {
                items.addAll(cardTotal(card));
                if (items.isEmpty()) {
                    problems.add(surface + " / " + tab + " / " + title + ": card rewards not all read; no total given.");
                }
            } else {
                bannerGems(text, cardTop, bottom).ifPresent(items::add);
                iconItems(text, cardTop, bottom, tiles).stream()
                        .filter(i -> items.stream().noneMatch(b -> b.key().equals(i.key())))
                        .forEach(items::add);
                items.addAll(listedItems(text.whiteLines(), top, bottom));
            }
            Integer remaining = remaining(window, button);
            String cardLimit = cardLimit(window, button);
            if (remaining == null || cardLimit == null) {
                String under = underButtonText(text, button);
                if (remaining == null) {
                    Matcher read = REMAINING.matcher(under);
                    remaining = read.find() ? Integer.valueOf(read.group(1)) : null;
                }
                if (cardLimit == null) {
                    Matcher read = LIMIT.matcher(under);
                    cardLimit = read.find() ? read.group(1).toLowerCase(Locale.ROOT) : null;
                }
            }
            if (cardLimit == null) {
                // "Weekly Remaining: 1" states the reset period and what is left of it in one line; the
                // Chief Gear Tool Pack prints its limit no other way.
                String nearButton = window.stream()
                        .filter(l -> Math.abs(l.top() - button.y()) <= REMAINING_WINDOW)
                        .map(l -> clean(l.text()))
                        .collect(Collectors.joining(" ")) + " " + underButtonText(text, button);
                Matcher periodic = PERIOD_REMAINING.matcher(nearButton);
                if (periodic.find()) {
                    cardLimit = periodic.group(1).toLowerCase(Locale.ROOT);
                    remaining = remaining != null ? remaining : Integer.valueOf(periodic.group(2));
                }
            }
            Integer limitCount = limitCount(window, button, underButtonText(text, button));
            offers.add(new DealOffer(surface, tab, title, priceUsd, priceText, remaining,
                    false, card != null ? items : merge(items), frame, cardLimit != null ? cardLimit : pageLimit,
                    limitCount, countdown(window, allLines, cardTop), card));
        }

        if (buttons.isEmpty()) {
            unpricedOffer(text, pageTitleLine, surface, tab, pageTitle, frame).ifPresent(offers::add);
        }
        return new Page(pageTitle, offers, problems, buttons.size() == 1 ? clippedRow(tiles) : null, false);
    }

    private static Text readText(RawImageData capture, BufferedImage image, String surface, String tab,
            List<String> problems) {
        RawImageData mask = WhiteTextIsolator.isolate(image, 0, 0, image.getWidth(), image.getHeight(), 0);
        // The three passes are independent (each builds its own Tesseract instance) and were most of a
        // page read: about 5.5 s in sequence on 2026-09-18 frames. Together they take the slowest one.
        Future<List<TextLine>> plain = OCR_PASSES.submit(() -> OcrEngine.recognizeLines(capture,
                FRAME_TOP_LEFT, FRAME_BOTTOM_RIGHT, CommonOCRSettings.DEAL_PAGE_TEXT_SETTINGS));
        Future<List<TextLine>> white = OCR_PASSES.submit(() -> OcrEngine.recognizeLines(mask,
                FRAME_TOP_LEFT, FRAME_BOTTOM_RIGHT, CommonOCRSettings.DEAL_PAGE_TEXT_SETTINGS));
        Future<List<TextLine>> words = OCR_PASSES.submit(() -> OcrEngine.recognizeWords(mask,
                FRAME_TOP_LEFT, FRAME_BOTTOM_RIGHT, CommonOCRSettings.DEAL_PAGE_TEXT_SETTINGS));
        return new Text(pass(plain, surface, tab, problems), pass(white, surface, tab, problems),
                pass(words, surface, tab, problems), capture, mask, OptionalDouble.empty());
    }

    private static List<TextLine> pass(Future<List<TextLine>> result, String surface, String tab,
            List<String> problems) {
        try {
            return result.get();
        } catch (ExecutionException failed) {
            problems.add(surface + " / " + tab + ": page text unreadable (" + failed.getCause().getMessage() + ")");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            problems.add(surface + " / " + tab + ": page text read interrupted");
        }
        return List.of();
    }

    /** "TOP UP NOW" uses the purchase button's colour; a label made of words is not a price. */
    private static boolean isActionButton(List<TextLine> words, PriceButtonLocator.Box button) {
        String inside = wordsInside(words, button.x(), button.y(), button.right(), button.bottom());
        long letters = inside.chars().filter(Character::isLetter).count();
        return letters >= 4 && inside.chars().noneMatch(Character::isDigit);
    }

    /**
     * A row of three plus-sign slots anywhere in the band: the player picks part of what the pack contains.
     * A row with fewer slots above the floor is set aside and the bands above and below it are searched.
     */
    private static boolean isChooseYourOwn(RawImageData capture, int top, int bottom, int depth) {
        int clampedTop = Math.max(FRAME_TOP_LEFT.getY(), top);
        int clampedBottom = Math.min(FRAME_BOTTOM_RIGHT.getY(), bottom);
        if (depth > MAX_SLOT_ROWS || clampedBottom <= clampedTop) {
            return false;
        }
        ImageSearchResultData first = OpenCvPatternLocator.matchFromRawTemplate(capture, PICK_SLOT,
                new PointData(0, clampedTop), new PointData(FRAME_BOTTOM_RIGHT.getX(), clampedBottom),
                PICK_SLOT_MIN_SCORE);
        if (!first.isFound()) {
            return false;
        }
        int rowTop = Math.max(clampedTop, first.getY() - first.getMatchHeight() / 2 - SAME_ROW_TOLERANCE);
        int rowBottom = Math.min(clampedBottom, first.getY() + first.getMatchHeight() / 2 + SAME_ROW_TOLERANCE);
        if (slotsInRow(capture, 0, FRAME_BOTTOM_RIGHT.getX(), rowTop, rowBottom, 0) >= PICK_SLOTS_IN_A_ROW) {
            return true;
        }
        return isChooseYourOwn(capture, clampedTop, rowTop, depth + 1)
                || isChooseYourOwn(capture, rowBottom, clampedBottom, depth + 1);
    }

    /**
     * Counts non-overlapping slots between {@code left} and {@code right}. Each search returns only the best
     * match, which is rarely the leftmost slot, so both sides of every hit are searched.
     */
    private static int slotsInRow(RawImageData capture, int left, int right, int top, int bottom, int depth) {
        if (depth > PICK_SLOTS_IN_A_ROW * 2 || right - left <= 0) {
            return 0;
        }
        ImageSearchResultData slot = OpenCvPatternLocator.matchFromRawTemplate(capture, PICK_SLOT,
                new PointData(left, top), new PointData(right, bottom), PICK_SLOT_MIN_SCORE);
        if (!slot.isFound()) {
            return 0;
        }
        int slotLeft = slot.getX() - slot.getMatchWidth() / 2;
        int slotRight = slotLeft + slot.getMatchWidth();
        return 1 + slotsInRow(capture, left, slotLeft, top, bottom, depth + 1)
                + slotsInRow(capture, slotRight, right, top, bottom, depth + 1);
    }

    private static boolean bannerDiamond(Text text, TextLine number, int left, int right) {
        return right > left && OpenCvPatternLocator.matchFromRawTemplate(text.capture(), BANNER_DIAMOND,
                new PointData(left, number.top() - SAME_ROW_TOLERANCE),
                new PointData(right, number.bottom() + SAME_ROW_TOLERANCE), BANNER_DIAMOND_MIN_SCORE).isFound();
    }

    private static byte[] load(String resource) {
        try (InputStream in = DealFrameReader.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing classpath resource " + resource);
            }
            return in.readAllBytes();
        } catch (IOException unreadable) {
            throw new UncheckedIOException("Could not read " + resource, unreadable);
        }
    }

    /** The gems a card's banner grants: a large number with the banner diamond beside it. */
    private static Optional<DealItem> bannerGems(Text text, int top, int bottom) {
        // Words, not lines: the line pass reads the diamond into the number ("* 2500").
        Optional<DealItem> fromWords = text.whiteWords().stream()
                .filter(l -> l.top() >= top && l.bottom() <= bottom)
                .filter(l -> l.height() >= BANNER_MIN_HEIGHT)
                .filter(l -> QUANTITY.matcher(clean(l.text()).replace(" ", "")).matches())
                .filter(l -> bannerDiamond(text, l, Math.max(0, l.left() - BANNER_DIAMOND_REACH), l.left())
                        || bannerDiamond(text, l, l.left() + l.width(),
                                Math.min(FRAME_BOTTOM_RIGHT.getX(), l.left() + l.width() + BANNER_DIAMOND_REACH)))
                .map(l -> parseQuantity(clean(l.text()).replace(" ", "")))
                .filter(Objects::nonNull)
                .findFirst()
                .map(gems -> new DealItem(DealPriceTracker.GEMS, gems, "banner"));
        return fromWords.isPresent() ? fromWords : bannerBesideDiamond(text, top, bottom);
    }

    /**
     * The banner read glyph by glyph either side of its diamond. The page-wide word pass returns nothing for
     * the "2,500" of the one-pack event pages (Chief Gear Enhancement Special, 2026-10-22), and OCR of a crop
     * read it as 2900; an uncertain banner is left out.
     */
    private static Optional<DealItem> bannerBesideDiamond(Text text, int top, int bottom) {
        ImageSearchResultData diamond = OpenCvPatternLocator.matchFromRawTemplate(text.capture(), BANNER_DIAMOND,
                new PointData(0, top), new PointData(FRAME_BOTTOM_RIGHT.getX(), bottom), BANNER_DIAMOND_MIN_SCORE);
        if (!diamond.isFound()) {
            return Optional.empty();
        }
        BufferedImage image = ImageConverter.toBufferedImage(text.capture());
        int half = diamond.getMatchWidth() / 2;
        int rowTop = diamond.getY() - BANNER_ROW_HALF_HEIGHT;
        for (int[] span : List.of(new int[] {diamond.getX() - half - BANNER_NUMBER_REACH, diamond.getX() - half},
                new int[] {diamond.getX() + half, diamond.getX() + half + BANNER_NUMBER_REACH})) {
            Optional<Long> gems = TileCountReader.readRegion(image, span[0], rowTop, span[1] - span[0],
                    2 * BANNER_ROW_HALF_HEIGHT, 1).map(DealFrameReader::parseQuantity);
            if (gems.isPresent()) {
                return Optional.of(new DealItem(DealPriceTracker.GEMS, gems.get(), "banner"));
            }
        }
        return Optional.empty();
    }

    private Optional<DealOffer> unpricedOffer(Text text, TextLine pageTitleLine, String surface, String tab,
            String pageTitle, String frame) {
        int top = pageTitleLine == null ? FRAME_TOP_LEFT.getY() : pageTitleLine.bottom();
        List<TextLine> lines = Stream.concat(text.plainLines().stream(), text.whiteLines().stream()).toList();
        for (TextLine line : lines) {
            String clean = clean(line.text());
            Matcher points = POINTS_NEEDED.matcher(clean);
            if (points.find()) {
                return Optional.of(new DealOffer(surface, tab, pageTitle, null,
                        points.group(1) + " more top-up points", null, false,
                        merge(iconItems(text, top, line.top(), new ArrayList<>())), frame, null));
            }
        }
        for (TextLine line : lines) {
            if (PURCHASED.matcher(clean(line.text())).find()) {
                return Optional.of(new DealOffer(surface, tab, pageTitle, null, "Purchased", null, true,
                        merge(iconItems(text, top, line.top(), new ArrayList<>())), frame, null));
            }
        }
        return Optional.empty();
    }

    private static Integer clippedRow(List<DealItemLibrary.Match> tiles) {
        if (tiles.isEmpty()) {
            return null;
        }
        DealItemLibrary.Match last = tiles.stream().max(Comparator.comparingInt(m -> m.x() + m.width())).orElseThrow();
        boolean oneRow = tiles.stream().allMatch(m -> Math.abs(m.y() - last.y()) <= SAME_ROW_TOLERANCE);
        return oneRow && last.x() + last.width() >= CLIPPED_TILE_MIN_RIGHT ? last.y() + last.height() / 2 : null;
    }

    private static double letterWidth(TextLine line) {
        return (double) line.width() / Math.max(1, line.text().replace(" ", "").length());
    }

    private static TextLine pageTitle(List<TextLine> lines, boolean tabbed) {
        int top = tabbed ? TAB_STRIP_BOTTOM : PAGE_TITLE_TOP;
        List<TextLine> candidates = lines.stream()
                .filter(l -> l.top() >= top && l.bottom() <= PAGE_TITLE_BOTTOM && isTitleText(cleanTitle(l.text())))
                .toList();
        TextLine tallest = candidates.stream().max(Comparator.comparingInt(TextLine::height)).orElse(null);
        if (tallest == null) {
            return null;
        }
        // A long title wraps: "Exclusive Gear Enhancement" over "Special" on 2026-10-22, where the second
        // line alone became the title. Lines in the same type directly above or below belong to it. Type size
        // is judged by width per letter (21 and 19 px for those two lines, 11 for the subtitle under them):
        // the measured line heights were 33 and 44, because "Special" descends.
        List<TextLine> title = new ArrayList<>(List.of(tallest));
        double letterWidth = letterWidth(tallest);
        boolean grew = true;
        while (grew) {
            grew = false;
            for (TextLine line : candidates) {
                double ratio = letterWidth(line) / letterWidth;
                if (title.contains(line) || ratio < 1 / TITLE_TYPE_RATIO || ratio > TITLE_TYPE_RATIO
                        || Math.abs(line.left() - tallest.left()) > TITLE_LEFT_TOLERANCE) {
                    continue;
                }
                boolean stacked = title.stream().anyMatch(t -> Math.abs(line.top() - t.bottom()) <= tallest.height() / 2
                        || Math.abs(t.top() - line.bottom()) <= tallest.height() / 2);
                if (stacked) {
                    title.add(line);
                    grew = true;
                }
            }
        }
        if (title.size() == 1) {
            return tallest;
        }
        title.sort(Comparator.comparingInt(TextLine::top));
        TextLine first = title.get(0);
        TextLine last = title.get(title.size() - 1);
        int left = title.stream().mapToInt(TextLine::left).min().orElse(first.left());
        int right = title.stream().mapToInt(l -> l.left() + l.width()).max().orElse(first.left() + first.width());
        return new TextLine(title.stream().map(TextLine::text).collect(Collectors.joining(" ")), left, first.top(),
                right - left, last.bottom() - first.top(), first.confidence());
    }

    private static TextLine cardTitle(List<TextLine> lines, int windowTop, PriceButtonLocator.Box button) {
        return lines.stream()
                .filter(l -> l.top() >= Math.max(windowTop, CARD_TITLE_MIN_TOP) && l.bottom() <= button.y()
                        && button.y() - l.bottom() <= CARD_TITLE_MAX_ABOVE_BUTTON)
                .filter(l -> isTitleText(cleanTitle(l.text())))
                .max(Comparator.comparingInt(TextLine::height))
                .orElse(null);
    }

    /**
     * Real titles are mostly real words; OCR of artwork is mostly one- and two-letter fragments.
     * Callers judge the line after {@link #cleanTitle} trims stray glyphs off its ends, because a real
     * banner title often carries several ("Charm Craftsman Pack ,,. on | So.").
     */
    static boolean isTitleText(String raw) {
        String text = clean(raw);
        String lower = text.toLowerCase(Locale.ROOT);
        String[] tokens = text.split(" ");
        long words = Arrays.stream(tokens).filter(t -> t.chars().filter(Character::isLetter).count() >= 3).count();
        long letters = text.chars().filter(Character::isLetter).count();
        return letters >= MIN_TITLE_LETTERS
                && words * 10 >= tokens.length * 6L
                && NOT_A_TITLE.stream().noneMatch(lower::contains)
                && !TIMER.matcher(text).find()
                && !LISTED_ROW.matcher(text).matches();
    }

    /** Keeps the run of real words: drops stray glyphs OCR attaches to either end of a banner. */
    static String cleanTitle(String raw) {
        String[] tokens = clean(raw).split(" ");
        int start = 0;
        int end = tokens.length;
        while (start < end && !isWordLike(tokens[start])) {
            start++;
        }
        while (end > start && !isTitleEnd(tokens[end - 1])) {
            end--;
        }
        return start >= end ? clean(raw) : String.join(" ", Arrays.copyOfRange(tokens, start, end));
    }

    /**
     * A title may end in a word or in its tier number. Read as plain text, "Fire Crystal Leap Pack 3" lost
     * its "3" (too short to count as a word) while the top-up badge beside it, read as "205.", was kept
     * (three digits counted as a word), giving "Fire Crystal Leap Pack 4 205." for one tier and no tier
     * at all for the next. A trailing token needs letters, or must be a one- or two-digit tier.
     */
    private static boolean isTitleEnd(String token) {
        return token.chars().filter(Character::isLetter).count() >= 3 || token.matches("\\d{1,2}")
                || token.matches("(?i)of|in|to|a|&");
    }

    private static boolean isWordLike(String token) {
        return token.chars().filter(Character::isLetterOrDigit).count() >= 3
                || token.matches("(?i)of|in|to|a|&");
    }

    /** The last price on the button: a discounted button prints the struck-out original first ("$5.97 $4.99"). */
    static Double parsePrice(String priceText) {
        Matcher price = PRICE.matcher(priceText == null ? "" : priceText.replace(" ", ""));
        Double last = null;
        while (price.find()) {
            last = Double.valueOf(price.group(1) + "." + price.group(2));
        }
        return last;
    }

    private static String readPrice(BufferedImage image, List<TextLine> words, PriceButtonLocator.Box button) {
        String inside = wordsInside(words, button.x(), button.y(), button.right(), button.bottom());
        if (PRICE.matcher(inside.replace(" ", "")).find()) {
            return inside;
        }
        int width = (int) (button.width() * PRICE_TEXT_WIDTH_SHARE);
        RawImageData crop = WhiteTextIsolator.isolateScaled(image, button.x(), button.y(), width, button.height(),
                PRICE_CROP_HEIGHT);
        String strict = cropRead(crop, CommonOCRSettings.DEAL_PRICE_SETTINGS);
        if (PRICE.matcher(strict).find()) {
            return strict;
        }
        // The digits-and-dot glyph set drops the decimal point on some buttons: eight offers of the
        // 2026-10-22 scan read "$499" for a plainly printed $4.99. Reading the same crop without the
        // restriction recovered "$4.99" on every button measured, at the cost of stray marks around it,
        // which the price pattern ignores. The strict read stays first because it comes back clean.
        String loose = cropRead(crop, CommonOCRSettings.DEAL_PAGE_TEXT_SETTINGS);
        if (PRICE.matcher(loose.replace(" ", "")).find()) {
            return loose;
        }
        return strict.isEmpty() ? inside : strict;
    }

    private static String cropRead(RawImageData crop, OcrSettingsData settings) {
        try {
            return OcrEngine.recognizeText(crop, new PointData(0, 0),
                    new PointData(crop.getWidth(), crop.getHeight()), settings).trim();
        } catch (OcrException unreadable) {
            return "";
        }
    }

    private static String wordsInside(List<TextLine> words, int left, int top, int right, int bottom) {
        return words.stream()
                .filter(w -> centreX(w) >= left && centreX(w) <= right && centreY(w) >= top && centreY(w) <= bottom)
                .sorted(Comparator.comparingInt(TextLine::left))
                .map(w -> clean(w.text()))
                .collect(Collectors.joining(" "));
    }

    private static Integer remaining(List<TextLine> window, PriceButtonLocator.Box button) {
        return window.stream()
                .filter(l -> Math.abs(l.top() - button.y()) <= REMAINING_WINDOW)
                .map(l -> REMAINING.matcher(clean(l.text())))
                .filter(Matcher::find)
                .map(m -> Integer.valueOf(m.group(1)))
                .findFirst()
                .orElse(null);
    }

    /** Plain and white-mask readings of the strip just under a button, joined for pattern matching. */
    private static String underButtonText(Text text, PriceButtonLocator.Box button) {
        PointData topLeft = new PointData(Math.max(0, button.x() - UNDER_BUTTON_MARGIN), button.bottom());
        PointData bottomRight = new PointData(Math.min(FRAME_BOTTOM_RIGHT.getX(), button.right() + UNDER_BUTTON_MARGIN),
                Math.min(FRAME_BOTTOM_RIGHT.getY(), button.bottom() + UNDER_BUTTON_HEIGHT));
        if (bottomRight.getY() <= topLeft.getY()) {
            return "";
        }
        StringBuilder read = new StringBuilder();
        for (RawImageData source : List.of(text.capture(), text.whiteMask())) {
            try {
                read.append(clean(OcrEngine.recognizeText(source, topLeft, bottomRight,
                        CommonOCRSettings.DEAL_PAGE_TEXT_SETTINGS))).append(" ");
            } catch (OcrException unreadable) {
                // An unreadable strip leaves the value unknown, which is what the caller records.
            }
        }
        return read.toString();
    }

    /** A limit printed beside this card's own button ("Lifetime Limit: 1"). */
    /**
     * A card's terms, when its window prints both "Login daily to obtain" and "Valid: N day(s)". Measured on
     * the 2026-10-22 Weekly and Monthly cards: the grant rows sit between "Buy to get" and "Login daily",
     * the daily rows between "Login daily" and the price button, and "Valid" prints under the button, still
     * inside the card's window.
     */
    private CardTerms cardTerms(Text text, List<TextLine> window, int cardTop, PriceButtonLocator.Box button,
            List<DealItemLibrary.Match> tiles) {
        Integer days = window.stream()
                .map(l -> VALID_DAYS.matcher(clean(l.text())))
                .filter(Matcher::find)
                .map(m -> Integer.valueOf(m.group(1)))
                .findFirst()
                .orElse(null);
        TextLine daily = window.stream()
                .filter(l -> LOGIN_DAILY.matcher(clean(l.text())).find() && l.bottom() < button.y())
                .findFirst()
                .orElse(null);
        if (days == null || daily == null || days <= 0) {
            return null;
        }
        int grantTop = window.stream()
                .filter(l -> BUY_TO_GET.matcher(clean(l.text())).find() && l.top() < daily.top())
                .map(TextLine::bottom)
                .findFirst()
                .orElse(cardTop);
        int matchedBefore = tiles.size();
        List<DealItem> grantRead = iconItems(text, grantTop, daily.top(), tiles, true);
        List<DealItem> dailyRead = iconItems(text, daily.bottom(), button.y(), tiles);
        int matched = tiles.size() - matchedBefore;
        if (grantRead.size() + dailyRead.size() < matched) {
            // A tile was recognised but its count was not confirmed. A card's total multiplies its daily
            // rows by its days, so leaving one out is not a smaller total but a wrong one: the Weekly Card
            // came out at 10 crystals instead of 52. Such a card reports no total at all.
            return new CardTerms(days, List.of(), List.of());
        }
        return new CardTerms(days, merge(grantRead), merge(dailyRead));
    }

    /** Everything a card pays over its whole validity: the grant once, plus each daily reward every day. */
    static List<DealItem> cardTotal(CardTerms card) {
        Map<String, Long> totals = new LinkedHashMap<>();
        card.upfront().forEach(i -> totals.merge(i.key(), i.quantity(), Long::sum));
        card.daily().forEach(i -> totals.merge(i.key(), i.quantity() * card.validDays(), Long::sum));
        return totals.entrySet().stream()
                .map(e -> new DealItem(e.getKey(), e.getValue(), "card"))
                .toList();
    }

    private static Integer limitCount(List<TextLine> window, PriceButtonLocator.Box button, String under) {
        Optional<Integer> near = window.stream()
                .filter(l -> Math.abs(l.top() - button.y()) <= REMAINING_WINDOW)
                .map(l -> LIMIT.matcher(clean(l.text())))
                .filter(Matcher::find)
                .map(m -> Integer.valueOf(m.group(2)))
                .findFirst();
        if (near.isPresent()) {
            return near.get();
        }
        Matcher read = LIMIT.matcher(under);
        return read.find() ? Integer.valueOf(read.group(2)) : null;
    }

    /**
     * The countdown that applies to this offer: one inside its own card first, else the page's own timer
     * above the first card ("2d 01:48:15" under the Fire Crystal Value Pack's title).
     */
    private static String countdown(List<TextLine> window, List<TextLine> all, int cardTop) {
        Optional<String> own = window.stream()
                .map(l -> COUNTDOWN.matcher(clean(l.text())))
                .filter(Matcher::find)
                .map(m -> m.group().trim())
                .findFirst();
        if (own.isPresent()) {
            return own.get();
        }
        return all.stream()
                .filter(l -> l.bottom() <= cardTop && l.top() >= PAGE_TITLE_TOP)
                .map(l -> COUNTDOWN.matcher(clean(l.text())))
                .filter(Matcher::find)
                .map(m -> m.group().trim())
                .findFirst()
                .orElse(null);
    }

    /**
     * Price buttons the colour test cannot see. On a gold card the orange button and the card are one
     * component: on the 2026-10-22 Ultra Value Monthly Card it measured 673 x 537 px at 0.41 fill, so the
     * $9.99 offer was never read. Its white price text was read, though. A printed price well clear of every
     * detected button stands in for the button, sized around the text.
     */
    private static List<PriceButtonLocator.Box> printedPriceButtons(List<TextLine> whiteLines,
            List<PriceButtonLocator.Box> detected) {
        List<PriceButtonLocator.Box> printed = new ArrayList<>();
        for (TextLine line : whiteLines) {
            String compact = clean(line.text()).replace(" ", "");
            if (!PRICE_ONLY.matcher(compact).matches() || line.top() < CARD_TITLE_MIN_TOP) {
                continue;
            }
            boolean nearButton = detected.stream().anyMatch(b ->
                    centreX(line) >= b.x() - PRINTED_PRICE_CLEARANCE && centreX(line) <= b.right() + PRINTED_PRICE_CLEARANCE
                            && centreY(line) >= b.y() - PRINTED_PRICE_CLEARANCE
                            && centreY(line) <= b.bottom() + PRINTED_PRICE_CLEARANCE);
            if (!nearButton) {
                printed.add(new PriceButtonLocator.Box(line.left() - PRINTED_PRICE_PADDING,
                        line.top() - PRINTED_PRICE_PADDING, line.width() + 2 * PRINTED_PRICE_PADDING,
                        line.height() + 2 * PRINTED_PRICE_PADDING));
            }
        }
        return printed;
    }

    private static String cardLimit(List<TextLine> window, PriceButtonLocator.Box button) {
        return window.stream()
                .filter(l -> Math.abs(l.top() - button.y()) <= REMAINING_WINDOW)
                .map(l -> LIMIT.matcher(clean(l.text())))
                .filter(Matcher::find)
                .map(m -> m.group(1).toLowerCase(Locale.ROOT))
                .findFirst()
                .orElse(null);
    }

    /**
     * A limit that belongs to no single button applies to every card on the page: the Daily Deals tab
     * prints one "Daily Limit: 1" above all of its cards.
     */
    private static String pageLimit(List<TextLine> lines, List<PriceButtonLocator.Box> buttons) {
        return lines.stream()
                .filter(l -> buttons.stream().noneMatch(b -> Math.abs(l.top() - b.y()) <= REMAINING_WINDOW))
                .map(l -> LIMIT.matcher(clean(l.text())))
                .filter(Matcher::find)
                .map(m -> m.group(1).toLowerCase(Locale.ROOT))
                .findFirst()
                .orElse(null);
    }

    private List<DealItem> iconItems(Text text, int top, int bottom, List<DealItemLibrary.Match> tiles) {
        return iconItems(text, top, bottom, tiles, false);
    }

    private List<DealItem> iconItems(Text text, int top, int bottom, List<DealItemLibrary.Match> tiles,
            boolean sideCounts) {
        List<DealItem> items = new ArrayList<>();
        if (library.size() == 0 || bottom <= top) {
            return items;
        }
        int clampedBottom = Math.min(bottom, FRAME_BOTTOM_RIGHT.getY());
        Map<Integer, List<TextLine>> rowWords = new LinkedHashMap<>();
        PointData from = new PointData(0, top);
        PointData to = new PointData(FRAME_BOTTOM_RIGHT.getX(), clampedBottom);
        List<DealItemLibrary.Match> found = text.tileScale().isPresent()
                ? library.find(text.capture(), from, to, text.tileScale().getAsDouble())
                : library.find(text.capture(), from, to);
        BufferedImage image = ImageConverter.toBufferedImage(text.capture());
        for (DealItemLibrary.Match match : found) {
            tiles.add(match);
            if (bandCutOff(image, match)) {
                // Part of the number is off the frame, so any read of it is a fragment: on 2026-10-22 a
                // clipped 4,800 was kept as 77. The tile is read again once scrolling brings it whole.
                continue;
            }
            // A tile reaching the card's inner edge may be cut by it. Only the glyph read is taken there: it
            // rejects a part-hidden digit, where OCR read a cut 130 as 134. A wholly hidden last digit reads
            // short ("24" of 240) and gives way to the whole read once the row is swiped.
            boolean atEdge = match.x() + match.width() >= CLIPPED_TILE_MIN_RIGHT;
            Optional<Long> quantity = atEdge
                    ? TileCountReader.read(image, match.x(), match.y(), match.scale()).map(DealFrameReader::parseQuantity)
                    : agreedQuantity(image, text, rowWords, match);
            if (quantity.isEmpty() && sideCounts) {
                quantity = sideCount(text.whiteWords(), match);
            }
            quantity.ifPresent(q -> items.add(new DealItem(match.key(), q, "icon")));
        }
        return items;
    }

    /**
     * A card's up-front grant prints its count beside the icon ("x10", "x5,000"), not in the tile's corner:
     * on the 2026-10-22 Weekly Card the crystal tile's own band held no number, and "x10" sat level with it
     * to the right.
     */
    private static Optional<Long> sideCount(List<TextLine> words, DealItemLibrary.Match match) {
        int left = match.x() + match.width();
        int right = left + SIDE_COUNT_REACH;
        return words.stream()
                .filter(w -> w.left() >= left - SAME_ROW_TOLERANCE && w.left() <= right)
                .filter(w -> centreY(w) >= match.y() && centreY(w) <= match.y() + match.height() + SAME_ROW_TOLERANCE)
                .map(w -> SIDE_COUNT.matcher(clean(w.text()).replace(" ", "")))
                .filter(Matcher::matches)
                .map(m -> parseQuantity(m.group(1)))
                .filter(Objects::nonNull)
                .findFirst();
    }

    private static boolean bandCutOff(BufferedImage image, DealItemLibrary.Match match) {
        return match.y() + scaled(QUANTITY_BOTTOM, match) > image.getHeight()
                || match.x() + scaled(TILE_RIGHT, match) > image.getWidth()
                || match.x() + scaled(TILE_LEFT, match) < 0;
    }

    /**
     * A tile's number, kept only when two independent reads agree on it. Every single read has produced a
     * wrong value on a real frame: the whole-frame word pass read 2,000 as 7000, a fallback read a Polishing
     * Solution's 15 as 151, and the smallest crop read 2,000 as "Sto". On the Leap Pack and chief gear frames
     * of 2026-10-22 the crops at 40 and 56 px agreed on 13 of 14 tiles, every time correctly; where they do
     * not, the other crop sizes and the word reads vote too. No agreement leaves the tile unread.
     */
    private static Optional<Long> agreedQuantity(BufferedImage image, Text text,
            Map<Integer, List<TextLine>> rowWords, DealItemLibrary.Match match) {
        Optional<Long> glyphs = TileCountReader.read(image, match.x(), match.y(), match.scale())
                .map(DealFrameReader::parseQuantity);
        if (glyphs.isPresent()) {
            return glyphs;
        }
        List<Long> crops = new ArrayList<>();
        for (int height : CONSENSUS_CROP_HEIGHTS) {
            croppedQuantity(image, match, height, false).ifPresent(crops::add);
            Optional<Long> agreed = majority(crops);
            if (agreed.isPresent()) {
                return agreed;
            }
        }
        List<Long> all = new ArrayList<>(crops);
        quantity(text.whiteWords(), match).ifPresent(all::add);
        // A narrow strip gives the recogniser one row of numbers to lay out instead of a whole busy frame;
        // on the brown Vault tiles only the strip read them.
        List<TextLine> strip = rowWords.computeIfAbsent(match.y() / SAME_ROW_TOLERANCE,
                row -> rowStripWords(text.whiteMask(), match));
        quantity(strip, match).ifPresent(all::add);
        Optional<Long> agreed = majority(all);
        if (agreed.isPresent()) {
            return agreed;
        }
        // One crop read that nothing contradicts stands; the other reads gave no number at all. A single
        // digit often reads at only one size: the Weekly Card's 6 daily crystals read at 40 px and nowhere
        // else, and without them its 52 crystals became 10. A lone word-pass read is never enough: that is
        // where 7000 and 151 came from.
        Set<Long> distinct = new HashSet<>(all);
        if (crops.size() >= 1 && distinct.size() == 1) {
            return Optional.of(crops.get(0));
        }
        // A pale tinted tile floor passes the white mask and swallows the digits; masking pure white only
        // separates them. Consulted only here, and it must agree with itself, because on ordinary tiles the
        // stricter mask thins the digits.
        List<Long> pure = new ArrayList<>();
        for (int height : CONSENSUS_CROP_HEIGHTS) {
            croppedQuantity(image, match, height, true).ifPresent(pure::add);
        }
        return majority(pure);
    }

    /** The value read at least twice and more often than any other value, if there is one. */
    static Optional<Long> majority(List<Long> votes) {
        Map<Long, Long> counts = votes.stream()
                .collect(Collectors.groupingBy(v -> v, LinkedHashMap::new, Collectors.counting()));
        long best = counts.values().stream().mapToLong(c -> c).max().orElse(0);
        List<Long> leaders = counts.entrySet().stream()
                .filter(e -> e.getValue() == best)
                .map(Map.Entry::getKey)
                .toList();
        return best >= 2 && leaders.size() == 1 ? Optional.of(leaders.get(0)) : Optional.empty();
    }

    /**
     * The tile's number, read from a crop of its own band without a glyph restriction. Measured on the
     * 2026-10-22 Leap Pack frame at three crop heights: the unrestricted read at 40 px got every tile right,
     * while the digits-only set read "10K" gems as "10" and "2,000" meat as "00".
     */
    private static Optional<Long> croppedQuantity(BufferedImage image, DealItemLibrary.Match match, int height,
            boolean pureWhite) {
        int left = match.x() + scaled(TILE_LEFT, match);
        int right = match.x() + scaled(TILE_RIGHT, match);
        int top = match.y() + scaled(QUANTITY_TOP, match);
        int bottom = match.y() + scaled(QUANTITY_BOTTOM, match);
        RawImageData crop = pureWhite
                ? WhiteTextIsolator.isolatePureScaled(image, left, top, right - left, bottom - top, height,
                        PURE_WHITE_SATURATION)
                : WhiteTextIsolator.isolateScaled(image, left, top, right - left, bottom - top, height);
        String read;
        try {
            read = OcrEngine.recognizeText(crop, new PointData(0, 0), new PointData(crop.getWidth(), crop.getHeight()),
                    pureWhite ? CommonOCRSettings.DEAL_TILE_COUNT_SETTINGS : CommonOCRSettings.DEAL_PAGE_TEXT_SETTINGS);
        } catch (OcrException unreadable) {
            return Optional.empty();
        }
        // The count is printed right-aligned at the tile's bottom-right, so it is the number the read ends
        // with; whatever comes before it is artwork read as letters ("OY130" and "ee,130" for a 130).
        Matcher trailing = TRAILING_QUANTITY.matcher(clean(read.trim()));
        return trailing.find() ? Optional.ofNullable(parseQuantity(trailing.group(1))) : Optional.empty();
    }

    private static List<TextLine> rowStripWords(RawImageData mask, DealItemLibrary.Match match) {
        int top = Math.max(0, match.y() + scaled(QUANTITY_TOP, match));
        int bottom = Math.min(FRAME_BOTTOM_RIGHT.getY(), match.y() + scaled(QUANTITY_BOTTOM, match));
        if (bottom <= top) {
            return List.of();
        }
        try {
            return OcrEngine.recognizeWords(mask, new PointData(0, top), new PointData(FRAME_BOTTOM_RIGHT.getX(), bottom),
                    CommonOCRSettings.DEAL_PAGE_TEXT_SETTINGS);
        } catch (OcrException unreadable) {
            return List.of();
        }
    }

    /** The rightmost number printed in the tile's bottom band; a tile with no readable number is left out. */
    private static Optional<Long> quantity(List<TextLine> words, DealItemLibrary.Match match) {
        int left = match.x() + scaled(TILE_LEFT, match);
        int right = match.x() + scaled(TILE_RIGHT, match);
        int top = match.y() + scaled(QUANTITY_TOP, match);
        int bottom = match.y() + scaled(QUANTITY_BOTTOM, match);
        return words.stream()
                .filter(w -> centreX(w) >= left && centreX(w) <= right && centreY(w) >= top && centreY(w) <= bottom)
                .filter(w -> QUANTITY.matcher(clean(w.text())).matches())
                .max(Comparator.comparingInt(TextLine::left))
                .map(w -> parseQuantity(w.text()));
    }

    private static int scaled(int fullSizeOffset, DealItemLibrary.Match match) {
        return (int) Math.round(fullSizeOffset * match.scale());
    }

    private static List<DealItem> listedItems(List<TextLine> lines, int top, int bottom) {
        List<DealItem> items = new ArrayList<>();
        for (TextLine line : lines) {
            if (line.top() < top || line.bottom() > bottom) {
                continue;
            }
            Matcher row = LISTED_ROW.matcher(clean(line.text()));
            if (row.matches()) {
                Long quantity = parseQuantity(row.group(2));
                String name = itemName(row.group(1));
                if (quantity != null && isListedItemName(name)) {
                    items.add(new DealItem(name, quantity, "text"));
                }
            }
        }
        return items;
    }

    /** Drops the stray glyphs OCR makes of the row's own icon, and restores "1h" read as "lh". */
    static String itemName(String raw) {
        String[] words = clean(raw).split(" ");
        int start = 0;
        while (start < words.length - 1 && !startsItemName(words[start])) {
            start++;
        }
        String[] kept = Arrays.copyOfRange(words, start, words.length);
        if (kept.length > 0) {
            Matcher speedup = SHORT_SPEEDUP.matcher(kept[0]);
            if (speedup.matches()) {
                kept[0] = "1" + speedup.group(1);
            }
        }
        return String.join(" ", kept).trim();
    }

    /**
     * A listed reward row names its item in plain words ("5m Construction Speedup", "10K Meat"). The
     * Weekly/Monthly Cards page produced rows such as "\"fy; x2" and "oF x2" from its artwork; those are not items.
     */
    static boolean isListedItemName(String name) {
        return LISTED_ITEM_NAME.matcher(name).matches()
                && Arrays.stream(name.split(" ")).anyMatch(word -> word.chars().filter(Character::isLetter).count() >= 3);
    }

    /** An item name starts with an amount ("5m", "10K", "100") or a real word, never a stray glyph pair. */
    private static boolean startsItemName(String token) {
        return token.matches("\\d+[A-Za-z]{0,3}") || SHORT_SPEEDUP.matcher(token).matches()
                || token.chars().filter(Character::isLetter).count() >= 3;
    }

    static Long parseQuantity(String raw) {
        Matcher suffixed = SUFFIXED.matcher(raw == null ? "" : clean(raw));
        if (suffixed.matches()) {
            String fraction = suffixed.group(2) == null ? "0" : suffixed.group(2);
            double value = Double.parseDouble(suffixed.group(1) + "." + fraction);
            long unit = Character.toUpperCase(suffixed.group(3).charAt(0)) == 'M' ? 1_000_000L : 1_000L;
            return Math.round(value * unit);
        }
        String digits = raw == null ? "" : raw.replaceAll("[^0-9]", "");
        if (digits.isEmpty() || digits.length() > 9) {
            return null;
        }
        long value = Long.parseLong(digits);
        return value > 0 ? value : null;
    }

    /** One entry per item; when two reads of the same item disagree, the larger was the complete one. */
    private static List<DealItem> merge(List<DealItem> items) {
        Map<String, DealItem> merged = new LinkedHashMap<>();
        for (DealItem item : items) {
            merged.merge(item.key(), item, (a, b) -> a.quantity() >= b.quantity() ? a : b);
        }
        return List.copyOf(merged.values());
    }

    private static int centreX(TextLine line) {
        return line.left() + line.width() / 2;
    }

    private static int centreY(TextLine line) {
        return line.top() + line.height() / 2;
    }

    private static String clean(String raw) {
        return raw == null ? "" : raw.replaceAll("\\s+", " ").trim();
    }
}
