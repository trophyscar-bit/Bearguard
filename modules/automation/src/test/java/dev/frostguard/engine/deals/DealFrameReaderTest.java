package dev.frostguard.engine.deals;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.frostguard.api.deals.DealItem;
import dev.frostguard.api.deals.DealOffer;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.vision.match.OpenCvPatternLocator;

class DealFrameReaderTest {

    private static DealFrameReader reader;

    @BeforeAll
    static void loadLibrary() throws Exception {
        // The app binds OpenCV during bootstrap; a test JVM has to do it itself.
        OpenCvPatternLocator.loadNativeLibrary();
        Path icons = Path.of(Objects.requireNonNull(
                DealFrameReaderTest.class.getResource("/deals/item-icons")).toURI());
        reader = new DealFrameReader(DealItemLibrary.load(icons));
    }

    @Test
    void readsTitlePriceRemainingAndTileQuantitiesOfAOnePackTab() throws Exception {
        DealFrameReader.Page page = read("gem-shop-hall-of-chiefs.png", "Hall of Chiefs");

        assertEquals(1, page.offers().size());
        DealOffer offer = page.offers().get(0);
        assertEquals("Hall of Chiefs Pack", offer.title());
        assertEquals(4.99, offer.priceUsd(), 1e-9);
        assertEquals(1, offer.remaining());
        Map<String, Long> items = quantities(offer);
        assertEquals(16L, items.get("1h Speedup"));
        assertEquals(160L, items.get("5min Speedup"));
        assertEquals(25L, items.get("100 VIP XP"));
        assertEquals(500L, items.get("10K Meat"));
        assertTrue(page.clippedTileRowY() != null, "the fifth tile is cut off, so the row must be swiped");
    }

    @Test
    void keepsStackedCardsApartByTitleAndPrice() throws Exception {
        DealFrameReader.Page page = read("gem-shop-custom-chest-top.png", "Custom Pet Chest");

        assertEquals(2, page.offers().size());
        DealOffer first = page.offers().get(0);
        DealOffer second = page.offers().get(1);
        assertEquals(4.99, first.priceUsd(), 1e-9);
        assertEquals(9.99, second.priceUsd(), 1e-9);
        assertTrue(first.title().startsWith("Shining Custom Chest"), first.title());
        assertNotEquals(first.packKey(), second.packKey());
        assertEquals(1, first.remaining());
    }

    @Test
    void readsListedRowsOnTheTimedPackPopup() throws Exception {
        DealOffer offer = read("popup-city-construction.png", "Timed Pack").offers().get(0);

        assertEquals(9.99, offer.priceUsd(), 1e-9);
        Map<String, Long> items = quantities(offer);
        assertEquals(24L, items.get("1h Construction Speedup"), items.toString());
        assertEquals(288L, items.get("5m Construction Speedup"), items.toString());
        assertEquals(40L, items.get("100 VIP XP"), items.toString());
        assertEquals(1000L, items.get("10K Meat"), items.toString());
    }

    @Test
    void readsTheCraftsmansTreasureTilesItCanSeeAndLeavesUnreadOnesOut() throws Exception {
        DealOffer offer = read("popup-craftsmans-treasure.png", "Craftsman's Treasure").offers().get(0);

        assertTrue(offer.title().startsWith("Craftsman") && offer.title().endsWith("Treasure"), offer.title());
        assertEquals(4.99, offer.priceUsd(), 1e-9);
        Map<String, Long> items = quantities(offer);
        assertEquals(2500L, items.get("Gems"), items.toString());
        assertEquals(500L, items.get("10K Meat"), items.toString());
        items.values().forEach(q -> assertTrue(q > 0));
    }

    @Test
    void reportsAPurchasedPackWithoutInventingAPrice() throws Exception {
        DealFrameReader.Page page = read("deals-vault-purchased.png", "Vault of Enigma");

        assertEquals(1, page.offers().size());
        DealOffer offer = page.offers().get(0);
        assertTrue(offer.purchased());
        assertNull(offer.priceUsd());
        assertEquals(2500L, quantities(offer).get("Gems"));
    }

    @Test
    void treatsTopUpNowAsAnActionAndRecordsTheRemainingPoints() throws Exception {
        DealFrameReader.Page page = read("popup-top-up-gift.png", "Top-up Gift");

        assertEquals(1, page.offers().size());
        DealOffer offer = page.offers().get(0);
        assertNull(offer.priceUsd());
        assertTrue(offer.priceText().contains("96,000"), offer.priceText());
        assertTrue(page.problems().isEmpty(), page.problems().toString());
    }

    @Test
    void cleansOcrNoiseAroundNamesAndTitles() {
        assertEquals("5m Construction Speedup", DealFrameReader.itemName("SF 5m Construction Speedup"));
        assertEquals("1h Construction Speedup", DealFrameReader.itemName("lh Construction Speedup"));
        assertEquals("Shining Custom Chest", DealFrameReader.cleanTitle("Shining Custom Chest / my"));
        assertEquals("Dazzling Custom Chest", DealFrameReader.cleanTitle("q Dazzling Custom Chest"));
        assertFalse(DealFrameReader.isTitleText("et no po"));
    }

    private static DealFrameReader.Page read(String name, String tab) throws Exception {
        BufferedImage image = ImageIO.read(Objects.requireNonNull(
                DealFrameReaderTest.class.getResourceAsStream("/deals/" + name), name));
        DealFrameReader.Page page = reader.read(rgbaFrame(image), "Test", tab, name, !name.startsWith("popup-"));
        System.out.println(name + " -> " + page);
        return page;
    }

    private static Map<String, Long> quantities(DealOffer offer) {
        return offer.items().stream().collect(Collectors.toMap(DealItem::key, DealItem::quantity, (a, b) -> a));
    }

    private static RawImageData rgbaFrame(BufferedImage image) {
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
        return RawImageData.capture(rgba, image.getWidth(), image.getHeight(), 32);
    }
}
