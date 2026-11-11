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
    private static final int COUNT_LEFT = 335;
    private static final int COUNT_RIGHT = 415;
    private static final int FORGE_BUTTON_X = 360;
    private static final int FORGE_BUTTON_OFFSET = 267;
    // The "+N" badge on the Gear Details icon; wide enough for "+100", clear of the class shield.
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
        int[] count = count(SquadOcr.text(frame, COUNT_LEFT, top, COUNT_RIGHT, bottom, WHITE_STONE_COUNT),
                SquadOcr.text(frame, COUNT_LEFT, top, COUNT_RIGHT, bottom, RED_STONE_BALANCE));
        if (percents == null || count == null) return null;
        return new ForgeDialog(piece, percents[0], percents[1], count[0], count[1]);
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
            Matcher m = PIECE.matcher(line.text());
            if (m.find()) return m.group(1) + " " + m.group(2);
        }
        return null;
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
