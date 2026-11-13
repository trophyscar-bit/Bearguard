package dev.frostguard.tasks.shop;

import java.util.List;
import java.util.Locale;
import java.util.OptionalInt;

import dev.frostguard.api.domain.OcrSettingsData;
import dev.frostguard.api.domain.OcrSettingsData.TextLayout;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.vision.ocr.OcrEngine;
import dev.frostguard.vision.ocr.TextLine;

/**
 * Finds the unselected Daily Deals tab in the Gem Shop's tab strip by its label, since the
 * strip's tab order changes with the active events and the tab has no fixed slot.
 */
final class DailyDealsTabLabel {

    /** The band of the strip that holds the tab labels. */
    static final PointData LABEL_TOP_LEFT = new PointData(0, 150);
    static final PointData LABEL_BOTTOM_RIGHT = new PointData(719, 190);

    private static final OcrSettingsData SETTINGS = OcrSettingsData.assembler()
            .textLayout(TextLayout.TEXT_BLOCK)
            .stripBackground(false)
            .build();

    private DailyDealsTabLabel() {
    }

    static List<TextLine> read(RawImageData frame, PointData topLeft, PointData bottomRight) {
        try {
            return OcrEngine.recognizeWords(frame, topLeft, bottomRight, SETTINGS);
        } catch (Exception e) {
            return List.of();
        }
    }

    /** Horizontal centre of the label's "Daily" or "Deals" word, when one was read. */
    static OptionalInt centerX(List<TextLine> words) {
        for (TextLine w : words) {
            String text = w.text() == null ? "" : w.text().toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
            if (text.startsWith("daily") || text.equals("deals") || text.equals("dail")) {
                return OptionalInt.of(w.left() + w.width() / 2);
            }
        }
        return OptionalInt.empty();
    }
}
