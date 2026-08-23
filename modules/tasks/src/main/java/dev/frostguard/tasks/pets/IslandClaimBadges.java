package dev.frostguard.tasks.pets;

import java.awt.image.BufferedImage;
import java.util.List;

import dev.frostguard.engine.nav.CommonGameAreas;
import dev.frostguard.vision.color.ColorBlobFinder;
import dev.frostguard.vision.color.GameColors;

/**
 * Finds the claim badges on a My Island frame.
 *
 * <p>A badge is a bubble carrying the game's Life Essence crystal. The tree and every crafting
 * station can each show one, at whatever position that player arranged their island in, so the
 * island is scanned rather than searched at fixed points.
 *
 * <p>Template matching is the wrong signal here. The bubbles bounce, so a freshly cut template of
 * one only self-matched at ~0.84 against a 0.90 threshold, and the badge over the tree is different
 * artwork from the ones over the stations. The crystal inside is what every badge shares, and its
 * size identifies it.
 *
 * <p>The thresholds come from 29 badge sightings across 15 live 720x1280 island frames: the crystal
 * covers 913-1476 green pixels in a 35-42 x 37-59 box at 0.54-0.70 fill, the height varying because
 * the bubble bounces. Every other green thing on the same frames topped out at 214 pixels in a
 * 19x21 box, so the gap is more than fourfold on area alone. Do not calibrate this against the
 * bundled claim templates: they are cut at a smaller scale than the game renders and measure only
 * 22-24 x 33, which is why a template-derived window found nothing on real frames.
 *
 * <p>The window also keeps the routine harmless if it ever scans the wrong screen. The city view's
 * green villager markers measure 25x25 and 9x52, both rejected on width and area.
 */
final class IslandClaimBadges {

    private static final int MIN_CRYSTAL_PIXELS = 600;
    private static final int CRYSTAL_MIN_WIDTH = 28;
    private static final int CRYSTAL_MAX_WIDTH = 52;
    private static final int CRYSTAL_MIN_HEIGHT = 30;
    private static final int CRYSTAL_MAX_HEIGHT = 70;
    private static final double CRYSTAL_MIN_FILL_RATIO = 0.45;
    private static final int MIN_ANCHOR_PIXELS = 400;
    private static final int SETTLED_RADIUS = 15;

    private IslandClaimBadges() {}

    /**
     * Every green blob on the island large enough to be worth judging, largest first. Callers filter
     * with {@link #isClaimBadge} so that what was rejected can be logged.
     */
    static List<ColorBlobFinder.Blob> candidates(BufferedImage frame) {
        return ColorBlobFinder.find(frame, CommonGameAreas.ISLAND_CLAIM_BADGE_AREA,
                GameColors::isVividGreen, MIN_CRYSTAL_PIXELS);
    }

    /** Whether a candidate blob has the crystal's dimensions and density. */
    static boolean isClaimBadge(ColorBlobFinder.Blob blob) {
        return blob.width() >= CRYSTAL_MIN_WIDTH && blob.width() <= CRYSTAL_MAX_WIDTH
                && blob.height() >= CRYSTAL_MIN_HEIGHT && blob.height() <= CRYSTAL_MAX_HEIGHT
                && blob.fillRatio() >= CRYSTAL_MIN_FILL_RATIO;
    }

    /**
     * Whether the frame really shows My Island, proved by the essence counter in the top bar.
     *
     * <p>Without this the detector would happily work on whatever screen a failed navigation left
     * behind. The world map's marching panel carries a 36x37 green gathering icon at 0.46 fill that
     * passes every crystal rule, and tapping it starts a march. Across 30 live frames the counter is
     * present on every island frame and absent from every world and city frame.
     */
    static boolean onIslandScreen(BufferedImage frame) {
        return ColorBlobFinder.find(frame, CommonGameAreas.ISLAND_ESSENCE_COUNTER,
                        GameColors::isVividGreen, MIN_ANCHOR_PIXELS)
                .stream()
                .anyMatch(IslandClaimBadges::isClaimBadge);
    }

    /**
     * The badges that held still between two captures.
     *
     * <p>Claiming sends a reward crystal flying from the badge up to the counter, and it is the same
     * colour and size as a badge with no bubble around it, so a scan landing mid-flight would count
     * empty sky as a claim and tap it. A real badge keeps its place, bouncing at most 8px between
     * captures, while a reward crystal covers roughly 30px in the same interval.
     */
    static List<ColorBlobFinder.Blob> settled(List<ColorBlobFinder.Blob> earlier,
            List<ColorBlobFinder.Blob> later) {
        List<ColorBlobFinder.Blob> earlierBadges = earlier.stream().filter(IslandClaimBadges::isClaimBadge).toList();
        return later.stream()
                .filter(IslandClaimBadges::isClaimBadge)
                .filter(blob -> earlierBadges.stream().anyMatch(
                        before -> before.centre().manhattanDistanceTo(blob.centre()) <= SETTLED_RADIUS))
                .toList();
    }
}
