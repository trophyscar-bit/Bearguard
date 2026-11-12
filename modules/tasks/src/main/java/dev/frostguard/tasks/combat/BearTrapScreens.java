package dev.frostguard.tasks.combat;

import java.util.List;
import java.util.Optional;
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
    /** The "Marching 6/6" panel down the left of the world map. */
    static final AreaData MARCH_PANEL_TEXT = new AreaData(new PointData(0, 200), new PointData(260, 300));
    /** The coordinate bar above the bottom menu on the world map. */
    static final AreaData MAP_COORDINATE_BAR = new AreaData(new PointData(150, 1040), new PointData(520, 1100));

    /** The "Confirmation" dialog that Quick Use opens on the Pet Skill popup. */
    static final AreaData BATTLE_SKILLS_QUESTION = new AreaData(new PointData(60, 470), new PointData(660, 560));
    static final AreaData BATTLE_SKILLS_USE_BUTTON = new AreaData(new PointData(400, 790), new PointData(620, 840));
    static final PointData BATTLE_SKILLS_USE_CENTER = new PointData(512, 815);

    private static final Pattern TRAP_TITLE = Pattern.compile("hunt\\w*\\s*trap\\s*([0-9Il|!])", Pattern.CASE_INSENSITIVE);
    // White coordinates over artwork read as "K:786. Y:236" or "x:.752 Y:234", so X may come back as K.
    private static final Pattern MAP_COORDS = Pattern.compile("[xk]\\W{0,3}(\\d{2,4})\\D{0,3}y\\W{0,3}(\\d{2,4})", Pattern.CASE_INSENSITIVE);
    // The level prefix is optional and loose: the popup's "Lv. 1" was read as "Ly. 1" on Nov 12 and a
    // popup that was really open got rejected. The chat line "Rally and defeat Raging Bear" has no
    // number before it, so it still does not match.
    private static final Pattern RAGING_BEAR = Pattern.compile("(?:l\\w?\\W{0,2}\\s*)?\\d+\\s*raging\\s*bear", Pattern.CASE_INSENSITIVE);
    private static final Pattern MARCH_QUEUE = Pattern.compile("march\\w*\\W{0,3}\\s*(\\d)\\s*/\\s*(\\d)", Pattern.CASE_INSENSITIVE);
    private static final Pattern NOT_ELIGIBLE = Pattern.compile("eligib|inelig", Pattern.CASE_INSENSITIVE);
    private static final Pattern BATTLE_SKILLS = Pattern.compile("use\\s*all\\s*available\\s*battle\\s*skill", Pattern.CASE_INSENSITIVE);

    private BearTrapScreens() {
    }

    /** Centre of the Go button on the row titled "Hunting Trap N", if that row is on screen. */
    static Optional<PointData> trapGoButton(List<TextLine> lines, int trapNumber) {
        return trapTitle(lines, trapNumber).map(title -> new PointData(GO_X, title.top() + GO_BELOW_TITLE));
    }

    /**
     * Where that row prints its trap's coordinates ("X:786, Y:236", white over the trap picture,
     * about 115 px below the title). Read it with white-text OCR.
     */
    static Optional<AreaData> trapRowCoordinatesArea(List<TextLine> lines, int trapNumber) {
        return trapTitle(lines, trapNumber).map(title -> new AreaData(
                new PointData(40, title.top() + 100), new PointData(195, title.top() + 135)));
    }

    /**
     * Where the Raging Bear popup prints the bear's coordinates ("X:752 Y:234", white over the bear
     * picture, left of centre and about 45 px above the title). Read it with white-text OCR.
     */
    static Optional<AreaData> bearPopupCoordinatesArea(List<TextLine> lines) {
        for (TextLine line : lines) {
            if (RAGING_BEAR.matcher(normalise(line.text())).find()) {
                int centre = line.left() + line.width() / 2;
                return Optional.of(new AreaData(
                        new PointData(Math.max(0, centre - 200), Math.max(0, line.top() - 50)),
                        new PointData(centre - 30, line.top() - 10)));
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

    /** True when the "Marching 6/6" panel on the map shows every march slot in use. */
    static boolean marchQueueFull(List<TextLine> lines) {
        for (TextLine line : lines) {
            java.util.regex.Matcher m = MARCH_QUEUE.matcher(line.text());
            if (m.find() && m.group(1).equals(m.group(2)) && !"0".equals(m.group(1))) return true;
        }
        return false;
    }

    /** The game refusing a rally because this account already took part in this bear. */
    static boolean saysNotEligible(List<TextLine> lines) {
        return anyMatches(lines, NOT_ELIGIBLE);
    }

    /** The free "Use all available Battle Skills?" confirmation that Quick Use opens. */
    static boolean isBattleSkillsConfirmation(List<TextLine> lines) {
        return anyMatches(lines, BATTLE_SKILLS);
    }

    /**
     * Whether the bear on screen is the configured trap's. Both traps' popups say "Lv. 1 Raging
     * Bear", so only coordinates tell them apart: the popup's own, or failing that the camera bar
     * read straight after Go. Without the trap's coordinates from its Special Buildings row there
     * is nothing to compare against, and the answer is no.
     */
    static boolean isConfiguredTrap(Optional<PointData> trap, Optional<PointData> popup, Optional<PointData> camera) {
        if (trap.isEmpty()) {
            return false;
        }
        if (popup.isPresent()) {
            return near(popup.get(), trap.get(), TRAP_TOLERANCE);
        }
        return camera.isPresent() && near(camera.get(), trap.get(), TRAP_TOLERANCE);
    }

    static final int TRAP_TOLERANCE = 2;

    static boolean near(PointData a, PointData b, int tolerance) {
        return Math.abs(a.getX() - b.getX()) <= tolerance && Math.abs(a.getY() - b.getY()) <= tolerance;
    }

    private static Optional<TextLine> trapTitle(List<TextLine> lines, int trapNumber) {
        for (TextLine line : lines) {
            Matcher m = TRAP_TITLE.matcher(normalise(line.text()));
            if (m.find() && trapDigit(m.group(1)) == trapNumber) {
                return Optional.of(line);
            }
        }
        return Optional.empty();
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
