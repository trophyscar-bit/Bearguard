package dev.frostguard.engine.nav;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.vision.ocr.OcrEngine;

/**
 * Saved frames from 2026-11-13 (Gear Forge). Only the rows the check reads are kept, put back at
 * their original y on a black 720x1280 frame so the real coordinates are exercised.
 */
class FormationExitDialogFrameTest {

    @Test
    void recognisesTheSaveTheFormationFirstDialog() throws Exception {
        assertTrue(isFormationExitPrompt("/navigation/formation-exit-dialog.png", 400));
    }

    @Test
    void doesNotMistakeTheCapacityConfirmationForIt() throws Exception {
        assertFalse(isFormationExitPrompt("/navigation/capacity-confirm-gear-forge-ticker-dialog.png", 340));
    }

    @Test
    void doesNotFireOnTheFormationScreenItself() throws Exception {
        assertFalse(isFormationExitPrompt("/navigation/gear-forge-troops-no-dialog.png", 330));
    }

    @Test
    void anUnreadableBodyIsNotTheDialog() {
        assertFalse(CommonOCRSettings.isFormationExitPrompt(null));
        assertFalse(CommonOCRSettings.isFormationExitPrompt(""));
    }

    private static boolean isFormationExitPrompt(String path, int y) throws Exception {
        BufferedImage frame = new BufferedImage(720, 1280, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = frame.createGraphics();
        g.drawImage(load(path), 0, y, null);
        g.dispose();
        String body = OcrEngine.recognizeText(rgbaFrame(frame),
                CommonGameAreas.FORMATION_EXIT_BODY.topLeft(),
                CommonGameAreas.FORMATION_EXIT_BODY.bottomRight(),
                CommonOCRSettings.FORMATION_EXIT_BODY_SETTINGS);
        return CommonOCRSettings.isFormationExitPrompt(body);
    }

    private static BufferedImage load(String path) throws IOException {
        try (InputStream stream = FormationExitDialogFrameTest.class.getResourceAsStream(path)) {
            return ImageIO.read(Objects.requireNonNull(stream, "Missing " + path));
        }
    }

    private static RawImageData rgbaFrame(BufferedImage image) {
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
