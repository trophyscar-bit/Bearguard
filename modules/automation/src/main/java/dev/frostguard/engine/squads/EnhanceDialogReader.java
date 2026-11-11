package dev.frostguard.engine.squads;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.frostguard.api.domain.OcrSettingsData;
import dev.frostguard.api.domain.OcrSettingsData.TextLayout;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.vision.convert.ImageConverter;

/**
 * Reads the Gear Enhancement dialog and its fodder list on 720x1280 frames. Every reader is a pure
 * function of one frame, so {@link GearEnhancePass} owns the taps. Anything that can't be read comes
 * back null or {@link TileKind#UNKNOWN}: a spend must never go ahead on a partial read.
 */
public final class EnhanceDialogReader {

    public static final String ENHANCE_LABEL = "Enhance";
    public static final String ASCEND_LABEL = "Ascend";
    public static final String QUICK_SELECT_LABEL = "Quick Select";
    public static final String SELECT_MORE_LABEL = "Select more";
    public static final PointData DETAILS_ENHANCE = new PointData(511, 815);
    public static final PointData QUICK_SELECT = new PointData(208, 1210);
    public static final PointData ENHANCE = new PointData(513, 1210);
    // Dragged down the gap between the first two tile columns, so a drag the game reads as a tap selects nothing.
    public static final PointData SCROLL_FROM = new PointData(154, 1100);
    public static final PointData SCROLL_TO = new PointData(154, 980);
    // A badged tile needs 85px in view, and the list shows 295, so a smaller step shows every one whole.
    public static final int MAX_SCROLL_STEP = 170;

    private static final int[] COLUMN_LEFT = { 33, 170, 307, 444, 581 };
    private static final int BADGE_CENTRE_FROM_LEFT = 99;
    private static final int BADGE_WINDOW = 24;
    private static final int LIST_TOP = 843;
    private static final int LIST_BOTTOM = 1137;
    private static final int TILE_TOP_FROM_BADGE_BOTTOM = 20;
    private static final int TILE_VISIBLE_NEEDED = 75;
    private static final int PROBE_X = 85;
    private static final int MAX_OFFSET = 240;
    private static final double SAME_LIST_ERROR = 6.0;

    private static final Pattern BAR = Pattern.compile("^(\\d+)/(\\d+)$");
    private static final OcrSettingsData WHITE_TITLE = OcrSettingsData.builder()
            .textLayout(TextLayout.SINGLE_LINE).isolateForeground(true).targetColor(Color.WHITE).build();
    private static final OcrSettingsData WHITE_BAR = OcrSettingsData.builder()
            .textLayout(TextLayout.SINGLE_LINE).allowedGlyphs("0123456789/")
            .isolateForeground(true).targetColor(Color.WHITE).build();

    /** {@code preview} is the level the selection would reach, null with nothing selected. */
    public record Dialog(int level, Integer preview, int xp, int xpNeeded, String leftButton, boolean enhanceLit) {
    }

    public enum TileKind {
        XP, GREY, GREEN, BLUE, PURPLE, UNKNOWN;

        /** Only XP items and grey or green gear may be fed to a piece. */
        public boolean safeFodder() {
            return this == XP || this == GREY || this == GREEN;
        }
    }

    public record Badge(int column, int tileTop, TileKind kind) {
    }

    private EnhanceDialogReader() {
    }

    /** The text on the Gear Details button that is Enhance below +100 and Ascend at it. */
    public static String detailsButtonLabel(RawImageData frame) {
        return SquadOcr.text(frame, 400, 790, 625, 840, SquadOcr.LINE);
    }

    /** Null unless the dialog is open and every field read cleanly. */
    public static Dialog read(RawImageData frame) {
        if (!SquadOcr.text(frame, 150, 125, 570, 170, WHITE_TITLE).contains("Gear Enhancement")) return null;
        if (!SquadOcr.text(frame, 85, 408, 320, 444, SquadOcr.LINE).contains("Enhancement Level")) return null;
        Integer left = HeroScreenReader.mastery(SquadOcr.text(frame, 410, 408, 500, 444, SquadOcr.LEVEL));
        Integer right = HeroScreenReader.mastery(SquadOcr.text(frame, 525, 408, 632, 444, SquadOcr.LEVEL));
        if (right == null) return null;
        int level = left != null ? left : right;
        Integer preview = left != null ? right : null;
        Integer barLevel = HeroScreenReader.mastery(SquadOcr.text(frame, 70, 758, 145, 792, SquadOcr.LEVEL));
        if (barLevel == null || barLevel != (preview != null ? preview : level)) return null;
        Matcher bar = BAR.matcher(SquadOcr.text(frame, 335, 762, 470, 790, WHITE_BAR));
        if (!bar.matches()) return null;
        int xp = Integer.parseInt(bar.group(1));
        int needed = Integer.parseInt(bar.group(2));
        if (xp >= needed) return null;
        String leftButton = SquadOcr.text(frame, 90, 1190, 330, 1232, SquadOcr.LINE);
        String rightButton = SquadOcr.text(frame, 395, 1190, 635, 1232, SquadOcr.LINE);
        if (!ENHANCE_LABEL.equals(rightButton)) return null;
        if (!QUICK_SELECT_LABEL.equals(leftButton) && !SELECT_MORE_LABEL.equals(leftButton)) return null;
        boolean lit = enhanceLit(frame);
        // With a selection showing, a lost left number would pass the preview off as the current level.
        if (left == null && (!QUICK_SELECT_LABEL.equals(leftButton) || lit)) return null;
        return new Dialog(level, preview, xp, needed, leftButton, lit);
    }

    private static boolean enhanceLit(RawImageData frame) {
        BufferedImage image = ImageConverter.toBufferedImage(frame);
        int lit = 0;
        int total = 0;
        for (int x = 385; x < 440; x++) {
            for (int y = 1185; y < 1235; y += 2) {
                int rgb = image.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF, b = rgb & 0xFF;
                total++;
                if (b > r + 60) lit++;
            }
        }
        return lit * 10 >= total * 7;
    }

    /** Every minus badge whose tile is in view; a badge cut off by the list's edge is left for another frame. */
    public static List<Badge> badges(RawImageData frame) {
        BufferedImage image = ImageConverter.toBufferedImage(frame);
        List<Badge> badges = new ArrayList<>();
        for (int column = 0; column < COLUMN_LEFT.length; column++) {
            int left = COLUMN_LEFT[column];
            int centre = left + BADGE_CENTRE_FROM_LEFT;
            for (int[] box : redComponents(image, centre - BADGE_WINDOW, Math.min(719, centre + BADGE_WINDOW))) {
                Badge badge = badge(image, column, left, box);
                if (badge != null) badges.add(badge);
            }
        }
        return badges;
    }

    private static Badge badge(BufferedImage image, int column, int left, int[] box) {
        int minX = box[0], maxX = box[1], minY = box[2], maxY = box[3], count = box[4];
        int width = maxX - minX + 1, height = maxY - minY + 1;
        if (count < 80 || width < 20 || height < 20) return null;
        if (minY <= LIST_TOP || maxY >= LIST_BOTTOM) return null;
        int top = maxY - TILE_TOP_FROM_BADGE_BOTTOM;
        if (top + TILE_VISIBLE_NEEDED > LIST_BOTTOM) return null;
        boolean large = width > 38 || height > 36;
        if (large || !hasMinusBar(image, minX, maxX, (minY + maxY) / 2)) return new Badge(column, top, TileKind.UNKNOWN);
        return new Badge(column, top, kind(image, left, top));
    }

    private static boolean hasMinusBar(BufferedImage image, int minX, int maxX, int centreY) {
        int white = 0;
        for (int y = centreY - 4; y <= centreY + 4; y++) {
            for (int x = minX; x <= maxX; x++) {
                int rgb = image.getRGB(x, y);
                if (((rgb >> 16) & 0xFF) > 220 && ((rgb >> 8) & 0xFF) > 220 && (rgb & 0xFF) > 220) white++;
            }
        }
        return white >= 8;
    }

    // {minX, maxX, minY, maxY, count} of each connected run of badge red in one column's window.
    private static List<int[]> redComponents(BufferedImage image, int fromX, int toX) {
        int width = toX - fromX + 1;
        int height = LIST_BOTTOM - LIST_TOP + 1;
        boolean[] seen = new boolean[width * height];
        List<int[]> components = new ArrayList<>();
        for (int y = LIST_TOP; y <= LIST_BOTTOM; y++) {
            for (int x = fromX; x <= toX; x++) {
                int index = (y - LIST_TOP) * width + x - fromX;
                if (seen[index] || !isBadgeRed(image.getRGB(x, y))) continue;
                int[] box = { x, x, y, y, 0 };
                Deque<int[]> stack = new ArrayDeque<>();
                stack.push(new int[] { x, y });
                seen[index] = true;
                while (!stack.isEmpty()) {
                    int[] p = stack.pop();
                    box[0] = Math.min(box[0], p[0]);
                    box[1] = Math.max(box[1], p[0]);
                    box[2] = Math.min(box[2], p[1]);
                    box[3] = Math.max(box[3], p[1]);
                    box[4]++;
                    for (int[] d : new int[][] { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } }) {
                        int nx = p[0] + d[0], ny = p[1] + d[1];
                        if (nx < fromX || nx > toX || ny < LIST_TOP || ny > LIST_BOTTOM) continue;
                        int ni = (ny - LIST_TOP) * width + nx - fromX;
                        if (!seen[ni] && isBadgeRed(image.getRGB(nx, ny))) {
                            seen[ni] = true;
                            stack.push(new int[] { nx, ny });
                        }
                    }
                }
                components.add(box);
            }
        }
        return components;
    }

    private static boolean isBadgeRed(int rgb) {
        return ((rgb >> 16) & 0xFF) > 190 && ((rgb >> 8) & 0xFF) < 80 && (rgb & 0xFF) < 80;
    }

    /** What the tile at this column and top is; XP items have no class shield and a steel-blue nut. */
    static TileKind kind(BufferedImage image, int left, int top) {
        TileKind colour = edgeColour(image, left, top);
        if (colour == TileKind.UNKNOWN) return TileKind.UNKNOWN;
        return !hasShield(image, left, top, colour) && hasXpIcon(image, left, top) ? TileKind.XP : colour;
    }

    // The tile's side edges, below the shield and the badge, are plain background.
    private static TileKind edgeColour(BufferedImage image, int left, int top) {
        int[] counts = new int[TileKind.values().length];
        int total = 0;
        for (int x : new int[] { left + 3, left + 102 }) {
            for (int y = top + 35; y <= top + 60; y++) {
                counts[colour(image.getRGB(x, y)).ordinal()]++;
                total++;
            }
        }
        for (TileKind kind : List.of(TileKind.GREY, TileKind.GREEN, TileKind.BLUE, TileKind.PURPLE)) {
            if (counts[kind.ordinal()] * 10 >= total * 7) return kind;
        }
        return TileKind.UNKNOWN;
    }

    private static TileKind colour(int rgb) {
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        if (Math.abs(r - g) <= 16 && Math.abs(g - b) <= 16 && (r + g + b) / 3 >= 100 && (r + g + b) / 3 <= 215) return TileKind.GREY;
        if (g > r + 50 && g > b + 50) return TileKind.GREEN;
        if (b > r + 50 && g > r + 20 && b > g + 25) return TileKind.BLUE;
        if (r > g + 30 && b > r + 30) return TileKind.PURPLE;
        return TileKind.UNKNOWN;
    }

    private static boolean hasShield(BufferedImage image, int left, int top, TileKind colour) {
        int same = 0;
        int total = 0;
        for (int x = left + 8; x <= left + 24; x++) {
            for (int y = top + 8; y <= top + 24; y++) {
                total++;
                if (colour(image.getRGB(x, y)) == colour) same++;
            }
        }
        return same * 10 < total * 9;
    }

    private static boolean hasXpIcon(BufferedImage image, int left, int top) {
        int steel = 0;
        for (int x = left + 30; x <= left + 75; x++) {
            for (int y = top + 30; y <= top + 70; y++) {
                int rgb = image.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
                if (b > r + 25 && b > g + 10 && g > r) steel++;
            }
        }
        return steel >= 300;
    }

    /** True when the list has not been scrolled: the first row sits at its opening position. */
    public static boolean isListTop(RawImageData frame) {
        BufferedImage image = ImageConverter.toBufferedImage(frame);
        return isDark(image.getRGB(PROBE_X, 850)) && isDark(image.getRGB(PROBE_X, 858))
                && !isDark(image.getRGB(PROBE_X, 862)) && isDark(image.getRGB(PROBE_X, 980));
    }

    /** True when no tile is cut off by the bottom of the list. */
    public static boolean isListBottom(RawImageData frame) {
        BufferedImage image = ImageConverter.toBufferedImage(frame);
        return isDark(image.getRGB(PROBE_X, 1110)) && isDark(image.getRGB(PROBE_X, 1137));
    }

    private static boolean isDark(int rgb) {
        return ((rgb >> 16) & 0xFF) < 45 && ((rgb >> 8) & 0xFF) < 50 && (rgb & 0xFF) < 60;
    }

    /** How far the list moved up between two frames, or null when they don't overlap. */
    public static Integer scrollOffset(RawImageData before, RawImageData after) {
        int[][] a = strip(ImageConverter.toBufferedImage(before));
        int[][] b = strip(ImageConverter.toBufferedImage(after));
        double bestError = Double.MAX_VALUE;
        int bestOffset = -1;
        for (int offset = 0; offset <= MAX_OFFSET && a.length - offset >= 40; offset++) {
            double error = error(a, b, offset);
            if (error < bestError) {
                bestError = error;
                bestOffset = offset;
            }
        }
        return bestError <= SAME_LIST_ERROR ? bestOffset : null;
    }

    private static double error(int[][] a, int[][] b, int offset) {
        long sum = 0;
        int samples = 0;
        for (int y = 0; y < a.length - offset; y += 3) {
            for (int x = 0; x < a[y].length; x++) {
                sum += Math.abs(a[y + offset][x] - b[y][x]);
                samples++;
            }
        }
        return sum / (double) samples;
    }

    private static int[][] strip(BufferedImage image) {
        int[][] rows = new int[290][110];
        for (int y = 0; y < rows.length; y++) {
            for (int x = 0; x < rows[y].length; x++) {
                int rgb = image.getRGB(30 + x * 6, 845 + y);
                rows[y][x] = (((rgb >> 16) & 0xFF) + ((rgb >> 8) & 0xFF) + (rgb & 0xFF)) / 3;
            }
        }
        return rows;
    }
}
