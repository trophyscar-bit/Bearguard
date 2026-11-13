package dev.frostguard.tasks.events;

import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import dev.frostguard.api.domain.AreaData;

/**
 * Pixel reading for the Red-dot Sweep: the solid red notification dots and the green claim buttons.
 * Nothing here taps or reads text; it only says where candidates are, and the routine decides what a
 * candidate is allowed to be.
 *
 * <p>Measured on live 720x1280 frames (2026-08 to 2026-11): a notification dot is a 13 px disc of
 * (255,30,31) holding 140-150 pixels, so it is told apart from red text, the 33 px "!" alert badges at the
 * ends of a tab strip and the red bar of a gauge by size alone. A claimable green button is a 147x51 pill
 * of (37,183,86) with 5,700 pixels; the green item frames in a reward row are about 87 px squares and the
 * disabled Claim is grey.
 */
final class RedDotVision {

    /** A connected run of matching pixels, with its inclusive bounding box. */
    record Blob(int left, int top, int right, int bottom, int pixels) {
        int width() {
            return right - left + 1;
        }

        int height() {
            return bottom - top + 1;
        }

        int centerX() {
            return (left + right) / 2;
        }

        int centerY() {
            return (top + bottom) / 2;
        }

        boolean overlaps(AreaData area) {
            return left <= area.bottomRight().getX() && right >= area.topLeft().getX()
                    && top <= area.bottomRight().getY() && bottom >= area.topLeft().getY();
        }

        /** True when the blob's box intersects {@code other} grown by {@code margin} on every side. */
        boolean near(Blob other, int margin) {
            return left <= other.right + margin && right >= other.left - margin
                    && top <= other.bottom + margin && bottom >= other.top - margin;
        }
    }

    static final int DOT_MIN_SIDE = 9;
    static final int DOT_MAX_SIDE = 18;
    static final int DOT_MIN_PIXELS = 90;
    static final int DOT_MAX_PIXELS = 230;
    static final double DOT_MIN_FILL = 0.55;

    static final int BUTTON_MIN_WIDTH = 90;
    static final int BUTTON_MAX_WIDTH = 320;
    static final int BUTTON_MIN_HEIGHT = 36;
    static final int BUTTON_MAX_HEIGHT = 90;
    static final int BUTTON_MIN_PIXELS = 2500;
    static final double BUTTON_MIN_FILL = 0.6;

    /** A tab is white when selected and dark blue otherwise; the dot sits on its top-right corner. */
    static final int SELECTED_TAB_PROBE_DX = -22;
    static final int SELECTED_TAB_PROBE_DY = 8;
    private static final int SELECTED_TAB_MIN_CHANNEL = 235;

    private static final int SIGNATURE_COLUMNS = 8;
    private static final int SIGNATURE_ROWS = 5;
    static final int TAB_ICON_WIDTH = 150;
    static final int TAB_ICON_TOP = 118;
    static final int TAB_ICON_BOTTOM = 180;
    static final double SAME_TAB_MAX_DIFF = 14.0;

    private RedDotVision() {
    }

    static boolean isRed(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        return r >= 220 && g <= 70 && b <= 70;
    }

    static boolean isButtonGreen(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        return g >= 150 && r <= 90 && b <= 120 && g - r >= 80;
    }

    /** Notification dots inside {@code area}, left to right then top to bottom. */
    static List<Blob> redDots(BufferedImage img, AreaData area) {
        List<Blob> dots = new ArrayList<>();
        for (Blob blob : components(img, area, RedDotVision::isRed)) {
            if (blob.width() >= DOT_MIN_SIDE && blob.width() <= DOT_MAX_SIDE
                    && blob.height() >= DOT_MIN_SIDE && blob.height() <= DOT_MAX_SIDE
                    && Math.abs(blob.width() - blob.height()) <= 3
                    && blob.pixels() >= DOT_MIN_PIXELS && blob.pixels() <= DOT_MAX_PIXELS
                    && fill(blob) >= DOT_MIN_FILL) {
                dots.add(blob);
            }
        }
        return dots;
    }

    /** Green pill-shaped buttons inside {@code area}, in reading order. */
    static List<Blob> greenButtons(BufferedImage img, AreaData area) {
        List<Blob> buttons = new ArrayList<>();
        for (Blob blob : components(img, area, RedDotVision::isButtonGreen)) {
            if (blob.width() >= BUTTON_MIN_WIDTH && blob.width() <= BUTTON_MAX_WIDTH
                    && blob.height() >= BUTTON_MIN_HEIGHT && blob.height() <= BUTTON_MAX_HEIGHT
                    && blob.pixels() >= BUTTON_MIN_PIXELS && fill(blob) >= BUTTON_MIN_FILL) {
                buttons.add(blob);
            }
        }
        return buttons;
    }

    /** True when the tab whose dot is {@code dot} is the selected (white) one. */
    static boolean isSelectedTab(BufferedImage img, Blob dot) {
        int x = clamp(dot.left() + SELECTED_TAB_PROBE_DX, 0, img.getWidth() - 1);
        int y = clamp(dot.top() + SELECTED_TAB_PROBE_DY, 0, img.getHeight() - 1);
        int rgb = img.getRGB(x, y);
        return ((rgb >> 16) & 0xFF) >= SELECTED_TAB_MIN_CHANNEL
                && ((rgb >> 8) & 0xFF) >= SELECTED_TAB_MIN_CHANNEL
                && (rgb & 0xFF) >= SELECTED_TAB_MIN_CHANNEL;
    }

    /** Where to tap a tab given its dot: the middle of the tab body, left of the dot. */
    static int tabTapX(Blob dot) {
        return Math.max(20, dot.left() - 90);
    }

    /**
     * A coarse grey grid of the tab's icon area, so the same tab is recognised after the strip has moved.
     * The dot is deliberately outside it.
     */
    static int[] tabSignature(BufferedImage img, Blob dot) {
        int right = dot.left() - 8;
        int left = Math.max(0, right - TAB_ICON_WIDTH);
        int[] grid = new int[SIGNATURE_COLUMNS * SIGNATURE_ROWS];
        int cellW = Math.max(1, (right - left) / SIGNATURE_COLUMNS);
        int cellH = Math.max(1, (TAB_ICON_BOTTOM - TAB_ICON_TOP) / SIGNATURE_ROWS);
        for (int row = 0; row < SIGNATURE_ROWS; row++) {
            for (int col = 0; col < SIGNATURE_COLUMNS; col++) {
                long sum = 0;
                int n = 0;
                for (int y = TAB_ICON_TOP + row * cellH; y < TAB_ICON_TOP + (row + 1) * cellH; y += 2) {
                    for (int x = left + col * cellW; x < left + (col + 1) * cellW; x += 2) {
                        if (x < img.getWidth() && y < img.getHeight()) {
                            int rgb = img.getRGB(x, y);
                            sum += (((rgb >> 16) & 0xFF) + ((rgb >> 8) & 0xFF) + (rgb & 0xFF)) / 3;
                            n++;
                        }
                    }
                }
                grid[row * SIGNATURE_COLUMNS + col] = n == 0 ? 0 : (int) (sum / n);
            }
        }
        return grid;
    }

    static double signatureDistance(int[] a, int[] b) {
        long total = 0;
        for (int i = 0; i < a.length; i++) {
            total += Math.abs(a[i] - b[i]);
        }
        return (double) total / a.length;
    }

    static boolean sameTab(int[] a, int[] b) {
        return signatureDistance(a, b) <= SAME_TAB_MAX_DIFF;
    }

    /** Mean per-channel difference between two frames over {@code area}, sampled every other pixel. */
    static double meanDifference(BufferedImage before, BufferedImage after, AreaData area) {
        long total = 0;
        int samples = 0;
        for (int y = area.topLeft().getY(); y <= area.bottomRight().getY(); y += 2) {
            for (int x = area.topLeft().getX(); x <= area.bottomRight().getX(); x += 2) {
                int a = before.getRGB(x, y);
                int b = after.getRGB(x, y);
                total += Math.abs(((a >> 16) & 0xFF) - ((b >> 16) & 0xFF))
                        + Math.abs(((a >> 8) & 0xFF) - ((b >> 8) & 0xFF))
                        + Math.abs((a & 0xFF) - (b & 0xFF));
                samples += 3;
            }
        }
        return samples == 0 ? 0 : (double) total / samples;
    }

    private static double fill(Blob blob) {
        return (double) blob.pixels() / (blob.width() * blob.height());
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private interface PixelTest {
        boolean matches(int rgb);
    }

    private static List<Blob> components(BufferedImage img, AreaData area, PixelTest test) {
        int x0 = Math.max(0, area.topLeft().getX());
        int y0 = Math.max(0, area.topLeft().getY());
        int x1 = Math.min(img.getWidth() - 1, area.bottomRight().getX());
        int y1 = Math.min(img.getHeight() - 1, area.bottomRight().getY());
        int w = x1 - x0 + 1;
        int h = y1 - y0 + 1;
        List<Blob> blobs = new ArrayList<>();
        if (w <= 0 || h <= 0) {
            return blobs;
        }
        boolean[] seen = new boolean[w * h];
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                int idx = (y - y0) * w + (x - x0);
                if (seen[idx] || !test.matches(img.getRGB(x, y))) {
                    continue;
                }
                seen[idx] = true;
                queue.add(new int[] {x, y});
                int minX = x;
                int maxX = x;
                int minY = y;
                int maxY = y;
                int count = 0;
                while (!queue.isEmpty()) {
                    int[] p = queue.poll();
                    count++;
                    minX = Math.min(minX, p[0]);
                    maxX = Math.max(maxX, p[0]);
                    minY = Math.min(minY, p[1]);
                    maxY = Math.max(maxY, p[1]);
                    for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                        int nx = p[0] + d[0];
                        int ny = p[1] + d[1];
                        if (nx < x0 || nx > x1 || ny < y0 || ny > y1) {
                            continue;
                        }
                        int nIdx = (ny - y0) * w + (nx - x0);
                        if (!seen[nIdx] && test.matches(img.getRGB(nx, ny))) {
                            seen[nIdx] = true;
                            queue.add(new int[] {nx, ny});
                        }
                    }
                }
                blobs.add(new Blob(minX, minY, maxX, maxY, count));
            }
        }
        return blobs;
    }
}
