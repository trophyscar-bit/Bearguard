package dev.frostguard.engine.helper;

import java.awt.image.BufferedImage;
import java.util.OptionalLong;
import java.util.function.IntPredicate;
import java.util.regex.Pattern;

import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.engine.nav.CommonOCRSettings;
import dev.frostguard.vision.color.GameColors;
import dev.frostguard.vision.convert.ImageConverter;
import dev.frostguard.vision.ocr.OcrEngine;
import dev.frostguard.vision.ocr.OcrException;

/**
 * Reads the gem balance from the HUD's gem pill: a blue diamond, the white count, and on most
 * screens a green "+".
 *
 * <p>The digits are located by colour rather than by a fixed crop, because the pill moves with the
 * number of digits and a fixed crop that touches either icon makes Tesseract invent or fold a digit:
 * City Bonus read 59,293 as 99,293 on 12 Nov 2026, and the home HUD read 56,256 as 596,256. The
 * strip handed to OCR starts after the diamond's last column, ends before the "+" (the Deals bar has
 * no "+", so there the white ink itself ends it), and is trimmed to the white ink in between.</p>
 *
 * <p>Anything not positively found is unknown: no diamond, no ink, or a read that is not a grouped
 * number such as "59,293". Nothing falls back to a guessed crop.</p>
 */
public final class HudGemReader {

    /** A column belongs to an icon or to the digits once it holds this many matching pixels. */
    private static final int MIN_PIXELS_PER_COLUMN = 3;
    /** The diamond is about 40 px wide on a 720 px frame; a narrower blue run is something else. */
    private static final int MIN_DIAMOND_WIDTH = 20;
    /** The "+" is about 22 px wide; its narrow anti-aliased fringe must not stop the ink search. */
    private static final int MIN_PLUS_WIDTH = 8;
    /** Clear space kept around the white ink, never reaching into either icon. */
    private static final int INK_MARGIN = 3;

    private static final Pattern GROUPED_NUMBER = Pattern.compile("\\d{1,3}(,\\d{3})*");

    /**
     * @param gems     the balance, or empty when it is unknown
     * @param evidence what was found or why the balance is unknown, for the log
     */
    public record Reading(OptionalLong gems, String evidence) {
    }

    private HudGemReader() {
    }

    /**
     * @param frame a full screen capture
     * @param band  the HUD strip that holds the whole gem pill, diamond included
     */
    public static Reading read(RawImageData frame, AreaData band) {
        if (frame == null || !frame.isValid()) {
            return unknown("no frame");
        }
        BufferedImage image = ImageConverter.toBufferedImage(frame);
        int left = Math.max(0, band.topLeft().getX());
        int top = Math.max(0, band.topLeft().getY());
        int right = Math.min(image.getWidth(), band.bottomRight().getX());
        int bottom = Math.min(image.getHeight(), band.bottomRight().getY());
        if (right <= left || bottom <= top) {
            return unknown("the gem band lies outside the frame");
        }

        int[] diamond = firstRun(image, left, right, top, bottom, GameColors::isGemDiamondBlue, MIN_DIAMOND_WIDTH);
        if (diamond == null) {
            return unknown("no gem diamond in the band");
        }
        int afterDiamond = diamond[1] + 1;
        int[] plus = firstRun(image, afterDiamond, right, top, bottom, GameColors::isVividGreen, MIN_PLUS_WIDTH);
        int limit = plus == null ? right : plus[0];

        int firstInk = -1;
        int lastInk = -1;
        for (int x = afterDiamond; x < limit; x++) {
            if (columnCount(image, x, top, bottom, GameColors::isLabelWhite) >= MIN_PIXELS_PER_COLUMN) {
                if (firstInk < 0) {
                    firstInk = x;
                }
                lastInk = x;
            }
        }
        if (firstInk < 0) {
            return unknown("no white digits between the diamond (ends x=" + diamond[1] + ") and x=" + limit);
        }
        int stripLeft = Math.max(afterDiamond, firstInk - INK_MARGIN);
        int stripRight = Math.min(limit, lastInk + 1 + INK_MARGIN);
        String strip = "strip x=" + stripLeft + "-" + stripRight + (plus == null ? " (no +)" : "");

        String text;
        try {
            text = OcrEngine.recognizeText(frame, new PointData(stripLeft, top), new PointData(stripRight, bottom),
                    CommonOCRSettings.HUD_FULL_NUMBER_SETTINGS);
        } catch (OcrException | RuntimeException failure) {
            return unknown(strip + ": OCR failed (" + failure.getMessage() + ")");
        }
        OptionalLong gems = parseGrouped(text);
        String quoted = "'" + (text == null ? "" : text.trim()) + "'";
        if (gems.isEmpty()) {
            return unknown(strip + ": read " + quoted + " is not a grouped number");
        }
        return new Reading(gems, strip + ": read " + quoted);
    }

    /** "59,293" or "250"; separators out of place, or anything else in the text, make it unknown. */
    static OptionalLong parseGrouped(String text) {
        String clean = text == null ? "" : text.replaceAll("\\s", "");
        if (!GROUPED_NUMBER.matcher(clean).matches()) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(Long.parseLong(clean.replace(",", "")));
    }

    /** The first run of at least {@code minWidth} matching columns, as {first, last}, or null. */
    private static int[] firstRun(BufferedImage image, int from, int to, int top, int bottom, IntPredicate colour,
            int minWidth) {
        int start = -1;
        for (int x = from; x <= to; x++) {
            boolean hit = x < to && columnCount(image, x, top, bottom, colour) >= MIN_PIXELS_PER_COLUMN;
            if (hit && start < 0) {
                start = x;
            } else if (!hit && start >= 0) {
                if (x - start >= minWidth) {
                    return new int[] {start, x - 1};
                }
                start = -1;
            }
        }
        return null;
    }

    private static int columnCount(BufferedImage image, int x, int top, int bottom, IntPredicate colour) {
        int count = 0;
        for (int y = top; y < bottom; y++) {
            if (colour.test(image.getRGB(x, y))) {
                count++;
            }
        }
        return count;
    }

    private static Reading unknown(String why) {
        return new Reading(OptionalLong.empty(), why);
    }
}
