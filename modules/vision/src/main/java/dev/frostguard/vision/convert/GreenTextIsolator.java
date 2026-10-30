package dev.frostguard.vision.convert;

import java.awt.Color;
import java.awt.image.BufferedImage;

import dev.frostguard.api.domain.RawImageData;

/**
 * Turns the game's bright green progress numerals -- the done count in an Intel Monthly Card mission's
 * "(570/5)" -- into black text on a white page, leaving the white target and brackets out.
 *
 * <p>Measured on the 2026-10-23 card frames: the green glyphs sit at hue 70-170 degrees with saturation
 * of at least 110/255 and brightness of at least 140/255; the white "/5)" beside them and the blue row
 * behind fall outside, so the mask holds the done count alone.</p>
 */
public final class GreenTextIsolator {

    private static final float MIN_HUE = 70f / 360f;
    private static final float MAX_HUE = 170f / 360f;
    private static final float MIN_SATURATION = 110f / 255f;
    private static final float MIN_BRIGHTNESS = 140f / 255f;
    private static final int INK = 0xFF000000;
    private static final int PAPER = 0xFFFFFFFF;

    private GreenTextIsolator() {
    }

    /** Masks a region of the frame onto a white page with {@code padding} pixels of margin. */
    public static RawImageData isolate(BufferedImage frame, int x, int y, int width, int height, int padding) {
        int left = Math.max(0, x);
        int top = Math.max(0, y);
        int right = Math.min(frame.getWidth(), x + width);
        int bottom = Math.min(frame.getHeight(), y + height);
        int outWidth = Math.max(1, right - left) + 2 * padding;
        int outHeight = Math.max(1, bottom - top) + 2 * padding;
        byte[] rgba = new byte[outWidth * outHeight * 4];
        java.util.Arrays.fill(rgba, (byte) 0xFF);
        float[] hsb = new float[3];
        for (int sy = top; sy < bottom; sy++) {
            for (int sx = left; sx < right; sx++) {
                int rgb = frame.getRGB(sx, sy);
                Color.RGBtoHSB((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, hsb);
                boolean green = hsb[0] >= MIN_HUE && hsb[0] <= MAX_HUE && hsb[1] >= MIN_SATURATION
                        && hsb[2] >= MIN_BRIGHTNESS;
                int value = green ? INK : PAPER;
                int offset = ((sy - top + padding) * outWidth + (sx - left + padding)) * 4;
                rgba[offset] = (byte) ((value >> 16) & 0xFF);
                rgba[offset + 1] = (byte) ((value >> 8) & 0xFF);
                rgba[offset + 2] = (byte) (value & 0xFF);
            }
        }
        return RawImageData.capture(rgba, outWidth, outHeight, 32);
    }
}
