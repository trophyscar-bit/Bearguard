package dev.frostguard.vision.deals;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.Rect;
import org.opencv.core.Size;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

/**
 * Reads the count printed in a deal tile's bottom-right corner by matching each glyph against reference
 * glyphs cut from saved frames, instead of general-purpose OCR.
 *
 * <p>The game prints every tile count in one font: white, outlined, about twelve pixels tall at any tile
 * size. Masked to pure white on a magnified crop, each character is its own component, so the count is the
 * row of digit-sized components ending at the tile's right edge. On 78 tiles of the 2026-10-22 frames with
 * known counts, each frame classified against references from the other frames, every glyph's own class
 * scored at least 0.958 and every other class at most 0.904 (6 against 8); the one exception is two digits
 * printed touching ("48"), whose split halves scored 0.902 and are rejected by {@link #MIN_SCORE}. Any
 * glyph below the floor or too close to a second class leaves the count unread: OCR may still try it, and
 * a character with no reference (no 7 has been cut yet) can never be forced onto a known one.</p>
 */
public final class TileCountReader {

    private static final String CHARACTERS = "012345689K";
    private static final int GLYPH_WIDTH = 20;
    private static final int GLYPH_HEIGHT = 28;
    private static final int MAGNIFICATION = 4;
    /** Count text is near-pure white; a purple tile's lilac floor measured saturation 50-79 and is excluded. */
    private static final double MAX_SATURATION = 35;
    private static final double MIN_VALUE = 190;
    /** Glyph heights on the magnified crop: 41-48 px at tile sizes from 0.70 to 1.03. */
    private static final int MIN_GLYPH_HEIGHT = 36;
    private static final int MAX_GLYPH_HEIGHT = 52;
    private static final int MIN_GLYPH_WIDTH = 8;
    /** A single glyph is at most about 42 px wide; wider components are touching digits, about 34 px each. */
    private static final int MAX_SINGLE_GLYPH_WIDTH = 45;
    private static final double GLYPH_PITCH = 34;
    private static final int SPLIT_SEARCH = 8;
    private static final int MAX_BASELINE_DRIFT = 8;
    private static final int MAX_GLYPH_GAP = 40;
    static final double MIN_SCORE = 0.93;
    static final double MIN_MARGIN = 0.03;

    /** The tile's count band, relative to the matched icon and in full-size tile pixels. */
    private static final int BAND_LEFT = -6;
    private static final int BAND_RIGHT = 96;
    private static final int BAND_TOP = 50;
    private static final int BAND_BOTTOM = 102;

    private static final Map<Character, List<float[]>> REFERENCES = loadReferences();

    private TileCountReader() {
    }

    /**
     * The count under a tile whose icon was matched with its top-left corner at ({@code iconX}, {@code iconY})
     * at {@code scale} of a full-size tile, as written ("4000", "50K"), or empty when any glyph is uncertain.
     */
    public static Optional<String> read(BufferedImage frame, int iconX, int iconY, double scale) {
        int left = Math.max(0, (int) (iconX + BAND_LEFT * scale));
        int right = Math.min(frame.getWidth(), (int) (iconX + BAND_RIGHT * scale));
        int top = Math.max(0, (int) (iconY + BAND_TOP * scale));
        int bottom = Math.min(frame.getHeight(), (int) (iconY + BAND_BOTTOM * scale));
        return readRegion(frame, left, top, right - left, bottom - top, MAGNIFICATION);
    }

    /**
     * The number ending at the right of a region, read at {@code magnification}. Tile counts are about 12 px
     * tall and are magnified four times; a pack's gem banner prints the same font at about 40 px and is read
     * as it is. Its glyphs differ slightly in rendering, so the references include banner glyphs too.
     */
    public static Optional<String> readRegion(BufferedImage frame, int x, int y, int width, int height,
            int magnification) {
        int left = Math.max(0, x);
        int top = Math.max(0, y);
        int right = Math.min(frame.getWidth(), x + width);
        int bottom = Math.min(frame.getHeight(), y + height);
        if (right - left < 8 || bottom - top < 8) {
            return Optional.empty();
        }
        Mat mask = whiteMask(frame, left, top, right - left, bottom - top, magnification);
        List<Mat> glyphs = glyphs(mask);
        mask.release();
        if (glyphs.isEmpty()) {
            return Optional.empty();
        }
        StringBuilder text = new StringBuilder();
        for (Mat glyph : glyphs) {
            Optional<Character> character = classify(glyph);
            glyph.release();
            if (character.isEmpty()) {
                return Optional.empty();
            }
            text.append(character.get());
        }
        // K only ever ends a count, and a count never starts with 0.
        String read = text.toString();
        if (read.indexOf('K') >= 0 && read.indexOf('K') != read.length() - 1 || read.startsWith("0")
                || read.equals("K")) {
            return Optional.empty();
        }
        return Optional.of(read);
    }

    private static Mat whiteMask(BufferedImage frame, int x, int y, int width, int height, int magnification) {
        Mat bgr = new Mat(height, width, CvType.CV_8UC3);
        byte[] pixels = new byte[width * height * 3];
        int offset = 0;
        for (int row = 0; row < height; row++) {
            for (int column = 0; column < width; column++) {
                int rgb = frame.getRGB(x + column, y + row);
                pixels[offset++] = (byte) (rgb & 0xFF);
                pixels[offset++] = (byte) ((rgb >> 8) & 0xFF);
                pixels[offset++] = (byte) ((rgb >> 16) & 0xFF);
            }
        }
        bgr.put(0, 0, pixels);
        Mat magnified = new Mat();
        if (magnification == 1) {
            bgr.copyTo(magnified);
        } else {
            Imgproc.resize(bgr, magnified, new Size(), magnification, magnification, Imgproc.INTER_CUBIC);
        }
        Mat hsv = new Mat();
        Imgproc.cvtColor(magnified, hsv, Imgproc.COLOR_BGR2HSV);
        Mat mask = new Mat(hsv.rows(), hsv.cols(), CvType.CV_8UC1);
        byte[] hsvPixels = new byte[hsv.rows() * hsv.cols() * 3];
        hsv.get(0, 0, hsvPixels);
        byte[] maskPixels = new byte[hsv.rows() * hsv.cols()];
        for (int i = 0; i < maskPixels.length; i++) {
            int saturation = hsvPixels[i * 3 + 1] & 0xFF;
            int value = hsvPixels[i * 3 + 2] & 0xFF;
            maskPixels[i] = (byte) (value >= MIN_VALUE && saturation <= MAX_SATURATION ? 255 : 0);
        }
        mask.put(0, 0, maskPixels);
        bgr.release();
        magnified.release();
        hsv.release();
        return mask;
    }

    private record Component(int x, int y, int width, int height, int label) {
        int bottom() {
            return y + height;
        }
    }

    /** The glyphs of the right-most run of digit-sized components, left to right, each normalised in size. */
    private static List<Mat> glyphs(Mat mask) {
        Mat labels = new Mat();
        Mat stats = new Mat();
        Mat centroids = new Mat();
        int count = Imgproc.connectedComponentsWithStats(mask, labels, stats, centroids, 8, CvType.CV_32S);
        List<Component> candidates = new ArrayList<>();
        for (int i = 1; i < count; i++) {
            int x = (int) stats.get(i, Imgproc.CC_STAT_LEFT)[0];
            int y = (int) stats.get(i, Imgproc.CC_STAT_TOP)[0];
            int width = (int) stats.get(i, Imgproc.CC_STAT_WIDTH)[0];
            int height = (int) stats.get(i, Imgproc.CC_STAT_HEIGHT)[0];
            // A component touching the band's edge is a neighbouring tile's artwork or a cut-off digit.
            boolean touchesEdge = y == 0 || y + height >= mask.rows() || x + width >= mask.cols();
            if (height >= MIN_GLYPH_HEIGHT && height <= MAX_GLYPH_HEIGHT && width >= MIN_GLYPH_WIDTH && !touchesEdge) {
                candidates.add(new Component(x, y, width, height, i));
            }
        }
        List<Mat> glyphs = new ArrayList<>();
        if (!candidates.isEmpty()) {
            candidates.sort(Comparator.comparingInt(Component::x));
            List<Component> run = new ArrayList<>();
            run.add(candidates.get(candidates.size() - 1));
            for (int i = candidates.size() - 2; i >= 0; i--) {
                Component next = run.get(run.size() - 1);
                Component candidate = candidates.get(i);
                if (Math.abs(candidate.bottom() - next.bottom()) > MAX_BASELINE_DRIFT
                        || next.x() - (candidate.x() + candidate.width()) > MAX_GLYPH_GAP) {
                    break;
                }
                run.add(candidate);
            }
            for (int i = run.size() - 1; i >= 0; i--) {
                glyphs.addAll(split(labels, run.get(i)));
            }
        }
        labels.release();
        stats.release();
        centroids.release();
        return glyphs;
    }

    /** One component as one or more normalised glyphs; touching digits are cut at their thinnest column. */
    private static List<Mat> split(Mat labels, Component component) {
        int width = component.width();
        int height = component.height();
        int[] label = new int[width * height];
        labels.submat(new Rect(component.x(), component.y(), width, height)).get(0, 0, label);
        byte[] ink = new byte[width * height];
        int[] columnInk = new int[width];
        for (int i = 0; i < label.length; i++) {
            if (label[i] == component.label()) {
                ink[i] = (byte) 255;
                columnInk[i % width]++;
            }
        }
        Mat whole = new Mat(height, width, CvType.CV_8UC1);
        whole.put(0, 0, ink);
        int parts = width > MAX_SINGLE_GLYPH_WIDTH ? Math.max(1, (int) Math.round(width / GLYPH_PITCH)) : 1;
        List<Integer> cuts = new ArrayList<>();
        cuts.add(0);
        for (int j = 1; j < parts; j++) {
            int middle = j * width / parts;
            int from = Math.max(1, middle - SPLIT_SEARCH);
            int to = Math.min(width - 1, middle + SPLIT_SEARCH);
            int best = from;
            for (int column = from; column < to; column++) {
                if (columnInk[column] < columnInk[best]) {
                    best = column;
                }
            }
            cuts.add(best);
        }
        cuts.add(width);
        List<Mat> glyphs = new ArrayList<>();
        for (int j = 0; j + 1 < cuts.size(); j++) {
            Mat part = whole.submat(new Rect(cuts.get(j), 0, cuts.get(j + 1) - cuts.get(j), height));
            Mat normalised = new Mat();
            Imgproc.resize(part, normalised, new Size(GLYPH_WIDTH, GLYPH_HEIGHT), 0, 0, Imgproc.INTER_AREA);
            glyphs.add(normalised);
        }
        whole.release();
        return glyphs;
    }

    private static Optional<Character> classify(Mat glyph) {
        float[] pixels = toFloats(glyph);
        Map<Character, Double> best = new LinkedHashMap<>();
        for (Map.Entry<Character, List<float[]>> reference : REFERENCES.entrySet()) {
            double score = 0;
            for (float[] sample : reference.getValue()) {
                score = Math.max(score, correlation(pixels, sample));
            }
            best.put(reference.getKey(), score);
        }
        List<Map.Entry<Character, Double>> ranked = best.entrySet().stream()
                .sorted(Map.Entry.<Character, Double>comparingByValue().reversed())
                .toList();
        double first = ranked.get(0).getValue();
        double second = ranked.size() > 1 ? ranked.get(1).getValue() : 0;
        return first >= MIN_SCORE && first - second >= MIN_MARGIN ? Optional.of(ranked.get(0).getKey())
                : Optional.empty();
    }

    private static double correlation(float[] a, float[] b) {
        double product = 0;
        double aa = 0;
        double bb = 0;
        for (int i = 0; i < a.length; i++) {
            product += a[i] * b[i];
            aa += a[i] * a[i];
            bb += b[i] * b[i];
        }
        return product / Math.sqrt(aa * bb + 1e-9);
    }

    private static float[] toFloats(Mat glyph) {
        byte[] raw = new byte[GLYPH_WIDTH * GLYPH_HEIGHT];
        glyph.get(0, 0, raw);
        float[] values = new float[raw.length];
        for (int i = 0; i < raw.length; i++) {
            values[i] = (raw[i] & 0xFF) / 255f;
        }
        return values;
    }

    private static Map<Character, List<float[]>> loadReferences() {
        Map<Character, List<float[]>> references = new LinkedHashMap<>();
        for (char character : CHARACTERS.toCharArray()) {
            String resource = "/deals/count-glyphs/" + character + ".png";
            try (InputStream in = TileCountReader.class.getResourceAsStream(resource)) {
                if (in == null) {
                    throw new IllegalStateException("Missing classpath resource " + resource);
                }
                Mat sheet = Imgcodecs.imdecode(new MatOfByte(in.readAllBytes()), Imgcodecs.IMREAD_GRAYSCALE);
                List<float[]> samples = new ArrayList<>();
                for (int x = 0; x + GLYPH_WIDTH <= sheet.cols(); x += GLYPH_WIDTH) {
                    samples.add(toFloats(sheet.submat(new Rect(x, 0, GLYPH_WIDTH, GLYPH_HEIGHT))));
                }
                sheet.release();
                references.put(character, samples);
            } catch (IOException unreadable) {
                throw new UncheckedIOException("Could not read " + resource, unreadable);
            }
        }
        return references;
    }
}
