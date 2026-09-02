package dev.frostguard.tasks.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import dev.frostguard.api.domain.OcrSettingsData;
import dev.frostguard.api.domain.OcrSettingsData.TextLayout;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.vision.ocr.OcrEngine;
import dev.frostguard.vision.ocr.PanelRowIndex;
import dev.frostguard.vision.ocr.TextLine;

/**
 * End-to-end reads of the two panels this routine depends on, against real captured frames.
 *
 * <p>These exist because the previous reader was calibrated as a set of fixed pixel boxes and two
 * of them were wrong in ways no unit test could see. The Steel box sat one row too high and had
 * been recording the <em>Iron</em> row as steel for the life of the telemetry history; and boxes
 * that tight starved the reader of context, so it dropped decimal points and turned 4.39M into
 * 439M. Both faults are in the fixtures below, and both are asserted against.</p>
 *
 * <p>The fixtures are full 720x1280 frames with everything outside the panel painted out, so the
 * production coordinates and column boundaries apply here exactly as they do live.</p>
 */
class ResourceStockpilePanelReadTest {

    /** Mirrors the routine's PANEL_TEXT_SETTINGS: whole panel, no isolation, no glyph filter. */
    private static final OcrSettingsData PANEL = OcrSettingsData.assembler()
            .textLayout(TextLayout.TEXT_BLOCK).stripBackground(false).build();

    private static final PointData SUMMARY_TL = new PointData(60, 380);
    private static final PointData SUMMARY_BR = new PointData(700, 980);
    private static final PointData OVERVIEW_TL = new PointData(60, 420);
    private static final PointData OVERVIEW_BR = new PointData(700, 900);
    private static final int OWNED_COLUMN_X = 440;
    private static final int TOTAL_RESOURCES_COLUMN_X = 460;
    private static final int SPEEDUP_VALUE_COLUMN_X = 400;

    private static PanelRowIndex read(String fixture, PointData tl, PointData br) throws Exception {
        BufferedImage img;
        try (InputStream in = ResourceStockpilePanelReadTest.class
                .getResourceAsStream("/panels/" + fixture)) {
            assertNotNull(in, "missing fixture /panels/" + fixture);
            img = ImageIO.read(in);
        }
        int w = img.getWidth(), h = img.getHeight();
        byte[] data = new byte[w * h * 4];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = img.getRGB(x, y);
                int off = (y * w + x) * 4;
                data[off] = (byte) ((rgb >> 16) & 0xFF);
                data[off + 1] = (byte) ((rgb >> 8) & 0xFF);
                data[off + 2] = (byte) (rgb & 0xFF);
                data[off + 3] = (byte) 0xFF;
            }
        }
        List<TextLine> words = OcrEngine.recognizeWords(
                RawImageData.capture(data, w, h, 4), tl, br, PANEL);
        return PanelRowIndex.of(words);
    }

    private static Long labelled(PanelRowIndex panel, String label, int columnX) {
        return panel.labelled(label)
                .map(r -> ResourceStockpileRoutine.parseScaled(r.textFrom(columnX)))
                .orElse(null);
    }

    /** The bug that made every steel figure in the history wrong. */
    @Test
    void steelReadsItsOwnRowAndNotIron() throws Exception {
        PanelRowIndex panel = read("summary-resources.png", SUMMARY_TL, SUMMARY_BR);

        Long steel = labelled(panel, "Steel", TOTAL_RESOURCES_COLUMN_X);
        assertEquals(1_740_000L, steel, "the panel shows Steel 1.74M");
        assertNotEquals(4_390_000L, steel, "4.39M is Iron, the row the old crop box landed on");
    }

    /** And the decimal point the narrow crops dropped, which inflated values a hundredfold. */
    @Test
    void everyResourceKeepsItsDecimalPoint() throws Exception {
        PanelRowIndex panel = read("summary-resources.png", SUMMARY_TL, SUMMARY_BR);

        assertEquals(89_550L, labelled(panel, "Gems", TOTAL_RESOURCES_COLUMN_X));
        assertEquals(87_880_000L, labelled(panel, "Meat", TOTAL_RESOURCES_COLUMN_X));
        assertEquals(70_600_000L, labelled(panel, "Wood", TOTAL_RESOURCES_COLUMN_X));
        assertEquals(14_360_000L, labelled(panel, "Coal", TOTAL_RESOURCES_COLUMN_X));
        assertEquals(4_390_000L, labelled(panel, "Iron", TOTAL_RESOURCES_COLUMN_X));
    }

    @Test
    void everySpeedupBucketResolvesToItsOwnDuration() throws Exception {
        PanelRowIndex panel = read("summary-speedup.png", SUMMARY_TL, SUMMARY_BR);

        assertEquals(2_903L, duration(panel, "General"), "2 day(s)23 min");
        assertEquals(2_090L, duration(panel, "Training"), "1 day(s)10 hr(s)50 min");
        assertEquals(900L, duration(panel, "Construction"), "15 hr(s)");
        assertEquals(996L, duration(panel, "Research"), "16 hr(s)36 min");
        assertEquals(7_930L, duration(panel, "Healing"), "5 day(s)12 hr(s)10 min");
    }

    private static Long duration(PanelRowIndex panel, String label) {
        return panel.labelled(label)
                .map(r -> ResourceStockpileRoutine.parseDurationMinutes(
                        r.textFrom(SPEEDUP_VALUE_COLUMN_X)))
                .orElse(null);
    }

    /**
     * The Overview's rows carry no label, so they are taken in order -- which is only safe when
     * all four resolve. The shielded amount printed under each owned figure must not be mistaken
     * for it.
     */
    @Test
    void overviewYieldsFourOwnedStockpilesInOrder() throws Exception {
        PanelRowIndex panel = read("overview-owned.png", OVERVIEW_TL, OVERVIEW_BR);

        List<Long> owned = new ArrayList<>();
        for (PanelRowIndex.Row row : panel.rows()) {
            Optional<TextLine> top = row.topmostFrom(OWNED_COLUMN_X);
            if (top.isEmpty()) continue;
            Long parsed = ResourceStockpileRoutine.parseScaled(top.get().text().trim());
            if (parsed != null) owned.add(parsed);
        }

        assertEquals(List.of(78_700_000L, 61_400_000L, 12_500_000L, 3_900_000L), owned,
                "meat, wood, coal, iron -- not the shielded amounts beneath them");
    }

    /** A label matching more than one row is declined rather than guessed. */
    @Test
    void ambiguousLabelsAreDeclined() throws Exception {
        PanelRowIndex panel = read("summary-speedup.png", SUMMARY_TL, SUMMARY_BR);

        assertTrue(panel.labelled("Speedup").isEmpty(), "every row says Speedup");
    }
}
