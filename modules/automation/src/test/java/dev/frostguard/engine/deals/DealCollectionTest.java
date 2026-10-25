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
