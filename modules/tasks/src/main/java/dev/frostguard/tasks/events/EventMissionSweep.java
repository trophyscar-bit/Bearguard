package dev.frostguard.tasks.events;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.OcrSettingsData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.tasks.events.EventMissionClassifier.Kind;
import dev.frostguard.tasks.events.EventMissionClassifier.Verdict;
import dev.frostguard.vision.ocr.OcrEngine;

/**
 * One pass over an event's mission list: scroll all of it, claim every finished mission by its green Claim
 * button, and say which unfinished ones a bot task could do. It never taps a Go button, a gem or priced
 * button, or any row naming Chief Gear, and it stops on a screen it does not recognise.
 *
 * <p>The sweep is plain logic over a {@link Screen}, so the rules are tested against saved frames and the
 * routine supplies the emulator. Which task does a doable mission, and whether that task is switched on, is
 * the caller's business ({@link EventMissionTrigger}).
 */
final class EventMissionSweep {

    /** Where the list sits on a 720x1280 screen and how it scrolls. */
    record Geometry(int listTop, int listBottom, int buttonX, int buttonColumnLeft, int buttonColumnRight,
            int titleLeft, int titleRight, int titleAboveButton, int titleHalfHeight,
            int swipeFromY, int swipeToY, int swipeX, int maxPages, int minButtonHeight) {
    }

    /** What the sweep needs from the emulator. */
    interface Screen {
        BufferedImage frame();

        /** The mission title in the band above the button centred at {@code buttonCenterY}; null when unreadable. */
        String readTitle(BufferedImage frame, int buttonCenterY);

        void tap(int x, int y);

        void swipe(int fromX, int fromY, int toX, int toY);

        void sleep(long millis);

        /** The page is still the mission list (nothing popped up over it). */
        boolean onMissionList();

        void warn(String message);

        void saveFrame(String tag, BufferedImage frame);
    }

    enum ButtonState { CLAIM, GO }

    record Row(int centerY, ButtonState state) {
    }

    record Mission(String title, Verdict verdict) {
    }

    /** What one pass found. {@code doable} lists unfinished missions an existing task does, one per title. */
    record Result(int claimed, List<Mission> doable, List<Mission> purchase, List<Mission> skipped,
            boolean stoppedEarly, int pages) {
    }

    static final long CLAIM_SETTLE_MS = 2500;
    static final long SWIPE_SETTLE_MS = 1200;
    private static final int MAX_CLAIMS_PER_PAGE = 6;
    private static final int SAME_FRAME_SAMPLE_STEP = 6;
    private static final double SAME_FRAME_MAX_DIFF = 0.01;

    private EventMissionSweep() {
    }

    static Result run(Screen screen, Geometry geometry) {
        int claimed = 0;
        int pages = 0;
        boolean stoppedEarly = false;
        Map<String, Mission> doable = new LinkedHashMap<>();
        Map<String, Mission> purchase = new LinkedHashMap<>();
        Map<String, Mission> skipped = new LinkedHashMap<>();
        BufferedImage previous = null;

        scrollToTop(screen, geometry);
        for (int page = 0; page < geometry.maxPages(); page++) {
            BufferedImage frame = screen.frame();
            if (previous != null && sameList(previous, frame, geometry)) {
                break;
            }
            pages++;
            for (int claim = 0; claim < MAX_CLAIMS_PER_PAGE; claim++) {
                Row target = firstClaim(findRows(frame, geometry), screen, frame, skipped);
                if (target == null) {
                    break;
                }
                screen.tap(geometry.buttonX(), target.centerY());
                screen.sleep(CLAIM_SETTLE_MS);
                claimed++;
                if (!screen.onMissionList()) {
                    screen.warn("Mission sweep: the screen after a claim is not the mission list; stopping.");
                    screen.saveFrame("after-claim-unexpected", screen.frame());
                    stoppedEarly = true;
                    break;
                }
                frame = screen.frame();
            }
            if (stoppedEarly) {
                break;
            }
            for (Row row : findRows(frame, geometry)) {
                if (row.state() != ButtonState.GO) {
                    continue;
                }
                String title = screen.readTitle(frame, row.centerY());
                Verdict verdict = EventMissionClassifier.classify(title);
                Mission mission = new Mission(title == null ? "" : title.trim(), verdict);
                String key = EventMissionClassifier.key(title);
                switch (verdict.kind()) {
                    case DOABLE -> doable.putIfAbsent(key, mission);
                    case PURCHASE -> purchase.putIfAbsent(key, mission);
                    case EXCLUDED, UNREADABLE -> skipped.putIfAbsent(key + row.centerY(), mission);
                    case PASSIVE -> { }
                }
            }
            previous = frame;
            screen.swipe(geometry.swipeX(), geometry.swipeFromY(), geometry.swipeX(), geometry.swipeToY());
            screen.sleep(SWIPE_SETTLE_MS);
            if (!screen.onMissionList()) {
                screen.warn("Mission sweep: the list is gone after a scroll; stopping.");
                screen.saveFrame("after-scroll-unexpected", screen.frame());
                stoppedEarly = true;
                break;
            }
        }
        if (!stoppedEarly) {
            scrollToTop(screen, geometry);
        }
        return new Result(claimed, List.copyOf(doable.values()), List.copyOf(purchase.values()),
                List.copyOf(skipped.values()), stoppedEarly, pages);
    }

    /**
     * The first finished mission this sweep may claim, or null. A claim is refused on a Chief Gear row and
     * on a row whose title cannot be read; each is noted in {@code skipped} and its frame saved once.
     */
    private static Row firstClaim(List<Row> rows, Screen screen, BufferedImage frame, Map<String, Mission> skipped) {
        for (Row row : rows) {
            if (row.state() != ButtonState.CLAIM) {
                continue;
            }
            String title = screen.readTitle(frame, row.centerY());
            Verdict verdict = EventMissionClassifier.classify(title);
            if (verdict.kind() == Kind.EXCLUDED || verdict.kind() == Kind.UNREADABLE) {
                String key = EventMissionClassifier.key(title) + row.centerY();
                if (skipped.putIfAbsent(key, new Mission(title == null ? "" : title.trim(), verdict)) == null) {
                    screen.warn("Mission sweep: not claiming \"" + (title == null ? "" : title.trim()) + "\" ("
                            + verdict.kind().name().toLowerCase() + ").");
                    screen.saveFrame("claim-refused-" + verdict.kind().name().toLowerCase(), frame);
                }
                continue;
            }
            return row;
        }
        return null;
    }

    private static void scrollToTop(Screen screen, Geometry geometry) {
        for (int i = 0; i < geometry.maxPages(); i++) {
            BufferedImage before = screen.frame();
            screen.swipe(geometry.swipeX(), geometry.swipeToY(), geometry.swipeX(), geometry.swipeFromY());
            screen.sleep(SWIPE_SETTLE_MS);
            if (sameList(before, screen.frame(), geometry)) {
                return;
            }
        }
    }

    /** Green Claim and blue Go buttons in the button column, each at least {@code minButtonHeight} tall. */
    static List<Row> findRows(BufferedImage image, Geometry g) {
        List<Row> rows = new ArrayList<>();
        ButtonState current = null;
        int start = -1;
        int bottom = Math.min(g.listBottom(), image.getHeight());
        for (int y = g.listTop(); y <= bottom; y++) {
            ButtonState at = y < bottom ? buttonAt(image, g, y) : null;
            if (at != current) {
                if (current != null && y - start >= g.minButtonHeight()) {
                    rows.add(new Row((start + y) / 2, current));
                }
                current = at;
                start = at == null ? -1 : y;
            }
        }
        return rows;
    }

    private static ButtonState buttonAt(BufferedImage image, Geometry g, int y) {
        int left = image.getRGB(g.buttonColumnLeft(), y);
        int right = image.getRGB(g.buttonColumnRight(), y);
        if (isClaimGreen(left) && isClaimGreen(right)) {
            return ButtonState.CLAIM;
        }
        if (isGoBlue(left) && isGoBlue(right)) {
            return ButtonState.GO;
        }
        return null;
    }

    // Claim (37,183,86) and Go (79,165,252) sampled from live frames on 2026-11-09.
    static boolean isClaimGreen(int rgb) {
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        return g >= 150 && r <= 90 && b <= 130 && g - r >= 80;
    }

    static boolean isGoBlue(int rgb) {
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        return b >= 220 && g >= 130 && r <= 120;
    }

    /** Two frames of the list that look alike: the scroll did not move, so the list has ended. */
    static boolean sameList(BufferedImage a, BufferedImage b, Geometry g) {
        int sampled = 0;
        int different = 0;
        int bottom = Math.min(Math.min(g.listBottom(), a.getHeight()), b.getHeight());
        int right = Math.min(a.getWidth(), b.getWidth());
        for (int y = g.listTop(); y < bottom; y += SAME_FRAME_SAMPLE_STEP) {
            for (int x = 0; x < right; x += SAME_FRAME_SAMPLE_STEP) {
                sampled++;
                if (!closeColour(a.getRGB(x, y), b.getRGB(x, y))) {
                    different++;
                }
            }
        }
        return sampled > 0 && different <= sampled * SAME_FRAME_MAX_DIFF;
    }

    private static boolean closeColour(int p, int q) {
        return Math.abs(((p >> 16) & 0xFF) - ((q >> 16) & 0xFF)) < 12
                && Math.abs(((p >> 8) & 0xFF) - ((q >> 8) & 0xFF)) < 12
                && Math.abs((p & 0xFF) - (q & 0xFF)) < 12;
    }

    /** Reads the title band above a button centred at {@code buttonCenterY}; null when OCR finds nothing. */
    static String ocrTitle(RawImageData raw, Geometry g, int buttonCenterY) {
        int middle = buttonCenterY - g.titleAboveButton();
        PointData topLeft = new PointData(g.titleLeft(), Math.max(0, middle - g.titleHalfHeight()));
        PointData bottomRight = new PointData(g.titleRight(), middle + g.titleHalfHeight());
        try {
            String text = OcrEngine.recognizeText(raw, topLeft, bottomRight, OcrSettingsData.forWhiteTextOnDark());
            return text == null || text.isBlank() ? null : text.trim();
        } catch (Exception ex) {
            return null;
        }
    }

    /** The tasks the doable missions name, in order, without repeats. */
    static List<TpDailyTaskEnum> tasksOf(Result result) {
        return result.doable().stream().map(m -> m.verdict().task()).distinct().toList();
    }
}
