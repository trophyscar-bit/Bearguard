package dev.frostguard.vision.deals;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Finds the orange purchase buttons on shop and deal panels.
 *
 * <p>Detected by colour rather than a template because the button stretches to its layout:
 * measured sizes run from 137 x 53 px on Daily Deals cards to 301 x 75 px on the timed pack
 * pop-up. Fill separates buttons from other orange artwork: the thirteen buttons measured on eight
 * 720 x 1280 frames fill 0.71 or more of their box, every other orange shape of comparable size
 * fills 0.45 or less. The area limits only bound the size; a "Purchase All" button drawn on an
 * orange banner merges with it and is not found.</p>
 */
public final class PriceButtonLocator {

    public record Box(int x, int y, int width, int height) {
        public int right() {
            return x + width;
        }

        public int bottom() {
            return y + height;
        }
    }

    private static final float MIN_HUE = 30f / 360f;
    private static final float MAX_HUE = 64f / 360f;
    private static final float MIN_SATURATION = 150f / 255f;
    private static final float MIN_BRIGHTNESS = 200f / 255f;
    /** Closing radius. Bridges the white price glyphs so the button reads as one component. */
    private static final int CLOSE_RADIUS = 4;
    private static final int MIN_AREA = 6_000;
    private static final int MAX_AREA = 40_000;
    private static final double MIN_ASPECT = 1.8;
    private static final double MAX_ASPECT = 4.5;
    /** Rejects ragged gold artwork whose bounding box is mostly background. */
    private static final double MIN_FILL = 0.6;

    private PriceButtonLocator() {
    }

    public static List<Box> locate(BufferedImage frame) {
        int width = frame.getWidth();
        int height = frame.getHeight();
        boolean[] mask = new boolean[width * height];
        float[] hsb = new float[3];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = frame.getRGB(x, y);
                Color.RGBtoHSB((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, hsb);
                mask[y * width + x] = hsb[0] >= MIN_HUE && hsb[0] <= MAX_HUE
                        && hsb[1] >= MIN_SATURATION && hsb[2] >= MIN_BRIGHTNESS;
            }
        }
        boolean[] closed = invert(dilate(invert(dilate(mask, width, height)), width, height));
        return components(closed, width, height);
    }

    private static List<Box> components(boolean[] mask, int width, int height) {
        List<Box> boxes = new ArrayList<>();
        boolean[] seen = new boolean[mask.length];
        int[] queue = new int[mask.length];
        for (int start = 0; start < mask.length; start++) {
            if (!mask[start] || seen[start]) {
                continue;
            }
            int head = 0;
            int tail = 0;
            queue[tail++] = start;
            seen[start] = true;
            int minX = width, minY = height, maxX = -1, maxY = -1, count = 0;
            while (head < tail) {
                int index = queue[head++];
                int x = index % width;
                int y = index / width;
                count++;
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x);
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
                if (x > 0) tail = visit(mask, seen, queue, tail, index - 1);
                if (x < width - 1) tail = visit(mask, seen, queue, tail, index + 1);
                if (y > 0) tail = visit(mask, seen, queue, tail, index - width);
                if (y < height - 1) tail = visit(mask, seen, queue, tail, index + width);
            }
            int boxWidth = maxX - minX + 1;
            int boxHeight = maxY - minY + 1;
            int area = boxWidth * boxHeight;
            double aspect = (double) boxWidth / boxHeight;
            if (area >= MIN_AREA && area <= MAX_AREA && aspect >= MIN_ASPECT && aspect <= MAX_ASPECT
                    && count >= MIN_FILL * area) {
                boxes.add(new Box(minX, minY, boxWidth, boxHeight));
            }
        }
        boxes.sort(Comparator.comparingInt(Box::y).thenComparingInt(Box::x));
        return boxes;
    }

    private static int visit(boolean[] mask, boolean[] seen, int[] queue, int tail, int index) {
        if (mask[index] && !seen[index]) {
            seen[index] = true;
            queue[tail++] = index;
        }
        return tail;
    }

    private static boolean[] invert(boolean[] mask) {
        boolean[] out = new boolean[mask.length];
        for (int i = 0; i < mask.length; i++) {
            out[i] = !mask[i];
        }
        return out;
    }

    /** Square dilation as two separable passes, each a sliding window count. */
    private static boolean[] dilate(boolean[] mask, int width, int height) {
        boolean[] horizontal = new boolean[mask.length];
        for (int y = 0; y < height; y++) {
            int count = 0;
            for (int x = -CLOSE_RADIUS; x < width; x++) {
                int enter = x + CLOSE_RADIUS;
                int leave = x - CLOSE_RADIUS - 1;
                if (enter < width && mask[y * width + enter]) count++;
                if (leave >= 0 && mask[y * width + leave]) count--;
                if (x >= 0) horizontal[y * width + x] = count > 0;
            }
        }
        boolean[] out = new boolean[mask.length];
        for (int x = 0; x < width; x++) {
            int count = 0;
            for (int y = -CLOSE_RADIUS; y < height; y++) {
                int enter = y + CLOSE_RADIUS;
                int leave = y - CLOSE_RADIUS - 1;
                if (enter < height && horizontal[enter * width + x]) count++;
                if (leave >= 0 && horizontal[leave * width + x]) count--;
                if (y >= 0) out[y * width + x] = count > 0;
            }
        }
        return out;
    }
}
