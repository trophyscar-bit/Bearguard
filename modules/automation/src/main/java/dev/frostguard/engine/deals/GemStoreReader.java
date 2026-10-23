package dev.frostguard.engine.deals;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.frostguard.api.domain.OcrSettingsData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.engine.nav.CommonOCRSettings;
import dev.frostguard.vision.convert.ImageConverter;
import dev.frostguard.vision.convert.WhiteTextIsolator;
import dev.frostguard.vision.ocr.OcrEngine;
import dev.frostguard.vision.ocr.OcrException;

/**
 * Reads the gem bundles on the gem shop's "Get Gems" tab.
 *
 * <p>The tab has no orange purchase buttons, so {@link DealFrameReader} finds nothing on it. It shows
 * six blue cards in a 2 x 3 grid, each with a cyan gem quantity, a white price and, until the tier is
 * bought, an orange "First +N" bonus ribbon.</p>
 *
 * <p>The page is recognised by that grid, found from the cards' own colour, and confirmed by at least
 * one gem quantity read from a card. A card whose quantity or price cannot be read is left out and
 * reported; nothing is guessed.</p>
 *
 * <p>Every region below is measured on the 720 x 1280 frame of 2026-10-22 (fixture
 * {@code gem-shop-get-gems.png}); offsets are relative to a card's top-left corner.</p>
 */
public final class GemStoreReader {

    /** A rectangle on the frame, in frame pixels. */
    public record Box(int x, int y, int width, int height) {
        public int right() {
            return x + width;
        }

        public int bottom() {
            return y + height;
        }
    }

    /**
     * @param bonusGems the first-purchase bonus printed on the card's ribbon; {@code null} when the card
     *                  shows no ribbon or its number could not be read (the latter is reported)
     */
    public record GemBundle(long gems, double priceUsd, Long bonusGems, Box card) {
    }

    private static final Logger log = LoggerFactory.getLogger(GemStoreReader.class);

    /** Tab labels end above this line, so the grid search starts below it. */
    private static final int PANEL_TOP = 190;

    /**
     * Card body colour. Sampled bodies run hue 213-224, saturation 126-177, brightness 212-241; the
     * navy gaps between cards are brightness 118-167 and fall out.
     */
    private static final float CARD_MIN_HUE = 200f / 360f;
    private static final float CARD_MAX_HUE = 235f / 360f;
    private static final float CARD_MIN_SATURATION = 90f / 255f;
    private static final float CARD_MIN_BRIGHTNESS = 190f / 255f;
    /**
     * Rows and columns at or above this share of card colour belong to a card. Inside the cards no
     * column falls below 0.28 (the gem artwork is paler); the gaps between them stay at 0.08 or less.
     */
    private static final double MIN_LINE_FILL = 0.15;
    /** Every card measures 315 x 233 px; the grid pitch is 335 px across and 265-266 px down. */
    private static final int CARD_WIDTH = 315;
    private static final int CARD_HEIGHT = 233;
    private static final int SIZE_TOLERANCE = 15;
    private static final int ALIGN_TOLERANCE = 4;
    private static final int GRID_COLUMNS = 2;
    private static final int GRID_ROWS = 3;
    /**
     * Cards fill 0.63-0.76 of their box with card colour. On the 17 other saved shop, deal and pop-up
     * frames, the only card-sized cell the run search finds (on the Regular Pack page) fills 0.41,
     * and none of them has even one band that splits into two card-sized cells.
     */
    private static final double MIN_CARD_FILL = 0.55;

    /**
     * Gem quantity band. The cyan digits sit at 108-205 x 143-168 on every card; the band stops at 172
     * because the paler price strip starts at 173, and at 255 because the top-up badge starts at 261.
     */
    private static final int QUANTITY_LEFT = 60;
    private static final int QUANTITY_TOP = 137;
    private static final int QUANTITY_WIDTH = 195;
    private static final int QUANTITY_HEIGHT = 35;
    /**
     * The digits are filled with exactly (151, 238, 254) on all six cards. Within the band, pixels
     * up to 49 channel steps from that colour number about 4,500 (fill and anti-aliasing), 50-69 steps
     * about 400, and the card behind them sits at 80 or more; the cut is the 50-69 valley.
     */
    private static final Color QUANTITY_INK = new Color(151, 238, 254);
    private static final int QUANTITY_INK_TOLERANCE = 50;
    /**
     * The masked band is shrunk to this height before reading. At its native 35 px the recogniser read
     * "500" as "200" and "2,500" as "2,200"; scaled to 18 or 24 px all six cards read correctly, at
     * 30 px two misread.
     */
    private static final int QUANTITY_READ_HEIGHT = 24;

    /** Price line: white glyphs at 100-217 x 187-212, read like the purchase buttons' labels. */
    private static final int PRICE_LEFT = 60;
    private static final int PRICE_TOP = 181;
    private static final int PRICE_WIDTH = 200;
    private static final int PRICE_HEIGHT = 38;
    private static final int PRICE_READ_HEIGHT = 40;

    /**
     * The ribbon overhangs the card's top-left corner (-5..123 x -9..58). Its orange fills 0.53-0.60 of
     * that box; the card body below it never fills more than 0.01 of a window that size.
     */
    private static final int RIBBON_LEFT = -5;
    private static final int RIBBON_TOP = -9;
    private static final int RIBBON_WIDTH = 129;
    private static final int RIBBON_HEIGHT = 68;
    private static final float RIBBON_MIN_HUE = 10f / 360f;
    private static final float RIBBON_MAX_HUE = 35f / 360f;
    private static final float RIBBON_MIN_SATURATION = 180f / 255f;
    private static final float RIBBON_MIN_BRIGHTNESS = 200f / 255f;
    private static final double MIN_RIBBON_FILL = 0.25;
    /**
     * The ribbon's "+N" line: glyphs on rows 24-41, the widest ("+50,000") ending at x 118 and the
     * ribbon's own white border starting at 120.
     */
    private static final int BONUS_LEFT = 0;
    private static final int BONUS_TOP = 22;
    private static final int BONUS_WIDTH = 120;
    private static final int BONUS_HEIGHT = 22;
    private static final int MASK_PADDING = 8;
    /**
     * The plus sign is the anchor for the bonus number. Read without it, the gem icon in front of the
     * number came back as a leading digit ("42,500", "410,000"); with it, the icon reads as an extra plus
     * ("++5,000") or nothing, and the number after the last plus was right on all six cards.
     */
    private static final OcrSettingsData BONUS_SETTINGS = OcrSettingsData.builder()
            .textLayout(OcrSettingsData.TextLayout.SINGLE_LINE)
            .allowedGlyphs("+0123456789,")
            .build();

    private static final Pattern GROUPED_NUMBER = Pattern.compile("\\d{1,3}(,\\d{3})*");
    private static final Pattern BONUS = Pattern.compile("\\+(\\d[\\d,]*)");

    private GemStoreReader() {
    }

    /** Reads the frame and logs anything left out. */
    public static Optional<List<GemBundle>> read(RawImageData frame) {
        List<String> problems = new ArrayList<>();
        Optional<List<GemBundle>> bundles = read(frame, problems);
        problems.forEach(problem -> log.warn("Gem store: {}", problem));
        return bundles;
    }

    /**
     * @param problems receives one entry per card or value that was left out
     * @return the readable bundles in grid order (left to right, top to bottom), or empty when the frame
     *         is not the Get Gems page
     */
    public static Optional<List<GemBundle>> read(RawImageData frame, List<String> problems) {
        BufferedImage image = ImageConverter.toBufferedImage(frame);
        Optional<List<Box>> grid = locateGrid(image);
        if (grid.isEmpty()) {
            return Optional.empty();
        }
        List<GemBundle> bundles = new ArrayList<>();
        for (Box card : grid.get()) {
            readCard(image, card, problems).ifPresent(bundles::add);
        }
        if (bundles.isEmpty()) {
            problems.add("a 2 x 3 card grid was found but no card's gem quantity and price could be read;"
                    + " not treated as the gem store");
            return Optional.empty();
        }
        return Optional.of(List.copyOf(bundles));
    }

    private static Optional<GemBundle> readCard(BufferedImage image, Box card, List<String> problems) {
        String quantityText = readQuantity(image, card);
        Long gems = parseGrouped(quantityText);
        if (gems == null) {
            problems.add("card at " + card + " left out: gem quantity unreadable (\"" + quantityText + "\")");
            return Optional.empty();
        }
        String priceText = readPrice(image, card);
        Double price = DealFrameReader.parsePrice(priceText);
        if (price == null) {
            problems.add("card at " + card + " (" + gems + " gems) left out: price unreadable (\"" + priceText + "\")");
            return Optional.empty();
        }
        Long bonus = null;
        if (hasRibbon(image, card)) {
            String bonusText = readBonus(image, card);
            bonus = parseBonus(bonusText);
            if (bonus == null) {
                problems.add("card at " + card + " (" + gems + " gems): first-purchase bonus unreadable (\""
                        + bonusText + "\")");
            }
        }
        return Optional.of(new GemBundle(gems, price, bonus, card));
    }

    /** The six card boxes, or empty unless the frame holds exactly a regular 2 x 3 grid of them. */
    private static Optional<List<Box>> locateGrid(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        if (height <= PANEL_TOP) {
            return Optional.empty();
        }
        boolean[][] mask = new boolean[height][width];
        int[] rowCount = new int[height];
        float[] hsb = new float[3];
        for (int y = PANEL_TOP; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = image.getRGB(x, y);
                Color.RGBtoHSB((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, hsb);
                boolean card = hsb[0] >= CARD_MIN_HUE && hsb[0] <= CARD_MAX_HUE
                        && hsb[1] >= CARD_MIN_SATURATION && hsb[2] >= CARD_MIN_BRIGHTNESS;
                mask[y][x] = card;
                if (card) {
                    rowCount[y]++;
                }
            }
        }

        List<Box> cards = new ArrayList<>();
        int rows = 0;
        int runStart = -1;
        for (int y = PANEL_TOP; y <= height; y++) {
            boolean filled = y < height && rowCount[y] >= MIN_LINE_FILL * width;
            if (filled && runStart < 0) {
                runStart = y;
            } else if (!filled && runStart >= 0) {
                int bandHeight = y - runStart;
                if (Math.abs(bandHeight - CARD_HEIGHT) <= SIZE_TOLERANCE) {
                    List<Box> row = cardsInBand(mask, runStart, bandHeight, width);
                    if (row.size() != GRID_COLUMNS) {
                        return Optional.empty();
                    }
                    cards.addAll(row);
                    rows++;
                }
                runStart = -1;
            }
        }
        if (rows != GRID_ROWS || !isRegular(cards)) {
            return Optional.empty();
        }
        return Optional.of(List.copyOf(cards));
    }

    private static List<Box> cardsInBand(boolean[][] mask, int top, int bandHeight, int width) {
        List<Box> cells = new ArrayList<>();
        int runStart = -1;
        for (int x = 0; x <= width; x++) {
            boolean filled = false;
            if (x < width) {
                int count = 0;
                for (int y = top; y < top + bandHeight; y++) {
                    if (mask[y][x]) {
                        count++;
                    }
                }
                filled = count >= MIN_LINE_FILL * bandHeight;
            }
            if (filled && runStart < 0) {
                runStart = x;
            } else if (!filled && runStart >= 0) {
                Box cell = new Box(runStart, top, x - runStart, bandHeight);
                if (Math.abs(cell.width() - CARD_WIDTH) <= SIZE_TOLERANCE && fill(mask, cell) >= MIN_CARD_FILL) {
                    cells.add(cell);
                }
                runStart = -1;
            }
        }
        return cells;
    }

    private static double fill(boolean[][] mask, Box box) {
        int count = 0;
        for (int y = box.y(); y < box.bottom(); y++) {
            for (int x = box.x(); x < box.right(); x++) {
                if (mask[y][x]) {
                    count++;
                }
            }
        }
        return (double) count / ((long) box.width() * box.height());
    }

    /** Columns line up from row to row and the rows are evenly spaced. */
    private static boolean isRegular(List<Box> cards) {
        for (int i = GRID_COLUMNS; i < cards.size(); i++) {
            Box above = cards.get(i - GRID_COLUMNS);
            if (Math.abs(cards.get(i).x() - above.x()) > ALIGN_TOLERANCE
                    || Math.abs(cards.get(i).right() - above.right()) > ALIGN_TOLERANCE) {
                return false;
            }
        }
        int firstPitch = cards.get(GRID_COLUMNS).y() - cards.get(0).y();
        for (int row = 2; row < GRID_ROWS; row++) {
            int pitch = cards.get(row * GRID_COLUMNS).y() - cards.get((row - 1) * GRID_COLUMNS).y();
            if (Math.abs(pitch - firstPitch) > ALIGN_TOLERANCE) {
                return false;
            }
        }
        return true;
    }

    /**
     * The cyan digits are repainted white on black so {@link WhiteTextIsolator} can mask and scale them
     * exactly as it does white labels.
     */
    private static String readQuantity(BufferedImage image, Box card) {
        BufferedImage whitened = whitened(image, card.x() + QUANTITY_LEFT, card.y() + QUANTITY_TOP, QUANTITY_WIDTH,
                QUANTITY_HEIGHT, QUANTITY_INK, QUANTITY_INK_TOLERANCE);
        RawImageData mask = WhiteTextIsolator.isolateScaled(whitened, 0, 0, whitened.getWidth(), whitened.getHeight(),
                QUANTITY_READ_HEIGHT);
        return cropRead(mask, CommonOCRSettings.DEAL_ITEM_QUANTITY_SETTINGS);
    }

    private static String readPrice(BufferedImage image, Box card) {
        RawImageData crop = WhiteTextIsolator.isolateScaled(image, card.x() + PRICE_LEFT, card.y() + PRICE_TOP,
                PRICE_WIDTH, PRICE_HEIGHT, PRICE_READ_HEIGHT);
        String strict = cropRead(crop, CommonOCRSettings.DEAL_PRICE_SETTINGS);
        if (DealFrameReader.parsePrice(strict) != null) {
            return strict;
        }
        // The digits-and-dot glyph set drops the decimal point on some labels ("$499" for $4.99); an
        // unrestricted read of the same crop recovered it on every purchase button measured.
        String loose = cropRead(crop, CommonOCRSettings.DEAL_PAGE_TEXT_SETTINGS);
        return DealFrameReader.parsePrice(loose) != null ? loose : strict;
    }

    private static boolean hasRibbon(BufferedImage image, Box card) {
        int left = Math.max(0, card.x() + RIBBON_LEFT);
        int top = Math.max(0, card.y() + RIBBON_TOP);
        int right = Math.min(image.getWidth(), card.x() + RIBBON_LEFT + RIBBON_WIDTH);
        int bottom = Math.min(image.getHeight(), card.y() + RIBBON_TOP + RIBBON_HEIGHT);
        int count = 0;
        float[] hsb = new float[3];
        for (int y = top; y < bottom; y++) {
            for (int x = left; x < right; x++) {
                int rgb = image.getRGB(x, y);
                Color.RGBtoHSB((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, hsb);
                if (hsb[0] >= RIBBON_MIN_HUE && hsb[0] <= RIBBON_MAX_HUE && hsb[1] >= RIBBON_MIN_SATURATION
                        && hsb[2] >= RIBBON_MIN_BRIGHTNESS) {
                    count++;
                }
            }
        }
        return count >= MIN_RIBBON_FILL * RIBBON_WIDTH * RIBBON_HEIGHT;
    }

    private static String readBonus(BufferedImage image, Box card) {
        RawImageData crop = WhiteTextIsolator.isolate(image, card.x() + BONUS_LEFT, card.y() + BONUS_TOP,
                BONUS_WIDTH, BONUS_HEIGHT, MASK_PADDING);
        return cropRead(crop, BONUS_SETTINGS);
    }

    static Long parseBonus(String text) {
        Matcher bonus = BONUS.matcher(text == null ? "" : text.replace(" ", ""));
        String last = null;
        while (bonus.find()) {
            last = bonus.group(1);
        }
        return parseGrouped(last);
    }

    /** "2,500" or "500"; a number whose separators are out of place is unreadable, not repaired. */
    static Long parseGrouped(String text) {
        String clean = text == null ? "" : text.replace(" ", "");
        if (!GROUPED_NUMBER.matcher(clean).matches()) {
            return null;
        }
        long value = Long.parseLong(clean.replace(",", ""));
        return value > 0 ? value : null;
    }

    /** Pixels within {@code tolerance} of {@code ink} on every channel become white, the rest black. */
    private static BufferedImage whitened(BufferedImage image, int x, int y, int width, int height, Color ink,
            int tolerance) {
        int left = Math.max(0, x);
        int top = Math.max(0, y);
        int right = Math.min(image.getWidth(), x + width);
        int bottom = Math.min(image.getHeight(), y + height);
        BufferedImage out = new BufferedImage(Math.max(1, right - left), Math.max(1, bottom - top),
                BufferedImage.TYPE_INT_RGB);
        for (int sy = top; sy < bottom; sy++) {
            for (int sx = left; sx < right; sx++) {
                int rgb = image.getRGB(sx, sy);
                int distance = Math.max(Math.abs(((rgb >> 16) & 0xFF) - ink.getRed()),
                        Math.max(Math.abs(((rgb >> 8) & 0xFF) - ink.getGreen()), Math.abs((rgb & 0xFF) - ink.getBlue())));
                out.setRGB(sx - left, sy - top, distance <= tolerance ? 0xFFFFFF : 0x000000);
            }
        }
        return out;
    }

    private static String cropRead(RawImageData crop, OcrSettingsData settings) {
        try {
            return OcrEngine.recognizeText(crop, new PointData(0, 0),
                    new PointData(crop.getWidth(), crop.getHeight()), settings).trim();
        } catch (OcrException unreadable) {
            return "";
        }
    }
}
