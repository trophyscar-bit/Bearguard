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
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.vision.convert.ImageConverter;
import dev.frostguard.vision.deals.SegmentedControl;
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
    void recordsEveryTierOfAChooseYourOwnPage() throws Exception {
        for (String frame : List.of("gem-shop-custom-chest-top.png", "gem-shop-custom-chest-middle.png",
                "gem-shop-custom-chest-scrolled-live.png")) {
            DealFrameReader.Page page = read(frame, "Mix & Match");

            assertTrue(page.chooseYourOwn(), frame);
            assertFalse(page.offers().isEmpty(), frame);
            // A tier whose title is under the sticky header is named after its tab and price, never the header.
            assertTrue(page.offers().stream().allMatch(o -> o.priceUsd() != null
                    && (o.title().matches("(Shining|Brilliant|Dazzling|Exquisite|Majestic) Custom Chest.*")
                            || o.title().startsWith("Mix & Match - $"))), frame + " -> " + page.offers());
        }
    }

    /**
     * An owned weekly card (2026-10-23 22:02, frame 042) shows "Claimed / Remaining claims: 5" where its
     * "Valid: 7 day(s)" stood. Its total is still the card's: 10 up front and 6 a day for 7 days.
     */
    @Test
    void totalsAnOwnedWeeklyCardOverItsWeek() throws Exception {
        DealOffer card = read("gem-shop-weekly-card-owned.png", "Weekly/Monthly Cards").offers().stream()
                .filter(o -> o.title().equals("Fire Crystal Weekly Card")).findFirst().orElseThrow();

        assertEquals(52L, quantities(card).get("Fire Crystal"), card.toString());
        assertEquals(7, card.card().validDays());
    }

    /** The first tier under the Custom Pet Chest header is named by its own banner, not the header's description. */
    @Test
    void namesTheFirstPickYourOwnTierByItsBanner() throws Exception {
        DealFrameReader.Page page = read("gem-shop-custom-pet-chest-top.png", "Custom Pet Chest");

        assertEquals(List.of("Shining Custom Chest", "Brilliant Custom Chest"),
                page.offers().stream().map(DealOffer::title).toList());
    }

    /**
     * Live frame of 2026-10-25: Hero Rally's Unlock preview. Its $9.99 button is grey only because no reward is
     * picked yet, so it is not a bought pack; the 70 Fire Crystals are what #fire-crystals ranks.
     */
    @Test
    void readsTheHeroRallyUnlockPreviewAsUnbought() throws Exception {
        DealFrameReader.Page page = read("popup-hero-rally-unlock.png", "Hero Rally");

        assertEquals(1, page.offers().size(), page.offers().toString());
        DealOffer offer = page.offers().get(0);
        assertEquals("Max Level Rewards Preview", offer.title());
        assertEquals(9.99, offer.priceUsd(), 0.001);
        assertFalse(offer.purchased(), "a pending reward choice greys the button, not a purchase");
        Map<String, Long> items = offer.items().stream().collect(Collectors.toMap(DealItem::key, DealItem::quantity));
        assertEquals(70L, items.get("Fire Crystal"));
        assertEquals(4800L, items.get("Gems"));
    }

    /** Live frame of 2026-10-23: the first "+" slot of the Shining Custom Chest, opened. */
    @Test
    void readsTheChoicesOfAPickSlot() throws Exception {
        BufferedImage image = ImageIO.read(Objects.requireNonNull(
                DealFrameReaderTest.class.getResourceAsStream("/deals/popup-custom-chest-select-items.png")));

        List<String> choices = reader.readSlotChoices(rgbaFrame(image)).orElseThrow().stream()
                .map(i -> i.key() + "=" + i.quantity()).toList();

        assertEquals(List.of("100 Enhancement XP Component=6", "Lucky Hero Gear Chest=4", "Essence Stones=4"), choices);
    }

    @Test
    void findsThreePickSlotsOnEachVisibleTier() throws Exception {
        DealFrameReader.Page page = read("gem-shop-custom-chest-top.png", "Mix & Match");

        assertEquals(page.offers().size(), page.pickSlots().size(), page.pickSlots().toString());
        page.pickSlots().values().forEach(slots -> assertEquals(3, slots.size(), slots.toString()));
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
        // Molly's Blessing is missed by the page-wide pass on its gradient banner; it is read from the card's
        // own title strip.
        assertEquals("Molly's Blessing", page.offers().get(0).title());
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
        // The "Purchase All" banner is an offer of its own; the "Daily Limit: 1" under it belongs to the tiers.
        List<Double> prices = page.offers().stream().map(DealOffer::priceUsd).toList();
        assertEquals(List.of(4.99, 0.99, 1.99, 2.99), prices, page.offers().toString());
        assertTrue(page.offers().get(0).title().startsWith("Purchase All"), page.offers().get(0).title());
        page.offers().stream().skip(1).forEach(o -> assertEquals("daily", o.limitPeriod(), o.toString()));
        assertEquals(30, page.offers().get(1).purchasesPerMonth());
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
        assertEquals("Brilliant Custom Chest", DealFrameReader.cleanTitle("Brilliant Custom Chest a"));
        assertEquals("Sign-in & Earn It", DealFrameReader.cleanTitle("Sign-in & Earn It"));
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
        assertEquals(2_500L, toolItems.get("Gems"), toolItems.toString());
        assertEquals(15L, toolItems.get("Polishing Solution"), toolItems.toString());
        assertEquals(25L, toolItems.get("100 VIP XP"), toolItems.toString());
        assertEquals(130L, toolItems.get("10K Meat"), toolItems.toString());
        assertEquals("weekly", tool.limitPeriod());
        assertEquals(1, tool.remaining());

        Map<String, Long> enhancementItems = quantities(enhancement);
        assertEquals(900L, enhancementItems.get("Hardened Alloy"), enhancementItems.toString());
        assertEquals(9L, enhancementItems.get("Polishing Solution"), enhancementItems.toString());
        assertEquals(20L, enhancementItems.get("Design Plans"), enhancementItems.toString());
        assertEquals(2_500L, enhancementItems.get("Gems"), enhancementItems.toString());
        assertEquals(25L, enhancementItems.get("100 VIP XP"), enhancementItems.toString());
        assertEquals("daily", enhancement.limitPeriod());
        assertEquals(5, enhancement.limitCount());
        // The third card's counts run off the bottom of the frame: its row is swiped once it scrolls up whole.
        assertEquals(2, page.cardRowYs().size(), "each whole card's row runs past its button: " + page.cardRowYs());

        // Items keep the card's left-to-right order; an unread tile is skipped, never moved.
        assertInCardOrder(tool, List.of("Gems", "Polishing Solution", "Hardened Alloy", "100 VIP XP", "10K Meat"));
        assertInCardOrder(enhancement,
                List.of("Gems", "Design Plans", "Polishing Solution", "Hardened Alloy", "100 VIP XP"));
    }

    private static void assertInCardOrder(DealOffer offer, List<String> cardOrder) {
        List<String> read = offer.items().stream().map(DealItem::key).toList();
        int next = 0;
        for (String key : read) {
            int at = cardOrder.indexOf(key);
            assertTrue(at >= next, offer.title() + " read " + read + ", card shows " + cardOrder);
            next = at + 1;
        }
    }

    /**
     * The one-pack event page of 2026-10-22, checked by eye: its banner gems, the purple Polishing Solution,
     * the "Event Remaining: 1" limit, and the last tile cut by the card edge (its 130 once read as 134) left
     * for the swiped frame to read whole.
     */
    @Test
    void readsEveryItemOfTheChiefGearEnhancementSpecialAcrossItsSwipedRow() throws Exception {
        DealOffer first = read("gem-shop-top-chief-gear-special.png", "The Top Chief").offers().get(0);
        DealOffer swiped = read("gem-shop-top-chief-gear-special-swiped.png", "The Top Chief").offers().get(0);

        assertEquals("Chief Gear Enhancement Special", first.title());
        assertEquals("event", first.limitPeriod());
        assertEquals(1, first.remaining());
        assertEquals(List.of("Gems=2500", "Design Plans=35", "Polishing Solution=40", "Hardened Alloy=4000",
                "100 VIP XP=25"), named(first));
        assertEquals(List.of("Gems=2500", "10K Meat=130", "10K Wood=130", "1K Coal=240", "1K Iron=60",
                "Alliance Gift I=1"), named(swiped));
    }

    @Test
    void joinsATitleThatWrapsOntoTwoLines() throws Exception {
        DealOffer offer = read("gem-shop-exclusive-gear-special.png", "Exclusive Gear").offers().get(0);

        assertEquals("Exclusive Gear Enhancement Special", offer.title());
        assertEquals(List.of("Gems=2500", "Gen 2 Custom Hero Widget Chest=5", "100 VIP XP=25", "10K Meat=130",
                "10K Wood=130"), named(offer));
    }

    /** Small tiles of the Leap Pack tab: 1hr(s), 1K VIP XP and 100K Meat were missing or misnamed before. */
    @Test
    void readsEveryTileOfTheSmallLeapPackGrid() throws Exception {
        DealFrameReader.Page page = read("gem-shop-leap-packs-purchased-1-2.png", "Fire Crystal Leap Pack");

        assertEquals(List.of("Gems=50000", "Fire Crystal=500", "1h Speedup=300", "5min Speedup=4800",
                "1K VIP XP=20", "100K Meat=1000", "100K Wood=1000", "100K Coal=200", "100K Iron=50", "Alliance Gift V=1"),
                named(offerPriced(page, 99.99)));
        assertEquals(List.of("Gems=5000", "Fire Crystal=50", "1h Speedup=30", "5min Speedup=480",
                "100 VIP XP=40", "10K Meat=1000", "10K Wood=1000", "10K Coal=200", "10K Iron=50", "Alliance Gift II=1"),
                named(offerPriced(page, 9.99)));
        assertEquals(List.of("Gems=2500", "Fire Crystal=25", "1h Speedup=15", "5min Speedup=240",
                "100 VIP XP=25", "10K Meat=500", "10K Wood=500", "10K Coal=100", "10K Iron=25", "Alliance Gift I=1"),
                named(offerPriced(page, 4.99)), "the bought pack's grey button still prices its card");
        assertEquals("Fire Crystal Leap Pack 1", offerPriced(page, 4.99).title(), "its narrow \"1\" stays in the title");
        assertEquals("Fire Crystal Leap Pack 2", offerPriced(page, 9.99).title());
        assertTrue(page.cardRowYs().isEmpty(), "Leap Pack rows are complete: " + page.cardRowYs());
        // Packs 1 and 2 were bought: their buttons are grey, and they are recorded as purchased.
        assertTrue(offerPriced(page, 4.99).purchased(), "Leap Pack 1 has a grey button");
        assertTrue(offerPriced(page, 9.99).purchased(), "Leap Pack 2 has a grey button");
        assertFalse(offerPriced(page, 99.99).purchased(), "Leap Pack 5 is still for sale");
    }

    @Test
    void readsTheIntelMonthlyCardGrantAtBothTiers() throws Exception {
        assertEquals(List.of("Gems=5000", "Fire Crystal=83", "1h Speedup=45", "100 VIP XP=40",
                "100K Resource Supply Chest=125", "Lucky Hero Gear Chest (5 Intel missions)=6",
                "Fire Crystal (10 Intel missions)=20", "Lucky Hero Gear Chest (20 Intel missions)=6"),
                named(read("deals-intel-monthly-card-mythic.png", "Intel Monthly Card").offers().get(0)));
        assertEquals(List.of("Gems=2500", "Fire Crystal=33", "1h Speedup=18", "100 VIP XP=25",
                "100K Resource Supply Chest=50", "Lucky Hero Gear Chest (5 Intel missions)=3",
                "Fire Crystal (10 Intel missions)=10", "Lucky Hero Gear Chest (20 Intel missions)=3"),
                named(read("deals-intel-monthly-card-epic.png", "Intel Monthly Card").offers().get(0)));
    }

    /**
     * Daily Deals of 2026-10-24: the "Purchase All Discount Packs" banner is an offer of its own, and each
     * untitled tier carries the gems printed beside its gem, not "4" read off the gem itself.
     */
    @Test
    void readsDailyDealsPurchaseAllAndEachTiersGems() throws Exception {
        DealFrameReader.Page page = read("gem-shop-daily-deals-top.png", "Daily Deals");

        assertEquals(List.of("Purchase All Discount Packs=4.99", "Daily Deals - $0.99=0.99",
                "Daily Deals - $1.99=1.99", "Daily Deals - $2.99=2.99"),
                page.offers().stream().map(o -> o.title() + "=" + o.priceUsd()).toList());
        assertEquals(List.of("Gems=500", "Hero Shard Chest=1", "3h Speedup=1", "Mystery Badge=200"),
                named(page.offers().get(1)));
        assertEquals(List.of(500L, 1000L, 1500L), page.offers().stream().skip(1)
                .map(o -> o.items().stream().filter(i -> i.key().equals("Gems")).mapToLong(DealItem::quantity).sum())
                .toList());
        assertEquals("daily", page.offers().get(1).limitPeriod());
    }

    /**
     * Regular Pack of 2026-10-25: a speedup is named by the mark in its tile's corner, so City Construction's
     * hammer-marked speedups are not the plain ones beside them, and Tech Pack's 1h is a research speedup.
     */
    @Test
    void namesSpeedupsByTheMarkOnTheirTile() throws Exception {
        DealOffer city = offerTitled(read("gem-shop-regular-construction-speedups.png", "Regular Pack"),
                "City Construction Pack");
        assertEquals(List.of("Gems=2500", "1h Speedup=3", "5min Speedup=36", "1h Construction Speedup=2",
                "5min Construction Speedup=24"), named(city));

        DealOffer tech = offerTitled(read("gem-shop-regular-research-speedups.png", "Regular Pack"), "Tech Pack");
        assertEquals(List.of("Gems=2500", "1h Speedup=3", "5min Speedup=36", "1h Research Speedup=2",
                "5min Research Speedup=24"), named(tech));
    }

    private static DealOffer offerTitled(DealFrameReader.Page page, String title) {
        return page.offers().stream().filter(o -> o.title().equals(title)).findFirst()
                .orElseThrow(() -> new AssertionError(title + " not in " + page.offers()));
    }

    /** Hero Rally's header "Unlock", which the word pass missed on every frame of 2026-10-25 evening. */
    @Test
    void findsTheHeroRallyUnlockButtonAndNoneElsewhere() throws Exception {
        PointData unlock = reader.unlockButton(frame("deals-hero-rally-unlock.png"), 640).orElseThrow();
        assertTrue(unlock.getX() > 500 && unlock.getX() < 680 && unlock.getY() > 370 && unlock.getY() < 450,
                unlock.toString());
        assertTrue(reader.unlockButton(frame("deals-journey-treasures.png"), 640).isEmpty());
        assertTrue(reader.unlockButton(frame("deals-intel-monthly-card-mythic.png"), 640).isEmpty());
    }

    private static RawImageData frame(String name) throws Exception {
        return rgbaFrame(ImageIO.read(Objects.requireNonNull(
                DealFrameReaderTest.class.getResourceAsStream("/deals/" + name), name)));
    }

    /** The mission rows' green "(570/5)": #fire-crystals counts mission rewards already passed. */
    @Test
    void readsTheIntelMissionsDoneFromTheCardRows() throws Exception {
        assertEquals(Map.of("Intel", 570L), read("deals-intel-monthly-card-mythic.png", "Intel Monthly Card")
                .missionsDone());
        assertEquals(Map.of("Intel", 570L), read("deals-intel-monthly-card-epic.png", "Intel Monthly Card")
                .missionsDone());
    }

    @Test
    void readsNoMissionsDoneWhereAPageHasNoMissionRows() throws Exception {
        assertTrue(read("gem-shop-weekly-card-owned.png", "Weekly Card").missionsDone().isEmpty());
    }

    /** Both tiers are named, selected or not, so the posts say "(Epic)", not "variant 1 of 2". */
    @Test
    void namesBothIntelCardTiersOnEitherFrame() throws Exception {
        assertEquals(List.of("Epic", "Mythic"), toggleLabels("deals-intel-monthly-card-epic.png"));
        assertEquals(List.of("Epic", "Mythic"), toggleLabels("deals-intel-monthly-card-mythic.png"));
    }

    private static List<String> toggleLabels(String name) throws Exception {
        RawImageData frame = rgbaFrame(ImageIO.read(Objects.requireNonNull(
                DealFrameReaderTest.class.getResourceAsStream("/deals/" + name))));
        SegmentedControl.Control toggle = SegmentedControl.locate(ImageConverter.toBufferedImage(frame)).orElseThrow();
        return toggle.options().stream().map(option -> reader.optionLabel(frame, option).orElse("?")).toList();
    }

    private static List<String> named(DealOffer offer) {
        return offer.items().stream().map(i -> i.key() + "=" + i.quantity()).toList();
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
