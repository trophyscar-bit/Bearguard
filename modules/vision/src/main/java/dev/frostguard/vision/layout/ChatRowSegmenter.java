package dev.frostguard.vision.layout;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Splits a chat screenshot into one row per message, using the avatar tiles as the anchor.
 *
 * <p>OCR'ing the whole feed as a single block and cutting the result apart afterwards cannot
 * recover message boundaries: the reader has already merged several people's lines into one
 * string, and a sender whose name OCR'd badly leaves nothing for a pattern to key on. Live
 * captures show exactly that -- senders read as {@code Ww} or {@code oe Ser ee Perak}, with
 * three people's text in one body.
 *
 * <p>The layout gives a better anchor than the text does. Every message owns exactly one avatar
 * tile in a fixed-width gutter down the left edge, so finding the tiles yields message boundaries
 * geometrically, before OCR is involved at all. The caller can then read each row's name strip and
 * body separately, which is what produces a real {@code (sender, text)} pair.
 *
 * <p>Avatars are detected by colour variance rather than brightness. They are colourful portrait
 * art on a flat page background, but they are not reliably <em>bright</em> -- several hero
 * portraits are darker than the page, and a brightness threshold drops them. Variance separates
 * "art" from "flat background" regardless of how dark the art is.
 */
public final class ChatRowSegmenter {

    /** One message's vertical extent, plus the avatar tile that anchored it. */
    public record Row(int top, int bottom, int avatarTop, int avatarBottom,
                      int nameTop, int nameBottom, boolean hasNameLine) {
        public int height() {
            return bottom - top + 1;
        }
    }

    /** Feed bounds and gutters, in the game's native 720x1280 space. */
    public static final int FEED_TOP = 175;
    public static final int FEED_BOTTOM = 1150;
    private static final int AVATAR_X0 = 14;
    private static final int AVATAR_X1 = 116;

    /** An always-empty column right of the bubbles, sampled as the flat-background reference. */
    private static final int BG_X0 = 690;
    private static final int BG_X1 = 715;

    private static final double BG_VARIANCE_MULTIPLE = 3.0;
    private static final double MIN_VARIANCE = 18.0;

    /** Shorter than this is a badge or bubble tail catching the gutter, not a portrait. */
    private static final int MIN_TILE_HEIGHT = 45;

    /** A portrait can carry a flat band across it; bridge gaps this small rather than splitting. */
    private static final int MERGE_GAP = 12;

    /** Above this multiple of the typical tile, a run is stacked avatars whose gap was bridged. */
    private static final double SPLIT_RATIO = 1.6;

    private ChatRowSegmenter() {
    }

    /**
     * @param frame a full-screen chat capture
     * @return one row per detected message, top to bottom; empty when the frame carries no feed
     */
    public static List<Row> segment(BufferedImage frame) {
        int top = Math.max(0, FEED_TOP);
        int bottom = Math.min(frame.getHeight(), FEED_BOTTOM);
        if (bottom - top < MIN_TILE_HEIGHT || frame.getWidth() < BG_X1) {
            return Collections.emptyList();
        }

        double threshold = Math.max(backgroundVariance(frame, top, bottom) * BG_VARIANCE_MULTIPLE,
                MIN_VARIANCE);
        List<int[]> tiles = splitStacked(mergeNearby(runsAbove(frame, top, bottom, threshold)));
        if (tiles.isEmpty()) {
            return Collections.emptyList();
        }

        // A row runs from its own avatar to the next one. Using the tile's own bottom would clip
        // anything taller than the portrait -- a gift card or a sticker overflows it by a wide
        // margin, and the body would be cut off mid-message.
        List<Row> rows = new ArrayList<>(tiles.size());
        for (int i = 0; i < tiles.size(); i++) {
            int[] tile = tiles.get(i);
            int rowBottom = (i + 1 < tiles.size()) ? tiles.get(i + 1)[0] - 1 : bottom - 1;
            int[] name = nameBand(frame, tile[0]);
            rows.add(new Row(tile[0], Math.max(rowBottom, tile[1]), tile[0], tile[1],
                    name[0], name[1], name[2] == 1));
        }
        return rows;
    }

    private static double backgroundVariance(BufferedImage frame, int top, int bottom) {
        double[] samples = new double[bottom - top];
        for (int y = top; y < bottom; y++) {
            samples[y - top] = rowVariance(frame, BG_X0, Math.min(BG_X1, frame.getWidth()), y);
        }
        java.util.Arrays.sort(samples);
        return samples[samples.length / 2];
    }

    private static double rowVariance(BufferedImage frame, int x0, int x1, int y) {
        int n = x1 - x0;
        if (n <= 0) {
            return 0.0;
        }
        double sumR = 0, sumG = 0, sumB = 0;
        for (int x = x0; x < x1; x++) {
            int rgb = frame.getRGB(x, y);
            sumR += (rgb >> 16) & 0xFF;
            sumG += (rgb >> 8) & 0xFF;
            sumB += rgb & 0xFF;
        }
        double meanR = sumR / n, meanG = sumG / n, meanB = sumB / n;
        double acc = 0;
        for (int x = x0; x < x1; x++) {
            int rgb = frame.getRGB(x, y);
            double dr = ((rgb >> 16) & 0xFF) - meanR;
            double dg = ((rgb >> 8) & 0xFF) - meanG;
            double db = (rgb & 0xFF) - meanB;
            acc += dr * dr + dg * dg + db * db;
        }
        return Math.sqrt(acc / n);
    }

    private static List<int[]> runsAbove(BufferedImage frame, int top, int bottom, double threshold) {
        List<int[]> runs = new ArrayList<>();
        int x1 = Math.min(AVATAR_X1, frame.getWidth());
        int start = -1;
        for (int y = top; y < bottom; y++) {
            boolean art = rowVariance(frame, AVATAR_X0, x1, y) > threshold;
            if (art && start < 0) {
                start = y;
            } else if (!art && start >= 0) {
                if (y - start >= MIN_TILE_HEIGHT) {
                    runs.add(new int[] {start, y - 1});
                }
                start = -1;
            }
        }
        if (start >= 0 && bottom - start >= MIN_TILE_HEIGHT) {
            runs.add(new int[] {start, bottom - 1});
        }
        return runs;
    }

    private static List<int[]> mergeNearby(List<int[]> runs) {
        List<int[]> merged = new ArrayList<>();
        for (int[] run : runs) {
            if (!merged.isEmpty() && run[0] - merged.get(merged.size() - 1)[1] <= MERGE_GAP) {
                merged.get(merged.size() - 1)[1] = run[1];
            } else {
                merged.add(new int[] {run[0], run[1]});
            }
        }
        merged.removeIf(r -> r[1] - r[0] < MIN_TILE_HEIGHT);
        return merged;
    }

    private static List<int[]> splitStacked(List<int[]> runs) {
        if (runs.size() < 2) {
            return runs;
        }
        // Take the typical height from the SMALLER half. Merged runs are outliers on the high
        // side, so a plain median drifts upward with them and stops recognising them as merges.
        int[] heights = runs.stream().mapToInt(r -> r[1] - r[0] + 1).sorted().toArray();
        double unit = heights[Math.max(0, heights.length / 4)];
        if (unit <= 0) {
            return runs;
        }

        List<int[]> out = new ArrayList<>();
        for (int[] run : runs) {
            int height = run[1] - run[0] + 1;
            int count = (int) Math.round(height / unit);
            if (count >= 2 && height > unit * SPLIT_RATIO) {
                double step = height / (double) count;
                for (int i = 0; i < count; i++) {
                    out.add(new int[] {(int) (run[0] + i * step), (int) (run[0] + (i + 1) * step) - 1});
                }
            } else {
                out.add(run);
            }
        }
        return out;
    }

    /** Text column used to find the sender line, clear of the avatar and the translate control. */
    private static final int NAME_X0 = 128;
    private static final int NAME_X1 = 620;

    /** The sender line is light text on the dark feed; this is what counts as a lit pixel. */
    private static final int NAME_INK_LUMA = 170;

    /** Lit pixels on a row before it counts as text rather than an anti-aliasing fringe. */
    private static final int NAME_INK_PIXELS = 6;

    /** How far below the avatar's top edge the sender line can be. */
    private static final int NAME_SEARCH_DEPTH = 100;

    /** Shortest run that can be a line of text rather than a border or a decoration. */
    private static final int NAME_MIN_HEIGHT = 10;

    /** Glyph tops fade before they stop, so the measured run starts a little below the real one. */
    private static final int NAME_PAD_TOP = 7;
    private static final int NAME_PAD_BOTTOM = 2;

    /** Used only when no text is found: the old fixed offsets, so a row is never left unreadable. */
    private static final int NAME_FALLBACK_TOP = 18;
    private static final int NAME_FALLBACK_BOTTOM = 56;

    /**
     * Finds the sender line by looking for it rather than assuming where it sits.
     *
     * <p>It used to be a fixed offset from the avatar's top edge, but that edge moves: crowns,
     * rank badges and snowflake decorations sit outside the portrait and drag the detected top
     * around. Nine pixels of drift is enough to clip the tops of the glyphs, and the reader then
     * returns "VIPG [INF jAthenaRyu" where the same band measured properly returns
     * "VIP6 [INF]AthenaRyu" -- which is where names like "ViIPO LINE PACHChanyu" came from.
     *
     * <p>The line is light text on the dark feed, so its own ink locates it: the first run of lit
     * rows below the avatar's top edge is the sender line.
     */
    private static int[] nameBand(BufferedImage frame, int avatarTop) {
        int limit = Math.min(frame.getHeight() - 1, avatarTop + NAME_SEARCH_DEPTH);
        int x1 = Math.min(frame.getWidth(), NAME_X1);
        int runStart = -1;
        for (int y = Math.max(0, avatarTop); y <= limit; y++) {
            int lit = 0;
            for (int x = NAME_X0; x < x1; x++) {
                int rgb = frame.getRGB(x, y);
                int luma = (((rgb >> 16) & 0xFF) * 299 + ((rgb >> 8) & 0xFF) * 587
                        + (rgb & 0xFF) * 114) / 1000;
                if (luma > NAME_INK_LUMA) {
                    lit++;
                }
            }
            if (lit >= NAME_INK_PIXELS) {
                if (runStart < 0) {
                    runStart = y;
                }
            } else if (runStart >= 0) {
                if (y - runStart >= NAME_MIN_HEIGHT) {
                    return new int[] {Math.max(0, runStart - NAME_PAD_TOP), y + NAME_PAD_BOTTOM, 1};
                }
                runStart = -1;
            }
        }
        // No ink at all above the bubble: the game prints the sender once and omits it on the
        // messages that follow from the same person, so this is a continuation rather than an
        // anonymous message. The flag lets the reader tell that apart from a name it simply could
        // not read, which are two different situations and want two different answers.
        return new int[] {avatarTop + NAME_FALLBACK_TOP, avatarTop + NAME_FALLBACK_BOTTOM, 0};
    }
}
