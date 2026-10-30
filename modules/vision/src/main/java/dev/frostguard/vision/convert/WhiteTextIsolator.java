package dev.frostguard.vision.convert;

import java.awt.Color;
import java.awt.image.BufferedImage;

import dev.frostguard.api.domain.RawImageData;

/**
 * Turns bright, unsaturated pixels -- the white glyphs the game draws over coloured buttons,
 * banners and item tiles -- into black text on a white page.
 *
 * <p>A hard mask rather than {@link ImagePreprocessor}'s soft distance-to-colour isolation: on
 * outlined white numerals the soft version, magnified bilinearly, dropped thin leading ones and
 * read thousands separators as nines (160 as 60, 2,500 as 25090), while the same crops masked
 * at brightness 190 and saturation 70 read correctly. The mask output is OCR'd without further
 * isolation.</p>
 */
public final class WhiteTextIsolator {

    private static final float MIN_BRIGHTNESS = 190f / 255f;
    private static final float MAX_SATURATION = 70f / 255f;
    private static final int INK = 0xFF000000;
    private static final int PAPER = 0xFFFFFFFF;

    private WhiteTextIsolator() {
    }

    /**
     * Masks a region and rescales it so the region is {@code targetHeight} pixels tall.
     *
     * <p>Recognition magnifies every crop four times; the largest button labels (34 px glyphs)
     * then exceed what the recogniser segments reliably and come back empty, while the same label
     * at the size of a smaller button reads. Normalising the region height removes the size
     * difference instead of tuning per button.</p>
     */
    public static RawImageData isolateScaled(BufferedImage frame, int x, int y, int width, int height,
            int targetHeight) {
        return scaled(isolate(frame, x, y, width, height, 0, MAX_SATURATION), targetHeight);
    }

    /**
     * Masks only near-pure white, after magnifying the region four times. On small glyphs a mask taken at
     * native size loses the thin strokes (a leading 1) to anti-aliasing; magnified first, the strokes keep
     * enough pixels to pass a strict saturation cap that a pale tinted background does not.
     */
    public static RawImageData isolatePureScaled(BufferedImage frame, int x, int y, int width, int height,
            int targetHeight, float maxSaturation) {
        int left = Math.max(0, x);
        int top = Math.max(0, y);
        int w = Math.max(1, Math.min(frame.getWidth(), x + width) - left);
        int h = Math.max(1, Math.min(frame.getHeight(), y + height) - top);
        BufferedImage magnified = new BufferedImage(w * 4, h * 4, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = magnified.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(frame.getSubimage(left, top, w, h), 0, 0, w * 4, h * 4, null);
        g.dispose();
        return scaled(isolate(magnified, 0, 0, w * 4, h * 4, 0, maxSaturation), targetHeight);
    }

    private static RawImageData scaled(RawImageData mask, int targetHeight) {
        if (mask.getHeight() <= targetHeight) {
            return mask;
        }
        BufferedImage source = ImageConverter.toBufferedImage(mask);
        int scaledWidth = Math.max(1, Math.round((float) source.getWidth() * targetHeight / source.getHeight()));
        BufferedImage scaled = new BufferedImage(scaledWidth + 16, targetHeight + 16, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = scaled.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, scaled.getWidth(), scaled.getHeight());
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(source, 8, 8, scaledWidth, targetHeight, null);
        g.dispose();
        return toRgba(scaled);
    }

    private static RawImageData toRgba(BufferedImage image) {
        byte[] rgba = new byte[image.getWidth() * image.getHeight() * 4];
        int offset = 0;
        for (int yy = 0; yy < image.getHeight(); yy++) {
            for (int xx = 0; xx < image.getWidth(); xx++) {
                int rgb = image.getRGB(xx, yy);
                rgba[offset++] = (byte) ((rgb >> 16) & 0xFF);
                rgba[offset++] = (byte) ((rgb >> 8) & 0xFF);
                rgba[offset++] = (byte) (rgb & 0xFF);
                rgba[offset++] = (byte) 0xFF;
            }
        }
        return RawImageData.capture(rgba, image.getWidth(), image.getHeight(), 32);
    }

    /**
     * Masks a region of the frame onto a white page with {@code padding} pixels of margin, so
     * glyphs touching the crop edge are not mistaken for noise.
     */
    public static RawImageData isolate(BufferedImage frame, int x, int y, int width, int height, int padding) {
        return isolate(frame, x, y, width, height, padding, MAX_SATURATION);
    }

    private static RawImageData isolate(BufferedImage frame, int x, int y, int width, int height, int padding,
            float maxSaturation) {
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
                int value = hsb[2] >= MIN_BRIGHTNESS && hsb[1] <= maxSaturation ? INK : PAPER;
                int offset = ((sy - top + padding) * outWidth + (sx - left + padding)) * 4;
                rgba[offset] = (byte) ((value >> 16) & 0xFF);
                rgba[offset + 1] = (byte) ((value >> 8) & 0xFF);
                rgba[offset + 2] = (byte) (value & 0xFF);
            }
        }
        return RawImageData.capture(rgba, outWidth, outHeight, 32);
    }
}
