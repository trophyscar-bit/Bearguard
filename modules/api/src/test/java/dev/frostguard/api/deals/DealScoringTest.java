package dev.frostguard.api.deals;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

class DealScoringTest {

    private static final LocalDate DAY = LocalDate.of(2026, 9, 13);

    @Test
    void packGivingTwiceTheUsualSpeedupsPerDollarScoresTwiceAnOrdinaryPack() {
        Map<LocalDate, DealScan> history = history(DAY,
                offer("A", 4.99, item("1h Speedup", 16)),
                offer("B", 4.99, item("1h Speedup", 16)),
                offer("C", 4.99, item("1h Speedup", 32)));

        List<DealScoring.ScoredOffer> scored = DealScoring.score(history, Map.of());

        assertEquals(1.0, find(scored, "A").score(), 1e-9);
        assertEquals(2.0, find(scored, "C").score(), 1e-9);
        assertEquals(DealScoring.BASIS_LEARNED, find(scored, "C").basis());
        assertEquals("Best this week", find(scored, "C").verdict());
    }

    @Test
    void offerWithoutReadableItemsOrPriceStaysUnscoredInsteadOfGuessed() {
        Map<LocalDate, DealScan> history = history(DAY,
                offer("No items", 4.99),
                new DealOffer("Deals", "Tab", "No price", null, "$4?99", 1, false,
                        List.of(item("1h Speedup", 16)), "f.png", null));

        List<DealScoring.ScoredOffer> scored = DealScoring.score(history, Map.of());

        assertNull(find(scored, "No items").score());
        assertEquals("Not scored", find(scored, "No items").verdict());
        assertNull(find(scored, "No price").score());
    }

    @Test
    void operatorDollarValuesAnchorTheScore() {
        Map<LocalDate, DealScan> history = history(DAY,
                offer("A", 5.0, item("1h Speedup", 10), item("10K Meat", 100)));

        DealScoring.ScoredOffer scored = DealScoring.score(history, Map.of("1h Speedup", 1.0)).get(0);

        assertEquals(2.0, scored.score(), 1e-3);
        assertEquals(DealScoring.BASIS_VALUES, scored.basis());
    }

    @Test
    void thinWeekIsLabelledAnEarlyReadAndThePoorerPackIsMarkedBad() {
        Map<LocalDate, DealScan> history = history(DAY,
                offer("Good", 5.0, item("Gems", 2500)),
                offer("Poor", 5.0, item("Gems", 1000)));

        List<DealScoring.ScoredOffer> scored = DealScoring.score(history, Map.of());

        assertTrue(find(scored, "Poor").verdict().startsWith("Early read: Bad"), find(scored, "Poor").verdict());
    }

    @Test
    void repeatPackRemembersItsLowestEarlierPriceAndFirstDay() {
        Map<LocalDate, DealScan> history = new TreeMap<>();
        history.put(DAY.minusDays(2), scan(offer("Pack", 9.99)));
        history.put(DAY.minusDays(1), scan(offer("Pack", 4.99)));
        history.put(DAY, scan(offer("Pack", 9.99)));

        DealScoring.ScoredOffer today = DealScoring.score(history, Map.of()).stream()
                .filter(o -> o.day().equals(DAY)).findFirst().orElseThrow();

        assertEquals(4.99, today.lowestEarlierPrice(), 1e-9);
        assertEquals(3, today.daysSeen());
        assertEquals(DAY.minusDays(2), today.firstSeen());
    }

    private static DealScoring.ScoredOffer find(List<DealScoring.ScoredOffer> scored, String title) {
        return scored.stream().filter(o -> o.offer().title().equals(title)).findFirst().orElseThrow();
    }

    private static Map<LocalDate, DealScan> history(LocalDate day, DealOffer... offers) {
        Map<LocalDate, DealScan> history = new TreeMap<>();
        history.put(day, scan(offers));
        return history;
    }

    private static DealScan scan(DealOffer... offers) {
        return new DealScan(DAY + "T20:30", List.of(offers), List.of());
    }

    private static DealOffer offer(String title, double price, DealItem... items) {
        return new DealOffer("Gem Shop", "Tab", title, price, "$" + price, 1, false, List.of(items), "f.png", null);
    }

    private static DealItem item(String key, long quantity) {
        return new DealItem(key, quantity, "icon");
    }
}
