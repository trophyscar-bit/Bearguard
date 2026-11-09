package dev.frostguard.tasks.city;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.util.Objects;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import dev.frostguard.api.domain.PointData;

/**
 * Pins the milestone chest choice to native captures of the Tundra Albums hub.
 *
 * <p>The 2026-11-09 fixture is the frame that exposed the bug: 1401/1635 with the 1,400 chest
 * sparkling in slot 4, three open chests before it and two locked chests after it.
 *
 * <p>Evidence level: saved real-frame verification.
 */
class MonumentMilestoneChestFrameTest {

    private static final String CLAIMABLE_1400 = "/monument/tundra-albums-hub-chest-1400-claimable-20261109.png";
    private static final String CLAIMABLE_935 = "/monument/tundra-albums-hub-20260820.png";
    private static final String NOTHING_READY = "/monument/tundra-albums-hub-20261028.png";

    private static BufferedImage frame(String name) throws Exception {
        return ImageIO.read(Objects.requireNonNull(
                MonumentMilestoneChestFrameTest.class.getResourceAsStream(name), "missing fixture " + name));
    }

    @Test
    void picksOnlyTheSparkling1400Chest() throws Exception {
        PointData chest = MonumentRoutine.findClaimableChest(frame(CLAIMABLE_1400));
        assertNotNull(chest);
        assertEquals(459, chest.getX());
        assertEquals(169, chest.getY());
    }

    @Test
    void picksTheSparkling935Chest() throws Exception {
        PointData chest = MonumentRoutine.findClaimableChest(frame(CLAIMABLE_935));
        assertNotNull(chest);
        assertEquals(459, chest.getX());
    }

    @Test
    void tapsNothingWhenNoChestSparkles() throws Exception {
        assertNull(MonumentRoutine.findClaimableChest(frame(NOTHING_READY)));
    }

    @Test
    void openAndLockedChestsScoreWellUnderTheSparkle() throws Exception {
        BufferedImage img = frame(CLAIMABLE_1400);
        for (int x : new int[] {192, 277, 363, 545, 635}) {
            assertTrue(MonumentRoutine.chestRingGlow(img, x) <= 0.08, "slot x=" + x);
        }
        assertTrue(MonumentRoutine.chestRingGlow(img, 459) >= 0.20);
    }
}
