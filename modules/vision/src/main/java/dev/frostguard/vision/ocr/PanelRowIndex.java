package dev.frostguard.vision.ocr;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Groups the words of a recognised game panel into rows, so a value can be found by the label
 * beside it rather than by the pixel box it happened to sit in when someone measured it.
 *
 * <p>Fixed crop boxes are what made the resource readings unreliable. Two failures, both real:</p>
 *
 * <p>The Steel box on the Resource &amp; Speedup Summary was measured one row too high and had been
 * reading the <em>Iron</em> row ever since -- steel in the telemetry history was never steel. A box
 * cannot notice that; a label can.</p>
 *
 * <p>And a box that tight starves Tesseract of context. Asked to read the Iron row alone it
 * returned "439M" for 4.39M -- the decimal point dropped, the value inflated a hundredfold. The
 * same pixels read as part of a whole-panel word pass come back "4.39M", every row, every decimal
 * intact. Tesseract does better when it can see a page than when it is shown a keyhole.</p>
 *
 * <p>Rows are found by clustering words on their vertical centre and splitting wherever the gap
 * exceeds {@link #DEFAULT_ROW_GAP}. That handles a label wrapping onto two lines (the Troop
 * Training row is three separate recognised fragments) without merging neighbouring rows, which
 * sit a good deal further apart than any within-row spread.</p>
 */
public final class PanelRowIndex {

    /**
     * Vertical distance between word centres beyond which a new row starts.
     *
     * <p>Measured on the live panels: within a row, fragments of a wrapped label and its value sit
     * 13-17px apart, and the Overview's protected sub-value sits 34px under its owned value.
     * Between rows the nearest centres are 58px apart, and usually 80-90. Forty splits every
     * observed row correctly with room on both sides.</p>
     */
    public static final int DEFAULT_ROW_GAP = 40;

    private final List<Row> rows;

    private PanelRowIndex(List<Row> rows) {
        this.rows = rows;
    }

    /** One panel row: every word whose vertical centre clustered together, ordered left to right. */
    public record Row(List<TextLine> words) {

        public int centerY() {
            return words.stream().mapToInt(w -> w.top() + w.height() / 2).sum() / words.size();
        }

        /** Every word from {@code minX} rightwards, in reading order. */
        public List<TextLine> wordsFrom(int minX) {
            return words.stream().filter(w -> w.left() >= minX)
                    .sorted(Comparator.comparingInt(TextLine::left)).collect(Collectors.toList());
        }

        /** The row's words from {@code minX} rightwards, joined with single spaces. */
        public String textFrom(int minX) {
            return wordsFrom(minX).stream().map(w -> w.text().trim())
                    .filter(t -> !t.isEmpty()).collect(Collectors.joining(" "));
        }

        public String text() {
            return textFrom(Integer.MIN_VALUE);
        }

        /**
         * The highest word from {@code minX} rightwards.
         *
         * <p>For the Overview panel this is what separates the owned stockpile from the shielded
         * amount printed under it: both land in the same row, and the owned figure is the upper
         * of the two.</p>
         */
        public Optional<TextLine> topmostFrom(int minX) {
            return topmostFrom(minX, w -> true);
        }

        /**
         * The highest word from {@code minX} rightwards that {@code accept} agrees with.
         *
         * <p>The Overview's rows carry a green "+" button inside the value column, and on a row
         * whose figure failed to resolve that button would otherwise be returned as the topmost
         * thing there. Letting the caller say what a value looks like keeps a stray glyph from
         * standing in for one.</p>
         */
        public Optional<TextLine> topmostFrom(int minX, java.util.function.Predicate<String> accept) {
            return words.stream()
                    .filter(w -> w.left() >= minX)
                    .filter(w -> accept.test(w.text() == null ? "" : w.text().trim()))
                    .min(Comparator.comparingInt(TextLine::top));
        }

        public boolean mentions(String label) {
            return text().toLowerCase(Locale.ROOT).contains(label.toLowerCase(Locale.ROOT));
        }
    }

    public static PanelRowIndex of(List<TextLine> words) {
        return of(words, DEFAULT_ROW_GAP);
    }

    public static PanelRowIndex of(List<TextLine> words, int rowGap) {
        List<TextLine> ordered = words.stream()
                .filter(w -> w.text() != null && !w.text().isBlank())
                .sorted(Comparator.comparingInt(w -> w.top() + w.height() / 2))
                .collect(Collectors.toList());

        List<Row> grouped = new ArrayList<>();
        List<TextLine> current = new ArrayList<>();
        int previousCenter = Integer.MIN_VALUE;
        for (TextLine word : ordered) {
            int center = word.top() + word.height() / 2;
            if (!current.isEmpty() && center - previousCenter > rowGap) {
                grouped.add(new Row(List.copyOf(current)));
                current.clear();
            }
            current.add(word);
            previousCenter = center;
        }
        if (!current.isEmpty()) {
            grouped.add(new Row(List.copyOf(current)));
        }
        return new PanelRowIndex(grouped);
    }

    public List<Row> rows() {
        return rows;
    }

    /**
     * The row whose text mentions {@code label}.
     *
     * <p>Empty when no row mentions it, and also when more than one does -- an ambiguous match is
     * exactly the situation that produced a steel reading of iron's number, so it declines rather
     * than picking one. ("Speedup" matches five rows; "General" matches one.)</p>
     */
    public Optional<Row> labelled(String label) {
        List<Row> matches = rows.stream().filter(r -> r.mentions(label)).collect(Collectors.toList());
        return matches.size() == 1 ? Optional.of(matches.get(0)) : Optional.empty();
    }
}
