package dev.frostguard.tasks.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Objects;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.tasks.combat.RallyTargetFilter.Facility;
import dev.frostguard.tasks.combat.RallyTargetFilter.Kind;
import dev.frostguard.vision.ocr.OcrEngine;
import dev.frostguard.api.domain.OcrSettingsData;
import dev.frostguard.vision.ocr.TextLine;

/**
 * Reads the facility name on the live War list during a Fortress battle (Stronghold No. 2 and
 * Fortress No. 2 rally cards) through the joiner's own target area. The fixtures are only the two
 * name rows, cropped from the frame; nothing else from it is kept.
 *
 * <p>Evidence level: saved real-frame verification.
 */
class FacilityWarListFrameTest {

    private static final PointData FIRST_JOIN_ICON = new PointData(641, 390);
    private static final PointData SECOND_JOIN_ICON = new PointData(641, 797);

    @Test
    void theFirstCardReadsStrongholdTwo() throws Exception {
        List<TextLine> lines = read(FIRST_JOIN_ICON, "facility-name-row-1-20261113.png");
        assertTrue(RallyTargetFilter.isFacilityRally(lines), "read: " + lines);
        assertEquals(new Facility(Kind.STRONGHOLD, 2), RallyTargetFilter.facilityOf(lines), "read: " + lines);
    }

    @Test
    void theSecondCardReadsFortressTwo() throws Exception {
        List<TextLine> lines = read(SECOND_JOIN_ICON, "facility-name-row-2-20261113.png");
        assertTrue(RallyTargetFilter.isFacilityRally(lines), "read: " + lines);
        assertEquals(new Facility(Kind.FORTRESS, 2), RallyTargetFilter.facilityOf(lines), "read: " + lines);
    }

    private static List<TextLine> read(PointData joinIcon, String row) throws Exception {
        AreaData area = FacilityRallyJoinRoutine.targetTextAreaFor(joinIcon);
        return OcrEngine.recognizeLines(frame(row, area.topLeft()), area.topLeft(), area.bottomRight(),
                OcrSettingsData.builder().textLayout(OcrSettingsData.TextLayout.SPARSE).build());
    }

    /** A blank 720x1280 frame with the saved name row placed where the joiner reads it. */
    private static RawImageData frame(String row, PointData at) throws Exception {
        BufferedImage crop = ImageIO.read(Objects.requireNonNull(
                FacilityWarListFrameTest.class.getResourceAsStream("/beartrap/" + row), "missing fixture " + row));
        BufferedImage image = new BufferedImage(720, 1280, BufferedImage.TYPE_INT_RGB);
        image.getGraphics().drawImage(crop, at.getX(), at.getY(), null);
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
