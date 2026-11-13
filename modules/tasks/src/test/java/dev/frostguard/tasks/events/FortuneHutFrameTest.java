package dev.frostguard.tasks.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Objects;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.vision.match.OpenCvPatternLocator;

/**
 * Live 720x1280 captures of Mia's Fortune Hut from 2026-11-12: the Deals tab strip with the Hut tab
 * unselected, the fresh board, the board after two and after four orbs (the count too low for the next),
 * the wish picker, and the token pack page with the free pack waiting and after it was claimed. The
 * scrolling broadcast banner, which carries other players' names, is blacked out in every board frame.
 *
 * <p>Not captured yet: the screen right after the wish orb opens, and a free Reset. The routine stops
 * and saves a frame when it meets a screen it does not know, so those frames come from the next run.
 */
class FortuneHutFrameTest {

    private static final String DIR = "/fortunehut/";

    @BeforeAll
    static void loadOpenCv() throws IOException {
        try {
            OpenCvPatternLocator.loadNativeLibrary();
        } catch (UnsatisfiedLinkError ignored) {
            // Another saved-frame test may already have loaded OpenCV in this JVM.
        }
    }

    @Test
    void aFreshBoardHasAllTenOrbsSealed() throws Exception {
        assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9), FortuneHutRoutine.unopenedOrbs(image("board-fresh.png")));
    }

    @Test
    void openedOrbsDropOutOfTheSealedList() throws Exception {
        assertEquals(List.of(2, 3, 4, 5, 6, 7, 8, 9), FortuneHutRoutine.unopenedOrbs(image("board-two-opened.png")));
        assertEquals(List.of(4, 5, 6, 7, 8, 9),
                FortuneHutRoutine.unopenedOrbs(image("board-four-opened-cost-red.png")));
    }

    @Test
    void theRevealedPreviewBoardShowsNoSealedOrb() throws Exception {
        assertTrue(FortuneHutRoutine.unopenedOrbs(image("board-preview-revealed.png")).isEmpty());
    }

    @Test
    void theCostReadsRedOnlyWhenTheTokensCannotPayIt() throws Exception {
        assertFalse(FortuneHutRoutine.costIsRed(image("board-fresh.png")));
        assertFalse(FortuneHutRoutine.costIsRed(image("board-two-opened.png")));
        assertTrue(FortuneHutRoutine.costIsRed(image("board-four-opened-cost-red.png")));
    }

    @Test
    void theBoardIsRecognisedByItsCostLabelAndOtherScreensAreNot() throws Exception {
        assertTrue(find("board-fresh.png", TemplatesEnum.FORTUNE_HUT_COST_LABEL).isFound());
        assertTrue(find("board-four-opened-cost-red.png", TemplatesEnum.FORTUNE_HUT_COST_LABEL).isFound());
        assertFalse(find("wish-picker.png", TemplatesEnum.FORTUNE_HUT_COST_LABEL).isFound());
        assertFalse(find("pack-free-available.png", TemplatesEnum.FORTUNE_HUT_COST_LABEL).isFound());
        assertFalse(find("deals-hut-tab-unselected.png", TemplatesEnum.FORTUNE_HUT_COST_LABEL).isFound());
    }

    @Test
    void theHutPageIsRecognisedByItsTitle() throws Exception {
        assertTrue(find("board-fresh.png", TemplatesEnum.FORTUNE_HUT_TITLE).isFound());
        assertTrue(find("board-four-opened-cost-red.png", TemplatesEnum.FORTUNE_HUT_TITLE).isFound());
        assertFalse(find("deals-hut-tab-unselected.png", TemplatesEnum.FORTUNE_HUT_TITLE).isFound());
        assertFalse(find("pack-free-available.png", TemplatesEnum.FORTUNE_HUT_TITLE).isFound());
    }

    @Test
    void theHutTabIsFoundInTheStripWhetherSelectedOrNot() throws Exception {
        ImageSearchResultData tab = findIn("deals-hut-tab-unselected.png", TemplatesEnum.FORTUNE_HUT_TAB,
                new PointData(0, 95), new PointData(719, 190));
        assertTrue(tab.isFound());
        assertEquals(335, tab.getPoint().getX(), 20);
        assertTrue(findIn("board-fresh.png", TemplatesEnum.FORTUNE_HUT_TAB_SELECTED,
                new PointData(0, 95), new PointData(719, 190)).isFound());
    }

    @Test
    void theWishPickerIsRecognisedAndItsButtonIsGreyUntilAnOptionIsChosen() throws Exception {
        assertTrue(find("wish-picker.png", TemplatesEnum.FORTUNE_HUT_WISH_HINT).isFound());
        assertFalse(find("board-fresh.png", TemplatesEnum.FORTUNE_HUT_WISH_HINT).isFound());
        assertFalse(FortuneHutRoutine.wishConfirmIsGreen(image("wish-picker.png")));
    }

    @Test
    void theFreePackButtonIsOnlyGreenWhileTheFreePackIsWaiting() throws Exception {
        assertTrue(find("pack-free-available.png", TemplatesEnum.FORTUNE_HUT_FREE_PACK_LABEL).isFound());
        assertTrue(FortuneHutRoutine.freePackButtonIsGreen(image("pack-free-available.png")));
    }

    @Test
    void onceClaimedThePaidPackSitsWhereTheFreeButtonWasAndIsNeverGreen() throws Exception {
        assertFalse(find("pack-free-claimed.png", TemplatesEnum.FORTUNE_HUT_FREE_PACK_LABEL).isFound());
        assertFalse(FortuneHutRoutine.freePackButtonIsGreen(image("pack-free-claimed.png")));
    }

    @Test
    void theTokenPackPageIsRecognisedWithAndWithoutTheFreePack() throws Exception {
        assertTrue(find("pack-free-available.png", TemplatesEnum.FORTUNE_HUT_PACK_TITLE).isFound());
        assertTrue(find("pack-free-claimed.png", TemplatesEnum.FORTUNE_HUT_PACK_TITLE).isFound());
        assertFalse(find("board-fresh.png", TemplatesEnum.FORTUNE_HUT_PACK_TITLE).isFound());
    }

    private ImageSearchResultData find(String name, TemplatesEnum template) throws IOException {
        return findIn(name, template, new PointData(0, 0), new PointData(719, 1279));
    }

    private ImageSearchResultData findIn(String name, TemplatesEnum template, PointData topLeft, PointData bottomRight)
            throws IOException {
        return OpenCvPatternLocator.locatePattern(bytes(name), template.getTemplate(), topLeft, bottomRight, 85);
    }

    private byte[] bytes(String name) throws IOException {
        try (InputStream in = Objects.requireNonNull(getClass().getResourceAsStream(DIR + name), name)) {
            return in.readAllBytes();
        }
    }

    private BufferedImage image(String name) throws IOException {
        return ImageIO.read(Objects.requireNonNull(getClass().getResourceAsStream(DIR + name), name));
    }
}
