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
    private final Set<String> pickShops = new HashSet<>();
    private final Map<String, List<String>> tabSpellings = new LinkedHashMap<>();
    /** Seven letters in common at least: "custom" alone would make Custom Pet Chest the Armament shop. */
    private static final int SAME_TAB_MIN_RUN = 7;
    /** Shorter reads than this are too little to judge by; they only match exactly. */
    private static final int SAME_NAME_MIN_LETTERS = 8;

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
        if (perShop) {
            pickShops.add(shop(offer));
        }
        offers.merge(key(offer, perShop), offer, this::mergeReads);
    }

    private String key(DealOffer offer, boolean perShop) {
        return offer.packKey() + "|" + (perShop ? shop(offer) : "") + "|" + priceKey(offer);
    }

    /**
     * Puts the collected reads in their final rows: every tier of a pick-your-own shop is kept per shop, then
     * fallback and noisy titles join their pack. A pick-your-own page is known by its pulsing "+" slots, so
     * some of its frames are not recognised as one; once any frame of a shop was, all its tiers are its own.
     */
    public void settle() {
        for (Map.Entry<String, DealOffer> entry : List.copyOf(offers.entrySet())) {
            DealOffer offer = entry.getValue();
            String perShop = key(offer, true);
            if (!entry.getKey().equals(perShop) && pickShops.contains(shop(offer))) {
                offers.remove(entry.getKey());
                offers.merge(perShop, offer, this::mergeReads);
            }
        }
        foldFallbackTitles();
        foldNoisyTitles();
        nameTabs();
    }

    /** The shop a pick-your-own tier was read in; one shop whose name OCR spells two ways is one shop. */
    private String shop(DealOffer offer) {
        String key = offer.tab() == null ? "" : offer.tab().replaceAll("[^A-Za-z]", "").toLowerCase(Locale.ROOT);
        for (String known : shops) {
            if (sameName(known, key)) {
                return known;
            }
        }
        shops.add(key);
        return key;
    }

    /**
     * Two reads of one name, as letters only. Not by a shared run of letters: "custompetchest" and
     * "customarmamentchest" share "custom" but are two shops. A name's start reads reliably while artwork
     * crosses its end ("Custom Armameng", "Custom Armament Ch _ wer;"), so the shorter read is compared with the
     * longer one's start: close throughout it is one name. "firecrystalenergy" against "firecrystalleappack"
     * stays two.
     */
    static boolean sameName(String a, String b) {
        String shorter = a.length() <= b.length() ? a : b;
        String longer = shorter == a ? b : a;
        if (shorter.length() < SAME_NAME_MIN_LETTERS) {
            return shorter.equals(longer);
        }
        return editDistance(shorter, longer.substring(0, shorter.length())) * 4 <= shorter.length();
    }

    /** Every spelling a tab's label was read with this run, from the strip and the page header. */
    public void noteTabSpelling(String surface, String spelling) {
        if (spelling != null && !spelling.isBlank()) {
            tabSpellings.computeIfAbsent(surface, k -> new ArrayList<>()).add(spelling);
        }
    }

    /**
     * Names each tab by a letter-by-letter vote across every read of it. The smallest strip labels never read
     * whole on any one capture of 2026-10-23 ("Sustom Armanient Chest", "Custom Armamentj Chest", "Custom
     * Armameng"), but each letter was read right more often than wrong.
     */
    void nameTabs() {
        // Strip labels are whole names; a page header is cut short by its artwork ("Custom Armameng") and would
        // vote "Chest" away. Headers only name a tab no strip read covers.
        Map<String, List<String>> spellings = new LinkedHashMap<>();
        tabSpellings.forEach((surface, list) -> spellings.computeIfAbsent(surface, k -> new ArrayList<>()).addAll(list));
        offers.values().forEach(o -> {
            if (o.tab() == null || o.tab().isBlank()) {
                return;
            }
            List<String> known = spellings.computeIfAbsent(o.surface(), k -> new ArrayList<>());
            String base = baseTab(o.tab());
            if (known.stream().noneMatch(k -> sameName(letters(k), letters(base)))) {
                known.add(base);
            }
        });
        for (Map.Entry<String, List<String>> surface : spellings.entrySet()) {
            List<List<String>> groups = new ArrayList<>();
            for (String spelling : surface.getValue()) {
                String letters = letters(spelling);
                if (letters.isEmpty()) {
                    continue;
                }
                groups.stream().filter(g -> sameName(letters(g.get(0)), letters)).findFirst()
                        .orElseGet(() -> {
                            List<String> group = new ArrayList<>();
                            groups.add(group);
                            return group;
                        })
                        .add(spelling);
            }
            for (List<String> group : groups) {
                String name = TabNameConsensus.of(group);
                for (Map.Entry<String, DealOffer> entry : List.copyOf(offers.entrySet())) {
                    DealOffer offer = entry.getValue();
                    if (!surface.getKey().equals(offer.surface()) || offer.tab() == null
                            || !sameName(letters(group.get(0)), letters(baseTab(offer.tab())))) {
                        continue;
                    }
                    String tier = offer.tab().substring(baseTab(offer.tab()).length());
                    offers.put(entry.getKey(), new DealOffer(offer.surface(), name + tier, offer.title(),
                            offer.priceUsd(), offer.priceText(), offer.remaining(), offer.purchased(), offer.items(),
                            offer.frame(), offer.limitPeriod(), offer.limitCount(), offer.timeLeft(), offer.card()));
                }
            }
        }
    }

    /** A tab name without a toggle tier after it: "Intel Monthly Card (Mythic)" is "Intel Monthly Card". */
    private static String baseTab(String tab) {
        int tier = tab.lastIndexOf(" (");
        return tier > 0 && tab.endsWith(")") ? tab.substring(0, tier) : tab;
    }

    private static String letters(String text) {
        return text.replaceAll("[^A-Za-z]", "").toLowerCase(Locale.ROOT);
    }

    static int editDistance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int substitute = previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(substitute, Math.min(previous[j], current[j - 1]) + 1);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
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
    void foldFallbackTitles() {
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
    void foldNoisyTitles() {
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
