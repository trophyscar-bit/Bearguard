package dev.frostguard.tasks.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.OcrSettingsData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.vision.ocr.OcrEngine;
import dev.frostguard.vision.ocr.TextLine;

/**
 * The own-rally and pet flows read these screens before tapping. Frames are live 720x1280 captures
 * from 2026-11-10; map frames keep only the popup or the coordinate bar, with player names blacked out.
 */
class BearTrapScreensFrameTest {

    @Test
    void findsEachTrapsGoButtonByItsRowTitle() throws Exception {
        List<TextLine> list = read("special-buildings-20261110.png", BearTrapScreens.SPECIAL_BUILDINGS_LIST);

        assertNear(new PointData(590, 360), BearTrapScreens.trapGoButton(list, 1).orElseThrow(), 6);
        assertNear(new PointData(590, 541), BearTrapScreens.trapGoButton(list, 2).orElseThrow(), 6);
        assertEquals(Optional.empty(), BearTrapScreens.trapGoButton(list, 3));
    }

    @Test
    void readsTheCameraCoordinatesAfterGo() throws Exception {
        assertEquals(Optional.of(new PointData(752, 234)), BearTrapScreens.mapCoordinates(
                read("map-bar-trap2-20261110.png", BearTrapScreens.MAP_COORDINATE_BAR)));
        // 2026-11-10 14:02: after a join session the camera had drifted off Trap 1 to here.
        assertEquals(Optional.of(new PointData(761, 237)), BearTrapScreens.mapCoordinates(
                read("tundra-popup-20261110.png", BearTrapScreens.MAP_COORDINATE_BAR)));
    }

    @Test
    void knowsTheRagingBearPopupFromAnEmptyTile() throws Exception {
        List<TextLine> bear = read("raging-bear-popup-20261110.png", BearTrapScreens.MAP_POPUP_TEXT);
        List<TextLine> tundra = read("tundra-popup-20261110.png", BearTrapScreens.MAP_POPUP_TEXT);

        assertTrue(BearTrapScreens.isRagingBearPopup(bear));
        assertFalse(BearTrapScreens.isRagingBearPopup(tundra));
        assertFalse(BearTrapScreens.saysNotEligible(bear));
        assertFalse(BearTrapScreens.saysNotEligible(tundra));
    }

    @Test
    void readsEachTrapsOwnCoordinatesFromItsRow() throws Exception {
        List<TextLine> list = read("special-buildings-20261110.png", BearTrapScreens.SPECIAL_BUILDINGS_LIST);

        assertEquals(Optional.of(new PointData(786, 236)), BearTrapScreens.mapCoordinates(readWhite(
                "special-buildings-20261110.png", BearTrapScreens.trapRowCoordinatesArea(list, 1).orElseThrow())));
        assertEquals(Optional.of(new PointData(752, 234)), BearTrapScreens.mapCoordinates(readWhite(
                "special-buildings-20261110.png", BearTrapScreens.trapRowCoordinatesArea(list, 2).orElseThrow())));
    }

    @Test
    void theBearPopupSaysWhichTrapItIs() throws Exception {
        // Both traps' popups say "Lv. 1 Raging Bear"; this one, from Go on Trap 2, prints X:752 Y:234.
        List<TextLine> popup = read("raging-bear-popup-20261110.png", BearTrapScreens.MAP_POPUP_TEXT);
        Optional<PointData> bear = BearTrapScreens.mapCoordinates(readWhite(
                "raging-bear-popup-20261110.png", BearTrapScreens.bearPopupCoordinatesArea(popup).orElseThrow()));

        assertEquals(Optional.of(new PointData(752, 234)), bear);
        assertTrue(BearTrapScreens.isConfiguredTrap(Optional.of(new PointData(752, 234)), bear, Optional.empty()));
        assertFalse(BearTrapScreens.isConfiguredTrap(Optional.of(new PointData(786, 236)), bear, Optional.empty()));
        assertEquals(Optional.empty(), BearTrapScreens.bearPopupCoordinatesArea(
                read("tundra-popup-20261110.png", BearTrapScreens.MAP_POPUP_TEXT)));
    }

    @Test
    void readsTheQuickUseConfirmationQuestion() throws Exception {
        assertTrue(BearTrapScreens.isBattleSkillsConfirmation(
                read("battle-skills-confirmation-20261110.png", BearTrapScreens.BATTLE_SKILLS_QUESTION)));
        assertFalse(BearTrapScreens.isBattleSkillsConfirmation(
                read("special-buildings-20261110.png", BearTrapScreens.BATTLE_SKILLS_QUESTION)));
    }

    private static void assertNear(PointData expected, PointData actual, int tolerance) {
        assertTrue(BearTrapScreens.near(expected, actual, tolerance),
                "expected near " + expected.getX() + "," + expected.getY() + " but was " + actual.getX() + "," + actual.getY());
    }

    private List<TextLine> readWhite(String name, AreaData area) throws Exception {
        BufferedImage image = ImageIO.read(Objects.requireNonNull(getClass().getResourceAsStream("/bear/" + name)));
        OcrSettingsData white = OcrSettingsData.builder().textLayout(OcrSettingsData.TextLayout.SPARSE)
                .isolateForeground(true).targetColor(java.awt.Color.WHITE).build();
        return OcrEngine.recognizeLines(rgba(image), area.topLeft(), area.bottomRight(), white);
    }

    private List<TextLine> read(String name, AreaData area) throws Exception {
        BufferedImage image = ImageIO.read(Objects.requireNonNull(getClass().getResourceAsStream("/bear/" + name)));
        OcrSettingsData sparse = OcrSettingsData.builder().textLayout(OcrSettingsData.TextLayout.SPARSE).build();
        return OcrEngine.recognizeLines(rgba(image), area.topLeft(), area.bottomRight(), sparse);
    }

    private static RawImageData rgba(BufferedImage image) {
        byte[] rgba = new byte[image.getWidth() * image.getHeight() * 4];
        int o = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int rgb = image.getRGB(x, y);
                rgba[o++] = (byte) ((rgb >> 16) & 0xFF);
                rgba[o++] = (byte) ((rgb >> 8) & 0xFF);
                rgba[o++] = (byte) (rgb & 0xFF);
                rgba[o++] = (byte) 0xFF;
            }
        }
        return RawImageData.capture(rgba, image.getWidth(), image.getHeight(), 32);
    }
}
