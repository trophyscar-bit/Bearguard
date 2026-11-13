package dev.frostguard.tasks.lifecycle;

import java.awt.Color;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.IntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.frostguard.api.domain.OcrSettingsData;
import dev.frostguard.api.domain.OcrSettingsData.TextLayout;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.engine.nav.SidebarFrameClassifier;
import dev.frostguard.engine.nav.SidebarSection;
import dev.frostguard.vision.convert.ImageConverter;
import dev.frostguard.vision.ocr.OcrEngine;
import dev.frostguard.vision.ocr.PanelRowIndex;
import dev.frostguard.vision.ocr.TextLine;

/**
 * Reads the sidebar's Daily tab rows the timer sweep cares about, each by its own title.
 *
 * <p>Alliance Contribution, both Hero Recruit timers, Pet Adventure and the Tree of Life used to
 * sit at the foot of the City tab, and the sweep found them by swiping that tab and reading fixed
 * boxes. The redesigned sidebar moved all of them to Daily; the City tab now ends at Tech Research
 * and Expert, so one swipe lands on the Training rows and the fixed boxes read camp timers or
 * nothing. Every saved "anchors-unreadable" frame shows exactly that.</p>
 *
 * <p>Daily rows come and go (finished ones can be hidden) and the list keeps whatever scroll
 * offset it was left at, so no row has a fixed position. Each card is therefore found by reading
 * its title and taking the line directly beneath it from the same frame. A value is only reported
 * under a title that matched exactly, on a frame whose selected tab measured as Daily: a timer
 * whose own label was not read is never attributed to anything.</p>
 */
final class DailyPanelReader {

    /** Daily cards the sweep reads, keyed by their title with case, spacing and punctuation removed. */
    enum Card {
        HERO_RECRUIT_ADVANCED("advanced"),
        HERO_RECRUIT_EPIC("epic"),
        PET_ADVENTURE("petadventure"),
        ALLIANCE_CONTRIBUTION("alliancecontribution"),
        TREE_OF_LIFE("treeoflife");

        private final String title;

        Card(String title) {
            this.title = title;
        }

        static Optional<Card> titled(String text) {
            String key = normalise(text);
            for (Card card : values()) {
                if (card.title.equals(key)) {
                    return Optional.of(card);
                }
            }
            return Optional.empty();
        }
    }

    /** One card as read: its countdown and its status line, either of which may be absent. */
    record Reading(Card card, int titleCenterY, Duration timer, String status) {
    }

    /** Text column of the cards. Starts right of the row icons so icon glyphs stay out of the words. */
    static final PointData TEXT_TOP_LEFT = new PointData(75, 300);
    static final PointData TEXT_BOTTOM_RIGHT = new PointData(380, 880);

    /** Card titles are centred on the card; section headers start at the left edge and get clipped. */
    private static final int CARD_CENTER_X = 224;
    private static final int CARD_CENTER_TOLERANCE = 35;
    private static final int CARD_TITLE_MIN_LEFT = 90;

    /**
     * Where a card's second line sits relative to its title. Measured on Daily frames from 08-21
     * and 11-13: 27-31 px for timers, status text and counters alike, while the next card's title
     * is at least 70 px below.
     */
    private static final int SECOND_LINE_MIN_DY = 15;
    private static final int SECOND_LINE_MAX_DY = 42;

    /** Words closer than this vertically are one line; a card's two lines sit ~28 px apart. */
    private static final int LINE_GAP = 8;

    private static final OcrSettingsData WORD_SETTINGS = OcrSettingsData.assembler()
            .textLayout(TextLayout.SPARSE)
            .stripBackground(false)
            .language("eng")
            .build();

    /**
     * Status lines are green on a dark card, which a plain read garbles ("Gan contribute)25/25").
     * Isolating the green reads them cleanly; the Tree of Life prints a slightly yellower green, so
     * a second shade is tried before giving up.
     */
    private static final List<OcrSettingsData> STATUS_SETTINGS = List.of(
            greenLine(new Color(0, 193, 0)),
            greenLine(new Color(40, 200, 40)));

    private static final Pattern TIMESTAMP = Pattern.compile("\\d{1,2}[:\\-]\\d{2}[:\\-]\\d{2}");

    private DailyPanelReader() {
    }

    /**
     * Reads one frame.
     *
     * @return the recognised cards, or empty when the frame is not provably the Daily tab — in
     *         which case nothing on it may be scheduled from
     */
    static Optional<List<Reading>> read(RawImageData frame) {
        if (frame == null || !isDailyTab(frame)) {
            return Optional.empty();
        }
        return Optional.of(readCards(frame));
    }

    /** The card reading alone, for a frame whose tab has already been proven. */
    static List<Reading> readCards(RawImageData frame) {
        List<TextLine> words;
        try {
            words = OcrEngine.recognizeWords(frame, TEXT_TOP_LEFT, TEXT_BOTTOM_RIGHT, WORD_SETTINGS);
        } catch (Exception ex) {
            return List.of();
        }
        return cards(words, centerY -> readStatus(frame, centerY));
    }

    static boolean isDailyTab(RawImageData frame) {
        try {
            return SidebarFrameClassifier.selectedSection(ImageConverter.toBufferedImage(frame))
                    .filter(SidebarSection.DAILY::equals)
                    .isPresent();
        } catch (RuntimeException ex) {
            return false;
        }
    }

    /**
     * Pairs every recognised card title with the line directly beneath it.
     *
     * @param words  words recognised across the card text column, in frame coordinates
     * @param status reads the status line under a title centred at the given y; only consulted
     *               for cards whose second line is not a countdown
     */
    static List<Reading> cards(List<TextLine> words, IntFunction<String> status) {
        List<PanelRowIndex.Row> lines = PanelRowIndex.of(words, LINE_GAP).rows();
        List<Reading> out = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            PanelRowIndex.Row line = lines.get(i);
            if (!isCardTitle(line)) {
                continue;
            }
            Optional<Card> card = Card.titled(line.text());
            if (card.isEmpty()) {
                continue;
            }
            int titleY = line.centerY();
            Duration timer = null;
            if (i + 1 < lines.size()) {
                PanelRowIndex.Row below = lines.get(i + 1);
                int dy = below.centerY() - titleY;
                if (dy >= SECOND_LINE_MIN_DY && dy <= SECOND_LINE_MAX_DY
                        && TIMESTAMP.matcher(below.text()).find()) {
                    timer = TimerSweepRoutine.extractTimer(below.text());
                }
            }
            String statusLine = null;
            if (timer == null && (card.get() == Card.ALLIANCE_CONTRIBUTION || card.get() == Card.TREE_OF_LIFE)) {
                statusLine = status.apply(titleY);
            }
            out.add(new Reading(card.get(), titleY, timer, statusLine));
        }
        return out;
    }

    /**
     * Folds the readings from several viewports of one scan into one per card. A reading that
     * carries a value beats one that does not, so a card cut off at the panel's edge in one
     * viewport is taken from the next.
     */
    static Map<Card, Reading> merge(List<List<Reading>> viewports) {
        Map<Card, Reading> merged = new EnumMap<>(Card.class);
        for (List<Reading> viewport : viewports) {
            for (Reading reading : viewport) {
                Reading held = merged.get(reading.card());
                if (held == null || (!hasValue(held) && hasValue(reading))) {
                    merged.put(reading.card(), reading);
                }
            }
        }
        return Collections.unmodifiableMap(merged);
    }

    private static boolean hasValue(Reading reading) {
        return reading.timer() != null || (reading.status() != null && !reading.status().isBlank());
    }

    private static boolean isCardTitle(PanelRowIndex.Row line) {
        int left = line.words().stream().mapToInt(TextLine::left).min().orElse(0);
        int right = line.words().stream().mapToInt(TextLine::right).max().orElse(0);
        int center = (left + right) / 2;
        return left >= CARD_TITLE_MIN_LEFT && Math.abs(center - CARD_CENTER_X) <= CARD_CENTER_TOLERANCE;
    }

    private static String readStatus(RawImageData frame, int titleCenterY) {
        PointData topLeft = new PointData(90, titleCenterY + 17);
        PointData bottomRight = new PointData(360, titleCenterY + 45);
        String fallback = null;
        for (OcrSettingsData settings : STATUS_SETTINGS) {
            try {
                String text = OcrEngine.recognizeText(frame, topLeft, bottomRight, settings);
                if (text != null && text.chars().anyMatch(Character::isDigit)) {
                    return text.trim();
                }
                if (fallback == null && text != null && !text.isBlank()) {
                    fallback = text.trim();
                }
            } catch (Exception ex) {
                // An unreadable status line is reported as absent, never guessed.
            }
        }
        return fallback;
    }

    private static OcrSettingsData greenLine(Color shade) {
        return OcrSettingsData.assembler()
                .textLayout(TextLayout.SINGLE_LINE)
                .isolateForeground(true)
                .targetColor(shade)
                .language("eng")
                .build();
    }

    private static String normalise(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (char c : text.toLowerCase(Locale.ROOT).toCharArray()) {
            if (c >= 'a' && c <= 'z') {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Pulls "n/m" out of a contribution status line such as "Can contribute 15/25". */
    static Optional<int[]> contribution(String status) {
        if (status == null) {
            return Optional.empty();
        }
        Matcher m = CONTRIBUTION.matcher(status);
        if (!m.find()) {
            return Optional.empty();
        }
        return Optional.of(new int[] { Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)) });
    }

    /** "contribute" then anything but digits — the live read put ")" between them once. */
    private static final Pattern CONTRIBUTION =
            Pattern.compile("contribute\\D{0,3}(\\d{1,2})\\s*/\\s*(\\d{1,2})", Pattern.CASE_INSENSITIVE);
}
