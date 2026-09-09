package dev.frostguard.tasks.lifecycle;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.vision.match.OpenCvPatternLocator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CloseableStartupOverlayPatternEvidenceTest {

    private static final PointData CLOSE_AREA_TOP_LEFT = new PointData(540, 65);
    private static final PointData CLOSE_AREA_BOTTOM_RIGHT = new PointData(690, 220);

    @BeforeAll
    static void loadOpenCv() throws IOException {
        try {
            OpenCvPatternLocator.loadNativeLibrary();
        } catch (UnsatisfiedLinkError ignored) {
            // Another saved-frame test may already have loaded OpenCV in this JVM.
        }
    }

    @Test
    void detectsConcreteCloseControlFromObservedOfferOverlay() throws IOException {
        ImageSearchResultData close = inspect("/startup/closeable-offer-overlay-20260821.png");

        assertTrue(close.isFound());
        assertTrue(close.hasMatchedArea());
        assertTrue(close.getMatchScore() >= 90);
    }

    @Test
    void detectsTheBlueCloseButtonThatBlockedStartupSevenTimes() throws IOException {
        // A Charm Master Pack offer with a blue close button. Startup sent its one bounded Android
        // Back, which a Unity-drawn modal ignores, then gave up and stopped the game -- seven times
        // across eight days before the frame was captured.
        ImageSearchResultData close = inspect("/startup/closeable-offer-overlay-blue-20260908.png");

        assertTrue(close.isFound());
        assertTrue(close.getMatchScore() >= 90);
    }

    @Test
    void theGoldTemplateAloneCannotSeeTheBlueButton() throws IOException {
        // Why a second template rather than a lower threshold: the gold variant scores about 44
        // on the blue button, which is indistinguishable from noise. Nothing safe separates them.
        ImageSearchResultData gold = inspectWith(TemplatesEnum.GAME_START_CLOSEABLE_OVERLAY_CLOSE,
                "/startup/closeable-offer-overlay-blue-20260908.png");

        assertFalse(gold.isFound());
        assertTrue(gold.getMatchScore() < 60,
                "if the gold template starts matching blue, this fixture no longer shows the bug");
    }

    @Test
    void rejectsHigherPriorityAndNonCloseableStartupDialogs() throws IOException {
        for (String path : new String[] {
                "/startup/mandatory-update-dialog-20260820.png",
                "/startup/resource-download-prompt-20260817.png",
                "/startup/welcome-back-dialog-20260821.png" }) {
            assertFalse(inspect(path).isFound(), path);
        }
    }

    /** Mirrors InitializeRoutine: an overlay is closeable if ANY known button style matches. */
    private static ImageSearchResultData inspect(String path) throws IOException {
        ImageSearchResultData last = null;
        for (TemplatesEnum variant : new TemplatesEnum[] {
                TemplatesEnum.GAME_START_CLOSEABLE_OVERLAY_CLOSE,
                TemplatesEnum.GAME_START_CLOSEABLE_OVERLAY_CLOSE_BLUE }) {
            last = OpenCvPatternLocator.locatePattern(bytes(path), variant.getTemplate(),
                    CLOSE_AREA_TOP_LEFT, CLOSE_AREA_BOTTOM_RIGHT, 90);
            if (last.isFound()) {
                return last;
            }
        }
        return last;
    }

    private static ImageSearchResultData inspectWith(TemplatesEnum variant, String path)
            throws IOException {
        return OpenCvPatternLocator.locatePattern(bytes(path), variant.getTemplate(),
                CLOSE_AREA_TOP_LEFT, CLOSE_AREA_BOTTOM_RIGHT, 90);
    }

    private static byte[] bytes(String path) throws IOException {
        try (InputStream stream = CloseableStartupOverlayPatternEvidenceTest.class.getResourceAsStream(path)) {
            return Objects.requireNonNull(stream, "Missing test resource: " + path).readAllBytes();
        }
    }
}
