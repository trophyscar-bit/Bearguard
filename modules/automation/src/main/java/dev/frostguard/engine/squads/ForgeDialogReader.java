package dev.frostguard.engine.squads;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.frostguard.api.domain.OcrSettingsData;
import dev.frostguard.api.domain.OcrSettingsData.TextLayout;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.vision.color.GameColors;
import dev.frostguard.vision.convert.ImageConverter;
import dev.frostguard.vision.ocr.TextLine;

/**
 * Reads the Mastery Forging dialog. A pure function of one frame, so the routine owns the taps.
 *
 * <p>The dialog is taller when the piece shows a pip bar, so everything below the stats is placed
 * relative to the "Gear Stats Up" line. Any field that can't be read makes the whole read null:
 * a spend must never go ahead on a partial read.
 */
public final class ForgeDialogReader {

    private static final Pattern PIECE = Pattern.compile("(Sniper|Saboteur|Guardian)\\s+(Goggles|Gauntlets|Belt|Boots)");
    private static final Pattern PERCENT = Pattern.compile("(\\d+)%");
    private static final Pattern BARE_ZERO = Pattern.compile("\\b0\\b(?!%)");
    private static final Pattern COUNT = Pattern.compile("(\\d+)/(\\d+)");
    private static final Pattern NEEDED_ONLY = Pattern.compile("/(\\d+)");
    // The balance prints white when it covers the cost and red when it doesn't, so a white read
    // holds both numbers only when the forge is affordable.
    private static final OcrSettingsData WHITE_STONE_COUNT = OcrSettingsData.builder()
            .textLayout(TextLayout.SINGLE_LINE).allowedGlyphs("0123456789/")
            .isolateForeground(true).targetColor(Color.WHITE).build();
    private static final OcrSettingsData RED_STONE_BALANCE = OcrSettingsData.builder()
            .textLayout(TextLayout.SINGLE_LINE).allowedGlyphs("0123456789")
            .isolateForeground(true).targetColor(new Color(235, 50, 55)).build();

    // From the top of the "Gear Stats Up" line to the stone count under the stone icon.
    private static final int COUNT_TOP_OFFSET = 164;
    private static final int COUNT_BOTTOM_OFFSET = 194;
    // The count is right-aligned under the stone icon, so a bigger balance grows to the left: 591 starts
    // near x=339 and 1,591 further still. The search is wide and the strip is cut to the ink inside it.
    private static final int COUNT_LEFT = 300;
    private static final int COUNT_RIGHT = 420;
    private static final int MIN_INK_PER_LINE = 2;
    private static final int MAX_INK_WIDTH = 100;
    private static final int FORGE_BUTTON_X = 360;
    private static final int FORGE_BUTTON_OFFSET = 267;
    // The "+N" badge on the Gear Details icon; wide enough for "+100", clear of the class shield.
    private static final int NAME_LEFT = 195;
    private static final int NAME_TOP = 245;
    private static final int NAME_RIGHT = 510;
    private static final int NAME_BOTTOM = 295;
    private static final int BADGE_LEFT = 122;
    private static final int BADGE_TOP = 252;
    private static final int BADGE_RIGHT = 186;
    private static final int BADGE_BOTTOM = 280;

    /** {@code stonesHave} is the balance and {@code stonesNeeded} the cost of this one forge. */
    public record ForgeDialog(String piece, int masteryPercent, int nextMasteryPercent,
                              int stonesHave, int stonesNeeded) {
    }

    /** {@code action} is the right-hand button, Enhance below +100 and Ascend at it; null when it didn't read. */
    public record GearDetails(String piece, int enhancement, String action) {
    }

    private ForgeDialogReader() {
    }

    /** Null unless the dialog is open and every field read cleanly. */
    public static ForgeDialog read(RawImageData frame) {
        List<TextLine> lines = SquadOcr.lines(frame, 60, 230, 660, 1060);
        // The white-on-blue title is dropped by most reads; this line is only on this dialog.
        if (lines.stream().noneMatch(l -> l.text().contains("Mastery Level"))) return null;
        String piece = piece(lines);
        TextLine stats = lines.stream().filter(l -> l.text().contains("Stats Up")).findFirst().orElse(null);
        if (piece == null || stats == null) return null;
        int[] percents = percents(stats.text());
        int top = stats.top() + COUNT_TOP_OFFSET;
        int bottom = stats.top() + COUNT_BOTTOM_OFFSET;
        int[] ink = countInk(frame, top, bottom);
        if (percents == null || ink == null) return null;
        int[] count = agreedCount(frame, ink);
        if (count == null) return null;
        return new ForgeDialog(piece, percents[0], percents[1], count[0], count[1]);
    }

    /**
     * The count is read from two crops cut tight to its ink at different margins, and a balance is only
     * trusted when both read the same. Rows above the digits (the stone's hammer and rim) make Tesseract
     * invent a digit: a real 51 read as 531, 91 and 591 from one fixed crop. Disagreement reads as unknown.
     */
    private static int[] agreedCount(RawImageData frame, int[] ink) {
        int[] tight = count(frame, ink[2] - 5, ink[0], ink[3] + 6, ink[1] + 2);
        if (tight == null) return null;
        int[] loose = count(frame, ink[2] - 10, ink[0] - 2, ink[3] + 10, ink[1] + 2);
        return loose != null && loose[0] == tight[0] && loose[1] == tight[1] ? tight : null;
    }

    private static int[] count(RawImageData frame, int left, int top, int right, int bottom) {
        return count(SquadOcr.text(frame, left, top, right, bottom, WHITE_STONE_COUNT),
                SquadOcr.text(frame, left, top, right, bottom, RED_STONE_BALANCE));
    }

    /**
     * {firstRow, lastRow, firstColumn, lastColumn} of the stone count's ink, or null when none is found. The
     * rows come from the white ink, because the "/40" is always white while the balance is red when short, and
     * the red hammer on the stone sits above them. The columns are then every white or red column in those
     * rows, so a three or four digit balance is never cut off at a fixed edge: the count is right-aligned.
     */
    static int[] countInk(RawImageData frame, int top, int bottom) {
        if (frame == null || !frame.isValid()) return null;
        java.awt.image.BufferedImage image = ImageConverter.toBufferedImage(frame);
        int left = Math.max(0, COUNT_LEFT);
        int right = Math.min(image.getWidth(), COUNT_RIGHT);
        int from = Math.max(0, top);
        int to = Math.min(image.getHeight(), bottom);
        int firstRow = -1, lastRow = -1;
        for (int y = from; y < to; y++) {
            int white = 0;
            for (int x = left; x < right; x++) if (GameColors.isLabelWhite(image.getRGB(x, y))) white++;
            if (white >= MIN_INK_PER_LINE) {
                if (firstRow < 0) firstRow = y;
                lastRow = y;
            }
        }
        if (firstRow < 0) return null;
        int firstCol = -1, lastCol = -1;
        for (int x = left; x < right; x++) {
            int ink = 0;
            for (int y = firstRow; y <= lastRow; y++) {
                int rgb = image.getRGB(x, y);
                if (GameColors.isLabelWhite(rgb) || isBalanceRed(rgb)) ink++;
            }
            if (ink >= MIN_INK_PER_LINE) {
                if (firstCol < 0) firstCol = x;
                lastCol = x;
            }
        }
        if (firstCol < 0 || lastCol - firstCol > MAX_INK_WIDTH) return null;
        return new int[] { firstRow, lastRow, firstCol, lastCol };
    }

    private static boolean isBalanceRed(int rgb) {
        return ((rgb >> 16) & 0xFF) > 190 && ((rgb >> 8) & 0xFF) < 110 && (rgb & 0xFF) < 110;
    }

    /** The centre of the Forge button, or null unless the dialog is open. */
    public static PointData forgeButton(RawImageData frame) {
        return SquadOcr.lines(frame, 60, 230, 660, 1060).stream()
                .filter(l -> l.text().contains("Stats Up")).findFirst()
                .map(stats -> new PointData(FORGE_BUTTON_X, stats.top() + FORGE_BUTTON_OFFSET))
                .orElse(null);
    }

    /** True when Gear Details is open, so the Mastery Forging button is where it should be. */
    public static boolean isGearDetails(RawImageData frame) {
        String buttons = String.join(" ", SquadOcr.lines(frame, 60, 770, 660, 860).stream()
                .map(TextLine::text).toList());
        return buttons.contains("Mastery Forging")
                && (buttons.contains(EnhanceDialogReader.ENHANCE_LABEL) || buttons.contains(EnhanceDialogReader.ASCEND_LABEL));
    }

    /** The piece and its "+N" badge, or null unless Gear Details is open and both read cleanly. */
    public static GearDetails readGearDetails(RawImageData frame) {
        if (!isGearDetails(frame)) return null;
        String piece = piece(SquadOcr.lines(frame, 60, 230, 510, 360));
        // The icon's "+N" and "Lv." text can pull the name out of the block read, as on Molly's +69 Boots; its own line still reads.
        if (piece == null) piece = piece(SquadOcr.text(frame, NAME_LEFT, NAME_TOP, NAME_RIGHT, NAME_BOTTOM, SquadOcr.LINE));
        Integer plus = HeroScreenReader.enhancement(
                SquadOcr.text(frame, BADGE_LEFT, BADGE_TOP, BADGE_RIGHT, BADGE_BOTTOM, SquadOcr.WHITE_PLUS_NUMBER),
                SquadOcr.text(frame, BADGE_LEFT, BADGE_TOP, BADGE_RIGHT, BADGE_BOTTOM, SquadOcr.PLUS_NUMBER));
        return piece == null || plus == null ? null : new GearDetails(piece, plus, action(frame));
    }

    private static String action(RawImageData frame) {
        String label = EnhanceDialogReader.detailsButtonLabel(frame);
        return EnhanceDialogReader.ENHANCE_LABEL.equals(label) || EnhanceDialogReader.ASCEND_LABEL.equals(label) ? label : null;
    }

    private static String piece(List<TextLine> lines) {
        for (TextLine line : lines) {
            String piece = piece(line.text());
            if (piece != null) return piece;
        }
        return null;
    }

    private static String piece(String text) {
        Matcher m = PIECE.matcher(text);
        return m.find() ? m.group(1) + " " + m.group(2) : null;
    }

    /**
     * {current, next}. Only numbers with a % count, because the arrow between them can read as a
     * digit ("10% 2 20%"). The game prints a current mastery of zero as a bare "0". A step other
     * than the game's 2% pip or 10% level means a misread.
     */
    static int[] percents(String line) {
        List<Integer> found = new ArrayList<>();
        Matcher m = PERCENT.matcher(line);
        while (m.find()) found.add(Integer.parseInt(m.group(1)));
        if (found.size() == 1 && BARE_ZERO.matcher(line).find()) found.addFirst(0);
        if (found.size() != 2) return null;
        int step = found.get(1) - found.get(0);
        return step == 2 || step == 10 ? new int[] { found.get(0), found.get(1) } : null;
    }

    /** {have, needed}, or null when the numbers contradict the colour they were printed in. */
    static int[] count(String white, String red) {
        Matcher both = COUNT.matcher(white);
        if (both.find()) {
            int have = Integer.parseInt(both.group(1));
            int needed = Integer.parseInt(both.group(2));
            return have >= needed ? new int[] { have, needed } : null;
        }
        Matcher neededOnly = NEEDED_ONLY.matcher(white);
        Integer have = SquadOcr.smallNumber(red);
        if (!neededOnly.find() || have == null) return null;
        int needed = Integer.parseInt(neededOnly.group(1));
        return have < needed ? new int[] { have, needed } : null;
    }
}
