package dev.frostguard.engine.deals;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.frostguard.api.deals.DealItem;
import dev.frostguard.api.deals.DealOffer;

class DealCollectionTest {

    @Test
    void fillsPurchaseAllWithEveryTierOfItsTab() {
        DealCollection collection = new DealCollection();
        collection.add(offer("Purchase All Discount Packs", 4.99, List.of()));
        collection.add(offer("Daily Deals - $0.99", 0.99, List.of(new DealItem("Gems", 500, "icon"))));
        collection.add(offer("Daily Deals - $1.99", 1.99,
                List.of(new DealItem("Gems", 1000, "icon"), new DealItem("Mystery Badge", 400, "icon"))));

        collection.settle();

        DealOffer bundle = collection.offers().values().stream()
                .filter(o -> o.title().startsWith("Purchase All")).findFirst().orElseThrow();
        assertEquals(List.of("Gems=1500", "Mystery Badge=400"),
                bundle.items().stream().map(i -> i.key() + "=" + i.quantity()).toList());
    }

    @Test
    void keepsOnePurchaseAllNamedByItsMostCommonRead() {
        DealCollection collection = new DealCollection();
        collection.add(offer("Purchase All Discount Packs", 4.99, List.of()));
        collection.add(offer("Purchase All Discount acks", 4.99, List.of()));
        collection.add(offer("Purchase All Discount Packs", 4.99, List.of()));
        collection.add(offer("Daily Deals - $0.99", 0.99, List.of(new DealItem("Gems", 500, "icon"))));
        collection.add(offer("Daily Deals - $1.99", 1.99, List.of(new DealItem("Gems", 1000, "icon"))));

        collection.settle();

        assertEquals(List.of("Purchase All Discount Packs"), collection.offers().values().stream()
                .map(DealOffer::title).filter(t -> t.startsWith("Purchase All")).toList());
    }

    @Test
    void foldsATitleCutShortIntoItsPack() {
        DealCollection collection = new DealCollection();
        collection.add(regular("Accessory Construction Contract", List.of(new DealItem("5min Speedup", 10, "icon"))));
        collection.add(regular("Accessory Construction Con", List.of(new DealItem("10K Coal", 100, "icon"))));
        collection.add(regular("Pet Food Pack", List.of(new DealItem("Pet Food", 6000, "icon"))));

        collection.settle();

        assertEquals(List.of("Accessory Construction Contract", "Pet Food Pack"),
                collection.offers().values().stream().map(DealOffer::title).toList());
    }

    /** A shorter title whose shared item counts differ is a different pack, not a cut read. */
    @Test
    void keepsAShorterTitleWhoseCountsDiffer() {
        DealCollection collection = new DealCollection();
        collection.add(regular("Accessory Construction Contract", List.of(new DealItem("5min Speedup", 10, "icon"))));
        collection.add(regular("Accessory Construction Con", List.of(new DealItem("5min Speedup", 20, "icon"))));

        collection.settle();

        assertEquals(2, collection.offers().size());
    }

    private static DealOffer regular(String title, List<DealItem> items) {
        return new DealOffer("Gem Shop", "Regular Pack", title, 4.99, "$4.99", null, false, items, "061.png",
                "weekly", null, null, null);
    }

    /** Every day of a day grid is paid to the buyer: the days add up into the item's plain name. */
    @Test
    void addsADayGridsDaysIntoTotals() {
        DealCollection collection = new DealCollection();
        collection.add(new DealOffer("Deals", "Journey Treasures", "Journey Treasures", 19.99, "$19.99", null, false,
                List.of(new DealItem("Gems", 10000, "icon"), new DealItem("Fire Crystal (Day 1)", 50, "day"),
                        new DealItem("Fire Crystal (Day 2)", 5, "day")), "124.png", null, null, null, null));
        collection.add(new DealOffer("Deals", "Journey Treasures", "Journey Treasures", 19.99, "$19.99", null, false,
                List.of(new DealItem("Fire Crystal (Day 2)", 5, "day"), new DealItem("Fire Crystal (Day 20)", 5, "day"),
                        new DealItem("Essence Stones (Day 13)", 15, "day")), "125.png", null, null, null, null));

        collection.settle();

        Map<String, Long> items = collection.offers().values().iterator().next().items().stream()
                .collect(java.util.stream.Collectors.toMap(DealItem::key, DealItem::quantity));
        assertEquals(60L, items.get("Fire Crystal"), "50 + 5 + 5; Day 2 read on both frames counts once");
        assertEquals(15L, items.get("Essence Stones"));
        assertEquals(10000L, items.get("Gems"));
        assertEquals(5L, items.get("Fire Crystal (Day 20)"));
    }

    /** A card read again with its title scrolled away gives its foot to the card last read at its price. */
    @Test
    void joinsACardCutAboveToTheCardReadBeforeIt() {
        DealCollection collection = new DealCollection();
        collection.add(leap("Fire Crystal Leap Pack 5", "027.png", null, List.of(new DealItem("Fire Crystal", 500, "icon"))));
        collection.add(leap(DealFrameReader.CUT_ABOVE_TITLE, "028.png", "lifetime",
                List.of(new DealItem("10K Meat", 1000, "icon"))));

        collection.settle();

        assertEquals(1, collection.offers().size(), collection.offers().toString());
        DealOffer pack = collection.offers().values().iterator().next();
        assertEquals("Fire Crystal Leap Pack 5", pack.title());
        assertEquals("lifetime", pack.limitPeriod());
        assertEquals(2, pack.items().size());
    }

    private static DealOffer leap(String title, String frame, String limit, List<DealItem> items) {
        return new DealOffer("Gem Shop", "Fire Crystal Leap Pack", title, 99.99, "$99.99", null, false, items, frame,
                limit, limit == null ? null : 1, null, null);
    }

    /** An offer read with no title takes no part in folding cut titles, and does not stop the settle. */
    @Test
    void settlesWithAnUntitledOffer() {
        DealCollection collection = new DealCollection();
        collection.add(regular("Accessory Construction Contract", List.of(new DealItem("5min Speedup", 10, "icon"))));
        collection.add(new DealOffer("Gem Shop", "Regular Pack", null, 4.99, "$4.99", null, false, List.of(),
                "062.png", null, null, null, null));

        collection.settle();

        assertEquals(2, collection.offers().size());
    }

    /** A tier read with nothing would leave the sum short of what the banner sells; it stays unknown. */
    @Test
    void leavesPurchaseAllUnknownWhileATierIsUnread() {
        DealCollection collection = new DealCollection();
        collection.add(offer("Purchase All Discount Packs", 4.99, List.of()));
        collection.add(offer("Daily Deals - $0.99", 0.99, List.of(new DealItem("Gems", 500, "icon"))));
        collection.add(offer("Daily Deals - $1.99", 1.99, List.of()));

        collection.settle();

        assertTrue(collection.offers().values().stream()
                .filter(o -> o.title().startsWith("Purchase All")).allMatch(o -> o.items().isEmpty()));
        assertTrue(collection.problems().stream().anyMatch(p -> p.contains("contents left unknown")),
                collection.problems().toString());
    }

    private static DealOffer offer(String title, double price, List<DealItem> items) {
        return new DealOffer("Gem Shop", "Daily Deals", title, price, "$" + price, null, false, items, "001.png",
                "daily", null, null, null);
    }
}
