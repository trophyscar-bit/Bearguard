package dev.frostguard.tasks.pets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Objects;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.vision.color.ColorBlobFinder;
import dev.frostguard.vision.color.GameColors;

/**
 * Real-frame coverage for My Island claim detection.
 *
 * <p>Both frames are live 720x1280 captures taken while the routine itself was running. The island
 * frame carries three badges - two over crafting stations and one over the tree, partly covered by
 * the game's own tutorial hand - at positions no fixed-coordinate version of this task could have
 * hit: the routine used to tap (362,488), (501,501) and (570,550) blind.
 */
class IslandClaimBadgesFrameTest {

    @Test
    void findsEveryBadgeIncludingTheOneOverTheTree() throws Exception {
        List<ColorBlobFinder.Blob> found = badges(island());

        assertEquals(3, found.size());
        assertTrue(found.stream().anyMatch(blob -> near(blob, 659, 611)), "right station");
        assertTrue(found.stream().anyMatch(blob -> near(blob, 538, 696)), "left station");
        assertTrue(found.stream().anyMatch(blob -> near(blob, 357, 349)), "tree");
    }

    @Test
    void rejectsEveryOtherGreenThingOnTheIsland() throws Exception {
        List<ColorBlobFinder.Blob> rejected = IslandClaimBadges.candidates(island()).stream()
                .filter(blob -> !IslandClaimBadges.isClaimBadge(blob))
                .toList();

        assertTrue(rejected.isEmpty(), "nothing else on the island should look like a crystal: " + rejected);
    }

    @Test
    void keepsTheTreeBadgeWhollyInsideTheSearchWindow() throws Exception {
        // The badge over the tree floats higher than any other and is the one the window can clip.
        // A clipped crystal loses a fifth of its pixels and shrinks toward the height floor, so every
        // badge found must still be full height.
        for (ColorBlobFinder.Blob blob : badges(island())) {
            assertTrue(blob.height() >= 50, "badge is clipped by the search window: " + blob);
        }
    }

    @Test
    void recognisesTheIslandScreenAndRefusesTheWorldMap() throws Exception {
        assertTrue(IslandClaimBadges.onIslandScreen(island()));
        assertFalse(IslandClaimBadges.onIslandScreen(worldMap()));
    }

    @Test
    void theWorldMapCarriesABadgeShapedIconThatOnlyTheScreenCheckRejects() throws Exception {
        // A failed navigation leaves the world map up, where the marching panel's gathering icon
        // passes every crystal rule. Tapping it starts a march, so the screen check - not the shape
        // rules - is what makes a wrong screen harmless.
        assertFalse(badges(worldMap()).isEmpty(), "the decoy must still be present for this to prove anything");
        assertFalse(IslandClaimBadges.onIslandScreen(worldMap()));
    }

    @Test
    void theHudCounterWouldPassTheShapeRulesIfTheWindowLetItThrough() throws Exception {
        // The counter in the top bar is the same artwork as a badge. Only the search window keeps it
        // from becoming a tap target, so that exclusion must never be widened past it.
        List<ColorBlobFinder.Blob> hud = ColorBlobFinder.find(
                island(), AreaData.of(0, 0, 720, 200), GameColors::isVividGreen, 400);

        assertTrue(hud.stream().anyMatch(IslandClaimBadges::isClaimBadge),
                "expected the HUD crystal to look exactly like a badge");
        assertTrue(badges(island()).stream().allMatch(blob -> blob.centre().row() > 200));
    }

    @Test
    void dropsARewardCrystalInFlightAndKeepsABouncingBadge() {
        ColorBlobFinder.Blob badgeBefore = blobAt(400, 500);
        ColorBlobFinder.Blob badgeAfter = blobAt(404, 502);
        ColorBlobFinder.Blob flyerBefore = blobAt(560, 300);
        ColorBlobFinder.Blob flyerAfter = blobAt(566, 260);

        List<ColorBlobFinder.Blob> settled = IslandClaimBadges.settled(
                List.of(badgeBefore, flyerBefore), List.of(badgeAfter, flyerAfter));

        assertEquals(List.of(badgeAfter), settled);
    }

    private static ColorBlobFinder.Blob blobAt(int col, int row) {
        return new ColorBlobFinder.Blob(new PointData(col, row), 1300, 40, 57, 0.59);
    }

    private List<ColorBlobFinder.Blob> badges(BufferedImage frame) {
        return IslandClaimBadges.candidates(frame).stream()
                .filter(IslandClaimBadges::isClaimBadge)
                .toList();
    }

    private static boolean near(ColorBlobFinder.Blob blob, int col, int row) {
        return Math.abs(blob.centre().col() - col) <= 5 && Math.abs(blob.centre().row() - row) <= 5;
    }

    private BufferedImage island() throws Exception {
        return load("/pets/my-island-claim-badges-20260823.png");
    }

    private BufferedImage worldMap() throws Exception {
        return load("/pets/world-map-not-island-20260823.png");
    }

    private BufferedImage load(String resource) throws Exception {
        return ImageIO.read(Objects.requireNonNull(getClass().getResourceAsStream(resource)));
    }
}
