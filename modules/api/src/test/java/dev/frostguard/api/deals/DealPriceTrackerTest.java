package dev.frostguard.api.deals;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

class DealPriceTrackerTest {

    private static final LocalDate DAY = LocalDate.of(2026, 9, 13);

    @Test
    void splitsEveryPackPriceExactlyAcrossItsItems() {
        Map<LocalDate, DealScan> history = new TreeMap<>();
        history.put(DAY, scan(
                pack("Speed Pack", 4.99, item("1h Speedup", 16), item("10K Meat", 500)),
                pack("Meat Pack", 9.99, item("10K Meat", 2000), item("1h Speedup", 10)),
                pack("Big Speed", 19.99, item("1h Speedup", 80))));

        List<DealPriceTracker.Observation> observations = DealPriceTracker.observations(history, Map.of());

        for (String pack : List.of("Speed Pack", "Meat Pack", "Big Speed")) {
            double paid = observations.stream().filter(o -> o.pack().equals(pack))
                    .mapToDouble(o -> o.unitUsd() * o.quantity()).sum();
            double price = observations.stream().filter(o -> o.pack().equals(pack)).findFirst().orElseThrow()
                    .packPriceUsd();
            assertEquals(price, paid, 1e-9, pack);
        }
    }

    @Test
    void operatorValueIsHeldFixedWhileOtherItemsAreFittedAroundIt() {
        Map<LocalDate, DealScan> history = new TreeMap<>();
        history.put(DAY, scan(pack("A", 10.0, item("1h Speedup", 5), item("Gems", 1000))));

        Map<String, Double> values = DealPriceTracker.unitValues(history, Map.of("1h Speedup", 1.0));

        assertEquals(1.0, values.get("1h Speedup"), 1e-12);
        assertEquals(0.005, values.get("Gems"), 1e-6);
    }

    @Test
    void gemPriceIsTheDollarPriceOverTheFittedValueOfAGem() {
        Map<LocalDate, DealScan> history = new TreeMap<>();
        history.put(DAY, scan(pack("A", 10.0, item("1h Speedup", 10), item("Gems", 1000))));

        DealPriceTracker.Observation speedup = DealPriceTracker.observations(history, Map.of("1h Speedup", 0.5))
                .stream().filter(o -> o.item().equals("1h Speedup")).findFirst().orElseThrow();

        assertEquals(0.5, speedup.unitUsd(), 1e-6);
        assertEquals(100.0, speedup.unitGems(), 1e-3);
    }

    @Test
    void summaryFindsCheapestPriciestRecentAveragesAndMonthOverMonthChange() {
        Map<LocalDate, DealScan> history = new TreeMap<>();
        history.put(LocalDate.of(2026, 8, 1), scan(pack("Aug", 10.0, item("1h Speedup", 10))));
        history.put(DAY.minusDays(10), scan(pack("Early Sep", 11.0, item("1h Speedup", 10))));
        history.put(DAY, scan(pack("Today", 12.0, item("1h Speedup", 10))));

        List<DealPriceTracker.Observation> observations = DealPriceTracker.observations(history, Map.of());
        DealPriceTracker.Summary summary = DealPriceTracker.summarize("1h Speedup", observations, DAY);

        assertEquals(3, summary.observations());
        assertEquals("Aug", summary.cheapest().pack());
        assertEquals(1.0, summary.cheapest().unitUsd(), 1e-9);
        assertEquals("Today", summary.priciest().pack());
        assertEquals(1.2, summary.last7AverageUsd(), 1e-9);
        assertEquals(1.15, summary.last30AverageUsd(), 1e-9);
        assertEquals(1.15 / 1.0 - 1, summary.monthOverMonthChange(), 1e-9);
        assertEquals(1.0, summary.monthlyAverageUsd().get(YearMonth.of(2026, 8)), 1e-9);
        assertNull(summary.cheapest().unitGems(), "no Gems item was ever priced, so gem prices stay unknown");
        assertEquals(3, summary.dailyCheapestUsd().size());
    }

    @Test
    void unpricedAndPurchasedPacksNeverBecomePrices() {
        Map<LocalDate, DealScan> history = new TreeMap<>();
        history.put(DAY, scan(
                new DealOffer("Deals", "Vault", "Vault", null, "Purchased", null, true,
                        List.of(item("Gems", 2500)), "f.png"),
                new DealOffer("Deals", "Top-up", "Top-up", null, "96,000 more top-up points", null, false,
                        List.of(item("Gems", 500000)), "f.png")));

        List<DealPriceTracker.Observation> observations = DealPriceTracker.observations(history, Map.of());

        assertEquals(0, observations.size());
        assertNull(DealPriceTracker.summarize("Gems", observations, DAY));
    }

    private static DealScan scan(DealOffer... offers) {
        return new DealScan(DAY + "T20:30", List.of(offers), List.of());
    }

    private static DealOffer pack(String title, double price, DealItem... items) {
        return new DealOffer("Gem Shop", "Tab", title, price, "$" + price, 1, false, List.of(items), "f.png");
    }

    private static DealItem item(String key, long quantity) {
        return new DealItem(key, quantity, "icon");
    }
}
