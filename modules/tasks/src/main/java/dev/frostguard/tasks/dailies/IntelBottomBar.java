package dev.frostguard.tasks.dailies;

import java.awt.image.BufferedImage;

import dev.frostguard.engine.nav.CommonGameAreas;
import dev.frostguard.vision.color.GameColors;
import dev.frostguard.vision.color.PixelStats;

/**
 * Reads the state of the Intel screen's two bottom buttons from their colour.
 *
 * <p>Execute All (left) runs every mission that needs no troops in one tap, for their combined
 * stamina; it is blue when there is something to run. Claim All (right) collects finished missions;
 * it is green when there is something to collect. Greyed out, both measure zero coloured pixels, so
 * the bar sits far from either side.
 */
final class IntelBottomBar {

    // Lit buttons measured 15,628 (blue) and 16,053 (green) of 21,700 pixels; greyed, exactly 0.
    static final int LIT_PIXEL_MIN = 3_000;

    private IntelBottomBar() {
    }

    static boolean executeAllReady(BufferedImage frame) {
        return PixelStats.count(frame, CommonGameAreas.INTEL_EXECUTE_ALL_BUTTON, GameColors::isActionBlue)
                >= LIT_PIXEL_MIN;
    }

    static boolean claimAllReady(BufferedImage frame) {
        return PixelStats.count(frame, CommonGameAreas.INTEL_CLAIM_ALL_BUTTON, GameColors::isVividGreen)
                >= LIT_PIXEL_MIN;
    }
}
