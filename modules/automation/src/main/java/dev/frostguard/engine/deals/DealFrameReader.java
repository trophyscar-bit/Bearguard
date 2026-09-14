package dev.frostguard.engine.deals;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import dev.frostguard.api.deals.DealItem;
import dev.frostguard.api.deals.DealOffer;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.engine.nav.CommonOCRSettings;
import dev.frostguard.vision.convert.ImageConverter;
import dev.frostguard.vision.convert.WhiteTextIsolator;
import dev.frostguard.vision.deals.PriceButtonLocator;
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
 * {@code Remaining}, {@code Purchased} and top-up requirements are dark text, read from the plain
 * frame. On the saved frames the plain reading alone missed every card title and listed row, and
 * the mask alone misses dark text.</p>
 *
 * <p>Nothing is guessed: an unreadable price stays {@code null} with its raw text kept, a tile
 * whose quantity is not read is left out, and a card title that cannot be read becomes the page
 * title plus the price, so different price tiers never merge in history.</p>
 */
public final class DealFrameReader {

    /**
     * @param clippedTileRowY centre y of a single-card tile row whose last tile runs into the card
     *                        edge, meaning more tiles are hidden to the right; {@code null} otherwise
     */
    public record Page(String title, List<DealOffer> offers, List<String> problems, Integer clippedTileRowY) {
    }

    private record Text(List<TextLine> plainLines, List<TextLine> whiteLines, List<TextLine> whiteWords,
            RawImageData capture, RawImageData whiteMask) {
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
    private static final int MIN_TITLE_LETTERS = 5;
    /** Item tiles on stacked cards sit beside and just below their button. */
    private static final int CARD_BELOW_BUTTON = 90;
    /** Purchase buttons carry their label on the left; the right edge holds a badge. */
    private static final double PRICE_TEXT_WIDTH_SHARE = 0.85;
    /** Height a button crop is normalised to before its fallback price read. */
    private static final int PRICE_CROP_HEIGHT = 40;
    private static final int REMAINING_WINDOW = 110;
    /** Quantity band of a tile, relative to the icon match (the template starts 6,4 into the tile). */
    private static final int TILE_LEFT = -6;
    private static final int TILE_RIGHT = 89;
    private static final int QUANTITY_TOP = 46;
    private static final int QUANTITY_BOTTOM = 92;
    /** Tiles on one row share a top within this many pixels. */
    private static final int SAME_ROW_TOLERANCE = 20;
    /** A row whose last tile ends past this x is cut off by the card edge (inner edge ~640). */
    private static final int CLIPPED_TILE_MIN_RIGHT = 620;

    private static final Pattern PRICE = Pattern.compile("\\$\\s?(\\d{1,4})[.°,:](\\d{2})");
    private static final Pattern REMAINING = Pattern.compile("(?i)remaining\\W*(\\d+)");
    private static final Pattern TIMER = Pattern.compile("\\d{1,2}:\\d{2}(:\\d{2})?");
    private static final Pattern LISTED_ROW = Pattern.compile("^(.*[A-Za-z].*?)\\s+[xX]\\s?(\\d[\\d,]*)\\b.*$");
    private static final Pattern PURCHASED = Pattern.compile("(?i)\\bpurchased\\b");
    private static final Pattern POINTS_NEEDED = Pattern.compile("(?i)top.?up.*?([\\d,]{3,})\\s*more\\s*points");

    private static final Pattern QUANTITY = Pattern.compile("^\\d{1,3}(,\\d{3})*$|^\\d{1,7}$");
    private static final Pattern SHORT_SPEEDUP = Pattern.compile("^[lIi|]([hm])$");
    private static final List<String> NOT_A_TITLE = List.of("remaining", "purchase", "select your", "best deal",
            "claimed", "top up", "one available", "resources", "traveling", "brilliant design", "utilize");

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
        BufferedImage image = ImageConverter.toBufferedImage(capture);
        Text text = readText(capture, image, surface, tab, problems);
        List<PriceButtonLocator.Box> buttons = PriceButtonLocator.locate(image).stream()
                .filter(button -> !isActionButton(text.whiteWords(), button))
                .toList();

        TextLine pageTitleLine = pageTitle(text.whiteLines(), tabbed);
        if (pageTitleLine == null) {
            pageTitleLine = pageTitle(text.plainLines(), tabbed);
        }
        String pageTitle = pageTitleLine == null ? tab : cleanTitle(pageTitleLine.text());
        List<DealOffer> offers = new ArrayList<>();
        List<DealItemLibrary.Match> tiles = new ArrayList<>();

        int windowTop = FRAME_TOP_LEFT.getY();
        for (PriceButtonLocator.Box button : buttons) {
            int top = windowTop;
            int bottom = button.bottom() + CARD_BELOW_BUTTON;
            String priceText = readPrice(image, text.whiteWords(), button);
            Matcher price = PRICE.matcher(priceText.replace(" ", ""));
            Double priceUsd = price.find() ? Double.valueOf(price.group(1) + "." + price.group(2)) : null;

            String title;
            int cardTop;
            if (buttons.size() == 1) {
                title = pageTitle;
                cardTop = pageTitleLine == null ? top : pageTitleLine.bottom();
            } else {
                TextLine cardTitle = cardTitle(text.whiteLines(), top, button);
                title = cardTitle != null ? cleanTitle(cardTitle.text())
                        : pageTitle + (priceUsd == null ? "" : " - $" + String.format(Locale.US, "%.2f", priceUsd));
                cardTop = cardTitle == null ? Math.max(top, CARD_TITLE_MIN_TOP) : cardTitle.bottom();
            }
            if (priceUsd == null) {
                problems.add(surface + " / " + tab + " / " + title + ": price unreadable (\"" + priceText + "\")");
            }

            List<DealItem> items = new ArrayList<>();
            items.addAll(iconItems(text, cardTop, bottom, tiles));
            items.addAll(listedItems(text.whiteLines(), top, bottom));

            List<TextLine> window = Stream.concat(text.plainLines().stream(), text.whiteLines().stream())
                    .filter(l -> l.top() >= top && l.bottom() <= bottom)
                    .toList();
            offers.add(new DealOffer(surface, tab, title, priceUsd, priceText, remaining(window, button),
                    false, merge(items), frame));
            windowTop = bottom;
        }

        if (buttons.isEmpty()) {
            unpricedOffer(text, pageTitleLine, surface, tab, pageTitle, frame).ifPresent(offers::add);
        }
        return new Page(pageTitle, offers, problems, buttons.size() == 1 ? clippedRow(tiles) : null);
    }

    private static Text readText(RawImageData capture, BufferedImage image, String surface, String tab,
            List<String> problems) {
        RawImageData mask = WhiteTextIsolator.isolate(image, 0, 0, image.getWidth(), image.getHeight(), 0);
        List<TextLine> plain = List.of();
        List<TextLine> white = List.of();
        List<TextLine> words = List.of();
        try {
            plain = OcrEngine.recognizeLines(capture, FRAME_TOP_LEFT, FRAME_BOTTOM_RIGHT,
                    CommonOCRSettings.DEAL_PAGE_TEXT_SETTINGS);
            white = OcrEngine.recognizeLines(mask, FRAME_TOP_LEFT, FRAME_BOTTOM_RIGHT,
                    CommonOCRSettings.DEAL_PAGE_TEXT_SETTINGS);
            words = OcrEngine.recognizeWords(mask, FRAME_TOP_LEFT, FRAME_BOTTOM_RIGHT,
                    CommonOCRSettings.DEAL_PAGE_TEXT_SETTINGS);
        } catch (OcrException ocrFailed) {
            problems.add(surface + " / " + tab + ": page text unreadable (" + ocrFailed.getMessage() + ")");
        }
        return new Text(plain, white, words, capture, mask);
    }

    /** "TOP UP NOW" uses the purchase button's colour; a label made of words is not a price. */
    private static boolean isActionButton(List<TextLine> words, PriceButtonLocator.Box button) {
        String inside = wordsInside(words, button.x(), button.y(), button.right(), button.bottom());
        long letters = inside.chars().filter(Character::isLetter).count();
        return letters >= 4 && inside.chars().noneMatch(Character::isDigit);
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
                        merge(iconItems(text, top, line.top(), new ArrayList<>())), frame));
            }
        }
        for (TextLine line : lines) {
            if (PURCHASED.matcher(clean(line.text())).find()) {
                return Optional.of(new DealOffer(surface, tab, pageTitle, null, "Purchased", null, true,
                        merge(iconItems(text, top, line.top(), new ArrayList<>())), frame));
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

    private static TextLine pageTitle(List<TextLine> lines, boolean tabbed) {
        int top = tabbed ? TAB_STRIP_BOTTOM : PAGE_TITLE_TOP;
        return lines.stream()
                .filter(l -> l.top() >= top && l.bottom() <= PAGE_TITLE_BOTTOM && isTitleText(l.text()))
                .max(Comparator.comparingInt(TextLine::height))
                .orElse(null);
    }

    private static TextLine cardTitle(List<TextLine> lines, int windowTop, PriceButtonLocator.Box button) {
        return lines.stream()
                .filter(l -> l.top() >= Math.max(windowTop, CARD_TITLE_MIN_TOP) && l.bottom() <= button.y())
                .filter(l -> isTitleText(l.text()))
                .max(Comparator.comparingInt(TextLine::height))
                .orElse(null);
    }

    /** Real titles are mostly real words; OCR of artwork is mostly one- and two-letter fragments. */
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
        while (end > start && !isWordLike(tokens[end - 1])) {
            end--;
        }
        return start >= end ? clean(raw) : String.join(" ", Arrays.copyOfRange(tokens, start, end));
    }

    private static boolean isWordLike(String token) {
        return token.chars().filter(Character::isLetterOrDigit).count() >= 3
                || token.matches("(?i)of|in|to|a|&");
    }

    private static String readPrice(BufferedImage image, List<TextLine> words, PriceButtonLocator.Box button) {
        String inside = wordsInside(words, button.x(), button.y(), button.right(), button.bottom());
        if (PRICE.matcher(inside.replace(" ", "")).find()) {
            return inside;
        }
        int width = (int) (button.width() * PRICE_TEXT_WIDTH_SHARE);
        RawImageData crop = WhiteTextIsolator.isolateScaled(image, button.x(), button.y(), width, button.height(),
                PRICE_CROP_HEIGHT);
        try {
            String read = OcrEngine.recognizeText(crop, new PointData(0, 0),
                    new PointData(crop.getWidth(), crop.getHeight()), CommonOCRSettings.DEAL_PRICE_SETTINGS).trim();
            return read.isEmpty() ? inside : read;
        } catch (OcrException ocrFailed) {
            return inside;
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

    private List<DealItem> iconItems(Text text, int top, int bottom, List<DealItemLibrary.Match> tiles) {
        List<DealItem> items = new ArrayList<>();
        if (library.size() == 0 || bottom <= top) {
            return items;
        }
        int clampedBottom = Math.min(bottom, FRAME_BOTTOM_RIGHT.getY());
        Map<Integer, List<TextLine>> rowWords = new LinkedHashMap<>();
        for (DealItemLibrary.Match match : library.find(text.capture(), new PointData(0, top),
                new PointData(FRAME_BOTTOM_RIGHT.getX(), clampedBottom))) {
            tiles.add(match);
            Optional<Long> quantity = quantity(text.whiteWords(), match);
            if (quantity.isEmpty()) {
                // A narrow strip gives the recogniser one row of numbers to lay out instead of a
                // whole busy frame; on the brown Vault tiles only the strip read them.
                List<TextLine> strip = rowWords.computeIfAbsent(match.y() / SAME_ROW_TOLERANCE,
                        row -> rowStripWords(text.whiteMask(), match));
                quantity = quantity(strip, match);
            }
            quantity.ifPresent(q -> items.add(new DealItem(match.key(), q, "icon")));
        }
        return items;
    }

    private static List<TextLine> rowStripWords(RawImageData mask, DealItemLibrary.Match match) {
        int top = Math.max(0, match.y() + QUANTITY_TOP);
        int bottom = Math.min(FRAME_BOTTOM_RIGHT.getY(), match.y() + QUANTITY_BOTTOM);
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
        int left = match.x() + TILE_LEFT;
        int right = match.x() + TILE_RIGHT;
        int top = match.y() + QUANTITY_TOP;
        int bottom = match.y() + QUANTITY_BOTTOM;
        return words.stream()
                .filter(w -> centreX(w) >= left && centreX(w) <= right && centreY(w) >= top && centreY(w) <= bottom)
                .filter(w -> QUANTITY.matcher(clean(w.text())).matches())
                .max(Comparator.comparingInt(TextLine::left))
                .map(w -> parseQuantity(w.text()));
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
                if (quantity != null && !name.isEmpty()) {
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

    /** An item name starts with an amount ("5m", "10K", "100") or a real word, never a stray glyph pair. */
    private static boolean startsItemName(String token) {
        return token.matches("\\d+[A-Za-z]{0,3}") || SHORT_SPEEDUP.matcher(token).matches()
                || token.chars().filter(Character::isLetter).count() >= 3;
    }

    static Long parseQuantity(String raw) {
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
