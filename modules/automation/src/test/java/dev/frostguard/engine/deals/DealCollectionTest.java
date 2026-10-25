package dev.frostguard.engine.deals;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

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
