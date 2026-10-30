package dev.frostguard.tasks.city;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.util.Objects;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

/**
 * Pins the My Requests state check to live Alliance Trade frames.
 *
 * <p>Written after a live pass read the open request's "Requesting..." label as "Request" and
 * re-opened the piece picker with a request already open. Both fixtures are native 720x1280
 * captures from 2026-10-28 with the Ally Requests list (player names and avatars) painted out
 * below y=460; the My Requests row under test is untouched. The checks only run once the trade
 * panel's own title has read, so other screens are not asserted here.
 *
 * <p>Evidence level: saved real-frame verification.
 */
class MonumentMyRequestsStateFrameTest {

    private static BufferedImage frame(String name) throws Exception {
        return ImageIO.read(Objects.requireNonNull(
                MonumentMyRequestsStateFrameTest.class.getResourceAsStream("/monument/" + name),
                "missing fixture " + name));
    }

    @Test
    void freeRowShowsTheRequestButtonAndNoOpenRequest() throws Exception {
        BufferedImage img = frame("alliance-trade-request-ready-20261028.png");
        assertTrue(MonumentRoutine.showsRequestButton(img));
        assertFalse(MonumentRoutine.showsOpenRequest(img));
    }

    @Test
    void openRequestShowsCancelAndIsNotMistakenForAFreeRow() throws Exception {
        BufferedImage img = frame("alliance-trade-request-open-20261028.png");
        assertTrue(MonumentRoutine.showsOpenRequest(img));
        assertFalse(MonumentRoutine.showsRequestButton(img));
    }
}
