package dev.frostguard.vision.deals;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Objects;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

class PriceButtonLocatorTest {

    @Test
    void findsBothPriceButtonsOnStackedCustomChestCards() throws Exception {
        List<PriceButtonLocator.Box> boxes = locate("gem-shop-custom-chest-top.png");

        assertEquals(2, boxes.size());
        assertNear(boxes.get(0), 429, 632, 157, 61);
        assertNear(boxes.get(1), 429, 996, 157, 63);
    }

    @Test
    void findsTheSingleWideButtonOnAOnePackTab() throws Exception {
        List<PriceButtonLocator.Box> boxes = locate("gem-shop-hall-of-chiefs.png");

        assertEquals(1, boxes.size());
        assertNear(boxes.get(0), 224, 1138, 270, 75);
    }

    @Test
    void findsTheButtonOnTimedPackAndCraftsmanPopups() throws Exception {
        assertEquals(1, locate("popup-city-construction.png").size());
        assertEquals(1, locate("popup-craftsmans-treasure.png").size());
    }

    @Test
    void findsTheSmallDailyDealsCardButtonsButNotTheBannerMergedPurchaseAll() throws Exception {
        List<PriceButtonLocator.Box> boxes = locate("tab-strip-scrolled-one.png");

        assertEquals(3, boxes.size(), boxes.toString());
        assertNear(boxes.get(0), 521, 734, 137, 53);
        assertNear(boxes.get(1), 521, 920, 137, 54);
        assertNear(boxes.get(2), 521, 1105, 137, 56);
    }

    @Test
    void findsNoButtonOnAPurchasedPack() throws Exception {
        assertTrue(locate("deals-vault-purchased.png").isEmpty());
    }

    private static List<PriceButtonLocator.Box> locate(String name) throws Exception {
        BufferedImage frame = ImageIO.read(Objects.requireNonNull(
                PriceButtonLocatorTest.class.getResourceAsStream("/deals/" + name), name));
        return PriceButtonLocator.locate(frame);
    }

    private static void assertNear(PriceButtonLocator.Box box, int x, int y, int width, int height) {
        String actual = box.toString();
        assertTrue(Math.abs(box.x() - x) <= 3 && Math.abs(box.y() - y) <= 3, actual);
        assertTrue(Math.abs(box.width() - width) <= 4 && Math.abs(box.height() - height) <= 4, actual);
    }
}
