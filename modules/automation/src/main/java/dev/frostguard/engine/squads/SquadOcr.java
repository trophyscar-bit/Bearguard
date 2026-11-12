package dev.frostguard.engine.squads;

import java.awt.Color;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.frostguard.api.domain.OcrSettingsData;
import dev.frostguard.api.domain.OcrSettingsData.TextLayout;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.vision.ocr.OcrEngine;
import dev.frostguard.vision.ocr.OcrException;
import dev.frostguard.vision.ocr.TextLine;

/** OCR presets and number parsing shared by the Squads screen readers. */
final class SquadOcr {

    static final OcrSettingsData LINE = OcrSettingsData.builder()
            .textLayout(TextLayout.SINGLE_LINE).build();
    static final OcrSettingsData SPARSE = OcrSettingsData.builder()
            .textLayout(TextLayout.SPARSE).build();
    // Gear and card levels print "Lv.6" in yellow over busy item art; isolating the yellow keeps
    // the art out of the read.
    static final OcrSettingsData YELLOW_LEVEL = OcrSettingsData.builder()
            .textLayout(TextLayout.SINGLE_LINE).allowedGlyphs("Lv.0123456789 ")
            .isolateForeground(true).targetColor(new Color(250, 225, 100)).build();
    static final OcrSettingsData LEVEL = OcrSettingsData.builder()
            .textLayout(TextLayout.SINGLE_LINE).allowedGlyphs("Lv.0123456789 ").build();
    // Card levels are white with a dark outline over portrait art.
    static final OcrSettingsData WHITE_LEVEL = LEVEL.toConfigurator()
            .isolateForeground(true).targetColor(Color.WHITE).build();
    static final OcrSettingsData PLUS_NUMBER = OcrSettingsData.builder()
            .textLayout(TextLayout.SINGLE_LINE).allowedGlyphs("+0123456789").build();
    // Gear "+N" badges: white digits over item art that bleeds into a plain read.
    static final OcrSettingsData WHITE_PLUS_NUMBER = PLUS_NUMBER.toConfigurator()
            .isolateForeground(true).targetColor(Color.WHITE).build();
    // White digits with a dark outline on a light pill: without isolation a lone "1" reads as "u"
    // and commas turn into slashes.
    static final OcrSettingsData WHITE_COUNT = OcrSettingsData.builder()
            .textLayout(TextLayout.SINGLE_LINE).allowedGlyphs("0123456789,")
            .isolateForeground(true).targetColor(Color.WHITE).build();
    // The squad capacity line "69,698/126,850" sits just left of a "!" icon that reads as junk
    // unless the glyphs are limited.
    static final OcrSettingsData CAPACITY = OcrSettingsData.builder()
            .textLayout(TextLayout.SINGLE_LINE).allowedGlyphs("0123456789,/").build();
    static final OcrSettingsData PERCENT = OcrSettingsData.builder()
            .textLayout(TextLayout.SINGLE_LINE).allowedGlyphs("0123456789%").build();

    private static final Pattern DIGITS = Pattern.compile("\\d[\\d,]*");
    private static final Pattern DECIMAL = Pattern.compile("(\\d+(?:\\.\\d+)?)");

    private SquadOcr() {
    }

    static String text(RawImageData frame, int left, int top, int right, int bottom, OcrSettingsData cfg) {
        try {
            return OcrEngine.recognizeText(frame, new PointData(left, top), new PointData(right, bottom), cfg).trim();
        } catch (OcrException | RuntimeException e) {
            return "";
        }
    }

    static List<TextLine> lines(RawImageData frame, int left, int top, int right, int bottom) {
        try {
            return OcrEngine.recognizeLines(frame, new PointData(left, top), new PointData(right, bottom), SPARSE);
        } catch (OcrException | RuntimeException e) {
            return List.of();
        }
    }

    /** The first run of digits (commas allowed) as a number, or null when there is none. */
    static Long number(String text) {
        if (text == null) return null;
        Matcher m = DIGITS.matcher(text);
        if (!m.find()) return null;
        String digits = m.group().replace(",", "");
        if (digits.isEmpty() || digits.length() > 15) return null;
        return Long.parseLong(digits);
    }

    static Integer smallNumber(String text) {
        Long value = number(text);
        return value == null || value > Integer.MAX_VALUE ? null : value.intValue();
    }

    static Double decimal(String text) {
        if (text == null) return null;
        Matcher m = DECIMAL.matcher(text.replace(",", ""));
        return m.find() ? Double.parseDouble(m.group(1)) : null;
    }
}
