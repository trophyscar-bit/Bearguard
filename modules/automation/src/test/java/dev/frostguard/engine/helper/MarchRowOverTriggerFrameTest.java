package dev.frostguard.engine.helper;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.engine.nav.CommonGameAreas;
import dev.frostguard.vision.match.OpenCvPatternLocator;

/**
 * The fifth row of the World Marching list lies over the sidebar trigger, so it has to be seen
 * before a trigger tap is sent. 2026-11-14 01:45 (3.24.4): both trigger taps selected the fifth
 * gather march and opened its tile card, Details and Recall; back closed it and the retry selected
 * it again.
 *
 * <p>Fixtures are the live open-failed frames cut to the Marching panel strip, with destination
 * coordinates, map labels and the tile card's player details painted over.
 */
class MarchRowOverTriggerFrameTest {

    private static final String DIR = "/navigation/";
    /** The fixtures are the panel strip x=0..259, y=200..639 of the full frame. */
    private static final int CROP_TOP = 200;

    @BeforeAll
    static void loadOpenCv() throws IOException {
        try {
            OpenCvPatternLocator.loadNativeLibrary();
        } catch (UnsatisfiedLinkError ignored) {
            // Another frame test may already have loaded it in this JVM.
        }
    }

    @Test
    void theFifthRowReachesOverTheTrigger() {
        AreaData trigger = CommonGameAreas.LEFT_MENU_TRIGGER;
        AreaData control = CommonGameAreas.WORLD_MARCHING_TRIGGER_ROW_CONTROL;
        // The row body runs from x=9 across to its control, over the same rows as the trigger.
        assertTrue(trigger.bottomRight().getX() >= 9, "Trigger reaches into the row body");
        assertTrue(control.topLeft().getY() <= trigger.topLeft().getY()
                && control.bottomRight().getY() >= trigger.bottomRight().getY(),
                "The control band spans the trigger's rows");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // The 3.24.4 frame: five gathering rows, recall control on the fifth.
            "march-row-trigger-20261114/five-gathering-rows.png",
            // A returning march on the fifth row shows speedup, not recall.
            "march-row-trigger-20261114/fifth-row-returning.png",
            // A sixth (returning) row below it does not change the fifth.
            "march-row-trigger-20261114/six-rows.png"})
    void seesAFifthMarchRowOverTheTrigger(String frame) throws IOException {
        assertTrue(fifthRowShown(frame), () -> "Expected a fifth march row in " + frame);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // Five marches out, but a tile card hides rows three to five: the caller has to look
            // again once the card is closed, not trust this reading.
            "march-row-trigger-20261114/tile-card-hides-lower-rows.png",
            // A bare map crop, nowhere near a march row.
            "tile-popup-20261113/world-map-no-popup.png"})
    void seesNoFifthRowWhereThereIsNone(String frame) throws IOException {
        assertFalse(fifthRowShown(frame), () -> "No fifth march row in " + frame);
    }

    private boolean fifthRowShown(String frame) throws IOException {
        byte[] bytes = resource(frame);
        AreaData control = CommonGameAreas.WORLD_MARCHING_TRIGGER_ROW_CONTROL;
        PointData topLeft = new PointData(control.topLeft().getX(), control.topLeft().getY() - CROP_TOP);
        PointData bottomRight =
                new PointData(control.bottomRight().getX(), control.bottomRight().getY() - CROP_TOP);
        for (TemplatesEnum template : SidebarNavigator.MARCH_ROW_CONTROLS) {
            if (OpenCvPatternLocator.locatePattern(bytes, template, topLeft, bottomRight,
                    SidebarNavigator.MARCH_ROW_CONTROL_THRESHOLD).isFound()) {
                return true;
            }
        }
        return false;
    }

    private byte[] resource(String name) throws IOException {
        try (InputStream stream = getClass().getResourceAsStream(DIR + name)) {
            return Objects.requireNonNull(stream, "Missing test resource: " + DIR + name).readAllBytes();
        }
    }
}
