package dev.frostguard.tasks.shop;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Objects;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.vision.match.OpenCvPatternLocator;

/**
 * Saved city frames from 2026-11-13 with the HUD and chat bar blanked. The routine's threshold is
 * 85; the bubble scored 93-97 on frames that were not used to cut the template and 51-60 on
 * frames without one.
 */
class BuildingDailyChestFrameTest {

    private static final PointData ORIGIN = new PointData(0, 0);
    private static final PointData FULL = new PointData(720, 1280);
    private static final int THRESHOLD = 85;

    @BeforeAll
    static void loadOpenCv() throws IOException {
        try {
            OpenCvPatternLocator.loadNativeLibrary();
        } catch (UnsatisfiedLinkError ignored) {
            // Another frame test may already have loaded OpenCV in this JVM.
        }
    }

    @Test
    void findsTheBubbleOverTheBakery() throws IOException {
        assertTrue(hit("city-bakery-bubble-20261113").isFound());
    }

    @Test
    void findsTheBubbleOverTheVinylShop() throws IOException {
        assertTrue(hit("city-vinyl-bubble-20261113").isFound());
    }

    @Test
    void doesNotFindABubbleOnceClaimedOrOnEmptySnow() throws IOException {
        assertFalse(hit("city-vinyl-claimed-20261113").isFound(), score("city-vinyl-claimed-20261113"));
        assertFalse(hit("city-empty-snow-20261113").isFound(), score("city-empty-snow-20261113"));
    }

    private static ImageSearchResultData hit(String frame) throws IOException {
        return OpenCvPatternLocator.locatePattern(
                resource(frame), TemplatesEnum.BUILDING_DAILY_CHEST, ORIGIN, FULL, THRESHOLD);
    }

    private static String score(String frame) {
        try {
            return String.valueOf(OpenCvPatternLocator.locatePattern(
                    resource(frame), TemplatesEnum.BUILDING_DAILY_CHEST, ORIGIN, FULL, 0));
        } catch (IOException e) {
            return e.toString();
        }
    }

    private static byte[] resource(String frame) throws IOException {
        String path = "/shop/" + frame + ".png";
        try (InputStream stream = BuildingDailyChestFrameTest.class.getResourceAsStream(path)) {
            return Objects.requireNonNull(stream, "Missing " + path).readAllBytes();
        }
    }
}
