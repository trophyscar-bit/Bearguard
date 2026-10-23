package dev.frostguard.engine.deals;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import dev.frostguard.api.deals.DealItem;
import dev.frostguard.api.deals.DealOffer;

/**
 * The offers of one scan, gathered from reads of many frames: a pack is read again as its page scrolls, its
 * item row is swiped and its carousel turns, and every read of it becomes one offer.
 *
 * <p>Used by the nightly scan and by the offline replay of saved frames, so a reader fix can be checked
 * against a whole run before it ships.</p>
 */
public final class DealCollection {

    private final Map<String, DealOffer> offers = new LinkedHashMap<>();
    private final List<String> problems = new ArrayList<>();
    private final List<String> shops = new ArrayList<>();
    private static final int SAME_TAB_MIN_RUN = 6;

    public Map<String, DealOffer> offers() {
        return offers;
    }

    public List<String> problems() {
        return problems;
    }

    public void add(DealOffer offer) {
        add(offer, false);
    }

    /**
     * @param perShop the pack is one of the tiers that several shops sell alike (pick-your-own chests), so it
     *                is kept apart per shop; any other pack seen in two tabs is the same pack read twice
     */
    public void add(DealOffer offer, boolean perShop) {
        String key = offer.packKey() + "|" + (perShop ? shop(offer) : "") + "|" + priceKey(offer);
        offers.merge(key, offer, this::mergeReads);
    }

    /** The shop a pick-your-own tier was read in; one shop whose name OCR spells two ways is one shop. */
    private String shop(DealOffer offer) {
        String key = offer.tab() == null ? "" : offer.tab().replaceAll("[^A-Za-z]", "").toLowerCase(Locale.ROOT);
        for (String known : shops) {
            if (sameTab(known, key)) {
                return known;
            }
        }
        shops.add(key);
        return key;
    }

    public static boolean sameTab(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) {
            return a.equals(b);
        }
        if (a.contains(b) || b.contains(a)) {
            return true;
        }
        int longest = 0;
        for (int i = 0; i < a.length(); i++) {
            for (int j = 0; j < b.length(); j++) {
                int k = 0;
                while (i + k < a.length() && j + k < b.length() && a.charAt(i + k) == b.charAt(j + k)) {
                    k++;
                }
                longest = Math.max(longest, k);
            }
        }
        return longest >= Math.max(SAME_TAB_MIN_RUN, Math.min(a.length(), b.length()) * 7 / 10);
    }

    /** The price a pack is told apart by: the parsed dollars, since one $99.99 also read as "$99 � 99". */
    static String priceKey(DealOffer offer) {
        return offer.priceUsd() != null ? String.format(Locale.ROOT, "%.2f", offer.priceUsd()) : offer.priceText();
    }

    /**
     * One offer from two reads of it. Items keep the order they were first seen in, which is card order.
     */
    DealOffer mergeReads(DealOffer first, DealOffer second) {
        Map<String, DealItem> items = new LinkedHashMap<>();
        Set<String> conflicting = new HashSet<>();
        for (DealItem item : Stream.concat(first.items().stream(), second.items().stream()).toList()) {
            DealItem earlier = items.get(item.key());
            if (earlier == null) {
                items.put(item.key(), item);
            } else if (earlier.quantity() != item.quantity()) {
                Optional<DealItem> whole = wholerRead(earlier, item);
                if (whole.isPresent()) {
                    items.put(item.key(), whole.get());
                } else if (conflicting.add(item.key())) {
                    problems.add(first.surface() + " / " + first.tab() + " / " + first.title() + ": " + item.key()
                            + " read as " + earlier.quantity() + " and " + item.quantity()
                            + " on different frames; left out.");
                }
            }
        }
        conflicting.forEach(items::remove);
        return new DealOffer(first.surface(), first.tab(), first.title(),
                first.priceUsd() != null ? first.priceUsd() : second.priceUsd(), first.priceText(),
                first.remaining() != null ? first.remaining() : second.remaining(),
                first.purchased() || second.purchased(), new ArrayList<>(items.values()), first.frame(),
                first.limitPeriod() != null ? first.limitPeriod() : second.limitPeriod(),
                first.limitCount() != null ? first.limitCount() : second.limitCount(),
                first.timeLeft() != null ? first.timeLeft() : second.timeLeft(),
                first.card() != null ? first.card() : second.card());
    }

    /**
     * Of two different reads of one tile, the one a cut-off tile could not have produced. A tile cut by its
     * row's edge loses its last digits, so a read that is the other's leading digits is the cut one: 24 of
     * 240. Any other disagreement means one read is wrong, and neither is kept.
     */
    static Optional<DealItem> wholerRead(DealItem a, DealItem b) {
        String digitsA = Long.toString(a.quantity());
        String digitsB = Long.toString(b.quantity());
        if (digitsB.length() > digitsA.length() && digitsB.startsWith(digitsA)) {
            return Optional.of(b);
        }
        if (digitsA.length() > digitsB.length() && digitsA.startsWith(digitsB)) {
            return Optional.of(a);
        }
        return Optional.empty();
    }

    /**
     * A card whose title was out of view on one frame is named after its tab and price ("Fire Crystal Leap
     * Pack - $99.99"). Where exactly one titled pack in that tab has that price, both reads are that pack;
     * on 2026-10-23 Leap Pack 5 appeared three times this way.
     */
    public void foldFallbackTitles() {
        for (Map.Entry<String, DealOffer> fallback : List.copyOf(offers.entrySet())) {
            DealOffer offer = fallback.getValue();
            if (!isFallbackTitle(offer)) {
                continue;
            }
            List<Map.Entry<String, DealOffer>> titled = offers.entrySet().stream()
                    .filter(e -> !e.getKey().equals(fallback.getKey()) && !isFallbackTitle(e.getValue()))
                    .filter(e -> Objects.equals(e.getValue().surface(), offer.surface())
                            && Objects.equals(shop(e.getValue()), shop(offer))
                            && Objects.equals(e.getValue().priceUsd(), offer.priceUsd()))
                    .toList();
            if (titled.size() == 1) {
                offers.put(titled.get(0).getKey(), mergeReads(titled.get(0).getValue(), offer));
                offers.remove(fallback.getKey());
            }
        }
    }

    /**
     * A title read with OCR noise after it ("Exquisite Custom Chest qu ustom Che Goo") is the pack whose title
     * it begins with, in the same tab at the same price, when exactly one such pack exists.
     */
    public void foldNoisyTitles() {
        for (Map.Entry<String, DealOffer> noisy : List.copyOf(offers.entrySet())) {
            DealOffer offer = noisy.getValue();
            if (!offers.containsKey(noisy.getKey())) {
                continue;
            }
            List<Map.Entry<String, DealOffer>> clean = offers.entrySet().stream()
                    .filter(e -> !e.getKey().equals(noisy.getKey()))
                    .filter(e -> Objects.equals(e.getValue().priceUsd(), offer.priceUsd())
                            && Objects.equals(e.getValue().surface(), offer.surface())
                            && Objects.equals(shop(e.getValue()), shop(offer))
                            && offer.title().startsWith(e.getValue().title() + " "))
                    .toList();
            if (clean.size() == 1) {
                offers.put(clean.get(0).getKey(), mergeReads(clean.get(0).getValue(), offer));
                offers.remove(noisy.getKey());
            }
        }
    }

    private static boolean isFallbackTitle(DealOffer offer) {
        return offer.priceUsd() != null
                && offer.title().endsWith(String.format(Locale.US, " - $%.2f", offer.priceUsd()));
    }
}
