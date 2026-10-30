package dev.frostguard.vision.deals;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc;

/**
 * Grey price buttons: a pack already bought, or one that opens only after the pack before it. Their white
 * price can be missed by the page-wide text pass (both Leap Pack buttons of 2026-10-23 frame 024 were), so
 * the button is found by its fill and read on its own.
 *
 * <p>Measured on that frame: the two buttons were 175 x 69 px of grey at a fill of 0.75; the only other grey
 * shape of similar width, the page header's art, filled 0.32.</p>
 */
public final class GreyButtonLocator {

    private static final float MAX_SATURATION = 0.15f;
    private static final float MIN_BRIGHTNESS = 0.35f;
    private static final float MAX_BRIGHTNESS = 0.80f;
    private static final int MIN_WIDTH = 140;
    private static final int MAX_WIDTH = 230;
    private static final int MIN_HEIGHT = 50;
    private static final int MAX_HEIGHT = 90;
    private static final double MIN_FILL = 0.6;
    /** On stacked cards price buttons sit in the right half; a pop-up centres its button. */
    private static final int CARD_MIN_LEFT = 380;
    /** A pop-up's button is larger: Hero Rally's Unlock preview, 284 x 84 at a fill of 0.84 (2026-10-25). */
    private static final int POPUP_MAX_WIDTH = 320;
    private static final int POPUP_MAX_HEIGHT = 100;

    private GreyButtonLocator() {
    }

    public static List<PriceButtonLocator.Box> locate(BufferedImage frame) {
        return locate(frame, true);
    }

    /** @param cards the frame shows stacked cards, whose buttons are all in the right half */
    public static List<PriceButtonLocator.Box> locate(BufferedImage frame, boolean cards) {
        int minLeft = cards ? CARD_MIN_LEFT : 0;
        int maxWidth = cards ? MAX_WIDTH : POPUP_MAX_WIDTH;
        int maxHeight = cards ? MAX_HEIGHT : POPUP_MAX_HEIGHT;
        int width = frame.getWidth();
        int height = frame.getHeight();
        byte[] grey = new byte[width * height];
        float[] hsb = new float[3];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = frame.getRGB(x, y);
                Color.RGBtoHSB((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, hsb);
                boolean isGrey = hsb[1] <= MAX_SATURATION && hsb[2] >= MIN_BRIGHTNESS && hsb[2] <= MAX_BRIGHTNESS;
                grey[y * width + x] = (byte) (isGrey ? 255 : 0);
            }
        }
        Mat mask = new Mat(height, width, CvType.CV_8UC1);
        mask.put(0, 0, grey);
        Mat labels = new Mat();
        Mat stats = new Mat();
        Mat centroids = new Mat();
        int count = Imgproc.connectedComponentsWithStats(mask, labels, stats, centroids, 8, CvType.CV_32S);
        List<PriceButtonLocator.Box> buttons = new ArrayList<>();
        for (int i = 1; i < count; i++) {
            int x = (int) stats.get(i, Imgproc.CC_STAT_LEFT)[0];
            int y = (int) stats.get(i, Imgproc.CC_STAT_TOP)[0];
            int w = (int) stats.get(i, Imgproc.CC_STAT_WIDTH)[0];
            int h = (int) stats.get(i, Imgproc.CC_STAT_HEIGHT)[0];
            double fill = stats.get(i, Imgproc.CC_STAT_AREA)[0] / ((double) w * h);
            if (x >= minLeft && w >= MIN_WIDTH && w <= maxWidth && h >= MIN_HEIGHT && h <= maxHeight
                    && fill >= MIN_FILL) {
                buttons.add(new PriceButtonLocator.Box(x, y, w, h));
            }
        }
        mask.release();
        labels.release();
        stats.release();
        centroids.release();
        return buttons;
    }
}
