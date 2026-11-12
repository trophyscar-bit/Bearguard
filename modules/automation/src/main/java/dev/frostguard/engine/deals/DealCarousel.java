package dev.frostguard.engine.deals;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.engine.nav.CommonOCRSettings;
import dev.frostguard.vision.convert.ImageConverter;
import dev.frostguard.vision.convert.WhiteTextIsolator;
import dev.frostguard.vision.match.OpenCvPatternLocator;
import dev.frostguard.vision.ocr.OcrEngine;
import dev.frostguard.vision.ocr.OcrException;

/**
 * A pack page whose pack is switched by white arrows either side of its title banner, such as the gem
 * shop's Dawn Market (five price tiers), optionally with a row of selectable options between the banner
 * and the item panel (Dawn Market's four category chests, the selected one framed by white corner marks).
 *
 * <p>Measured on 720 x 1280 frames of 2026-09-18: both arrows score 100 on all ten Dawn Market frames and
 * at most 74 anywhere in that night's 53 scan frames (the white speedup chevrons of the Weekly Cards tab);
 * the frame's corner marks score 87-100 on every tier and option against a 60-68 noise floor. The right
 * arrow is the left one mirrored. The option chests change colour with the tier, so options are found by
 * how far they stand out from the panel background, never by their picture.</p>
 */
public final class DealCarousel {

    public record Box(int x, int y, int width, int height) {
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

    /** The arrows and the banner between them, which carries the current pack's name. */
    public record Layout(Box leftArrow, Box rightArrow, Box banner) {
    }

    private static final double ARROW_MIN_SCORE = 90.0;
    private static final double BRACKET_MIN_SCORE = 80.0;
    /** The left arrow hugs the panel's left edge; a hit further in is artwork, not navigation. */
    private static final int LEFT_ARROW_MAX_X = 160;
    private static final int SAME_ROW_TOLERANCE = 6;
    private static final int BANNER_INSET = 10;
    /** Options sit just under the banner: 30-50 px on Dawn Market; searched a little further. */
    private static final int OPTION_SEARCH_DEPTH = 120;
    private static final int MAX_OPTION_WIDTH = 220;
    /** Pixels whose summed RGB distance from the panel background averages above this belong to an option. */
    private static final int OPTION_MIN_DISTANCE = 45;
    private static final int OPTION_MAX_GAP = 8;
    private static final int OPTION_MIN_WIDTH = 40;
    /** The selected option's glow spills past its frame and merges with neighbours; those are left out. */
    private static final int SELECTION_GLOW_MARGIN = 8;
    private static final int BACKGROUND_SAMPLE_WIDTH = 10;
    private static final int BADGE_MIN_RED = 200;
    private static final int BADGE_MAX_GREEN = 100;
    private static final int BADGE_MIN_BLUE_OVER_GREEN = 15;
    /** The rosette covers about 4,000 pixels of the frame; a few stray pink pixels are not a rosette. */
    private static final int BADGE_MIN_PIXELS = 200;
    private static final int BADGE_CLEARANCE = 4;

    private static final byte[] LEFT_ARROW = load("/deals/carousel-left.png");
    private static final byte[] RIGHT_ARROW = load("/deals/carousel-right.png");
    private static final byte[] BRACKET_LEFT = load("/deals/option-bracket-left.png");
    private static final byte[] BRACKET_RIGHT = load("/deals/option-bracket-right.png");

    private DealCarousel() {
    }

    public static Optional<Layout> locate(RawImageData frame) {
        int width = frame.getWidth();
        ImageSearchResultData left = OpenCvPatternLocator.matchFromRawTemplate(frame, LEFT_ARROW,
                new PointData(0, 0), new PointData(width / 2, frame.getHeight()), ARROW_MIN_SCORE);
        if (!left.isFound()) {
            return Optional.empty();
        }
        Box leftBox = box(left);
        if (leftBox.x() > LEFT_ARROW_MAX_X) {
            return Optional.empty();
        }
        ImageSearchResultData right = OpenCvPatternLocator.matchFromRawTemplate(frame, RIGHT_ARROW,
                new PointData(width / 2, leftBox.y() - SAME_ROW_TOLERANCE),
                new PointData(width, leftBox.bottom() + SAME_ROW_TOLERANCE), ARROW_MIN_SCORE);
        if (!right.isFound()) {
            return Optional.empty();
        }
        Box rightBox = box(right);
        Box banner = new Box(leftBox.right() + BANNER_INSET, leftBox.y(),
                rightBox.x() - leftBox.right() - 2 * BANNER_INSET, leftBox.height());
        return Optional.of(new Layout(leftBox, rightBox, banner));
    }

    /** The option framed by the white corner marks, if the carousel has an option row. */
    public static Optional<Box> selectedOption(RawImageData frame, Layout layout) {
        int top = layout.banner().bottom();
        int bottom = Math.min(frame.getHeight(), top + OPTION_SEARCH_DEPTH);
        ImageSearchResultData left = OpenCvPatternLocator.matchFromRawTemplate(frame, BRACKET_LEFT,
                new PointData(0, top), new PointData(frame.getWidth(), bottom), BRACKET_MIN_SCORE);
        if (!left.isFound()) {
            return Optional.empty();
        }
        Box leftMark = box(left);
        ImageSearchResultData right = OpenCvPatternLocator.matchFromRawTemplate(frame, BRACKET_RIGHT,
                new PointData(leftMark.right(), leftMark.y() - SAME_ROW_TOLERANCE),
                new PointData(Math.min(frame.getWidth(), leftMark.x() + MAX_OPTION_WIDTH),
                        leftMark.bottom() + SAME_ROW_TOLERANCE),
                BRACKET_MIN_SCORE);
        if (!right.isFound()) {
            return Optional.empty();
        }
        int width = box(right).right() - leftMark.x();
        return Optional.of(new Box(leftMark.x(), leftMark.y(), width, width));
    }

    /**
     * Centres of the options visible beside the selected one, left to right, excluding the selected one and
     * any neighbour its glow has merged into. The caller taps them one at a time; each tap moves the glow,
     * so neighbours hidden in one frame stand clear in the next.
     */
    public static List<Integer> otherOptionCentres(RawImageData frame, Layout layout, Box selected) {
        BufferedImage image = ImageConverter.toBufferedImage(frame);
        int top = selected.y() + 4;
        int bottom = Math.min(image.getHeight(), selected.y() + selected.height() * 3 / 4);
        int[] background = backgroundColour(image, layout, top, bottom);
        boolean[] marked = new boolean[image.getWidth()];
        for (int x = 0; x < image.getWidth(); x++) {
            long distance = 0;
            for (int y = top; y < bottom; y++) {
                int rgb = image.getRGB(x, y);
                distance += Math.abs(((rgb >> 16) & 0xFF) - background[0])
                        + Math.abs(((rgb >> 8) & 0xFF) - background[1])
                        + Math.abs((rgb & 0xFF) - background[2]);
            }
            marked[x] = distance / Math.max(1, bottom - top) > OPTION_MIN_DISTANCE;
        }
        List<int[]> runs = new ArrayList<>();
        for (int x = 0; x < marked.length; x++) {
            if (!marked[x]) {
                continue;
            }
            int start = x;
            while (x < marked.length && marked[x]) {
                x++;
            }
            if (!runs.isEmpty() && start - runs.get(runs.size() - 1)[1] <= OPTION_MAX_GAP) {
                runs.get(runs.size() - 1)[1] = x;
            } else {
                runs.add(new int[]{start, x});
            }
        }
        List<Integer> centres = new ArrayList<>();
        for (int[] run : runs) {
            boolean clearOfSelection = run[1] < selected.x() - SELECTION_GLOW_MARGIN
                    || run[0] > selected.right() + SELECTION_GLOW_MARGIN;
            if (run[1] - run[0] >= OPTION_MIN_WIDTH && clearOfSelection) {
                centres.add((run[0] + run[1]) / 2);
            }
        }
        return centres;
    }

    /** The pack name printed on the banner, or an empty string when it cannot be read. */
    public static String bannerTitle(RawImageData frame, Layout layout) {
        BufferedImage image = ImageConverter.toBufferedImage(frame);
        Box banner = layout.banner();
        int right = Math.min(banner.right(), badgeLeft(image, banner) - BADGE_CLEARANCE);
        RawImageData mask = WhiteTextIsolator.isolate(image, banner.x(), banner.y(), right - banner.x(),
                banner.height(), BANNER_INSET);
        try {
            return OcrEngine.recognizeText(mask, new PointData(0, 0),
                    new PointData(mask.getWidth(), mask.getHeight()), CommonOCRSettings.DEAL_TAB_LABEL_SETTINGS)
                    .replaceAll("\\s+", " ").trim();
        } catch (OcrException unreadable) {
            return "";
        }
    }

    /**
     * Left edge of the pink "Best Deals" rosette pinned over the banner's right end, or past the banner when
     * there is none. Its white lettering otherwise joins the pack name ("est YPrayers of Dawn Pack"). The
     * rosette measures (235-255, 58-80, 94-118); the salmon panel behind it has green near 120 and the
     * banner's orange near 172.
     */
    private static int badgeLeft(BufferedImage image, Box banner) {
        int left = Integer.MAX_VALUE;
        int count = 0;
        for (int y = banner.y(); y < Math.min(image.getHeight(), banner.bottom()); y++) {
            for (int x = banner.x(); x < Math.min(image.getWidth(), banner.right()); x++) {
                int rgb = image.getRGB(x, y);
                int red = (rgb >> 16) & 0xFF;
                int green = (rgb >> 8) & 0xFF;
                int blue = rgb & 0xFF;
                if (red > BADGE_MIN_RED && green < BADGE_MAX_GREEN && blue - green > BADGE_MIN_BLUE_OVER_GREEN) {
                    left = Math.min(left, x);
                    count++;
                }
            }
        }
        return count >= BADGE_MIN_PIXELS ? left : banner.right() + BADGE_CLEARANCE;
    }

    /** The panel colour beside the arrows, where no option is drawn. */
    private static int[] backgroundColour(BufferedImage image, Layout layout, int top, int bottom) {
        List<Integer> reds = new ArrayList<>();
        List<Integer> greens = new ArrayList<>();
        List<Integer> blues = new ArrayList<>();
        int[] starts = {layout.leftArrow().x(), layout.rightArrow().right() - BACKGROUND_SAMPLE_WIDTH};
        for (int start : starts) {
            for (int x = start; x < start + BACKGROUND_SAMPLE_WIDTH; x++) {
                for (int y = top; y < bottom; y++) {
                    int rgb = image.getRGB(x, y);
                    reds.add((rgb >> 16) & 0xFF);
                    greens.add((rgb >> 8) & 0xFF);
                    blues.add(rgb & 0xFF);
                }
            }
        }
        return new int[]{median(reds), median(greens), median(blues)};
    }

    private static int median(List<Integer> values) {
        List<Integer> sorted = new ArrayList<>(values);
        sorted.sort(null);
        return sorted.get(sorted.size() / 2);
    }

    private static Box box(ImageSearchResultData match) {
        return new Box(match.getX() - match.getMatchWidth() / 2, match.getY() - match.getMatchHeight() / 2,
                match.getMatchWidth(), match.getMatchHeight());
    }

    private static byte[] load(String resource) {
        try (InputStream in = DealCarousel.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing classpath resource " + resource);
            }
            return in.readAllBytes();
        } catch (IOException unreadable) {
            throw new UncheckedIOException("Could not read " + resource, unreadable);
        }
    }
}
