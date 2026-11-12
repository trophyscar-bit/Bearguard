package dev.frostguard.engine.squads;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.vision.convert.ImageConverter;

/**
 * Hero cards as drawn on the Heroes grid and on the Deploy screen. Both use the same 155x272 card
 * art at the same scale, so a portrait cut from the grid identifies the hero on a saved squad.
 */
public final class HeroCards {

    public static final int CARD_WIDTH = 155;
    public static final int CARD_HEIGHT = 272;
    // Grid columns measured on the Heroes screen; the grid scrolls vertically only.
    private static final int[] GRID_LEFT = { 33, 200, 366, 532 };
    private static final int GRID_TOP_LIMIT = 85;
    private static final int GRID_BOTTOM_LIMIT = 1125;
    // Deploy screen hero cards, left to right.
    private static final int[] DEPLOY_LEFT = { 94, 283, 472 };
    private static final int DEPLOY_TOP = 247;

    // Real frames scored 0.94-0.99 for the same hero and at most 0.43 for the next best, so 0.80
    // with a 0.25 lead over the runner-up keeps a wide margin on both sides.
    static final double MATCH_MIN = 0.80;
    static final double MATCH_LEAD = 0.25;
    static final int OWNED_STAR_PIXELS_MIN = 100;

    private HeroCards() {
    }

    public record Card(int left, int top) {
        public int centreX() { return left + CARD_WIDTH / 2; }
        public int centreY() { return top + CARD_HEIGHT / 2; }
    }

    /** Grayscale face crop of a card, used for identity matching. */
    public record Portrait(double[] pixels) {
    }

    public static boolean isHeroesGrid(RawImageData frame) {
        return SquadOcr.text(frame, 90, 18, 260, 64, SquadOcr.LINE).startsWith("Heroes");
    }

    /** Fully visible cards on the Heroes grid, in reading order. */
    public static List<Card> gridCards(RawImageData frame) {
        BufferedImage image = ImageConverter.toBufferedImage(frame);
        List<Card> cards = new ArrayList<>();
        List<Integer> tops = cardTops(image, GRID_LEFT[0] + 77);
        for (int top : tops) {
            for (int left : GRID_LEFT) {
                if (!isGap(image.getRGB(left + 77, top + CARD_HEIGHT / 2))) {
                    cards.add(new Card(left, top));
                }
            }
        }
        return cards;
    }

    public static List<Card> deployCards() {
        List<Card> cards = new ArrayList<>();
        for (int left : DEPLOY_LEFT) cards.add(new Card(left, DEPLOY_TOP));
        return cards;
    }

    /**
     * Owned cards show a row of stars where unowned ones show a shard bar. Real frames measured
     * 227-579 lit star pixels on owned cards and 0 on unowned and empty grid cells.
     */
    public static boolean isOwned(RawImageData frame, Card card) {
        BufferedImage image = ImageConverter.toBufferedImage(frame);
        int lit = 0;
        for (int x = card.left() + 8; x < card.left() + 147; x++) {
            for (int y = card.top() + 228; y < card.top() + 266; y++) {
                int rgb = image.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
                if (r < 150 && g > 190 && b > 190) lit++;
            }
        }
        return lit >= OWNED_STAR_PIXELS_MIN;
    }

    /** The "Lv. N" printed on a card, or null when it cannot be read. */
    public static Integer level(RawImageData frame, Card card) {
        // Neither preset reads every card on its own: the white mask loses levels over pale art and
        // the plain read loses them over busy art, so the white read gets a plain second opinion.
        for (var cfg : java.util.List.of(SquadOcr.WHITE_LEVEL, SquadOcr.LEVEL)) {
            String text = SquadOcr.text(frame, card.left() + 8, card.top() + 203,
                    card.left() + 84, card.top() + 233, cfg);
            Integer level = text.contains("L") ? SquadOcr.smallNumber(text) : null;
            if (level != null) return level;
        }
        return null;
    }

    public static Portrait portrait(RawImageData frame, Card card) {
        BufferedImage image = ImageConverter.toBufferedImage(frame);
        int x0 = card.left() + 25, y0 = card.top() + 40, w = 105, h = 150;
        double[] pixels = new double[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = image.getRGB(x0 + x, y0 + y);
                pixels[y * w + x] = 0.299 * ((rgb >> 16) & 0xFF) + 0.587 * ((rgb >> 8) & 0xFF) + 0.114 * (rgb & 0xFF);
            }
        }
        return new Portrait(pixels);
    }

    /** Normalised cross-correlation of two portraits, from -1 to 1. */
    public static double similarity(Portrait a, Portrait b) {
        double meanA = mean(a.pixels()), meanB = mean(b.pixels());
        double num = 0, da = 0, db = 0;
        for (int i = 0; i < a.pixels().length; i++) {
            double x = a.pixels()[i] - meanA, y = b.pixels()[i] - meanB;
            num += x * y;
            da += x * x;
            db += y * y;
        }
        return da == 0 || db == 0 ? 0 : num / Math.sqrt(da * db);
    }

    /** Index of the gallery portrait that clearly matches, or -1 when none does. */
    public static int bestMatch(Portrait target, List<Portrait> gallery) {
        int best = -1;
        double bestScore = -1, runnerUp = -1;
        for (int i = 0; i < gallery.size(); i++) {
            double score = similarity(target, gallery.get(i));
            if (score > bestScore) {
                runnerUp = bestScore;
                bestScore = score;
                best = i;
            } else if (score > runnerUp) {
                runnerUp = score;
            }
        }
        return bestScore >= MATCH_MIN && bestScore - runnerUp >= MATCH_LEAD ? best : -1;
    }

    // Card tops are where the panel's gap colour gives way to a card that stays unbroken for a
    // full card height, which skips the partly scrolled rows at the top and bottom of the panel.
    private static List<Integer> cardTops(BufferedImage image, int x) {
        List<Integer> tops = new ArrayList<>();
        int y = GRID_TOP_LIMIT;
        while (y < GRID_BOTTOM_LIMIT) {
            if (isGap(image.getRGB(x, y)) && y + 1 < GRID_BOTTOM_LIMIT && !isGap(image.getRGB(x, y + 1))) {
                int top = y + 1;
                int end = top;
                while (end < GRID_BOTTOM_LIMIT && !isGap(image.getRGB(x, end))) end++;
                // Small badges inside a card can break the run briefly; measure to the next gap
                // that lasts at least 4px.
                while (end < GRID_BOTTOM_LIMIT && gapRun(image, x, end) < 4) {
                    end++;
                    while (end < GRID_BOTTOM_LIMIT && !isGap(image.getRGB(x, end))) end++;
                }
                if (end - top >= CARD_HEIGHT - 8 && top + CARD_HEIGHT <= GRID_BOTTOM_LIMIT) tops.add(top);
                y = end;
            } else {
                y++;
            }
        }
        return tops;
    }

    private static int gapRun(BufferedImage image, int x, int y) {
        int run = 0;
        while (y + run < GRID_BOTTOM_LIMIT && isGap(image.getRGB(x, y + run))) run++;
        return run;
    }

    // The grid panel behind the cards measured (13,69,125).
    static boolean isGap(int rgb) {
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        return Math.abs(r - 13) < 12 && Math.abs(g - 69) < 14 && Math.abs(b - 125) < 16;
    }

    private static double mean(double[] values) {
        double sum = 0;
        for (double v : values) sum += v;
        return sum / values.length;
    }
}
