package dev.frostguard.tasks.dailies;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Objects;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.vision.match.OpenCvPatternLocator;

/**
 * Agnes's portrait on the Intel screen is her free daily Intel. Both frames are from 30 October:
 * the first with the portrait waiting, the second straight after tapping it ("Agnes found 2 new
 * intel for you!"), when it is gone and Execute All (2) has lit.
 */
class IntelAgnesPortraitFrameTest {

    @BeforeAll
    static void loadOpenCv() throws IOException {
        try {
            OpenCvPatternLocator.loadNativeLibrary();
        } catch (UnsatisfiedLinkError ignored) {
            // Another frame test may already have loaded OpenCV in this JVM.
        }
    }

    @Test
    void findsTheUnclaimedPortrait() throws IOException {
        ImageSearchResultData agnes = locate("/intel/intel-agnes-portrait-unclaimed-20261030.png");
        assertTrue(agnes.isFound(), "Agnes's unclaimed portrait should be found: " + agnes);
    }

    @Test
    void findsThePortraitTheRescanSleptOn() throws IOException {
        // 31 October 12:06, prod's own frame: "No intel missions found after re-scan" with her up.
        ImageSearchResultData agnes = locate("/intel/intel-agnes-portrait-rescan-20261031.png");
        assertTrue(agnes.isFound(), "Agnes's portrait on the re-scan frame should be found: " + agnes);
    }

    @Test
    void portraitIsGoneOnceClaimed() throws IOException {
        ImageSearchResultData agnes = locate("/intel/intel-agnes-claimed-two-new-20261030.png");
        assertFalse(agnes.isFound(), "No portrait after the claim: " + agnes);
    }

    @Test
    void claimingLeavesExecuteAllLit() throws IOException {
        assertTrue(IntelBottomBar.executeAllReady(
                javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(
                        resource("/intel/intel-agnes-claimed-two-new-20261030.png")))));
    }

    private static ImageSearchResultData locate(String frame) throws IOException {
        return OpenCvPatternLocator.locatePattern(resource(frame), TemplatesEnum.AGNES_CLAIM_INTEL,
                IntelligenceRoutine.AGNES_PORTRAIT_AREA.topLeft(), IntelligenceRoutine.AGNES_PORTRAIT_AREA.bottomRight(),
                IntelligenceRoutine.AGNES_PORTRAIT_THRESHOLD);
    }

    private static byte[] resource(String path) throws IOException {
        try (var stream = IntelAgnesPortraitFrameTest.class.getResourceAsStream(path)) {
            return Objects.requireNonNull(stream, "Missing test resource: " + path).readAllBytes();
        }
    }
}
