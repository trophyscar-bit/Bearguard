package dev.frostguard.vision.deals;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Finds the whole tabs visible in the tab strip of the gem shop and Deals panels.
 *
 * <p>Found from the page background showing between tabs rather than from their labels: OCR
 * joins neighbouring labels into one line ("Weekly/Monthly Cards Regular Pack Dawn Fund"), so a
 * scan that taps per label skipped two tabs out of three. Tabs are separated by a 5 px column of
 * dark navy at a 199 px pitch, measured on five live 720 x 1280 frames at different strip scroll
 * positions on both panels. The panel edges are dark as well, so every dark run is a boundary,
 * and only spans of a whole tab's width count, which drops tabs clipped by either edge.</p>
 */
public final class TabStripCells {

    public record Cell(int left, int right) {
        public int centre() {
            return (left + right) / 2;
        }

        public int width() {
            return right - left;
        }
    }

    /** Tab bodies below the icons that overhang the top of each tab. */
    private static final int BAND_TOP = 108;
    private static final int BAND_BOTTOM = 182;
    private static final int MAX_BACKGROUND_BRIGHTNESS = 90;
    private static final double MIN_BACKGROUND_SHARE = 0.85;
    private static final int MIN_TAB_WIDTH = 180;
    private static final int MAX_TAB_WIDTH = 215;
    /**
     * The open tab is drawn white; unselected tabs are mid blue. Measured on the 62 selected and 123
     * unselected cells of 2026-09-18's 63 frames: selected 211.7 or more (the icon-only Dawn Market tab,
     * whose chest artwork reaches into this band; labelled tabs read 220+), unselected 156 at most.
     */
    private static final int SELECTED_LABEL_TOP = 168;
    private static final int SELECTED_LABEL_BOTTOM = 180;
    private static final int MIN_SELECTED_BRIGHTNESS = 185;

    private TabStripCells() {
    }

    public static List<Cell> locate(BufferedImage frame) {
        int width = frame.getWidth();
        int bottom = Math.min(BAND_BOTTOM, frame.getHeight());
        boolean[] background = new boolean[width];
        for (int x = 0; x < width; x++) {
            int dark = 0;
            for (int y = BAND_TOP; y < bottom; y++) {
                if (brightness(frame.getRGB(x, y)) < MAX_BACKGROUND_BRIGHTNESS) {
                    dark++;
                }
            }
            background[x] = dark >= MIN_BACKGROUND_SHARE * (bottom - BAND_TOP);
        }

        List<Cell> cells = new ArrayList<>();
        int x = 0;
        while (x < width) {
            if (background[x]) {
                x++;
                continue;
            }
            int left = x;
            while (x < width && !background[x]) {
                x++;
            }
            boolean boundedLeft = left > 0;
            boolean boundedRight = x < width;
            int span = x - left;
            if (boundedLeft && boundedRight && span >= MIN_TAB_WIDTH && span <= MAX_TAB_WIDTH) {
                cells.add(new Cell(left, x));
            }
        }
        return cells;
    }

    public static boolean isSelected(BufferedImage frame, Cell cell) {
        long total = 0;
        int samples = 0;
        for (int y = SELECTED_LABEL_TOP; y < SELECTED_LABEL_BOTTOM; y++) {
            for (int x = cell.left() + 10; x < cell.right() - 10; x += 2) {
                total += brightness(frame.getRGB(x, y));
                samples++;
            }
        }
        return samples > 0 && total / samples >= MIN_SELECTED_BRIGHTNESS;
    }

    private static int brightness(int rgb) {
        return Math.max((rgb >> 16) & 0xFF, Math.max((rgb >> 8) & 0xFF, rgb & 0xFF));
    }
}
