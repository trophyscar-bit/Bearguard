package dev.frostguard.engine.alliance;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Prices the logged Alliance Gift purchases for the Statistics view.
 *
 * <p>A purchase is priced three ways, best first: an operator entry in
 * {@code data/alliance-spend/pack-prices.json}; the deal scan's price for a pack of the same title;
 * or an estimate from the gift tier. Each priced purchase says which one it used, so the view can
 * keep measured dollars apart from estimated ones.</p>
 */
public final class AllianceSpendReport {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public enum PriceSource { OPERATOR, DEAL_SCAN, TIER_ESTIMATE, NONE }

    /**
     * Dollars per tier when nothing better is known. Tier I is measured: all 11 Tier I purchases
     * that matched a scanned pack on 2026-10-26 were $4.99. II to IV are assumptions until enough
     * matches exist to measure them, and the view labels every figure that leans on them.
     */
    static final Map<String, Double> TIER_ESTIMATE = Map.of(
            "Alliance Gift I", 4.99, "Alliance Gift II", 9.99, "Alliance Gift III", 19.99, "Alliance Gift IV", 99.99);

    /**
     * How many edits two readings of one pack name may differ by: one stray letter ("Arcanel",
     * "Dream T Traveler"). Not two, since "Common" and "Uncommon" Lucky Chip packs are two apart.
     */
    static final int FUZZY_EDITS = 1;

    public record Purchase(LocalDateTime at, String tier, String buyer, boolean anonymous, String pack,
                           boolean backlog, Double usd, PriceSource source) {
    }

    public record BuyerTotal(String buyer, int purchases, double usd, int estimated) {
    }

    private final List<Purchase> purchases;

    private AllianceSpendReport(List<Purchase> purchases) {
        this.purchases = purchases;
    }

    public List<Purchase> purchases() {
        return purchases;
    }

    /** Loads the ledger and prices it from the workspace's deal scans and operator price file. */
    public static AllianceSpendReport load(Path workspaceRoot) throws IOException {
        Path spend = workspaceRoot.resolve("data").resolve("alliance-spend");
        Path gifts = spend.resolve("gifts.jsonl");
        List<JsonNode> rows = new ArrayList<>();
        if (Files.exists(gifts)) {
            for (String line : Files.readAllLines(gifts, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    JsonNode node = MAPPER.readTree(line);
                    if (node.hasNonNull("pack")) {
                        rows.add(node);
                    }
                }
            }
        }
        Map<String, Double> operator = new HashMap<>();
        Path manual = spend.resolve("pack-prices.json");
        if (Files.exists(manual)) {
            MAPPER.readValue(manual.toFile(), new TypeReference<Map<String, Double>>() { })
                    .forEach((k, v) -> operator.put(normalise(k), v));
        }
        return build(rows, operator, scanPrices(workspaceRoot.resolve("data").resolve("deals").resolve("scans")));
    }

    static AllianceSpendReport build(List<JsonNode> rows, Map<String, Double> operator,
                                     Map<String, TreeSet<Double>> scanned) {
        List<String> known = new ArrayList<>(operator.keySet());
        known.addAll(scanned.keySet());
        List<Purchase> out = new ArrayList<>();
        for (JsonNode row : rows) {
            String tier = row.path("tier").asText(null);
            String pack = row.path("pack").asText();
            String key = canonical(normalise(pack), known);
            Double usd = null;
            PriceSource source = PriceSource.NONE;
            Double estimate = tier == null ? null : TIER_ESTIMATE.get(tier);
            if (operator.containsKey(key)) {
                usd = operator.get(key);
                source = PriceSource.OPERATOR;
            } else if (scanned.containsKey(key)) {
                TreeSet<Double> seen = scanned.get(key);
                if (seen.size() == 1) {
                    usd = seen.first();
                    source = PriceSource.DEAL_SCAN;
                } else if (estimate != null && seen.contains(estimate)) {
                    usd = estimate;
                    source = PriceSource.DEAL_SCAN;
                }
            }
            if (usd == null && estimate != null) {
                usd = estimate;
                source = PriceSource.TIER_ESTIMATE;
            }
            out.add(new Purchase(LocalDateTime.parse(row.path("capturedAt").asText()), tier,
                    row.path("buyer").asText("").isBlank() ? null : row.path("buyer").asText(),
                    row.path("anonymous").asBoolean(false), pack, row.path("backlog").asBoolean(false), usd, source));
        }
        out.sort(Comparator.comparing(Purchase::at).reversed());
        return new AllianceSpendReport(out);
    }

    /** Every priced title in every saved deal scan, by normalised title. */
    static Map<String, TreeSet<Double>> scanPrices(Path scans) throws IOException {
        Map<String, TreeSet<Double>> prices = new HashMap<>();
        if (!Files.isDirectory(scans)) {
            return prices;
        }
        try (Stream<Path> files = Files.list(scans)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                JsonNode scan;
                try {
                    scan = MAPPER.readTree(file.toFile());
                } catch (IOException unreadable) {
                    continue;
                }
                for (JsonNode offer : scan.path("offers")) {
                    if (offer.hasNonNull("priceUsd") && offer.path("priceUsd").asDouble() > 0) {
                        prices.computeIfAbsent(normalise(offer.path("title").asText()), k -> new TreeSet<>())
                                .add(offer.path("priceUsd").asDouble());
                    }
                }
            }
        }
        return prices;
    }

    /** The known title a misread is closest to, or the read itself when none is close enough. */
    static String canonical(String read, List<String> known) {
        if (known.contains(read)) {
            return read;
        }
        String best = read;
        int bestEdits = FUZZY_EDITS + 1;
        for (String title : known) {
            // Numbered tiers ("Pack 2" / "Pack 3") are one edit apart but different packs.
            if (Math.abs(title.length() - read.length()) > FUZZY_EDITS
                    || !title.replaceAll("\\D", "").equals(read.replaceAll("\\D", ""))
                    || title.length() < 3 || read.length() < 3
                    || !title.substring(0, 3).equals(read.substring(0, 3))) {
                continue;
            }
            int edits = edits(read, title);
            if (edits < bestEdits) {
                bestEdits = edits;
                best = title;
            }
        }
        return best;
    }

    static int edits(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }

    static String normalise(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", "");
    }

    /** Purchases logged at or after {@code from}; null means all of them. */
    public List<Purchase> since(LocalDateTime from) {
        return from == null ? purchases : purchases.stream().filter(p -> !p.at().isBefore(from)).toList();
    }

    public static double usd(List<Purchase> list, boolean estimatesToo) {
        return list.stream()
                .filter(p -> p.usd() != null && (estimatesToo || p.source() != PriceSource.TIER_ESTIMATE))
                .mapToDouble(Purchase::usd).sum();
    }

    /** Spend per buyer, biggest first; anonymous purchases are one row. */
    public static List<BuyerTotal> byBuyer(List<Purchase> list) {
        Map<String, double[]> totals = new LinkedHashMap<>();
        for (Purchase p : list) {
            String who = p.anonymous() || p.buyer() == null ? "(anonymous)" : p.buyer();
            double[] t = totals.computeIfAbsent(who, k -> new double[3]);
            t[0]++;
            t[1] += p.usd() == null ? 0 : p.usd();
            if (p.source() == PriceSource.TIER_ESTIMATE) {
                t[2]++;
            }
        }
        List<BuyerTotal> out = new ArrayList<>();
        totals.forEach((who, t) -> out.add(new BuyerTotal(who, (int) t[0], t[1], (int) t[2])));
        out.sort(Comparator.comparingDouble(BuyerTotal::usd).reversed().thenComparing(BuyerTotal::buyer));
        return out;
    }

    /** Pack names with no operator or scanned price, most bought first: what to price next. */
    public static Map<String, Integer> unpricedPacks(List<Purchase> list) {
        Map<String, Integer> counts = new TreeMap<>();
        for (Purchase p : list) {
            if (p.source() == PriceSource.TIER_ESTIMATE || p.source() == PriceSource.NONE) {
                counts.merge(p.pack(), 1, Integer::sum);
            }
        }
        Map<String, Integer> sorted = new LinkedHashMap<>();
        counts.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(e -> sorted.put(e.getKey(), e.getValue()));
        return sorted;
    }
}
