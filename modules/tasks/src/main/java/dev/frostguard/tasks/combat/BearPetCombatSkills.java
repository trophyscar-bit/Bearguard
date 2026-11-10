package dev.frostguard.tasks.combat;

import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.vision.color.PixelStats;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Reads the Pet Skill popup for Bear Trap: which tiles are combat skills, and whether the selected
 * skill can be used right now.
 *
 * <p>The popup has no Quick Use any more. A skill has to be tapped first, and only then does Use
 * turn blue. Combat skills carry a red crossed-swords badge on the tile's top-left corner; growth
 * skills carry a green hammer. Tiles sit on a fixed 4-column grid, 146 px apart.
 */
final class BearPetCombatSkills {

    private static final int[] COLUMN_CENTERS_X = {141, 287, 433, 579};
    private static final int[] ROW_CENTERS_Y = {306, 452, 598};
    private static final int BADGE_OFFSET_X = -48;
    private static final int BADGE_OFFSET_Y = -46;
    private static final int BADGE_HALF_SIZE = 12;
    /** A red badge fills about 48 of the 576 pixels sampled; every other tile gives 0. */
    private static final int MIN_RED_BADGE_PIXELS = 20;

    /** Empty panel strip under the third row of tiles. */
    private static final AreaData PANEL_STRIP = new AreaData(new PointData(70, 690), new PointData(649, 729));
    private static final double MIN_PANEL_SHARE = 0.9;

    // A selected growth skill shows one Use, centred. A selected COMBAT skill shows two buttons side
    // by side: Quick Use on the left and Use on the right, with a gap where the single Use's centre
    // would be. Quick Use opens a free "Use all available Battle Skills?" confirmation, which the
    // routine reads before it presses that dialog's Use.
    private static final AreaData LEFT_PROBE = new AreaData(new PointData(90, 1065), new PointData(180, 1115));
    private static final AreaData CENTRE_PROBE = new AreaData(new PointData(300, 1065), new PointData(420, 1115));
    private static final AreaData RIGHT_PROBE = new AreaData(new PointData(560, 1065), new PointData(650, 1115));
    static final PointData SINGLE_USE_CENTER = new PointData(360, 1090);
    static final PointData RIGHT_USE_CENTER = new PointData(520, 1090);
    static final PointData QUICK_USE_CENTER = new PointData(200, 1090);
    private static final AreaData QUICK_USE_PROBE = new AreaData(new PointData(60, 1062), new PointData(120, 1118));

    private BearPetCombatSkills() {
    }

    /** Whether the Pet Skill popup is open, judged by its light-blue panel. */
    static boolean isPopupOpen(BufferedImage frame) {
        int panel = PixelStats.count(frame, PANEL_STRIP, rgb -> {
            int r = red(rgb), g = green(rgb), b = blue(rgb);
            return r >= 170 && r <= 225 && g >= 190 && g <= 235 && b >= 215 && b - r >= 15;
        });
        return panel >= area(PANEL_STRIP) * MIN_PANEL_SHARE;
    }

    /** Centres of the tiles that carry a red combat badge, in reading order. */
    static List<PointData> combatTiles(BufferedImage frame) {
        List<PointData> tiles = new ArrayList<>();
        for (int y : ROW_CENTERS_Y) {
            for (int x : COLUMN_CENTERS_X) {
                int bx = x + BADGE_OFFSET_X;
                int by = y + BADGE_OFFSET_Y;
                AreaData badge = new AreaData(
                        new PointData(bx - BADGE_HALF_SIZE, by - BADGE_HALF_SIZE),
                        new PointData(bx + BADGE_HALF_SIZE - 1, by + BADGE_HALF_SIZE - 1));
                int redPixels = PixelStats.count(frame, badge, rgb -> {
                    int r = red(rgb), g = green(rgb), b = blue(rgb);
                    return r >= 170 && g <= 110 && b <= 100 && r - g >= 90;
                });
                if (redPixels >= MIN_RED_BADGE_PIXELS) {
                    tiles.add(new PointData(x, y));
                }
            }
        }
        return tiles;
    }

    /**
     * Where to press Use for the selected skill, or empty when there is no blue Use (nothing
     * selected, on cooldown, or its effect still running).
     */
    static Optional<PointData> liveUseButton(BufferedImage frame) {
        double left = blueShare(frame, LEFT_PROBE);
        double centre = blueShare(frame, CENTRE_PROBE);
        double right = blueShare(frame, RIGHT_PROBE);
        if (right >= 0.6 && left >= 0.4) {
            return Optional.of(RIGHT_USE_CENTER);
        }
        if (right < 0.1 && left < 0.1 && centre >= 0.6) {
            return Optional.of(SINGLE_USE_CENTER);
        }
        return Optional.empty();
    }

    /** True when the selected skill is a ready combat skill with a blue Quick Use beside its Use. */
    static boolean offersQuickUse(BufferedImage frame) {
        Optional<PointData> use = liveUseButton(frame);
        return use.isPresent() && use.get().getX() == RIGHT_USE_CENTER.getX()
                && blueShare(frame, QUICK_USE_PROBE) >= 0.6;
    }

    /** True when the Quick Use confirmation's own Use button is blue. */
    static boolean confirmationUseIsBlue(BufferedImage frame) {
        return blueShare(frame, BearTrapScreens.BATTLE_SKILLS_USE_BUTTON) >= 0.6;
    }

    static double blueShare(BufferedImage frame, AreaData probe) {
        int live = PixelStats.count(frame, probe, rgb -> {
            int r = red(rgb), b = blue(rgb);
            return b >= 200 && r <= 130 && b - r >= 100;
        });
        return (double) live / area(probe);
    }

    private static int area(AreaData a) {
        return (a.bottomRight().getX() - a.topLeft().getX() + 1)
                * (a.bottomRight().getY() - a.topLeft().getY() + 1);
    }

    private static int red(int rgb) {
        return (rgb >> 16) & 0xFF;
    }

    private static int green(int rgb) {
        return (rgb >> 8) & 0xFF;
    }

    private static int blue(int rgb) {
        return rgb & 0xFF;
    }
}
