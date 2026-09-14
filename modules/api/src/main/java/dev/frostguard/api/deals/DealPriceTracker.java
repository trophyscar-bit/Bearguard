package dev.frostguard.api.deals;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Per-item price history: what one unit of an item cost inside every pack it appeared in.
 *
 * <p>A pack's price covers several items at once, so it is split by value. Every item has a
 * unit value; a pack's price is shared out in proportion to {@code quantity x unit value}, which
 * gives each item a unit price for that pack. Unit values are fitted to the whole history: each
 * value is scaled until the median pack containing that item costs exactly what its items are worth.
 * Values the operator set in {@code item-values.json} are held fixed and anchor the rest. Because
 * the fit runs over whatever history exists, the values move as new prices arrive.</p>
 *
 * <p>Gem prices divide an item's dollar price by the fitted dollar value of one gem, the {@value #GEMS}
 * item read from gem tiles and gem rows in packs. Without that item in the history, gem prices are
 * unknown rather than assumed. The gold badge beside a price button is top-up points, not gems.</p>
 */
public final class DealPriceTracker {

    static final int FIT_ITERATIONS = 60;
    public static final String GEMS = "Gems";

    private DealPriceTracker() {
    }

    public record Observation(LocalDate day, String item, double unitUsd, Double unitGems, String pack,
            double packPriceUsd, long quantity) {
    }

    public record Summary(String item, int observations, Observation latest, Observation cheapest,
            Observation priciest, double averageUsd, Double averageGems, Double last7AverageUsd,
            Double last30AverageUsd, SortedMap<YearMonth, Double> monthlyAverageUsd,
            SortedMap<LocalDate, Double> dailyCheapestUsd) {

        /** This month's average against last month's, as a fraction; {@code null} without both months. */
        public Double monthOverMonthChange() {
            if (monthlyAverageUsd.size() < 2) {
                return null;
            }
            List<Double> months = new ArrayList<>(monthlyAverageUsd.values());
            double previous = months.get(months.size() - 2);
            return previous <= 0 ? null : months.get(months.size() - 1) / previous - 1;
        }
    }

    /** Fitted dollars per unit for every item seen in a priced pack. */
    public static Map<String, Double> unitValues(Map<LocalDate, DealScan> history, Map<String, Double> manual) {
        List<DealOffer> packs = pricedPacks(history);
        Map<String, List<Double>> starts = new HashMap<>();
        for (DealOffer pack : packs) {
            for (DealItem item : pack.items()) {
                starts.computeIfAbsent(item.key(), k -> new ArrayList<>())
                        .add(pack.priceUsd() / (item.quantity() * pack.items().size()));
            }
        }
        Map<String, Double> values = new HashMap<>();
        starts.forEach((key, list) -> values.put(key, manual.containsKey(key) ? manual.get(key) : median(list)));

        for (int iteration = 0; iteration < FIT_ITERATIONS; iteration++) {
            Map<String, List<Double>> factors = new HashMap<>();
            for (DealOffer pack : packs) {
                double worth = worth(pack, values);
                if (worth <= 0) {
                    continue;
                }
                for (DealItem item : pack.items()) {
                    factors.computeIfAbsent(item.key(), k -> new ArrayList<>()).add(pack.priceUsd() / worth);
                }
            }
            factors.forEach((key, list) -> {
                if (!manual.containsKey(key)) {
                    values.put(key, values.get(key) * median(list));
                }
            });
        }
        return values;
    }

    public static List<Observation> observations(Map<LocalDate, DealScan> history, Map<String, Double> manual) {
        Map<String, Double> values = unitValues(history, manual);
        Double usdPerGem = values.get(GEMS);
        List<Observation> observations = new ArrayList<>();
        for (Map.Entry<LocalDate, DealScan> day : new TreeMap<>(history).entrySet()) {
            for (DealOffer pack : day.getValue().offers()) {
                if (!pack.isPricedPack()) {
                    continue;
                }
                double worth = worth(pack, values);
                if (worth <= 0) {
                    continue;
                }
                for (DealItem item : pack.items()) {
                    double unitUsd = pack.priceUsd() * values.get(item.key()) / worth;
                    observations.add(new Observation(day.getKey(), item.key(), unitUsd,
                            usdPerGem == null || usdPerGem <= 0 ? null : unitUsd / usdPerGem, pack.title(), pack.priceUsd(),
                            item.quantity()));
                }
            }
        }
        return observations;
    }

    /** Summary of one item's observations, or {@code null} when the item was never priced. */
    public static Summary summarize(String item, List<Observation> all, LocalDate today) {
        List<Observation> mine = all.stream().filter(o -> o.item().equals(item)).toList();
        if (mine.isEmpty()) {
            return null;
        }
        Observation latest = mine.stream().max(Comparator.comparing(Observation::day)).orElseThrow();
        Observation cheapest = mine.stream().min(Comparator.comparingDouble(Observation::unitUsd)).orElseThrow();
        Observation priciest = mine.stream().max(Comparator.comparingDouble(Observation::unitUsd)).orElseThrow();
        double average = mine.stream().mapToDouble(Observation::unitUsd).average().orElse(0);
        List<Double> gems = mine.stream().map(Observation::unitGems).filter(g -> g != null).toList();

        SortedMap<YearMonth, List<Double>> byMonth = new TreeMap<>();
        SortedMap<LocalDate, Double> dailyCheapest = new TreeMap<>();
        for (Observation o : mine) {
            byMonth.computeIfAbsent(YearMonth.from(o.day()), m -> new ArrayList<>()).add(o.unitUsd());
            dailyCheapest.merge(o.day(), o.unitUsd(), Math::min);
        }
        SortedMap<YearMonth, Double> monthly = new TreeMap<>();
        byMonth.forEach((month, list) -> monthly.put(month, list.stream().mapToDouble(d -> d).average().orElse(0)));

        return new Summary(item, mine.size(), latest, cheapest, priciest, average,
                gems.isEmpty() ? null : gems.stream().mapToDouble(d -> d).average().orElse(0),
                averageSince(mine, today.minusDays(6)), averageSince(mine, today.minusDays(29)), monthly,
                dailyCheapest);
    }

    private static Double averageSince(List<Observation> observations, LocalDate from) {
        List<Double> recent = observations.stream().filter(o -> !o.day().isBefore(from))
                .map(Observation::unitUsd).toList();
        return recent.isEmpty() ? null : recent.stream().mapToDouble(d -> d).average().orElse(0);
    }

    static double worth(DealOffer pack, Map<String, Double> values) {
        double worth = 0;
        for (DealItem item : pack.items()) {
            Double value = values.get(item.key());
            if (value != null) {
                worth += item.quantity() * value;
            }
        }
        return worth;
    }

    static List<DealOffer> pricedPacks(Map<LocalDate, DealScan> history) {
        List<DealOffer> packs = new ArrayList<>();
        history.values().forEach(scan -> scan.offers().stream().filter(DealOffer::isPricedPack).forEach(packs::add));
        return packs;
    }

    static double median(List<Double> values) {
        if (values.isEmpty()) {
            return 0;
        }
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Comparator.naturalOrder());
        int mid = sorted.size() / 2;
        return sorted.size() % 2 == 1 ? sorted.get(mid) : (sorted.get(mid - 1) + sorted.get(mid)) / 2;
    }
}
