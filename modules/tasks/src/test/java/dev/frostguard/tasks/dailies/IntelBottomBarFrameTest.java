package dev.frostguard.tasks.dailies;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

/** The Intel bottom bar's button states, read off real frames captured on 27 and 29 October 2026. */
class IntelBottomBarFrameTest {

    /** Fresh refresh: "Execute All (8) · 84" lit blue, "Claim All (0)" grey. */
    @Test
    void executeAllLitAndNothingToClaim() throws IOException {
        BufferedImage frame = frame("intel-execute-all-ready-20261029.png");
        assertTrue(IntelBottomBar.executeAllReady(frame));
        assertFalse(IntelBottomBar.claimAllReady(frame));
    }

    /** Two seconds after Execute All: every mission done, "Claim All (8)" lit green. */
    @Test
    void afterExecutingClaimAllLightsUp() throws IOException {
        BufferedImage frame = frame("intel-claim-all-ready-20261029.png");
        assertFalse(IntelBottomBar.executeAllReady(frame));
        assertTrue(IntelBottomBar.claimAllReady(frame));
    }

    /** Claimed: only the Fire Beast left, both buttons grey. */
    @Test
    void bothGreyOnceEverythingIsClaimed() throws IOException {
        BufferedImage frame = frame("intel-bar-idle-20261029.png");
        assertFalse(IntelBottomBar.executeAllReady(frame));
        assertFalse(IntelBottomBar.claimAllReady(frame));
    }

    /** Before Agnes's Efficient Recon: Execute All padlocked and grey. */
    @Test
    void aLockedExecuteAllIsNotReady() throws IOException {
        BufferedImage frame = frame("intel-execute-all-locked-20261027.png");
        assertFalse(IntelBottomBar.executeAllReady(frame));
        assertFalse(IntelBottomBar.claimAllReady(frame));
    }

    private BufferedImage frame(String name) throws IOException {
        try (InputStream stream = getClass().getResourceAsStream("/intel/" + name)) {
            return ImageIO.read(Objects.requireNonNull(stream, name));
        }
    }
}
