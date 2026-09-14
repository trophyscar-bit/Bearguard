package dev.frostguard.api.deals;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Turns scan history into per-offer scores and verdicts.
 *
 * <p>Two bases, in priority order. When the operator has priced any of an offer's items in
 * {@code item-values.json}, the score is dollars of value per dollar paid over those items. Otherwise
 * each item is compared with how many units a dollar usually buys across every pack in the history
 * (the median), and the score is the average of those ratios: 1.0 is an ordinary pack, 2.0 gives twice
 * the usual amount per dollar. The learned rates move as more packs are seen, which is the point.</p>
 *
 * <p>An offer with no readable price or no readable items is left unscored rather than guessed.</p>
 */
public final class DealScoring {

    public static final String BASIS_VALUES = "your $ values";
    public static final String BASIS_LEARNED = "vs usual per-dollar amounts";

    static final int WEEK_DAYS = 7;
    /** Below this many scored offers in the week there is nothing meaningful to rank against. */
    static final int MIN_WEEK_SAMPLE = 3;
    static final double GREAT_RATIO = 1.25;
    static final double FAIR_RATIO = 0.85;

    private DealScoring() {
    }

    public record ItemRate(String key, double medianPerDollar, int observations, Double usdPerUnit) {
    }

    public record ScoredOffer(LocalDate day, DealOffer offer, Double score, String basis, String verdict,
            Double lowestEarlierPrice, int daysSeen, LocalDate firstSeen) {
    }

    public static List<ItemRate> itemRates(Map<LocalDate, DealScan> history, Map<String, Double> values) {
        Map<String, List<Double>> perDollar = perDollarObservations(history);
        List<ItemRate> rates = new ArrayList<>();
        for (Map.Entry<String, List<Double>> entry : new TreeMap<>(perDollar).entrySet()) {
            rates.add(new ItemRate(entry.getKey(), median(entry.getValue()), entry.getValue().size(),
                    values.get(entry.getKey())));
        }
        return rates;
    }

    /** Every offer in the history, scored, with the verdict it earned on its own day. */
    public static List<ScoredOffer> score(Map<LocalDate, DealScan> history, Map<String, Double> values) {
        Map<String, Double> medians = new HashMap<>();
        perDollarObservations(history).forEach((key, list) -> medians.put(key, median(list)));

        Map<LocalDate, DealScan> ordered = new TreeMap<>(history);
        List<ScoredOffer> unjudged = new ArrayList<>();
        Map<String, LocalDate> firstSeen = new HashMap<>();
        Map<String, Double> lowestPrice = new HashMap<>();
        Map<String, Integer> daysSeen = new HashMap<>();
        for (Map.Entry<LocalDate, DealScan> day : ordered.entrySet()) {
            Map<String, Double> lowestToday = new HashMap<>();
            for (DealOffer offer : day.getValue().offers()) {
                String pack = offer.packKey();
                Double[] scored = scoreOffer(offer, values, medians);
                unjudged.add(new ScoredOffer(day.getKey(), offer, scored[0],
                        scored[0] == null ? null : (scored[1] > 0 ? BASIS_VALUES : BASIS_LEARNED), null,
                        lowestPrice.get(pack), daysSeen.getOrDefault(pack, 0) + 1,
                        firstSeen.getOrDefault(pack, day.getKey())));
                if (offer.priceUsd() != null) {
                    lowestToday.merge(pack, offer.priceUsd(), Math::min);
                }
                lowestToday.putIfAbsent(pack, null);
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
            List<ScoredOffer> week = all.stream()
                    .filter(o -> o.score() != null && candidate.basis().equals(o.basis())
                            && !o.day().isBefore(from) && !o.day().isAfter(candidate.day()))
                    .toList();
            double weekMedian = median(week.stream().map(ScoredOffer::score).toList());
            double weekBest = week.stream().mapToDouble(ScoredOffer::score).max().orElse(candidate.score());
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

    /** Returns {score or null, 1 when operator values were used else 0}. */
    private static Double[] scoreOffer(DealOffer offer, Map<String, Double> values, Map<String, Double> medians) {
        Double price = offer.priceUsd();
        if (price == null || price <= 0 || offer.items().isEmpty()) {
            return new Double[]{null, 0.0};
        }
        double valued = 0;
        int valuedItems = 0;
        for (DealItem item : offer.items()) {
            Double usd = values.get(item.key());
            if (usd != null && item.quantity() > 0) {
                valued += usd * item.quantity();
                valuedItems++;
            }
        }
        if (valuedItems > 0) {
            return new Double[]{valued / price, 1.0};
        }
        double ratioSum = 0;
        int ratios = 0;
        for (DealItem item : offer.items()) {
            Double median = medians.get(item.key());
            if (median != null && median > 0 && item.quantity() > 0) {
                ratioSum += (item.quantity() / price) / median;
                ratios++;
            }
        }
        return ratios == 0 ? new Double[]{null, 0.0} : new Double[]{ratioSum / ratios, 0.0};
    }

    private static Map<String, List<Double>> perDollarObservations(Map<LocalDate, DealScan> history) {
        Map<String, List<Double>> perDollar = new HashMap<>();
        for (DealScan scan : history.values()) {
            for (DealOffer offer : scan.offers()) {
                if (offer.priceUsd() == null || offer.priceUsd() <= 0) {
                    continue;
                }
                for (DealItem item : offer.items()) {
                    if (item.quantity() > 0) {
                        perDollar.computeIfAbsent(item.key(), k -> new ArrayList<>())
                                .add(item.quantity() / offer.priceUsd());
                    }
                }
            }
        }
        return perDollar;
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
