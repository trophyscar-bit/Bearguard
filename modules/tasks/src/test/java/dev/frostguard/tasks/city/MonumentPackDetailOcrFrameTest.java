package dev.frostguard.tasks.city;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.util.Objects;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.vision.ocr.OcrEngine;

/**
 * Pins the pack detail's Enable anchor to live frames. Written after every pass of 2026-10-29
 * refused five Explore the World packs: the two-line pack title read as "ta ar ah ae". The
 * backpack list has a blue Obtain button in the same spot, which must not pass.
 *
 * <p>Evidence level: saved real-frame verification.
 */
class MonumentPackDetailOcrFrameTest {

    @Test
    void enableReadsOnASingleLineTitledPack() throws Exception {
        assertTrue(enableLabel("pack-detail-general-20261028.png").contains("enable"));
    }

    @Test
    void enableReadsOnATwoLineTitledPack() throws Exception {
        assertTrue(enableLabel("pack-detail-two-line-title-20261029.png").contains("enable"));
    }

    @Test
    void obtainButtonOnTheBackpackListIsNotEnable() throws Exception {
        assertFalse(enableLabel("fragment-backpack-general-pack-20261028.png").contains("enable"));
    }

    private static String enableLabel(String name) throws Exception {
        String text = OcrEngine.recognizeText(frame(name), MonumentRoutine.PACK_DETAIL_ENABLE_LABEL_TL,
                MonumentRoutine.PACK_DETAIL_ENABLE_LABEL_BR, MonumentRoutine.PANEL_TITLE_OCR_SETTINGS);
        return text == null ? "" : text.toLowerCase();
    }

    private static RawImageData frame(String name) throws Exception {
        BufferedImage image = ImageIO.read(Objects.requireNonNull(
                MonumentPackDetailOcrFrameTest.class.getResourceAsStream("/monument/" + name),
                "missing fixture " + name));
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
