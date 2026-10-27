package dev.frostguard.tasks.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.OcrSettingsData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.vision.match.OpenCvPatternLocator;
import dev.frostguard.vision.ocr.OcrEngine;

/**
 * The gather speed boost path from live 27 Oct 2026 captures: the home HUD's City Bonus button,
 * City Bonus with its gem count, the opened Gathering Speed boosts, and the Obtain and Purchase
 * dialogs. The old blind taps missed the button and spent nothing for weeks.
 */
class GatherSpeedFrameTest {

    private static final String DIR = "/gatherspeed/";

    @BeforeAll
    static void loadOpenCv() throws IOException {
        try {
            OpenCvPatternLocator.loadNativeLibrary();
        } catch (UnsatisfiedLinkError ignored) {
            // Another saved-frame test may already have loaded OpenCV in this JVM.
        }
    }

    @Test
    void theCityBonusButtonIsFoundOnHomeWhereTheOldTapMissedIt() throws IOException {
        ImageSearchResultData button = OpenCvPatternLocator.locatePattern(bytes("home.png"),
                TemplatesEnum.GATHER_SPEED_CITY_BONUS_BUTTON.getTemplate(),
                GatherSpeedRoutine.CITY_BONUS_BUTTON_AREA.topLeft(),
                GatherSpeedRoutine.CITY_BONUS_BUTTON_AREA.bottomRight(), 90);
        assertTrue(button.isFound());
        // The old tap at (40,118) lies above the button.
        assertTrue(button.getMatchedArea().topLeft().getY() > 118);
    }

    @Test
    void eachScreenOfThePathIsRecognised() throws IOException {
        assertTrue(find("city-bonus-wars.png", TemplatesEnum.GATHER_SPEED_CITY_BONUS_TITLE).isFound());
        assertFalse(find("home.png", TemplatesEnum.GATHER_SPEED_CITY_BONUS_TITLE).isFound());
        assertTrue(find("obtain-more-8h.png", TemplatesEnum.GATHER_SPEED_OBTAIN_MORE_TITLE).isFound());
        assertTrue(find("purchase-250.png", TemplatesEnum.GATHER_SPEED_PURCHASE_TITLE).isFound());
    }

    @Test
    void theUseButtonIsReachedFromEachBoostRow() throws IOException {
        ImageSearchResultData eight = find("growth-gathering-open.png", TemplatesEnum.GATHER_SPEED_BOOST_8H_ROW);
        ImageSearchResultData day = find("growth-gathering-open.png", TemplatesEnum.GATHER_SPEED_BOOST_24H_ROW);
        assertTrue(eight.isFound() && day.isFound());
        // Measured Use buttons: 8h at about (654,564), 24h at about (654,712).
        assertNear(654, 564, use(eight));
        assertNear(654, 712, use(day));
    }

    @Test
    void theGemCountReadsOnCityBonus() throws Exception {
        String text = OcrEngine.recognizeText(frame("city-bonus-wars.png"), GatherSpeedRoutine.GEMS_TOP_LEFT,
                GatherSpeedRoutine.GEMS_BOTTOM_RIGHT, OcrSettingsData.forWhiteTextOnDark());
        assertEquals(464_517L, GatherSpeedRoutine.parseGems(text), "read '" + text + "'");
    }

    @Test
    void aPurchaseCountsOnlyWhenBothGemReadsShowThePrice() {
        assertTrue(GatherSpeedRoutine.spentFor(464_517L, 464_267L, 250));
        assertFalse(GatherSpeedRoutine.spentFor(464_517L, 464_517L, 250));
        assertFalse(GatherSpeedRoutine.spentFor(null, 464_267L, 250));
        assertFalse(GatherSpeedRoutine.spentFor(464_517L, null, 250));
        assertNull(GatherSpeedRoutine.parseGems("+"));
    }

    private PointData use(ImageSearchResultData row) {
        PointData topLeft = row.getMatchedArea().topLeft();
        return new PointData(topLeft.getX() + GatherSpeedRoutine.USE_BUTTON_DX,
                topLeft.getY() + GatherSpeedRoutine.USE_BUTTON_DY);
    }

    private void assertNear(int x, int y, PointData actual) {
        assertTrue(Math.abs(actual.getX() - x) <= 12 && Math.abs(actual.getY() - y) <= 12,
                actual.getX() + "," + actual.getY());
    }

    private ImageSearchResultData find(String name, TemplatesEnum template) throws IOException {
        return OpenCvPatternLocator.locatePattern(bytes(name), template.getTemplate(),
                new PointData(0, 0), new PointData(719, 1279), 90);
    }

    private byte[] bytes(String name) throws IOException {
        try (InputStream in = Objects.requireNonNull(getClass().getResourceAsStream(DIR + name), name)) {
            return in.readAllBytes();
        }
    }

    private RawImageData frame(String name) throws IOException {
        BufferedImage image = ImageIO.read(Objects.requireNonNull(getClass().getResourceAsStream(DIR + name), name));
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
