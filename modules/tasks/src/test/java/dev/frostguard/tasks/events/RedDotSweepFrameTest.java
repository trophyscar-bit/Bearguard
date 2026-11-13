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
import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.engine.nav.CommonGameAreas;
import dev.frostguard.tasks.events.RedDotVision.Blob;
import dev.frostguard.vision.match.OpenCvPatternLocator;

/**
 * Live 720x1280 captures: an Events page (Officer Project) with one green Claim and three grey ones, the Deals
 * and Gem Shop tab strips with dots on some tabs, and a Gem Shop page with no dot at all. They were already
 * committed for other tests and carry no player name; the gem counter is blacked out.
 */
class RedDotSweepFrameTest {

    private static final String DIR = "/reddot/";
    private static final AreaData CONTENT = RedDotSweepRoutine.CONTENT_AREA;
    private static final AreaData STRIP_DOTS = RedDotSweepRoutine.STRIP_DOT_AREA;

    @BeforeAll
    static void loadOpenCv() throws IOException {
        try {
            OpenCvPatternLocator.loadNativeLibrary();
        } catch (UnsatisfiedLinkError ignored) {
            // Another saved-frame test may already have loaded OpenCV in this JVM.
        }
    }

    @Test
    void everyEventsTabWithADotIsFoundAndOnlyTheMiddleOneIsSelected() throws Exception {
        BufferedImage page = image("events-officer-project-claimable.png");
        List<Blob> dots = RedDotVision.redDots(page, STRIP_DOTS);
        assertEquals(3, dots.size(), "the two alert badges at the strip ends are not dots");
        assertEquals(247, dots.get(0).centerX(), 3);
        assertEquals(446, dots.get(1).centerX(), 3);
        assertEquals(646, dots.get(2).centerX(), 3);
        assertFalse(RedDotVision.isSelectedTab(page, dots.get(0)));
        assertTrue(RedDotVision.isSelectedTab(page, dots.get(1)));
        assertFalse(RedDotVision.isSelectedTab(page, dots.get(2)));
    }

    @Test
    void theOnlyGreenButtonIsTheReadyClaimAndTheGreyOnesAreNotCandidates() throws Exception {
        BufferedImage page = image("events-officer-project-claimable.png");
        List<Blob> buttons = RedDotVision.greenButtons(page, CONTENT);
        assertEquals(1, buttons.size(), "the green reward-item frames and grey Claims must not count");
        Blob claim = buttons.get(0);
        assertEquals(578, claim.centerX(), 6);
        assertEquals(707, claim.centerY(), 6);
    }

    @Test
    void theReadyClaimMatchesAClaimTemplateAndNoGreyOneDoes() throws Exception {
        Blob claim = RedDotVision.greenButtons(image("events-officer-project-claimable.png"), CONTENT).get(0);
        AreaData box = AreaData.of(claim.left() - 8, claim.top() - 8, claim.right() + 8, claim.bottom() + 8);
        assertTrue(find("events-officer-project-claimable.png", TemplatesEnum.EVENTS_CLAIM_BUTTON, box).isFound());
        AreaData greyBox = AreaData.of(497, 845, 662, 915);
        assertFalse(find("events-officer-project-claimable.png", TemplatesEnum.EVENTS_CLAIM_BUTTON, greyBox).isFound());
    }

    @Test
    void theClaimButtonsOwnDotIsNotAnUnexplainedDot() throws Exception {
        BufferedImage page = image("events-officer-project-claimable.png");
        Blob claim = RedDotVision.greenButtons(page, CONTENT).get(0);
        List<Blob> inContent = RedDotVision.redDots(page, CONTENT);
        assertEquals(2, inContent.size(), "the rules-note dot and the Claim's own dot");
        assertEquals(1, inContent.stream().filter(d -> d.near(claim, 12)).count());
    }

    @Test
    void aDealsTabStripShowsItsDotAndOffersNothingToTap() throws Exception {
        BufferedImage page = image("deals-top-up-dot-on-dead-shot.png");
        List<Blob> dots = RedDotVision.redDots(page, STRIP_DOTS);
        assertEquals(1, dots.size());
        assertFalse(RedDotVision.isSelectedTab(page, dots.get(0)));
        assertTrue(RedDotVision.greenButtons(page, CONTENT).isEmpty(), "the blue Top Up Now is not a claim");
    }

    @Test
    void theGemShopStripHasTwoDotsAndNoGreenButton() throws Exception {
        BufferedImage page = image("gem-shop-daily-deals-dots.png");
        List<Blob> dots = RedDotVision.redDots(page, STRIP_DOTS);
        assertEquals(2, dots.size());
        assertTrue(RedDotVision.isSelectedTab(page, dots.get(0)));
        assertFalse(RedDotVision.isSelectedTab(page, dots.get(1)));
        assertTrue(RedDotVision.greenButtons(page, CONTENT).isEmpty(), "orange price buttons are never candidates");
    }

    @Test
    void aGemShopPageWithoutDotsOffersNothing() throws Exception {
        BufferedImage page = image("gem-shop-custom-chest-no-dot.png");
        assertTrue(RedDotVision.redDots(page, STRIP_DOTS).isEmpty());
        assertTrue(RedDotVision.greenButtons(page, CONTENT).isEmpty(), "green item frames are not buttons");
    }

    @Test
    void theSameTabIsRecognisedAgainAndADifferentOneIsNot() throws Exception {
        BufferedImage events = image("events-officer-project-claimable.png");
        BufferedImage shop = image("gem-shop-daily-deals-dots.png");
        List<Blob> eventDots = RedDotVision.redDots(events, STRIP_DOTS);
        List<Blob> shopDots = RedDotVision.redDots(shop, STRIP_DOTS);
        int[] first = RedDotVision.tabSignature(events, eventDots.get(0));
        assertTrue(RedDotVision.sameTab(first, RedDotVision.tabSignature(events, eventDots.get(0))));
        assertFalse(RedDotVision.sameTab(first, RedDotVision.tabSignature(events, eventDots.get(2))));
        assertFalse(RedDotVision.sameTab(first, RedDotVision.tabSignature(shop, shopDots.get(1))));
    }

    @Test
    void theGemShopHeaderIsOnlyOnGemShopPages() throws Exception {
        AreaData header = AreaData.of(0, 0, 330, 90);
        assertTrue(find("gem-shop-daily-deals-dots.png", TemplatesEnum.GEM_SHOP_HEADER, header).isFound());
        assertTrue(find("gem-shop-custom-chest-no-dot.png", TemplatesEnum.GEM_SHOP_HEADER, header).isFound());
        assertFalse(find("events-officer-project-claimable.png", TemplatesEnum.GEM_SHOP_HEADER, header).isFound());
        assertFalse(find("deals-top-up-dot-on-dead-shot.png", TemplatesEnum.GEM_SHOP_HEADER, header).isFound());
    }

    @Test
    void thePanelHeadersAreNotMistakenForEachOther() throws Exception {
        assertTrue(find("events-officer-project-claimable.png", TemplatesEnum.EVENTS_HEADER,
                RedDotSweepRoutine.HEADER_AREA).isFound());
        assertFalse(find("deals-top-up-dot-on-dead-shot.png", TemplatesEnum.EVENTS_HEADER,
                RedDotSweepRoutine.HEADER_AREA).isFound());
        assertTrue(find("deals-top-up-dot-on-dead-shot.png", TemplatesEnum.DEALS_TITLE,
                CommonGameAreas.DEALS_HEADER).isFound());
        assertFalse(find("events-officer-project-claimable.png", TemplatesEnum.DEALS_TITLE,
                CommonGameAreas.DEALS_HEADER).isFound());
    }

    private ImageSearchResultData find(String name, TemplatesEnum template, AreaData area) throws IOException {
        return OpenCvPatternLocator.locatePattern(bytes(name), template.getTemplate(),
                area.topLeft(), area.bottomRight(), 85);
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
