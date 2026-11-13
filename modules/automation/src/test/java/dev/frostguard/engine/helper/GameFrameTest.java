package dev.frostguard.engine.helper;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.util.Objects;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import dev.frostguard.api.domain.RawImageData;

/** The launcher frame was saved off the live run of 2026-11-12 19:59, when the game had exited under the bot. */
class GameFrameTest {

    @Test
    void theEmulatorLauncherIsNotTheGame() throws Exception {
        RawImageData launcher = frame("/helper/launcher-landscape.png");

        assertTrue(launcher.isValid());
        assertTrue(GameFrame.isNotTheGame(launcher), "1280x720: Back here opens the exit prompt, so none is pressed");
    }

    @Test
    void everyPortraitGameFrameIsLeftAlone() throws Exception {
        assertFalse(GameFrame.isNotTheGame(frame("/squads/enhance-selectmore-72.png")));
        assertFalse(GameFrame.isNotTheGame(frame("/squads/enhance-details.png")));
        assertFalse(GameFrame.isNotTheGame(frame("/squads/forge-Molly-boots.png")));
    }

    @Test
    void aMissingOrUnreadableFrameIsNotProofTheGameIsGone() {
        assertFalse(GameFrame.isNotTheGame(null));
        assertFalse(GameFrame.isNotTheGame(new RawImageData()));
    }

    private static RawImageData frame(String resource) throws Exception {
        BufferedImage image = ImageIO.read(Objects.requireNonNull(GameFrameTest.class.getResourceAsStream(resource), resource));
        byte[] rgba = new byte[image.getWidth() * image.getHeight() * 4];
        int offset = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int rgb = image.getRGB(x, y);
                rgba[offset++] = (byte) ((rgb >> 16) & 0xFF);
                rgba[offset++] = (byte) ((rgb >> 8) & 0xFF);
                rgba[offset++] = (byte) (rgb & 0xFF);
                rgba[offset++] = (byte) 0xFF;
            }
        }
        return RawImageData.capture(rgba, image.getWidth(), image.getHeight(), 32);
    }
}
