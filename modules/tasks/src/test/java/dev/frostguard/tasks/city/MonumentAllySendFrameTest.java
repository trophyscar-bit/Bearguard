package dev.frostguard.tasks.city;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.util.Objects;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

/**
 * Pins the Ally Requests send checks to live Alliance Trade frames.
 *
 * <p>Native 720x1280 captures from 2026-10-28: the panel as it opens, and the Confirmation that
 * one Send raised (closed with its X; nothing was sent). Ally names and avatars are painted out;
 * the Owned label, the Send button and the Confirm button under test are untouched.
 *
 * <p>Evidence level: saved real-frame verification.
 */
class MonumentAllySendFrameTest {

    private static BufferedImage frame(String name) throws Exception {
        return ImageIO.read(Objects.requireNonNull(
                MonumentAllySendFrameTest.class.getResourceAsStream("/monument/" + name),
                "missing fixture " + name));
    }

    @Test
    void panelShowsAnActiveFirstRowSendAndNoConfirmation() throws Exception {
        BufferedImage img = frame("alliance-trade-send-ready-20261028.png");
        assertTrue(MonumentRoutine.showsActiveSendButton(img));
        assertFalse(MonumentRoutine.showsSendConfirmation(img));
    }

    @Test
    void confirmationIsRecognisedAndHidesTheRowSendButton() throws Exception {
        BufferedImage img = frame("alliance-trade-send-confirmation-20261028.png");
        assertTrue(MonumentRoutine.showsSendConfirmation(img));
        assertFalse(MonumentRoutine.showsActiveSendButton(img));
    }

    @Test
    void openRequestFrameWithItsListPaintedOutShowsNoSendButton() throws Exception {
        assertFalse(MonumentRoutine.showsActiveSendButton(frame("alliance-trade-request-open-20261028.png")));
    }
}
