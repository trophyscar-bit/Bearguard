package dev.frostguard.api.deals;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Turns scan history into per-offer scores and verdicts.
 *
 * <p>A pack's score is what its items are worth divided by what it costs, using the unit values
 * {@link DealPriceTracker} fits to the same history, so a pack verdict and an item's price history
 * never disagree about what an item is worth. 1.0 is an ordinary pack; 2.0 is twice the usual worth
 * per dollar. When the operator has priced any of the pack's items, the basis says so.</p>
 *
 * <p>An offer with no readable price or no readable items is left unscored rather than guessed.</p>
 */
public final class DealScoring {

    public static final String BASIS_VALUES = "includes your $ values";
    public static final String BASIS_LEARNED = "vs fitted item prices";

    static final int WEEK_DAYS = 7;
    /** Below this many scored offers in the week there is nothing meaningful to rank against. */
    static final int MIN_WEEK_SAMPLE = 3;
    static final double GREAT_RATIO = 1.25;
    static final double FAIR_RATIO = 0.85;

    private DealScoring() {
    }

    public record ItemRate(String key, double usdPerUnit, int observations, Double operatorUsdPerUnit) {
    }

    public record ScoredOffer(LocalDate day, DealOffer offer, Double score, String basis, String verdict,
            Double lowestEarlierPrice, int daysSeen, LocalDate firstSeen) {
    }

    public static List<ItemRate> itemRates(Map<LocalDate, DealScan> history, Map<String, Double> manual) {
        Map<String, Double> values = DealPriceTracker.unitValues(history, manual);
        Map<String, Integer> counts = new HashMap<>();
        DealPriceTracker.pricedPacks(history)
                .forEach(pack -> pack.items().forEach(item -> counts.merge(item.key(), 1, Integer::sum)));
        List<ItemRate> rates = new ArrayList<>();
        new TreeMap<>(values).forEach((key, value) ->
                rates.add(new ItemRate(key, value, counts.getOrDefault(key, 0), manual.get(key))));
        return rates;
    }

    /** Every offer in the history, scored, with the verdict it earned on its own day. */
    public static List<ScoredOffer> score(Map<LocalDate, DealScan> history, Map<String, Double> manual) {
        Map<String, Double> values = DealPriceTracker.unitValues(history, manual);

        List<ScoredOffer> unjudged = new ArrayList<>();
        Map<String, LocalDate> firstSeen = new HashMap<>();
        Map<String, Double> lowestPrice = new HashMap<>();
        Map<String, Integer> daysSeen = new HashMap<>();
        for (Map.Entry<LocalDate, DealScan> day : new TreeMap<>(history).entrySet()) {
            Map<String, Double> lowestToday = new HashMap<>();
            for (DealOffer offer : day.getValue().offers()) {
                String pack = offer.packKey();
                Double score = null;
                String basis = null;
                if (offer.isPricedPack()) {
                    double worth = DealPriceTracker.worth(offer, values);
                    if (worth > 0) {
                        score = worth / offer.priceUsd();
                        basis = offer.items().stream().anyMatch(i -> manual.containsKey(i.key()))
                                ? BASIS_VALUES : BASIS_LEARNED;
                    }
                }
                unjudged.add(new ScoredOffer(day.getKey(), offer, score, basis, null, lowestPrice.get(pack),
                        daysSeen.getOrDefault(pack, 0) + 1, firstSeen.getOrDefault(pack, day.getKey())));
                lowestToday.putIfAbsent(pack, null);
                if (offer.priceUsd() != null) {
                    lowestToday.merge(pack, offer.priceUsd(), (a, b) -> a == null ? b : Math.min(a, b));
                }
            }
            for (Map.Entry<String, Double> seen : lowestToday.entrySet()) {
                firstSeen.putIfAbsent(seen.getKey(), day.getKey());
                daysSeen.merge(seen.getKey(), 1, Integer::sum);
                if (seen.getValue() != null) {
                    lowestPrice.merge(seen.getKey(), seen.getValue(), Math::min);
                }
            }
        }

        List<ScoredOffer> judged = new ArrayList<>(unjudged.size());
        for (ScoredOffer candidate : unjudged) {
            judged.add(withVerdict(candidate, unjudged));
        }
        return judged;
    }

    private static ScoredOffer withVerdict(ScoredOffer candidate, List<ScoredOffer> all) {
        String verdict;
        if (candidate.score() == null) {
            verdict = candidate.offer().purchased() ? "Purchased" : "Not scored";
        } else {
            LocalDate from = candidate.day().minusDays(WEEK_DAYS - 1L);
            List<Double> week = all.stream()
                    .filter(o -> o.score() != null && !o.day().isBefore(from) && !o.day().isAfter(candidate.day()))
                    .map(ScoredOffer::score)
                    .toList();
            double weekMedian = DealPriceTracker.median(week);
            double weekBest = week.stream().mapToDouble(d -> d).max().orElse(candidate.score());
            double ratio = weekMedian > 0 ? candidate.score() / weekMedian : 1.0;
            String band = ratio >= GREAT_RATIO ? "Great" : ratio >= FAIR_RATIO ? "Fair" : "Bad deal";
            if (week.size() < MIN_WEEK_SAMPLE) {
                verdict = "Early read: " + band;
            } else if (candidate.score() >= weekBest) {
                verdict = "Best this week";
            } else {
                verdict = band;
            }
        }
        return new ScoredOffer(candidate.day(), candidate.offer(), candidate.score(), candidate.basis(), verdict,
                candidate.lowestEarlierPrice(), candidate.daysSeen(), candidate.firstSeen());
    }
}
