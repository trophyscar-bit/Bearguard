package dev.frostguard.engine.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.util.Locale;
import java.util.Objects;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import dev.frostguard.api.domain.OcrSettingsData;
import dev.frostguard.api.domain.OcrSettingsData.TextLayout;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.vision.ocr.OcrEngine;

/**
 * Pins where the Lighthouse row actually is against a real Daily sidebar.
 *
 * <p>The routine used to fabricate a search hit at a fixed {@code y=649} and hand it downstream as
 * if it had been located. On this frame y=649 reads "Daily Activity Triumph" -- so the Go button
 * being tapped belonged to Alliance Triumph, the Intel map never opened, and the routine reported
 * "Intel unreachable" 163 times over two days without one success.
 *
 * <p>The fixture is cropped to the sidebar panel: no avatar, power, alliance tag, account name or
 * chat is included.
 *
 * <p>Evidence level: saved real-frame verification.
 */
class DailySidebarLighthouseRowFrameTest {

    private static final String FIXTURE = "/intel/daily-sidebar-lighthouse-row-20260822.png";

    /** The fixture is cropped from y=240 of the full screen, so row centres shift by that much. */
    private static final int CROP_ORIGIN_Y = 240;

    private static final OcrSettingsData LABEL_SETTINGS = OcrSettingsData.assembler()
            .textLayout(TextLayout.TEXT_BLOCK)
            .stripBackground(false)
            .language("eng")
            .build();

    private static String labelAt(RawImageData frame, int screenCentreY) throws Exception {
        int y = screenCentreY - CROP_ORIGIN_Y;
        String text = OcrEngine.recognizeText(frame,
                new PointData(90, y - 33),
                new PointData(390, y + 32),
                LABEL_SETTINGS);
        return text == null ? "" : text.toLowerCase(Locale.ROOT);
    }

    @Test
    void theLighthouseRowIsWhereTheScanLooksForIt() throws Exception {
        RawImageData frame = frame();

        assertTrue(labelAt(frame, 723).contains("intel"),
                "y=723 should be the Lighthouse Intel row on this capture");
    }

    @Test
    void theOldHardcodedRowPositionIsADifferentTaskEntirely() throws Exception {
        // This is the coordinate the routine used to tap Go against. It is not the Lighthouse, so
        // every pass opened Alliance Triumph and then reported Intel unreachable.
        String label = labelAt(frame(), 649);

        assertEquals(false, label.contains("intel"),
                "the old constant is not the Lighthouse row on this capture, it read: '"
                        + label.trim() + "'");
    }

    private static RawImageData frame() throws Exception {
        BufferedImage image = ImageIO.read(Objects.requireNonNull(
                DailySidebarLighthouseRowFrameTest.class.getResourceAsStream(FIXTURE),
                "missing fixture " + FIXTURE));
        return rgbaFrame(image);
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
