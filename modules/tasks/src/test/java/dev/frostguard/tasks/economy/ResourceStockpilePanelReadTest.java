package dev.frostguard.tasks.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    private static final PointData OVERVIEW_TL = new PointData(430, 470);
    private static final PointData OVERVIEW_BR = new PointData(600, 905);
    private static final int OWNED_COLUMN_X = 440;
    private static final int TOTAL_RESOURCES_COLUMN_X = 460;
    private static final int SPEEDUP_VALUE_COLUMN_X = 400;

    private static BufferedImage load(String fixture) throws Exception {
        try (InputStream in = ResourceStockpilePanelReadTest.class
                .getResourceAsStream("/panels/" + fixture)) {
            assertNotNull(in, "missing fixture /panels/" + fixture);
            return ImageIO.read(in);
        }
    }

    private static RawImageData capture(BufferedImage img) {
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
        return RawImageData.capture(data, w, h, 4);
    }

    private static PanelRowIndex rows(BufferedImage img, PointData tl, PointData br) throws Exception {
        List<TextLine> words = OcrEngine.recognizeWords(capture(img), tl, br, PANEL);
        return PanelRowIndex.of(words);
    }

    private static PanelRowIndex read(String fixture, PointData tl, PointData br) throws Exception {
        return rows(load(fixture), tl, br);
    }

    private static final List<String> SUMMARY_ROWS =
            List.of("Gems", "Meat", "Wood", "Coal", "Iron", "Steel");
    private static final List<String> SPEEDUP_ROWS =
            List.of("General", "Training", "Construction", "Research", "Healing");

    private static Long labelled(PanelRowIndex panel, String label, int columnX) {
        return panel.orderedValues(columnX, SUMMARY_ROWS)
                .map(t -> ResourceStockpileRoutine.parseScaled(t.get(label)))
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
        return panel.orderedValues(SPEEDUP_VALUE_COLUMN_X, SPEEDUP_ROWS)
                .map(t -> ResourceStockpileRoutine.parseDurationMinutes(t.get(label)))
                .orElse(null);
    }

    /** The owned figures, top to bottom, picked out by the routine's own selector. */
    private static List<Long> ownedValues(BufferedImage img, PointData tl, PointData br) throws Exception {
        return ResourceStockpileRoutine.ownedValues(rows(img, tl, br), img);
    }

    private static List<Long> ownedValues(String fixture, PointData tl, PointData br) throws Exception {
        return ownedValues(load(fixture), tl, br);
    }

    /**
     * The rule this replaced: the topmost word in each row that parses. Kept here only so the
     * hazard test can show what it would have done.
     */
    private static List<Long> topmostParseable(BufferedImage img, PointData tl, PointData br) throws Exception {
        List<Long> owned = new ArrayList<>();
        for (PanelRowIndex.Row row : rows(img, tl, br).rows()) {
            row.topmostFrom(OWNED_COLUMN_X, t -> ResourceStockpileRoutine.parseScaled(t) != null)
               .ifPresent(t -> owned.add(ResourceStockpileRoutine.parseScaled(t.text().trim())));
        }
        return owned;
    }

    /**
     * The Overview's rows carry no label, so they are taken in order -- which is only safe when
     * all four resolve. The shielded amount printed under each owned figure must not be mistaken
     * for it.
     */
    @Test
    void overviewYieldsFourOwnedStockpilesInOrder() throws Exception {
        assertEquals(List.of(78_700_000L, 61_400_000L, 12_500_000L, 3_900_000L),
                ownedValues("overview-owned.png", OVERVIEW_TL, OVERVIEW_BR),
                "meat, wood, coal, iron -- not the shielded amounts beneath them");
    }

    /**
     * A frame the bot saved when it failed, 2026-09-10 19:57, on both attempts. Every figure on it
     * is sharp; the whole-panel pass still returned only iron, because Tesseract merged meat's
     * two values and its shield icon into one word ("orem") and dropped wood's and coal's
     * entirely. Deterministic -- the same frame fails the same way every time -- which is why the
     * retry could not rescue it and why frames captured by hand never showed it.
     */
    @Test
    void theOwnedColumnReadsAFrameTheWholePanelPassCouldNot() throws Exception {
        String fixture = "overview-owned-world-layout-fail.png";

        assertEquals(List.of(325_400_000L, 353_900_000L, 64_700_000L, 17_900_000L),
                ownedValues(fixture, OVERVIEW_TL, OVERVIEW_BR),
                "meat, wood, coal, iron from the Owned column alone");

        assertTrue(ownedValues(fixture, new PointData(60, 420), new PointData(700, 900)).size() < 4,
                "the whole-panel region this replaced reads this frame short -- if that ever stops"
                        + " being true, this fixture no longer pins the fault it was saved for");
    }

    /**
     * Owned figures are navy and the shielded amounts teal. Boxes are where the reader placed each
     * figure on the 19:57 frame.
     */
    @Test
    void ownedAndShieldedFiguresAreToldApartByColour() throws Exception {
        BufferedImage img = load("overview-owned-world-layout-fail.png");
        int[][] owned = { {458, 501, 95, 20}, {458, 614, 95, 20}, {466, 726, 80, 20}, {470, 839, 72, 20} };
        int[][] shield = { {469, 535, 94, 20}, {472, 648, 88, 19}, {476, 760, 79, 20}, {487, 873, 58, 19} };
        for (int[] b : owned) {
            assertFalse(ResourceStockpileRoutine.isShieldColoured(img, new TextLine("x", b[0], b[1], b[2], b[3], 90f)),
                    "owned figure at y=" + b[1] + " must read as navy");
        }
        for (int[] b : shield) {
            assertTrue(ResourceStockpileRoutine.isShieldColoured(img, new TextLine("x", b[0], b[1], b[2], b[3], 90f)),
                    "shielded figure at y=" + b[1] + " must read as teal");
        }
    }

    /**
     * The 23:40 failure, reproduced on a real frame: meat's owned figure does not come through, and
     * the shielded amount under it is the next number in the row.
     */
    @Test
    void aMissingOwnedFigureIsNeverReplacedByTheShieldedOne() throws Exception {
        BufferedImage img = load("overview-owned-world-layout-fail.png");
        // Paint meat's owned figure (325.4M) out with the cell's own background.
        java.awt.Graphics2D g = img.createGraphics();
        g.setColor(new java.awt.Color(img.getRGB(430, 505)));
        g.fillRect(455, 497, 106, 28);
        g.dispose();

        assertEquals(207_600_000L, topmostParseable(img, OVERVIEW_TL, OVERVIEW_BR).get(0),
                "the old rule takes meat's shielded 207.6M as its stockpile -- the hazard is real here");

        assertEquals(List.of(353_900_000L, 64_700_000L, 17_900_000L),
                ownedValues(img, OVERVIEW_TL, OVERVIEW_BR),
                "meat resolves nothing; the panel comes back short instead of carrying a shield figure");
    }

    /**
     * A row the whole-column pass cannot read is read again from its own cell, with digits only.
     *
     * <p>The frame is a real failure from 9/12. The column pass has to use an open alphabet, and
     * Tesseract answered "A411.6M" for wood's 411.6M -- a hallucinated leading letter. It does not
     * parse, so the row resolved nothing and the panel was refused, every cycle for six hours,
     * because wood happened to be the value in the 400-millions.</p>
     */
    @Test
    void aRowTheColumnPassCannotReadIsReadAgainWithDigitsOnly() throws Exception {
        BufferedImage img = load("overview-owned-letter-hallucination.png");
        PanelRowIndex panel = rows(img, OVERVIEW_TL, OVERVIEW_BR);

        assertEquals(List.of(360_200_000L, 73_300_000L, 21_400_000L),
                ResourceStockpileRoutine.ownedValues(panel, img),
                "the column pass alone loses wood entirely");

        assertEquals(List.of(360_200_000L, 411_600_000L, 73_300_000L, 21_400_000L),
                ResourceStockpileRoutine.ownedValues(panel, img, capture(img)),
                "read again from its own cell, wood resolves -- and in its own row's place");
    }

    /**
     * The other shape of the same fault: the figure is read, but not as a number.
     *
     * <p>A real failure from 9/12 at 23:29, hours after the re-read above shipped. Here wood's
     * 411.4M came back as "A11.4M" -- present in the row, just unparseable. The first re-read
     * anchored on the row's topmost word on the assumption that it was the shielded amount, which
     * only holds when the owned figure produced no word at all; with a word present the box aimed a
     * row-gap too high, at blank panel, and wood was dropped anyway. The anchor has to be the
     * figure's own position when there is one.</p>
     */
    @Test
    void aRowReadAsLettersIsReadAgainFromItsOwnPosition() throws Exception {
        BufferedImage img = load("overview-owned-letter-prefix-present.png");
        PanelRowIndex panel = rows(img, OVERVIEW_TL, OVERVIEW_BR);

        assertEquals(List.of(358_900_000L, 73_300_000L, 21_900_000L),
                ResourceStockpileRoutine.ownedValues(panel, img),
                "the column pass reads wood as 'A11.4M' and cannot use it");

        assertEquals(List.of(358_900_000L, 411_400_000L, 73_300_000L, 21_900_000L),
                ResourceStockpileRoutine.ownedValues(panel, img, capture(img)),
                "re-read from where the unreadable word actually sits, wood resolves as 411.4M");
    }

    /** A label matching more than one row is declined rather than guessed. */
    @Test
    void ambiguousLabelsAreDeclined() throws Exception {
        PanelRowIndex panel = read("summary-speedup.png", SUMMARY_TL, SUMMARY_BR);

        assertTrue(panel.labelled("Speedup").isEmpty(), "every row says Speedup");
    }

    /**
     * A live frame on which the reader returned every number and not one label -- "Gems", "Meat",
     * "Wood", "Coal", "Iron" and "Steel" all missing, while the same panel on another frame reads
     * them at 96% confidence. Anchoring on the label alone gave up here and logged "no unambiguous
     * 'Steel' row"; the table still has to be readable.
     */
    @Test
    void readsTheTableOnAFrameWhoseLabelsDidNotResolve() throws Exception {
        PanelRowIndex panel = read("summary-resources-labels-unread.png", SUMMARY_TL, SUMMARY_BR);

        assertTrue(panel.labelled("Steel").isEmpty(), "the fixture's point: no label to anchor to");

        Optional<Map<String, String>> table = panel.orderedValues(TOTAL_RESOURCES_COLUMN_X, SUMMARY_ROWS);
        assertTrue(table.isPresent(), "six value rows in the expected order");
        assertEquals(1_750_000L, ResourceStockpileRoutine.parseScaled(table.get().get("Steel")));
        assertEquals(4_410_000L, ResourceStockpileRoutine.parseScaled(table.get().get("Iron")));
        assertEquals(88_370_000L, ResourceStockpileRoutine.parseScaled(table.get().get("Meat")));
    }

    /** A table that is not the expected shape is refused outright rather than read off by position. */
    @Test
    void aTableOfTheWrongLengthIsRefused() throws Exception {
        PanelRowIndex panel = read("summary-resources.png", SUMMARY_TL, SUMMARY_BR);

        assertTrue(panel.orderedValues(TOTAL_RESOURCES_COLUMN_X,
                List.of("Gems", "Meat", "Wood")).isEmpty(),
                "three expected rows against six read ones must not silently take the first three");
    }
}
