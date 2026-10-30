package dev.frostguard.vision.deals;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Finds the segmented control ("Epic | Mythic") some deal pages draw directly under the tab strip.
 *
 * <p>The open option is a saturated blue pill and every other option a pale grey-blue pill of the
 * same height, side by side on the white panel. Measured on 720 x 1280 frames: 12 that show the
 * control (Weekly Benefits Card and Intel Monthly Card, 11 with Mythic open and 1 with Epic open) and
 * 117 that do not, from the 2026-10-22 deals scan and dev captures. The open pill's fill has median
 * saturation 0.90 (hue 190-212), the closed pill's 0.26 (hue 211-213, brightness 0.91, saturation at
 * most 0.27), so the two colour classes are split across the unclaimed gap from 0.40 to 0.50. Pills
 * are 327-330 x 56-58 px with tops at y 195, 16 px apart, and fill 0.88-0.92 of their box.</p>
 *
 * <p>The positive anchor is the row: at least two adjacent pills of matching top and height, exactly
 * one of them open. In the band no negative frame produced a pill-sized closed-colour component, and
 * the fullest pill-sized open-colour component filled 0.74 of its box (blue card art on a scrolled
 * Weekly Cards list) against 0.88 for the weakest real pill. Anything short of a whole row is reported
 * as not found rather than guessed.</p>
 */
public final class SegmentedControl {

    public record Option(int x, int y, int width, int height) {
        public int right() {
            return x + width;
        }

        public int bottom() {
            return y + height;
        }

        public int centreX() {
            return x + width / 2;
        }

        public int centreY() {
            return y + height / 2;
        }
    }

    /** The options left to right, and the index of the open one. */
    public record Control(List<Option> options, int selectedIndex) {
        public Control {
            options = List.copyOf(options);
        }

        public Option selected() {
            return options.get(selectedIndex);
        }
    }

    /** Below the tab strip, which ends near y 182, and above the page body. */
    private static final int BAND_TOP = 186;
    private static final int BAND_BOTTOM = 300;

    private static final float SELECTED_MIN_HUE = 185f / 360f;
    private static final float SELECTED_MAX_HUE = 220f / 360f;
    private static final float SELECTED_MIN_SATURATION = 0.50f;
    /** Admits the pill's darker lower rim (0x095490, brightness 0.57) so the box covers the whole pill. */
    private static final float SELECTED_MIN_BRIGHTNESS = 0.50f;

    private static final float UNSELECTED_MIN_HUE = 200f / 360f;
    private static final float UNSELECTED_MAX_HUE = 225f / 360f;
    private static final float UNSELECTED_MIN_SATURATION = 0.15f;
    private static final float UNSELECTED_MAX_SATURATION = 0.40f;
    private static final float UNSELECTED_MIN_BRIGHTNESS = 0.80f;
    /** Excludes the white panel and the white option labels. */
    private static final float UNSELECTED_MAX_BRIGHTNESS = 0.97f;

    private static final int MIN_PILL_HEIGHT = 40;
    private static final int MAX_PILL_HEIGHT = 70;
    private static final int MIN_PILL_WIDTH = 150;
    private static final int MIN_PILL_ASPECT = 3;
    private static final double MIN_PILL_FILL = 0.8;

    private static final int MAX_TOP_DIFFERENCE = 4;
    private static final int MAX_HEIGHT_DIFFERENCE = 6;
    private static final int MAX_GAP = 30;

    private static final byte OTHER = 0;
    private static final byte SELECTED = 1;
    private static final byte UNSELECTED = 2;

    private record Pill(Option box, boolean selected) {
    }

    private SegmentedControl() {
    }

    public static Optional<Control> locate(BufferedImage frame) {
        int width = frame.getWidth();
        int top = Math.min(BAND_TOP, frame.getHeight());
        int bottom = Math.min(BAND_BOTTOM, frame.getHeight());
        int height = bottom - top;
        if (height <= 0) {
            return Optional.empty();
        }
        byte[] classes = classify(frame, top, width, height);
        List<Pill> pills = pills(classes, width, height, top);
        pills.sort(Comparator.comparingInt(pill -> pill.box().x()));

        List<Control> controls = new ArrayList<>();
        int start = 0;
        while (start < pills.size()) {
            int end = start + 1;
            while (end < pills.size() && adjacent(pills.get(end - 1).box(), pills.get(end).box())) {
                end++;
            }
            rowControl(pills.subList(start, end)).ifPresent(controls::add);
            start = end;
        }
        return controls.size() == 1 ? Optional.of(controls.get(0)) : Optional.empty();
    }

    private static byte[] classify(BufferedImage frame, int top, int width, int height) {
        byte[] classes = new byte[width * height];
        float[] hsb = new float[3];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = frame.getRGB(x, top + y);
                Color.RGBtoHSB((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, hsb);
                classes[y * width + x] = classOf(hsb);
            }
        }
        return classes;
    }

    private static byte classOf(float[] hsb) {
        float hue = hsb[0];
        float saturation = hsb[1];
        float brightness = hsb[2];
        if (hue >= SELECTED_MIN_HUE && hue <= SELECTED_MAX_HUE && saturation >= SELECTED_MIN_SATURATION
                && brightness >= SELECTED_MIN_BRIGHTNESS) {
            return SELECTED;
        }
        if (hue >= UNSELECTED_MIN_HUE && hue <= UNSELECTED_MAX_HUE
                && saturation >= UNSELECTED_MIN_SATURATION && saturation <= UNSELECTED_MAX_SATURATION
                && brightness >= UNSELECTED_MIN_BRIGHTNESS && brightness <= UNSELECTED_MAX_BRIGHTNESS) {
            return UNSELECTED;
        }
        return OTHER;
    }

    /** Pill-shaped components of a single colour class that do not run past the band's bottom. */
    private static List<Pill> pills(byte[] classes, int width, int height, int top) {
        List<Pill> pills = new ArrayList<>();
        boolean[] seen = new boolean[classes.length];
        int[] queue = new int[classes.length];
        for (int start = 0; start < classes.length; start++) {
            byte kind = classes[start];
            if (kind == OTHER || seen[start]) {
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
                if (x > 0) tail = visit(classes, kind, seen, queue, tail, index - 1);
                if (x < width - 1) tail = visit(classes, kind, seen, queue, tail, index + 1);
                if (y > 0) tail = visit(classes, kind, seen, queue, tail, index - width);
                if (y < height - 1) tail = visit(classes, kind, seen, queue, tail, index + width);
            }
            int boxWidth = maxX - minX + 1;
            int boxHeight = maxY - minY + 1;
            boolean pillShaped = boxHeight >= MIN_PILL_HEIGHT && boxHeight <= MAX_PILL_HEIGHT
                    && boxWidth >= MIN_PILL_WIDTH && boxWidth >= MIN_PILL_ASPECT * boxHeight
                    && count >= MIN_PILL_FILL * boxWidth * boxHeight
                    && maxY < height - 1;
            if (pillShaped) {
                pills.add(new Pill(new Option(minX, top + minY, boxWidth, boxHeight), kind == SELECTED));
            }
        }
        return pills;
    }

    private static int visit(byte[] classes, byte kind, boolean[] seen, int[] queue, int tail, int index) {
        if (classes[index] == kind && !seen[index]) {
            seen[index] = true;
            queue[tail++] = index;
        }
        return tail;
    }

    private static boolean adjacent(Option left, Option right) {
        int gap = right.x() - left.right();
        return gap >= 0 && gap <= MAX_GAP
                && Math.abs(right.y() - left.y()) <= MAX_TOP_DIFFERENCE
                && Math.abs(right.height() - left.height()) <= MAX_HEIGHT_DIFFERENCE;
    }

    private static Optional<Control> rowControl(List<Pill> row) {
        if (row.size() < 2) {
            return Optional.empty();
        }
        int selectedIndex = -1;
        for (int i = 0; i < row.size(); i++) {
            if (row.get(i).selected()) {
                if (selectedIndex >= 0) {
                    return Optional.empty();
                }
                selectedIndex = i;
            }
        }
        if (selectedIndex < 0) {
            return Optional.empty();
        }
        return Optional.of(new Control(row.stream().map(Pill::box).toList(), selectedIndex));
    }
}
