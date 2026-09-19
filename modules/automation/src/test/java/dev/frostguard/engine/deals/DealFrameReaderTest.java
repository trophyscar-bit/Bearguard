package dev.frostguard.engine.deals;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;
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
        assertNull(offer.limitPeriod());
        Map<String, Long> items = quantities(offer);
        assertEquals(16L, items.get("1h Speedup"));
        assertEquals(160L, items.get("5min Speedup"));
        assertEquals(25L, items.get("100 VIP XP"));
        assertEquals(500L, items.get("10K Meat"));
        assertTrue(page.clippedTileRowY() != null, "the fifth tile is cut off, so the row must be swiped");
    }

    @Test
    void leavesOutChooseYourOwnCustomChestCards() throws Exception {
        for (String frame : List.of("gem-shop-custom-chest-top.png", "gem-shop-custom-chest-middle.png",
                "gem-shop-custom-chest-scrolled-live.png")) {
            DealFrameReader.Page page = read(frame, "Mix & Match");

            assertTrue(page.chooseYourOwn(), frame);
            assertTrue(page.offers().isEmpty(), frame + " -> " + page.offers());
        }
    }

    @Test
    void recordsNothingForTheWeeklyBenefitsCardPickYourPackPage() throws Exception {
        DealFrameReader.Page page = read("deals-weekly-benefits-card-live.png", "Weekly Benefits Card");

        assertFalse(page.chooseYourOwn(), "its orange pick slots are not the Custom Chest plus sign");
        assertTrue(page.offers().isEmpty(), page.offers().toString());
    }

    @Test
    void readsEachRegularPackCardWithItsOwnTitleAndPurchaseLimit() throws Exception {
        DealFrameReader.Page page = read("gem-shop-regular-pack-live.png", "Regular Pack");

        assertFalse(page.chooseYourOwn());
        assertEquals(3, page.offers().size(), page.offers().toString());
        page.offers().forEach(o -> assertEquals(4.99, o.priceUsd(), 1e-9));
        // Molly's Blessing sits on a gradient banner the white mask does not separate, so its title falls
        // back to the page title plus price rather than borrowing the tab strip or a neighbour's banner.
        assertEquals("Regular Pack - $4.99", page.offers().get(0).title());
        assertEquals("lifetime", page.offers().get(0).limitPeriod());
        assertTrue(page.offers().get(1).title().startsWith("Charm Design Pack"), page.offers().get(1).title());
        assertNull(page.offers().get(1).limitPeriod());
        assertEquals(1, page.offers().get(1).remaining());
        assertTrue(page.offers().get(2).title().startsWith("Charm Craftsman Pack"), page.offers().get(2).title());
        assertEquals(5, page.offers().get(2).remaining());
    }

    @Test
    void appliesThePageWideDailyLimitToEveryDailyDealsCard() throws Exception {
        DealFrameReader.Page page = read("gem-shop-daily-deals-live.png", "Daily Deals");

        assertFalse(page.chooseYourOwn());
        // The "Purchase All" button is drawn on an orange banner and merges with it, so only the three card
        // buttons are found.
        List<Double> prices = page.offers().stream().map(DealOffer::priceUsd).toList();
        assertEquals(List.of(0.99, 1.99, 2.99), prices, page.offers().toString());
        page.offers().forEach(o -> assertEquals("daily", o.limitPeriod(), o.toString()));
        assertEquals(30, page.offers().get(0).purchasesPerMonth());
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
    void takesTheDiscountedPriceWhenTheOriginalIsStruckOut() {
        assertEquals(4.99, DealFrameReader.parsePrice("$5.97 $4.99"), 1e-9);
        assertEquals(4.99, DealFrameReader.parsePrice("4.99"), 1e-9);
        assertNull(DealFrameReader.parsePrice("2,500"));
    }

    @Test
    void cleansOcrNoiseAroundNamesAndTitles() {
        assertEquals("5m Construction Speedup", DealFrameReader.itemName("SF 5m Construction Speedup"));
        assertEquals("1h Construction Speedup", DealFrameReader.itemName("lh Construction Speedup"));
        assertEquals("Shining Custom Chest", DealFrameReader.cleanTitle("Shining Custom Chest / my"));
        assertEquals("Dazzling Custom Chest", DealFrameReader.cleanTitle("q Dazzling Custom Chest"));
        assertFalse(DealFrameReader.isTitleText("et no po"));
        assertTrue(DealFrameReader.isListedItemName("5m Construction Speedup"));
        assertFalse(DealFrameReader.isListedItemName("\"fy;"));
        assertFalse(DealFrameReader.isListedItemName("oF"));
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

    static RawImageData rgbaFrame(BufferedImage image) {
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
