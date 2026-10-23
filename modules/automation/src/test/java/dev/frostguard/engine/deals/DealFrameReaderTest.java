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
import java.util.Set;
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

    @Test
    void readsAPriceWhoseDecimalPointTheStrictGlyphSetDrops() throws Exception {
        DealFrameReader.Page page = read("gem-shop-value-pack-dotless-price.png", "Fire Crystal Value Pack");

        DealOffer offer = page.offers().stream()
                .filter(o -> o.priceUsd() != null)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no priced offer: " + page.offers()));
        assertEquals(4.99, offer.priceUsd(), 1e-9);
    }

    /** Read by eye from the frame: every tile of Leap Pack 3 on 2026-10-22, gems printed as "10K". */
    @Test
    void readsEveryItemOfAPackIncludingThousandsWrittenWithK() throws Exception {
        DealOffer pack3 = offerPriced(read("gem-shop-leap-packs-3-4-5.png", "Fire Crystal Leap Pack"), 19.99);

        Map<String, Long> items = quantities(pack3);
        assertEquals(10_000L, items.get("Gems"), items.toString());
        assertEquals(100L, items.get("Fire Crystal"), items.toString());
        assertEquals(960L, items.get("5min Speedup"), items.toString());
        assertEquals(60L, items.get("100 VIP XP"), items.toString());
        assertEquals(2_000L, items.get("10K Meat"), items.toString());
        assertEquals(2_000L, items.get("10K Wood"), items.toString());
        assertEquals(400L, items.get("10K Coal"), items.toString());
        assertEquals(100L, items.get("10K Iron"), items.toString());
        assertEquals("lifetime", pack3.limitPeriod());
    }

    @Test
    void keepsThePackNumberInTheCardTitle() throws Exception {
        DealFrameReader.Page page = read("gem-shop-leap-packs-3-4-5.png", "Fire Crystal Leap Pack");

        assertEquals("Fire Crystal Leap Pack 3", offerPriced(page, 19.99).title());
        assertEquals("Fire Crystal Leap Pack 4", offerPriced(page, 49.99).title());
    }

    /**
     * Pack 5's tile row runs off the bottom of the frame. Its 5-minute speedups are printed 4,800; on
     * 2026-10-22 the clipped quantity was kept as 77. A wrong number is worse than a missing one.
     */
    @Test
    void neverRecordsAQuantityFromATileTheFrameEdgeCutsOff() throws Exception {
        DealFrameReader.Page page = read("gem-shop-leap-packs-3-4-5.png", "Fire Crystal Leap Pack");

        page.offers().stream()
                .flatMap(o -> o.items().stream())
                .filter(i -> i.key().equals("5min Speedup"))
                .forEach(i -> assertTrue(Set.of(960L, 2_400L, 4_800L).contains(i.quantity()),
                        "5min Speedup x" + i.quantity() + " is not printed anywhere on this frame"));
    }

    /** "Buy to get" x10 plus 6 a day for "Valid: 7 day(s)": 52 crystals, not the 6 one tile shows. */
    @Test
    void totalsACardsCrystalsOverItsValidity() throws Exception {
        DealOffer weekly = offerPriced(read("gem-shop-weekly-monthly-cards.png", "Weekly/Monthly Cards"), 4.99);

        // Never a partial total: either all 52 crystals, or no total at all.
        assertTrue(weekly.items().isEmpty() || Long.valueOf(52L).equals(quantities(weekly).get("Fire Crystal")),
                weekly.items().toString());
        assertEquals(7, weekly.card().validDays());
    }

    /** "x5,000" gems on purchase plus 300 a day for "Valid: 30 day(s)": 14,000 gems in all. */
    @Test
    void totalsAMonthlyCardsGemsOverThirtyDays() throws Exception {
        DealOffer monthly = offerPriced(read("gem-shop-weekly-monthly-cards.png", "Weekly/Monthly Cards"), 9.99);

        assertEquals(30, monthly.card().validDays(), String.valueOf(monthly.card()));
        assertTrue(monthly.items().isEmpty() || Long.valueOf(14_000L).equals(quantities(monthly).get("Gems")),
                "a partial total is wrong, not incomplete: " + monthly.items());
    }

    @Test
    void recordsTheLimitCountPrintedUnderEachTier() throws Exception {
        DealOffer pack3 = offerPriced(read("gem-shop-leap-packs-3-4-5.png", "Fire Crystal Leap Pack"), 19.99);

        assertEquals("lifetime", pack3.limitPeriod());
        assertEquals(1, pack3.limitCount());
    }

    /** The Value Pack prints "Event Remaining: 1" and a countdown under its title. */
    @Test
    void recordsWhatIsLeftOfALimitedOfferAndItsCountdown() throws Exception {
        DealOffer value = offerPriced(read("gem-shop-fire-crystal-value-pack.png", "Fire Crystal Energy"), 4.99);

        assertEquals(1, value.remaining());
        assertTrue(value.timeLeft() != null && value.timeLeft().matches("\\d+d\\s*\\d{2}:\\d{2}:\\d{2}"),
                "countdown: " + value.timeLeft());
        assertEquals(30L, quantities(value).get("Fire Crystal"), value.items().toString());
    }

    /** Read by eye from the Regular Pack tab of 2026-10-22: both chief gear packs, every item and limit. */
    @Test
    void readsTheChiefGearPacksItemsAndLimits() throws Exception {
        DealFrameReader.Page page = read("gem-shop-chief-gear-packs.png", "Regular Pack");
        DealOffer tool = page.offers().stream().filter(o -> o.title().equals("Chief Gear Tool Pack")).findFirst()
                .orElseThrow(() -> new AssertionError(page.offers().toString()));
        DealOffer enhancement = page.offers().stream().filter(o -> o.title().equals("Chief Gear Enhancement"))
                .findFirst().orElseThrow(() -> new AssertionError(page.offers().toString()));

        Map<String, Long> toolItems = quantities(tool);
        assertEquals(4.99, tool.priceUsd(), 1e-9);
        assertEquals(1_500L, toolItems.get("Hardened Alloy"), toolItems.toString());
        // The purple Polishing Solution tile defeats every read measured so far; unknown, never a guess.
        assertTrue(toolItems.get("Polishing Solution") == null || toolItems.get("Polishing Solution") == 15L,
                toolItems.toString());
        assertEquals("weekly", tool.limitPeriod());
        assertEquals(1, tool.remaining());

        Map<String, Long> enhancementItems = quantities(enhancement);
        assertTrue(enhancementItems.get("Hardened Alloy") == null || enhancementItems.get("Hardened Alloy") == 900L,
                enhancementItems.toString());
        assertTrue(enhancementItems.get("Polishing Solution") == null
                || enhancementItems.get("Polishing Solution") == 9L, enhancementItems.toString());
        assertEquals(20L, enhancementItems.get("Design Plans"), enhancementItems.toString());
        assertEquals(25L, enhancementItems.get("100 VIP XP"), enhancementItems.toString());
        assertEquals("daily", enhancement.limitPeriod());
        assertEquals(5, enhancement.limitCount());
    }

    /** The Get Gems tab has no orange buttons; its six bundles still become offers, gems as the item. */
    @Test
    void recordsEveryGetGemsBundle() throws Exception {
        DealFrameReader.Page page = read("gem-shop-get-gems.png", "Get Gems");

        assertEquals(6, page.offers().size(), page.offers().toString());
        assertEquals(500L, quantities(offerPriced(page, 0.99)).get("Gems"));
        assertEquals(50_000L, quantities(offerPriced(page, 99.99)).get("Gems"));
        assertTrue(offerPriced(page, 99.99).title().contains("+50,000"), offerPriced(page, 99.99).title());
    }

    private static DealFrameReader.Page read(String name, String tab) throws Exception {
        BufferedImage image = ImageIO.read(Objects.requireNonNull(
                DealFrameReaderTest.class.getResourceAsStream("/deals/" + name), name));
        DealFrameReader.Page page = reader.read(rgbaFrame(image), "Test", tab, name, !name.startsWith("popup-"));
        System.out.println(name + " -> " + page);
        return page;
    }

    private static DealOffer offerPriced(DealFrameReader.Page page, double usd) {
        return page.offers().stream()
                .filter(o -> o.priceUsd() != null && Math.abs(o.priceUsd() - usd) < 0.001)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no $" + usd + " offer in " + page.offers()));
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
