package dev.frostguard.engine.nav;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.vision.match.OpenCvPatternLocator;

/**
 * The march Recall confirmation has to be recognisable from a frame before anything can refuse to
 * tap on it. Frames are the live ones from 2026-11-11, with chat, names, power and tile
 * coordinates painted out.
 */
class RecallConfirmDialogFrameTest {

    private static final String DIR = "/navigation/recall-20261111/";
    private static final int THRESHOLD = 90;

    @BeforeAll
    static void loadOpenCv() throws IOException {
        try {
            OpenCvPatternLocator.loadNativeLibrary();
        } catch (UnsatisfiedLinkError ignored) {
            // Another frame test may already have loaded it in this JVM.
        }
    }

    @Test
    void findsTheRecallConfirmationInTheFrameThatCaughtIt() throws IOException {
        ImageSearchResultData hit = locate("recall-confirm.png");

        assertTrue(hit.isFound(), () -> "Expected the Recall confirmation: " + hit);
    }

    @Test
    void doesNotFireOnTheTilePopupWhoseOwnRecallButtonSaysRecall() throws IOException {
        // The strongest decoy available: the same screen family, with an orange "Recall" button
        // on the tile popup. Backing out of that would be wrong -- it is not a confirmation.
        ImageSearchResultData hit = locate("world-tile-popup-with-recall-button.png");

        assertFalse(hit.isFound(), () -> "A Recall button is not a Recall confirmation: " + hit);
    }

    @Test
    void doesNotFireOnAnOrdinaryOpenSidebar() throws IOException {
        ImageSearchResultData hit = locate("daily-sidebar-open.png");

        assertFalse(hit.isFound(), () -> "No confirmation is open in this frame: " + hit);
    }

    private ImageSearchResultData locate(String frame) throws IOException {
        return OpenCvPatternLocator.locatePattern(resource(frame),
                TemplatesEnum.MARCHES_RECALL_CONFIRM_DIALOG,
                new PointData(0, 0), new PointData(719, 1279), THRESHOLD);
    }

    private byte[] resource(String name) throws IOException {
        try (InputStream stream = getClass().getResourceAsStream(DIR + name)) {
            return Objects.requireNonNull(stream, "Missing test resource: " + DIR + name).readAllBytes();
        }
    }
}
