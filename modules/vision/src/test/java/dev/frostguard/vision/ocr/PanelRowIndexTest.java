package dev.frostguard.vision.ocr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * Row grouping, checked against words measured off the live panels rather than invented ones --
 * the coordinates below are what the reader actually returned for a 720x1280 frame.
 */
class PanelRowIndexTest {

    private static TextLine word(String text, int left, int top, int width, int height) {
        return new TextLine(text, left, top, width, height, 90f);
    }

    /**
     * The Resource &amp; Speedup Summary. Steel is the row the production crop box never reached:
     * it was measured one row high and read Iron's 4.39M as steel for the life of the history.
     */
    private static PanelRowIndex summaryPanel() {
        return PanelRowIndex.of(List.of(
                word("Resources", 109, 393, 108, 16), word("Total", 315, 393, 54, 16),
                word("Items", 380, 392, 56, 20), word("Total", 469, 393, 54, 16),
                word("Resources", 539, 393, 103, 16),
                word("Gems", 136, 463, 59, 18), word("384", 354, 464, 45, 18),
                word("89.55K", 515, 464, 83, 18),
                word("Meat", 136, 553, 53, 17), word("9.16M", 343, 554, 66, 17),
                word("87.88M", 513, 554, 86, 17),
                word("Wood", 135, 643, 64, 17), word("9.10M", 343, 644, 66, 18),
                word("70.60M", 512, 644, 87, 18),
                word("Coal", 136, 733, 47, 18), word("1.79M", 344, 734, 64, 17),
                word("14.36M", 515, 734, 82, 17),
                word("Iron", 136, 823, 42, 17), word("494.1K", 335, 824, 82, 18),
                word("4.39M", 518, 824, 74, 18),
                word("Steel", 136, 913, 51, 17), word("1.74M", 524, 914, 65, 17)));
    }

    @Test
    void findsSteelsOwnValueAndNotTheRowAboveIt() {
        Optional<PanelRowIndex.Row> steel = summaryPanel().labelled("Steel");

        assertTrue(steel.isPresent());
        assertEquals("1.74M", steel.get().textFrom(460), "the crop box read Iron's 4.39M here");
    }

    @Test
    void keepsEveryResourceRowSeparate() {
        PanelRowIndex panel = summaryPanel();

        assertEquals("89.55K", panel.labelled("Gems").orElseThrow().textFrom(460));
        assertEquals("87.88M", panel.labelled("Meat").orElseThrow().textFrom(460));
        assertEquals("70.60M", panel.labelled("Wood").orElseThrow().textFrom(460));
        assertEquals("14.36M", panel.labelled("Coal").orElseThrow().textFrom(460));
        assertEquals("4.39M", panel.labelled("Iron").orElseThrow().textFrom(460));
        assertEquals(7, panel.rows().size(), "a header and six resource rows");
    }

    /**
     * The Speedup tab wraps two of its labels onto a second line, so one row arrives as three
     * separate recognised fragments. They still have to group as one row.
     */
    @Test
    void groupsAWrappedLabelWithItsValue() {
        PanelRowIndex panel = PanelRowIndex.of(List.of(
                word("Item", 140, 394, 60, 22), word("Total", 400, 394, 54, 22),
                word("Speedup", 470, 394, 90, 22),
                word("General", 136, 447, 100, 24), word("Speedup", 240, 447, 90, 24),
                word("2 day(s)23 min", 426, 450, 200, 25),
                word("Troop Training", 135, 538, 163, 23),
                word("1 day(s)10 hr(s)50 min", 426, 554, 233, 25),
                word("Speedup", 136, 568, 90, 23),
                word("Construction", 136, 628, 141, 18),
                word("15 hr(s)", 504, 643, 77, 21),
                word("Speedup", 136, 658, 90, 24)));

        assertEquals("1 day(s)10 hr(s)50 min",
                panel.labelled("Training").orElseThrow().textFrom(400));
        assertEquals("15 hr(s)", panel.labelled("Construction").orElseThrow().textFrom(400));
        assertEquals("2 day(s)23 min", panel.labelled("General").orElseThrow().textFrom(400));
    }

    /**
     * On the Overview the shielded amount sits 34px under the owned one with no label between
     * them, so both land in the same row and the upper of the two is the stockpile.
     */
    @Test
    void takesTheOwnedFigureNotTheShieldedOneBeneathIt() {
        PanelRowIndex panel = PanelRowIndex.of(List.of(
                word("Owned", 508, 433, 77, 19),
                word("78.7M", 467, 501, 76, 20), word("21.8M", 480, 535, 72, 20),
                word("61.4M", 468, 614, 75, 20),
                word("12.5M", 470, 726, 72, 20), word("3.3M", 486, 760, 60, 19),
                word("3.9M", 475, 839, 63, 20), word("761.7K", 474, 873, 83, 19)));

        List<PanelRowIndex.Row> rows = panel.rows();
        assertEquals(5, rows.size(), "a header and four resource rows");
        assertEquals("78.7M", rows.get(1).topmostFrom(440).orElseThrow().text());
        assertEquals("61.4M", rows.get(2).topmostFrom(440).orElseThrow().text());
        assertEquals("12.5M", rows.get(3).topmostFrom(440).orElseThrow().text());
        assertEquals("3.9M", rows.get(4).topmostFrom(440).orElseThrow().text());
    }

    /** An ambiguous label is declined, not guessed at. Guessing is how steel became iron. */
    @Test
    void refusesAnAmbiguousLabel() {
        PanelRowIndex panel = PanelRowIndex.of(List.of(
                word("General", 136, 447, 100, 24), word("Speedup", 240, 447, 90, 24),
                word("Research", 136, 717, 110, 24), word("Speedup", 250, 717, 90, 24)));

        assertFalse(panel.labelled("Speedup").isPresent(), "two rows mention it");
        assertTrue(panel.labelled("Research").isPresent());
    }

    @Test
    void toleratesAnEmptyRead() {
        assertTrue(PanelRowIndex.of(List.of()).rows().isEmpty());
        assertFalse(PanelRowIndex.of(List.of()).labelled("Steel").isPresent());
    }
}
