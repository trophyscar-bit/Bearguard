package dev.frostguard.tasks.combat;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.vision.ocr.TextLine;

/**
 * Reads the Bear Trap screens from OCR lines, so every tap in the own-rally and pet flows is made
 * on a screen the bot has recognised rather than on a fixed point it hopes is still right.
 */
final class BearTrapScreens {

    /** Alliance Territory, Special Buildings tab: the building rows, header to bottom edge. */
    static final AreaData SPECIAL_BUILDINGS_LIST = new AreaData(new PointData(190, 230), new PointData(560, 1240));
    /** A row's Go button sits this far below the top of its "Hunting Trap N" title. */
    static final int GO_BELOW_TITLE = 91;
    static final int GO_X = 590;

    /** The tile popup over the world map. The chat ticker below 1100 is left out on purpose. */
    static final AreaData MAP_POPUP_TEXT = new AreaData(new PointData(100, 150), new PointData(620, 1100));
    /** The coordinate bar above the bottom menu on the world map. */
    static final AreaData MAP_COORDINATE_BAR = new AreaData(new PointData(150, 1040), new PointData(520, 1100));

    /** The "Confirmation" dialog that Quick Use opens on the Pet Skill popup. */
    static final AreaData BATTLE_SKILLS_QUESTION = new AreaData(new PointData(60, 470), new PointData(660, 560));
    static final AreaData BATTLE_SKILLS_USE_BUTTON = new AreaData(new PointData(400, 790), new PointData(620, 840));
    static final PointData BATTLE_SKILLS_USE_CENTER = new PointData(512, 815);

    private static final Pattern TRAP_TITLE = Pattern.compile("hunt\\w*\\s*trap\\s*([0-9Il|!])", Pattern.CASE_INSENSITIVE);
    private static final Pattern MAP_COORDS = Pattern.compile("x\\s*[:;.]?\\s*(\\d{2,4})\\D{0,3}y\\s*[:;.]?\\s*(\\d{2,4})", Pattern.CASE_INSENSITIVE);
    private static final Pattern RAGING_BEAR = Pattern.compile("lv\\W{0,2}\\s*\\d+\\s*raging\\s*bear", Pattern.CASE_INSENSITIVE);
    private static final Pattern NOT_ELIGIBLE = Pattern.compile("eligib|inelig", Pattern.CASE_INSENSITIVE);
    private static final Pattern BATTLE_SKILLS = Pattern.compile("use\\s*all\\s*available\\s*battle\\s*skill", Pattern.CASE_INSENSITIVE);

    private BearTrapScreens() {
    }

    /** Centre of the Go button on the row titled "Hunting Trap N", if that row is on screen. */
    static Optional<PointData> trapGoButton(List<TextLine> lines, int trapNumber) {
        for (TextLine line : lines) {
            Matcher m = TRAP_TITLE.matcher(normalise(line.text()));
            if (m.find() && trapDigit(m.group(1)) == trapNumber) {
                return Optional.of(new PointData(GO_X, line.top() + GO_BELOW_TITLE));
            }
        }
        return Optional.empty();
    }

    /** The "X:752 Y:234" coordinates shown in the lines, if any. */
    static Optional<PointData> mapCoordinates(List<TextLine> lines) {
        for (TextLine line : lines) {
            Matcher m = MAP_COORDS.matcher(normalise(line.text()));
            if (m.find()) {
                return Optional.of(new PointData(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))));
            }
        }
        return Optional.empty();
    }

    /** True for the bear's own popup ("Lv. 1 Raging Bear"); the chat "Rally and defeat Raging Bear" is not it. */
    static boolean isRagingBearPopup(List<TextLine> lines) {
        return anyMatches(lines, RAGING_BEAR);
    }

    /** The game refusing a rally because this account already took part in this bear. */
    static boolean saysNotEligible(List<TextLine> lines) {
        return anyMatches(lines, NOT_ELIGIBLE);
    }

    /** The free "Use all available Battle Skills?" confirmation that Quick Use opens. */
    static boolean isBattleSkillsConfirmation(List<TextLine> lines) {
        return anyMatches(lines, BATTLE_SKILLS);
    }

    static boolean near(PointData a, PointData b, int tolerance) {
        return Math.abs(a.getX() - b.getX()) <= tolerance && Math.abs(a.getY() - b.getY()) <= tolerance;
    }

    private static boolean anyMatches(List<TextLine> lines, Pattern pattern) {
        StringBuilder all = new StringBuilder();
        for (TextLine line : lines) {
            String text = normalise(line.text());
            if (pattern.matcher(text).find()) {
                return true;
            }
            all.append(text).append(' ');
        }
        // A sentence the reader split across two lines still counts.
        return pattern.matcher(all).find();
    }

    private static int trapDigit(String s) {
        char c = s.charAt(0);
        return Character.isDigit(c) ? c - '0' : 1; // I, l, | and ! are how OCR misreads a 1
    }

    private static String normalise(String text) {
        return text == null ? "" : text;
    }
}
