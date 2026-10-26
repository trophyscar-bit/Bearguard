package dev.frostguard.engine.deals;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
    private final Map<String, DealOffer> cutBelow = new HashMap<>();
    /** A card cut at the bottom shows its title within a frame or two: one scroll, perhaps a row swipe between. */
    private static final int MAX_CUT_BELOW_FRAMES = 3;
    private final Map<String, List<String>> tabSpellings = new LinkedHashMap<>();
    /** Seven letters in common at least: "custom" alone would make Custom Pet Chest the Armament shop. */
    private static final int SAME_TAB_MIN_RUN = 7;
    /** Shorter reads than this are too little to judge by; they only match exactly. */
    private static final int SAME_NAME_MIN_LETTERS = 8;
    private static final Pattern DAY_ITEM = Pattern.compile("(.+) \\(Day \\d+\\)");
    /** "Exclusive Gear", the shortest cut title measured; two short words alone could be any pack. */
    private static final int CUT_TITLE_MIN_LETTERS = 12;
    /** Items besides gems that two reads must share, quantity and all, to be one card. */
    private static final int MIN_SHARED_ITEMS = 2;

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
        String place = offer.surface() + "|" + offer.tab();
        if (DealFrameReader.CUT_BELOW_TITLE.equals(offer.title())) {
            // A card entering at the bottom edge, its title not yet in view: the next card the scroll brings up in
            // this tab is the same card. Its tiles read here are kept for that card, ahead of what it shows later:
            // when a row was already swiped by the time its title showed, these are its first items.
            cutBelow.put(place, offer);
            return;
        }
        DealOffer pending = cutBelow.remove(place);
        boolean firstSight = !offers.containsKey(key(offer, perShop)) && !offers.containsKey(key(offer, true));
        if (pending != null && firstSight && Objects.equals(pending.priceUsd(), offer.priceUsd())
                && frameGap(pending, offer) <= MAX_CUT_BELOW_FRAMES) {
            offer = new DealOffer(offer.surface(), offer.tab(), offer.title(), offer.priceUsd(), offer.priceText(),
                    offer.remaining(), offer.purchased(), mergeReads(pending, offer).items(), offer.frame(),
                    offer.limitPeriod(), offer.limitCount(), offer.timeLeft(), offer.card());
        } else if (pending != null) {
            cutBelow.put(place, pending);
        }
        if (perShop) {
            pickShops.add(shop(offer));
        }
        offers.merge(key(offer, perShop), offer, this::mergeReads);
    }

    private static int frameGap(DealOffer earlier, DealOffer later) {
        try {
            return Integer.parseInt(later.frame().replaceAll("[^0-9]", ""))
                    - Integer.parseInt(earlier.frame().replaceAll("[^0-9]", ""));
        } catch (RuntimeException unnumbered) {
            return Integer.MAX_VALUE;
        }
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
        foldCutTitles();
        nameTabs();
        fillPurchaseAll();
        totalDays();
    }

    /**
     * A day-by-day grid (Journey Treasures) pays every day's reward to the buyer, so each item's days are added
     * into the item's plain name: Fire Crystal 50 on Day 1 and 5 on each of eight more days is Fire Crystal 90.
     * The "(Day N)" items stay beside the totals as the breakdown; a reader of the totals must not add them again.
     */
    private void totalDays() {
        for (Map.Entry<String, DealOffer> entry : List.copyOf(offers.entrySet())) {
            DealOffer offer = entry.getValue();
            Map<String, Long> totals = new LinkedHashMap<>();
            for (DealItem item : offer.items()) {
                Matcher day = DAY_ITEM.matcher(item.key());
                if (day.matches()) {
                    totals.merge(day.group(1), item.quantity(), Long::sum);
                }
            }
            if (totals.isEmpty()) {
                continue;
            }
            List<DealItem> items = new ArrayList<>();
            for (DealItem item : offer.items()) {
                Long days = totals.remove(item.key());
                items.add(days == null ? item : new DealItem(item.key(), item.quantity() + days, "days"));
            }
            totals.forEach((key, quantity) -> items.add(new DealItem(key, quantity, "days")));
            offers.put(entry.getKey(), new DealOffer(offer.surface(), offer.tab(), offer.title(), offer.priceUsd(),
                    offer.priceText(), offer.remaining(), offer.purchased(), items, offer.frame(), offer.limitPeriod(),
                    offer.limitCount(), offer.timeLeft(), offer.card()));
        }
    }

    /**
     * A "Purchase All" offer holds every other priced offer of its tab, so it is filled with their items. A tier
     * read with no items would make the sum short of what the banner sells, so then it is left empty and named.
     */
    private void fillPurchaseAll() {
        foldPurchaseAll();
        for (Map.Entry<String, DealOffer> entry : List.copyOf(offers.entrySet())) {
            DealOffer bundle = entry.getValue();
            if (!isPurchaseAll(bundle) || !bundle.items().isEmpty()) {
                continue;
            }
            List<DealOffer> tiers = offers.values().stream()
                    .filter(o -> o != bundle && !isPurchaseAll(o) && o.priceUsd() != null
                            && Objects.equals(o.surface(), bundle.surface())
                            && Objects.equals(o.tab(), bundle.tab()))
                    .toList();
            if (tiers.size() < 2 || tiers.stream().anyMatch(o -> o.items().isEmpty())) {
                problems.add(bundle.surface() + " / " + bundle.tab() + " / " + bundle.title() + ": its "
                        + tiers.size() + " tier(s) not all read; contents left unknown.");
                continue;
            }
            Map<String, Long> sum = new LinkedHashMap<>();
            tiers.forEach(tier -> tier.items().forEach(i -> sum.merge(i.key(), i.quantity(), Long::sum)));
            List<DealItem> items = sum.entrySet().stream()
                    .map(e -> new DealItem(e.getKey(), e.getValue(), "purchase all")).toList();
            offers.put(entry.getKey(), new DealOffer(bundle.surface(), bundle.tab(), bundle.title(), bundle.priceUsd(),
                    bundle.priceText(), bundle.remaining(), bundle.purchased(), items, bundle.frame(),
                    bundle.limitPeriod(), bundle.limitCount(), bundle.timeLeft(), bundle.card()));
        }
    }

    /**
     * One Purchase All per tab and price, named by its most common read: its banner sparkles, and the same
     * frames read "Purchase All Discount Packs", "Purchase All Discount acks" and "...Discount �Packs".
     */
    private void foldPurchaseAll() {
        Map<String, List<Map.Entry<String, DealOffer>>> groups = new LinkedHashMap<>();
        for (Map.Entry<String, DealOffer> entry : offers.entrySet()) {
            DealOffer offer = entry.getValue();
            if (isPurchaseAll(offer)) {
                groups.computeIfAbsent(offer.surface() + "|" + offer.tab() + "|" + offer.priceUsd(),
                        k -> new ArrayList<>()).add(entry);
            }
        }
        for (List<Map.Entry<String, DealOffer>> group : groups.values()) {
            if (group.size() < 2) {
                continue;
            }
            Map<String, Long> titles = new LinkedHashMap<>();
            group.forEach(e -> titles.merge(e.getValue().title(), 1L, Long::sum));
            String title = titles.entrySet().stream()
                    .max(Map.Entry.<String, Long>comparingByValue()
                            .thenComparing(e -> e.getKey().length()))
                    .map(Map.Entry::getKey).orElseThrow();
            DealOffer keep = group.stream().map(Map.Entry::getValue).filter(o -> o.title().equals(title))
                    .findFirst().orElseThrow();
            group.forEach(e -> offers.remove(e.getKey()));
            offers.put(key(keep, false), keep);
        }
    }

    private static boolean isPurchaseAll(DealOffer offer) {
        return offer.title() != null && offer.title().toLowerCase(Locale.ROOT).startsWith("purchase all");
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

    public static int editDistance(String a, String b) {
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
            // With several packs at the price (every Regular Pack card is $4.99), the one whose items the
            // fallback read repeats exactly, two or more of them, is the same card.
            if (titled.size() > 1) {
                titled = titled.stream().filter(e -> sharedItems(e.getValue(), offer) >= MIN_SHARED_ITEMS).toList();
            }
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

    /**
     * A title cut short is the pack whose title it begins like, in the same tab at the same price: "Accessory
     * Construction Con", "Troops Training Pac", "Exclusive Gear" and "Exclusive Gear Enhancemeitt" were each
     * a second row of a pack on 2026-10-25. Only when exactly one such pack exists and no item they share
     * differs in count, so "Chief Gear Enhancement" (20 plans) never joins "...Special" (35).
     */
    void foldCutTitles() {
        for (Map.Entry<String, DealOffer> cut : List.copyOf(offers.entrySet())) {
            DealOffer offer = cut.getValue();
            String cutLetters = letters(offer.title());
            if (!offers.containsKey(cut.getKey()) || offer.title().trim().split("\\s+").length < 2
                    || cutLetters.length() < CUT_TITLE_MIN_LETTERS) {
                continue;
            }
            List<Map.Entry<String, DealOffer>> whole = offers.entrySet().stream()
                    .filter(e -> !e.getKey().equals(cut.getKey()))
                    .filter(e -> Objects.equals(e.getValue().priceUsd(), offer.priceUsd())
                            && Objects.equals(e.getValue().surface(), offer.surface())
                            && Objects.equals(shop(e.getValue()), shop(offer)))
                    .filter(e -> letters(e.getValue().title()).length() > cutLetters.length()
                            && sameName(cutLetters, letters(e.getValue().title())))
                    .filter(e -> !conflicting(offer, e.getValue()))
                    .toList();
            if (whole.size() == 1) {
                offers.put(whole.get(0).getKey(), mergeReads(whole.get(0).getValue(), offer));
                offers.remove(cut.getKey());
            }
        }
    }

    private static boolean conflicting(DealOffer a, DealOffer b) {
        return a.items().stream().anyMatch(i -> b.items().stream()
                .anyMatch(j -> j.key().equals(i.key()) && j.quantity() != i.quantity()));
    }

    private static long sharedItems(DealOffer a, DealOffer b) {
        return a.items().stream()
                .filter(i -> b.items().stream().anyMatch(j -> j.key().equals(i.key()) && j.quantity() == i.quantity()))
                .filter(i -> !i.key().equals(dev.frostguard.api.deals.DealPriceTracker.GEMS))
                .count();
    }

    private static boolean isFallbackTitle(DealOffer offer) {
        return offer.priceUsd() != null
                && offer.title().endsWith(String.format(Locale.US, " - $%.2f", offer.priceUsd()));
    }
}
