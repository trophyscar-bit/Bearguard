package dev.frostguard.engine.helper;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.vision.match.OpenCvPatternLocator;
import java.io.IOException;
import java.util.Objects;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CrystalLaboratoryAnchorFrameTest {

    private static final String FRAME = "/navigation/crystal-laboratory/building-anchor.png";

    @BeforeAll
    static void loadOpenCv() throws IOException {
        try {
            OpenCvPatternLocator.loadNativeLibrary();
        } catch (UnsatisfiedLinkError ignored) {
            // Another frame test may already have loaded OpenCV in this JVM.
        }
    }

    @Test
    void findsTheStaticBuildingAnchorInsideItsMeasuredCityRegion() throws IOException {
        ImageSearchResultData anchor = OpenCvPatternLocator.locatePatternMono(
                resource(FRAME),
                TemplatesEnum.CRYSTAL_LAB_BUILDING_ANCHOR,
                NavigationHelper.CRYSTAL_LAB_BUILDING_ANCHOR.topLeft(),
                NavigationHelper.CRYSTAL_LAB_BUILDING_ANCHOR.bottomRight(),
                90);

        assertTrue(anchor.isFound(), () -> "Anchor should be detected: " + anchor);
        assertTrue(anchor.getX() >= NavigationHelper.CRYSTAL_LAB_BUILDING_ANCHOR.topLeft().getX());
        assertTrue(anchor.getX() <= NavigationHelper.CRYSTAL_LAB_BUILDING_ANCHOR.bottomRight().getX());
        assertTrue(anchor.getY() >= NavigationHelper.CRYSTAL_LAB_BUILDING_ANCHOR.topLeft().getY());
        assertTrue(anchor.getY() <= NavigationHelper.CRYSTAL_LAB_BUILDING_ANCHOR.bottomRight().getY());
    }

    private static byte[] resource(String path) throws IOException {
        try (var stream = CrystalLaboratoryAnchorFrameTest.class.getResourceAsStream(path)) {
            return Objects.requireNonNull(stream, "Missing test resource: " + path).readAllBytes();
        }
    }
}
