package dev.frostguard.tasks.city;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.vision.match.OpenCvPatternLocator;
import java.io.IOException;
import java.util.Objects;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The Monument building anchor is a daytime crop, and the city goes blue at night. On 2026-11-13 14:33
 * the tower sat dead centre of the screen and the colour match scored 88.9 against a 90 threshold, so
 * the routine said "no building" and skipped the chest, albums and Fragment Backpack. The greyscale
 * match scored 93.4 on the same frame, at the same spot the day frames hit. The fixtures are 440x480
 * crops around the tower (crop origin 120,400), so no HUD, chat or names are in them.
 */
class MonumentBuildingAnchorNightFrameTest {

    private static final PointData TL = new PointData(0, 0);
    private static final PointData BR = new PointData(440, 480);
    private static final PointData FULL_BR = new PointData(720, 1280);
    private static final double THRESHOLD = 90;

    @BeforeAll
    static void loadOpenCv() throws IOException {
        try {
            OpenCvPatternLocator.loadNativeLibrary();
        } catch (UnsatisfiedLinkError ignored) {
            // Another frame test may already have loaded OpenCV in this JVM.
        }
    }

    @Test
    void greyscaleMatchFindsTheTowerAtNightWhereColourMissesIt() throws IOException {
        byte[] night = frame("monument-tower-night-20261113.png");

        ImageSearchResultData colour = OpenCvPatternLocator.locatePattern(
                night, TemplatesEnum.MONUMENT_BUILDING_ANCHOR, TL, BR, THRESHOLD);
        ImageSearchResultData mono = OpenCvPatternLocator.locatePatternMono(
                night, TemplatesEnum.MONUMENT_BUILDING_ANCHOR, TL, BR, THRESHOLD);

        assertFalse(colour.isFound(), "colour match is what missed at night: " + colour);
        assertTrue(mono.isFound(), "greyscale match should find the night tower: " + mono);
        assertEquals(307, mono.getY());
        assertTrue(Math.abs(mono.getX() - 217) <= 6, "tower tap point: " + mono);
    }

    @Test
    void greyscaleMatchStillFindsTheTowerByDay() throws IOException {
        ImageSearchResultData mono = OpenCvPatternLocator.locatePatternMono(
                frame("monument-tower-day-20261113.png"), TemplatesEnum.MONUMENT_BUILDING_ANCHOR, TL, BR, THRESHOLD);

        assertTrue(mono.isFound(), "day tower: " + mono);
        assertEquals(307, mono.getY());
        assertTrue(Math.abs(mono.getX() - 254) <= 6, "tower tap point: " + mono);
    }

    @Test
    void greyscaleMatchDoesNotFireOnTheAllianceTradeOrHubScreens() throws IOException {
        for (String name : new String[] {
            "alliance-trade-request-open-20261028.png",
            "alliance-trade-send-ready-20261028.png",
            "tundra-albums-hub-20261028.png",
            "fragment-backpack-two-packs-20260823.png"}) {
            ImageSearchResultData mono = OpenCvPatternLocator.locatePatternMono(
                    frame(name), TemplatesEnum.MONUMENT_BUILDING_ANCHOR, TL, FULL_BR, THRESHOLD);
            assertFalse(mono.isFound(), name + " must not read as the city view: " + mono);
        }
    }

    private static byte[] frame(String name) throws IOException {
        String path = "/monument/" + name;
        try (var stream = MonumentBuildingAnchorNightFrameTest.class.getResourceAsStream(path)) {
            return Objects.requireNonNull(stream, "Missing test resource: " + path).readAllBytes();
        }
    }
}
