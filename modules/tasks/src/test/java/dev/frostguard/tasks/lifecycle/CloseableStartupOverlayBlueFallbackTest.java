package dev.frostguard.tasks.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Objects;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.vision.detection.CloseCrossDetector;
import dev.frostguard.vision.match.OpenCvPatternLocator;

/**
 * The blue close button on a startup offer.
 *
 * <p>A Charm Master Pack offer with this button blocked startup seven times: the bot sent its one
 * bounded Android Back, which a Unity-drawn modal ignores, and gave up. The shared close-cross
 * detector found nothing on this frame until its glyph-mask change; it now finds the button.
 * InitializeRoutine still asks the shared detector first and falls back to the colour template
 * only when it finds nothing.
 */
class CloseableStartupOverlayBlueFallbackTest {

    /** Mirrors InitializeRoutine's CLOSEABLE_OVERLAY_SEARCH_AREA. */
    private static final AreaData SEARCH_AREA = AreaData.of(540, 20, 719, 280);
    private static final int THRESHOLD = 90;
    private static final String BLUE = "/startup/closeable-offer-overlay-blue-20260908.png";

    @BeforeAll
    static void loadOpenCv() throws IOException {
        try {
            OpenCvPatternLocator.loadNativeLibrary();
        } catch (UnsatisfiedLinkError ignored) {
            // Another saved-frame test may already have loaded OpenCV in this JVM.
        }
    }

    @Test
    void theSharedDetectorAndTheBlueTemplateAgreeOnTheBlueButton() throws IOException {
        List<CloseCrossDetector.Detection> detections = CloseCrossDetector.locate(image(BLUE), SEARCH_AREA);
        assertEquals(1, detections.size(), detections::toString);
        ImageSearchResultData close = blueTemplate(BLUE);
        assertTrue(close.isFound());
        PointData shared = detections.getFirst().center();
        assertTrue(Math.hypot(shared.getX() - close.getPoint().getX(), shared.getY() - close.getPoint().getY()) <= 20,
                () -> "shared detector " + shared + " vs blue template " + close.getPoint());
    }

    @Test
    void theBlueTemplateFindsItInsideTheStartupSearchArea() throws IOException {
        ImageSearchResultData close = blueTemplate(BLUE);
        assertTrue(close.isFound());
        assertTrue(close.getMatchScore() >= THRESHOLD);
    }

    @Test
    void theBlueTemplateLeavesDialogsThatMustNotBeClosedAlone() throws IOException {
        for (String path : new String[] {
                "/startup/mandatory-update-dialog-20260820.png",
                "/startup/resource-download-prompt-20260817.png",
                "/startup/welcome-back-dialog-20260821.png" }) {
            assertFalse(blueTemplate(path).isFound(), path);
        }
    }

    private ImageSearchResultData blueTemplate(String path) throws IOException {
        return OpenCvPatternLocator.locatePattern(bytes(path),
                TemplatesEnum.GAME_START_CLOSEABLE_OVERLAY_CLOSE_BLUE.getTemplate(),
                SEARCH_AREA.topLeft(), SEARCH_AREA.bottomRight(), THRESHOLD);
    }

    private byte[] bytes(String path) throws IOException {
        try (InputStream in = Objects.requireNonNull(getClass().getResourceAsStream(path), path)) {
            return in.readAllBytes();
        }
    }

    private BufferedImage image(String path) throws IOException {
        return ImageIO.read(Objects.requireNonNull(getClass().getResourceAsStream(path), path));
    }
}
