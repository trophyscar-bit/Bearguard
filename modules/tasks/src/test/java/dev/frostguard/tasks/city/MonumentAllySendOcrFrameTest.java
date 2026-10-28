package dev.frostguard.tasks.city;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.image.BufferedImage;
import java.util.Objects;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.vision.convert.RegexNumberParser;
import dev.frostguard.vision.ocr.OcrEngine;

/**
 * Reads the send decision's OCR crops on the live Alliance Trade frames. An "Owned: 1" that read
 * as 2 or more would send the player's only copy, so the exact value is asserted on both the list
 * row and the Confirmation dialog.
 *
 * <p>Evidence level: saved real-frame verification.
 */
class MonumentAllySendOcrFrameTest {

    private static final String PANEL = "alliance-trade-send-ready-20261028.png";
    private static final String DIALOG = "alliance-trade-send-confirmation-20261028.png";

    @Test
    void firstRowOwnedReadsOne() throws Exception {
        assertEquals(1, MonumentRoutine.ownedCountFrom(read(PANEL,
                MonumentRoutine.ALLY_FIRST_ROW_OWNED_TL, MonumentRoutine.ALLY_FIRST_ROW_OWNED_BR)));
    }

    @Test
    void confirmationOwnedReadsOne() throws Exception {
        assertEquals(1, MonumentRoutine.ownedCountFrom(read(DIALOG,
                MonumentRoutine.SEND_CONFIRM_OWNED_TL, MonumentRoutine.SEND_CONFIRM_OWNED_BR)));
    }

    @Test
    void tradesLeftReadsFive() throws Exception {
        String text = OcrEngine.recognizeText(frame(PANEL), MonumentRoutine.TRADES_LEFT_TL,
                MonumentRoutine.TRADES_LEFT_BR, MonumentRoutine.REQUESTS_LEFT_OCR_SETTINGS);
        assertEquals(5, RegexNumberParser.numerator(text), "read: " + text);
    }

    private static String read(String name, PointData tl, PointData br) throws Exception {
        return OcrEngine.recognizeText(frame(name), tl, br, MonumentRoutine.OWNED_COUNT_OCR_SETTINGS);
    }

    private static RawImageData frame(String name) throws Exception {
        BufferedImage image = ImageIO.read(Objects.requireNonNull(
                MonumentAllySendOcrFrameTest.class.getResourceAsStream("/monument/" + name),
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
