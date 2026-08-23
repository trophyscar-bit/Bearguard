package dev.frostguard.tasks.pets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Objects;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import dev.frostguard.vision.color.ColorBlobFinder;

/**
 * Real-frame coverage for My Island claim detection.
 *
 * <p>The frame is a live 720x1280 capture taken while the routine itself was on the island, with the
 * chat strip blanked. It holds two claim badges over crafting stations and none over the tree, at
 * positions no fixed-coordinate version of this task could have hit: the coordinates the routine
 * used to tap blind were (362,488), (501,501) and (570,550).
 */
class IslandClaimBadgesFrameTest {

    @Test
    void findsBothStationBadgesAndNothingElse() throws Exception {
        List<ColorBlobFinder.Blob> accepted = badges();

        assertEquals(2, accepted.size(), "the frame shows exactly two claim badges");
        assertTrue(accepted.stream().anyMatch(blob -> near(blob, 539, 696)));
        assertTrue(accepted.stream().anyMatch(blob -> near(blob, 660, 611)));
    }

    @Test
    void rejectsEveryOtherGreenThingOnTheFrame() throws Exception {
        List<ColorBlobFinder.Blob> rejected = IslandClaimBadges.candidates(loadFrame()).stream()
                .filter(blob -> !IslandClaimBadges.isClaimBadge(blob))
                .toList();

        assertTrue(rejected.isEmpty(),
                "no green artwork on the island should survive the crystal rules, got " + rejected);
    }

    @Test
    void ignoresTheHudCounterCarryingTheSameCrystal() throws Exception {
        // The Life Essence counter in the top bar is the same artwork as the badges. It must never
        // become a tap target, which the search window is what prevents.
        assertTrue(badges().stream().allMatch(blob -> blob.centre().row() > 340));
    }

    @Test
    void tapsTheBadgeItselfNotTheBuildingUnderIt() throws Exception {
        // The centroid is the tap target, so it has to land on the crystal rather than drift onto the
        // bubble's tail or the station beneath.
        for (ColorBlobFinder.Blob blob : badges()) {
            assertTrue(blob.fillRatio() >= 0.45,
                    "a centroid is only a safe tap target on a densely filled blob: " + blob);
        }
    }

    private List<ColorBlobFinder.Blob> badges() throws Exception {
        return IslandClaimBadges.candidates(loadFrame()).stream()
                .filter(IslandClaimBadges::isClaimBadge)
                .toList();
    }

    private static boolean near(ColorBlobFinder.Blob blob, int col, int row) {
        return Math.abs(blob.centre().col() - col) <= 5 && Math.abs(blob.centre().row() - row) <= 5;
    }

    private BufferedImage loadFrame() throws Exception {
        return ImageIO.read(Objects.requireNonNull(
                getClass().getResourceAsStream("/pets/my-island-claim-badges-20260823.png")));
    }
}
