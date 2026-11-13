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
 * unselected, the fresh board, the board after one, two and four orbs (the count too low for the next),
 * the wish picker, and the token pack page with the free pack waiting, after it was claimed (box icon
 * route) and after it was claimed (the "+" route, where the free row is gone). The
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
    void theWishFoundScreenIsRecognisedAndOnlyItsRightButtonIsGreen() throws Exception {
        assertTrue(find("wish-found-next-round.png", TemplatesEnum.FORTUNE_HUT_WISH_FOUND).isFound());
        assertTrue(FortuneHutRoutine.nextRoundIsGreen(image("wish-found-next-round.png")));
        // The blue "Continue the current round" sits on the left and is never the tap target.
        int rgb = image("wish-found-next-round.png").getRGB(150, 990);
        assertFalse(dev.frostguard.tasks.events.WanderTheaterRoutine.isClaimGreen(rgb));
        for (String other : new String[] {"board-fresh.png", "board-four-opened-cost-red.png", "wish-picker.png",
                "pack-free-available.png", "deals-hut-tab-unselected.png"}) {
            assertFalse(find(other, TemplatesEnum.FORTUNE_HUT_WISH_FOUND).isFound(), other);
            assertFalse(FortuneHutRoutine.nextRoundIsGreen(image(other)), other);
        }
    }

    @Test
    void theBoardResetIsTappableOnlyWhileItReadsFree() throws Exception {
        assertTrue(find("board-wish-found-reset-free.png", TemplatesEnum.FORTUNE_HUT_RESET_FREE).isFound());
        // Before the wish is found Reset shows a token cost (15 here, 14, 6, 5 ... on other frames): never tapped.
        for (String costed : new String[] {"board-new-round-reset-cost-15.png", "board-fresh.png",
                "board-two-opened.png", "board-four-opened-cost-red.png", "preview-new-round.png"}) {
            assertFalse(find(costed, TemplatesEnum.FORTUNE_HUT_RESET_FREE).isFound(), costed);
        }
    }

    @Test
    void theFreeResetPickerIsRecognisedAndItsOrangeButtonCountsAsReady() throws Exception {
        assertTrue(find("picker-reset-free-orange.png", TemplatesEnum.FORTUNE_HUT_PICKER_FREE_HINT).isFound());
        assertTrue(FortuneHutRoutine.wishConfirmReady(image("picker-reset-free-orange.png")));
        // The plain picker still needs its green button and a grey one is not ready.
        assertFalse(FortuneHutRoutine.wishConfirmReady(image("wish-picker.png")));
        assertFalse(find("wish-picker.png", TemplatesEnum.FORTUNE_HUT_PICKER_FREE_HINT).isFound());
        assertFalse(find("board-fresh.png", TemplatesEnum.FORTUNE_HUT_PICKER_FREE_HINT).isFound());
    }

    @Test
    void theNewBoardPreviewIsRecognisedAndOnlyThePreview() throws Exception {
        assertTrue(find("preview-new-round.png", TemplatesEnum.FORTUNE_HUT_TAP_ANYWHERE).isFound());
        for (String other : new String[] {"board-fresh.png", "board-new-round-reset-cost-15.png", "wish-picker.png",
                "picker-reset-free-orange.png", "wish-found-next-round.png"}) {
            assertFalse(find(other, TemplatesEnum.FORTUNE_HUT_TAP_ANYWHERE).isFound(), other);
        }
    }

    @Test
    void theFreePackButtonIsOnlyGreenWhileTheFreePackIsWaiting() throws Exception {
        assertTrue(find("pack-free-available.png", TemplatesEnum.FORTUNE_HUT_FREE_PACK_LABEL).isFound());
        assertTrue(FortuneHutRoutine.freePackButtonIsGreen(image("pack-free-available.png")));
    }

    @Test
    void afterTheClaimTheFreeRowStaysOnTheBoxRouteWithAGreyButton() throws Exception {
        assertTrue(find("pack-free-claimed-via-box.png", TemplatesEnum.FORTUNE_HUT_PACK_TITLE).isFound());
        assertTrue(find("pack-free-claimed-via-box.png", TemplatesEnum.FORTUNE_HUT_FREE_PACK_LABEL).isFound());
        assertFalse(FortuneHutRoutine.freePackButtonIsGreen(image("pack-free-claimed-via-box.png")));
    }

    @Test
    void onTheOtherRouteOnceClaimedThePaidPackSitsWhereTheFreeButtonWasAndIsNeverGreen() throws Exception {
        assertFalse(find("pack-free-claimed.png", TemplatesEnum.FORTUNE_HUT_FREE_PACK_LABEL).isFound());
        assertFalse(FortuneHutRoutine.freePackButtonIsGreen(image("pack-free-claimed.png")));
    }

    @Test
    void theTokenPackPageIsRecognisedWithAndWithoutTheFreePack() throws Exception {
        assertTrue(find("pack-free-available.png", TemplatesEnum.FORTUNE_HUT_PACK_TITLE).isFound());
        assertTrue(find("pack-free-claimed.png", TemplatesEnum.FORTUNE_HUT_PACK_TITLE).isFound());
        assertFalse(find("board-fresh.png", TemplatesEnum.FORTUNE_HUT_PACK_TITLE).isFound());
    }

    /** Counts read live on 2026-11-12: 10 tokens fresh, 9 after the 1-token orb, 7 after the 2-token orb, 0 after 4. */
    @Test
    void theTokenCountIsReadExactlyFromBothBoxesOfEachBoardFrame() throws Exception {
        assertEquals(10, readTokens("board-fresh.png"));
        assertEquals(9, readTokens("board-one-opened-nine-tokens.png"));
        assertEquals(7, readTokens("board-two-opened.png"));
        assertEquals(0, readTokens("board-four-opened-cost-red.png"));
    }

    private static final java.time.LocalDateTime END = java.time.LocalDateTime.of(2026, 11, 14, 18, 59);

    @Test
    void theFinalPassRunsNinetyMinutesBeforeTheEventEndsWhenTheResetPassWouldBeLate() {
        // Evening before: the reset pass of 11-14 19:10 is after the end, so the final pass wins.
        assertEquals(java.time.LocalDateTime.of(2026, 11, 14, 17, 29), FortuneHutRoutine.nextPass(
                java.time.LocalDateTime.of(2026, 11, 13, 19, 12), java.time.LocalDateTime.of(2026, 11, 14, 19, 10), END));
        // The reset pass still comes first while it is before the final pass.
        assertEquals(java.time.LocalDateTime.of(2026, 11, 13, 19, 10), FortuneHutRoutine.nextPass(
                java.time.LocalDateTime.of(2026, 11, 13, 15, 0), java.time.LocalDateTime.of(2026, 11, 13, 19, 10), END));
        // At or after the final pass nothing is earlier than the reset pass, which the calendar gate parks.
        assertEquals(java.time.LocalDateTime.of(2026, 11, 14, 19, 10), FortuneHutRoutine.nextPass(
                java.time.LocalDateTime.of(2026, 11, 14, 17, 40), java.time.LocalDateTime.of(2026, 11, 14, 19, 10), END));
        // No calendar end: unchanged behaviour.
        assertEquals(java.time.LocalDateTime.of(2026, 11, 14, 19, 10), FortuneHutRoutine.nextPass(
                java.time.LocalDateTime.of(2026, 11, 13, 19, 12), java.time.LocalDateTime.of(2026, 11, 14, 19, 10), null));
    }

    @Test
    void onlyTheLastTwoHoursBeforeTheEndCountAsTheFinalWindow() {
        assertFalse(FortuneHutRoutine.inFinalWindow(java.time.LocalDateTime.of(2026, 11, 14, 16, 58), END));
        assertTrue(FortuneHutRoutine.inFinalWindow(java.time.LocalDateTime.of(2026, 11, 14, 17, 0), END));
        assertTrue(FortuneHutRoutine.inFinalWindow(java.time.LocalDateTime.of(2026, 11, 14, 17, 29), END));
        assertFalse(FortuneHutRoutine.inFinalWindow(java.time.LocalDateTime.of(2026, 11, 14, 19, 5), END));
        assertFalse(FortuneHutRoutine.inFinalWindow(java.time.LocalDateTime.of(2026, 11, 13, 19, 12), null));
    }

    @Test
    void onTheLastPassAnyTokenCountPaysForTheNextOrbAndABoardInProgressNeedsOnlyOne() {
        // Spend-everything uses a threshold of 1: needed is 1 for a fresh board and for a part-open one.
        assertEquals(1, FortuneHutRoutine.tokensNeeded(10, 1));
        assertEquals(1, FortuneHutRoutine.tokensNeeded(3, 1));
    }

    /** 2026-11-13: the pass read 7 for a header that reads 17; the strip is pasted on black, nothing else is kept. */
    @Test
    void aTwoDigitCountWithALeadingOneIsReadInFull() throws Exception {
        assertEquals(17, readTokens("header-seventeen-tokens.png"));
    }

    @Test
    void aCountThatContradictsTheCostColourIsNotTrusted() {
        // The 15:12 misread: 7 tokens read, 7 orbs open (next costs 15), cost shown white because the real count was 17.
        assertTrue(FortuneHutRoutine.tokensContradictCost(7, 3, false));
        // Consistent cases: enough tokens with a white cost, too few with a red one.
        assertFalse(FortuneHutRoutine.tokensContradictCost(17, 3, false));
        assertFalse(FortuneHutRoutine.tokensContradictCost(7, 3, true));
        // And the other way round: a red cost with a count that could pay it.
        assertTrue(FortuneHutRoutine.tokensContradictCost(40, 3, true));
        // The live frames agree with the rule: fresh board 10 tokens (cost 1, white), four orbs open 0 tokens (cost 6, red).
        assertFalse(FortuneHutRoutine.tokensContradictCost(10, 10, false));
        assertFalse(FortuneHutRoutine.tokensContradictCost(0, 6, true));
    }

    @Test
    void onlyPlainDigitsCountAsATokenReading() {
        assertEquals(96, FortuneHutRoutine.tokenCount(" 96\n"));
        assertEquals(null, FortuneHutRoutine.tokenCount("9)"));
        assertEquals(null, FortuneHutRoutine.tokenCount(""));
        assertEquals(null, FortuneHutRoutine.tokenCount(null));
    }

    @Test
    void aFreshBoardNeedsTheThresholdAndABoardInProgressNeedsItsWorstCaseRemainder() {
        assertEquals(96, FortuneHutRoutine.tokensNeeded(10, 96));
        assertEquals(60, FortuneHutRoutine.tokensNeeded(10, 60));
        // Four orbs opened (1+2+3+4 spent): 6+8+12+15+20+25 = 86 still to cover.
        assertEquals(86, FortuneHutRoutine.tokensNeeded(6, 96));
        assertEquals(50, FortuneHutRoutine.tokensNeeded(6, 50));
        // Nine opened: only the last orb (25).
        assertEquals(25, FortuneHutRoutine.tokensNeeded(1, 96));
        assertEquals(96, java.util.Arrays.stream(FortuneHutRoutine.ORB_COSTS).sum());
    }

    private Integer readTokens(String name) throws Exception {
        dev.frostguard.api.domain.RawImageData raw = raw(name);
        Integer wide = FortuneHutRoutine.tokenCount(dev.frostguard.vision.ocr.OcrEngine.recognizeText(raw,
                FortuneHutRoutine.TOKENS_TOP_LEFT, FortuneHutRoutine.TOKENS_BOTTOM_RIGHT,
                FortuneHutRoutine.TOKEN_DIGITS));
        Integer tight = FortuneHutRoutine.tokenCount(dev.frostguard.vision.ocr.OcrEngine.recognizeText(raw,
                FortuneHutRoutine.TOKENS_TIGHT_TOP_LEFT, FortuneHutRoutine.TOKENS_TIGHT_BOTTOM_RIGHT,
                FortuneHutRoutine.TOKEN_DIGITS));
        return wide != null && wide.equals(tight) ? wide : null;
    }

    private dev.frostguard.api.domain.RawImageData raw(String name) throws IOException {
        BufferedImage image = image(name);
        byte[] rgba = new byte[image.getWidth() * image.getHeight() * 4];
        int offset = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int rgb = image.getRGB(x, y);
                rgba[offset++] = (byte) ((rgb >> 16) & 0xFF);
                rgba[offset++] = (byte) ((rgb >> 8) & 0xFF);
                rgba[offset++] = (byte) (rgb & 0xFF);
                rgba[offset++] = (byte) 0xFF;
            }
        }
        return dev.frostguard.api.domain.RawImageData.capture(rgba, image.getWidth(), image.getHeight(), 32);
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
