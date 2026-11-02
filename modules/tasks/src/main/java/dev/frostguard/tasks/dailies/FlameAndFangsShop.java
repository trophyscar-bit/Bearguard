package dev.frostguard.tasks.dailies;

import java.awt.image.BufferedImage;

import dev.frostguard.engine.nav.CommonGameAreas;
import dev.frostguard.vision.color.GameColors;
import dev.frostguard.vision.color.PixelStats;

/**
 * Reads the Flame and Fangs shop's top row, Fire Crystals, from its colour.
 *
 * <p>Redeem spends at once, with no confirmation. Once it has been tapped, a "×N" pill opens beside
 * it that spends everything still affordable in one more tap.
 */
final class FlameAndFangsShop {

    // Measured 2 November: Redeem 4,674 of 6,500 pixels; the pill 6,241 of 7,420 when open, 0 when not.
    static final int LIT_PIXEL_MIN = 3_000;

    private FlameAndFangsShop() {
    }

    static boolean redeemLit(BufferedImage frame) {
        return PixelStats.count(frame, CommonGameAreas.FLAME_FANGS_TOP_ROW_REDEEM, GameColors::isIntelActionBlue)
                >= LIT_PIXEL_MIN;
    }

    static boolean bulkOffered(BufferedImage frame) {
        return PixelStats.count(frame, CommonGameAreas.FLAME_FANGS_TOP_ROW_BULK, GameColors::isIntelActionBlue)
                >= LIT_PIXEL_MIN;
    }
}
