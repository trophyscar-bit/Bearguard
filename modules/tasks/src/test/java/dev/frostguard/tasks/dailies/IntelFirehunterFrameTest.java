package dev.frostguard.tasks.dailies;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Objects;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.vision.match.OpenCvPatternLocator;

/**
 * The gold pin and the path behind it, from the live screen on 1 November. The re-scan frame is the
 * one Intel went to sleep on at 19:05, calling the map empty with the pin and Execute All (1) up.
 */
class IntelFirehunterFrameTest {

    private static final PointData ORIGIN = new PointData(0, 0);
    private static final PointData FULL = new PointData(720, 1280);

    @BeforeAll
    static void loadOpenCv() throws IOException {
        try {
            OpenCvPatternLocator.loadNativeLibrary();
        } catch (UnsatisfiedLinkError ignored) {
            // Another frame test may already have loaded OpenCV in this JVM.
        }
    }

    @Test
    void findsTheGoldPin() throws IOException {
        assertTrue(locate("intel-firebeast-gold-pin-20261101.png", TemplatesEnum.INTEL_FIREHUNTER_PIN, 85).isFound());
    }

    @Test
    void findsTheGoldPinOnTheFrameIntelSleptOn() throws IOException {
        assertTrue(locate("intel-firebeast-rescan-slept-20261101.png", TemplatesEnum.INTEL_FIREHUNTER_PIN, 85).isFound());
    }

    @Test
    void theSkullIsNotTheGoldPin() throws IOException {
        assertFalse(locate("intel-agnes-portrait-rescan-20261031.png", TemplatesEnum.INTEL_FIREHUNTER_PIN, 85).isFound());
    }

    @Test
    void theCardOpensWithView() throws IOException {
        assertTrue(locate("intel-firehunter-card-20261101.png", TemplatesEnum.INTEL_VIEW, 90).isFound());
    }

    @Test
    void theWorldCardOffersAttack() throws IOException {
        assertTrue(locate("intel-firebeast-world-card-20261101.png", TemplatesEnum.INTEL_ATTACK, 90).isFound());
    }

    private static ImageSearchResultData locate(String frame, TemplatesEnum template, int threshold) throws IOException {
        return OpenCvPatternLocator.locatePattern(resource("/intel/" + frame), template, ORIGIN, FULL, threshold);
    }

    private static byte[] resource(String path) throws IOException {
        try (var stream = IntelFirehunterFrameTest.class.getResourceAsStream(path)) {
            return Objects.requireNonNull(stream, "Missing test resource: " + path).readAllBytes();
        }
    }
}
