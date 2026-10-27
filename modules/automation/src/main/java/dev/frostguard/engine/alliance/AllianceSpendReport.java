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
 * <p>A purchase is priced, best first, from an operator entry in
 * {@code data/alliance-spend/pack-prices.json}; from the deal scan's packs of the same name or the
 * same family ("Daily Deals - $0.99", the "Mix &amp; Match" tab), narrowed to the price band of
 * the purchase's gift tier; or from the tier alone. Each purchase says which, so the view keeps
 * known dollars apart from estimated ones.</p>
 */
public final class AllianceSpendReport {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /**
     * Where a purchase's price came from. OPERATOR and DEAL_SCAN are known prices. SCAN_RANGE is a
     * pack the scan sells at several prices inside the tier's band (the lowest is used, so it is a
     * floor). TIER_ESTIMATE has no scanned price that fits. NONE has neither a price nor a tier.
     */
    public enum PriceSource {
        OPERATOR, DEAL_SCAN, SCAN_RANGE, TIER_ESTIMATE, NONE;

        public boolean known() {
            return this == OPERATOR || this == DEAL_SCAN;
        }
    }

    /**
     * Each gift tier corresponds to a band of pack prices. Every priced pack family in the scans of
     * 2026-10-22..26 steps $4.99 / $9.99 / $19.99 / $49.99 / $99.99 (Fire Crystal Leap Pack 1-5,
     * Shining to Majestic Custom Chest) while the gift keys step 30 / 60 / 120 / 300, so the keys
     * track the price; $0.99 to $2.99 Daily Deals also give Tier I. A tier-only estimate uses the
     * band's top for I and its single price for II and III; IV ($49.99 and up) uses $49.99, the
     * price whose ratio to $4.99 matches 300 keys to 30. All tier estimates are labelled as such.
     */
    static final Map<String, double[]> TIER_BAND = Map.of(
            "Alliance Gift I", new double[] {0, 4.99, 4.99},
            "Alliance Gift II", new double[] {4.99, 9.99, 9.99},
            "Alliance Gift III", new double[] {9.99, 19.99, 19.99},
            "Alliance Gift IV", new double[] {19.99, Double.MAX_VALUE, 49.99});

    /**
     * How many edits two readings of one pack name may differ by: one stray letter ("Arcanel",
     * "Dream T Traveler"). Not two, since "Common" and "Uncommon" Lucky Chip packs are two apart.
     */
    static final int FUZZY_EDITS = 1;

    /** One purchase as shown, plus the spellings it was read as and the screen it came from. */
    public record Purchase(LocalDateTime at, String tier, String buyer, boolean anonymous, String pack,
                           boolean backlog, Double usd, PriceSource source, String priceNote, String readBuyer,
                           String readPack, String frame) {
    }

    /** One buyer's spend: dollars from known pack prices and dollars estimated. */
    public record BuyerTotal(String buyer, int purchases, double knownUsd, double estimatedUsd) {
        public double usd() {
            return knownUsd + estimatedUsd;
        }
    }

    /** Prices the scans have seen: by pack name, and by family (a tiered name or its tab). */
    record ScanIndex(Map<String, TreeSet<Double>> names, Map<String, TreeSet<Double>> families) {
        static final ScanIndex EMPTY = new ScanIndex(Map.of(), Map.of());
    }

    private final List<Purchase> purchases;
    private final int unreadableLines;

    private AllianceSpendReport(List<Purchase> purchases, int unreadableLines) {
        this.purchases = purchases;
        this.unreadableLines = unreadableLines;
    }

    public List<Purchase> purchases() {
        return purchases;
    }

    /** Ledger lines that could not be read and were left out, so the view can say so. */
    public int unreadableLines() {
        return unreadableLines;
    }

    /** Loads the ledger and prices it from the workspace's deal reports and operator price file. */
    public static AllianceSpendReport load(Path workspaceRoot) throws IOException {
        Path spend = workspaceRoot.resolve("data").resolve("alliance-spend");
        Path gifts = spend.resolve("gifts.jsonl");
        List<JsonNode> rows = new ArrayList<>();
        int unreadable = 0;
        if (Files.exists(gifts)) {
            for (String line : Files.readAllLines(gifts, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    JsonNode node = MAPPER.readTree(line);
                    if (node.hasNonNull("pack")) {
                        LocalDateTime.parse(node.path("capturedAt").asText());
                        rows.add(node);
                    }
                } catch (Exception corrupt) {
                    unreadable++;
                }
            }
        }
        Aliases aliases = Aliases.EMPTY;
        Path aliasFile = spend.resolve("aliases.json");
        if (Files.exists(aliasFile)) {
            aliases = MAPPER.readValue(aliasFile.toFile(), Aliases.class);
        }
        AllianceSpendReport built = build(rows, operatorPrices(spend), allScans(workspaceRoot), aliases);
        return new AllianceSpendReport(built.purchases, unreadable);
    }

    /**
     * Whether a pack bought at a tier already has a known price (operator entry, a single scanned
     * price in the tier's band, or a followed gift link). The chest pass follows the links of the
     * packs this says no to.
     */
    public static java.util.function.BiPredicate<String, String> knownPrice(Path workspaceRoot) throws IOException {
        Path spend = workspaceRoot.resolve("data").resolve("alliance-spend");
        Map<String, Double> operator = operatorPrices(spend);
        ScanIndex scans = allScans(workspaceRoot);
        return (pack, tier) -> price(pack, tier, operator, scans).source.known();
    }

    private static Map<String, Double> operatorPrices(Path spend) throws IOException {
        Map<String, Double> operator = new HashMap<>();
        Path manual = spend.resolve("pack-prices.json");
        if (Files.exists(manual)) {
            MAPPER.readValue(manual.toFile(), new TypeReference<Map<String, Double>>() { })
                    .forEach((k, v) -> operator.put(normalise(k), v));
        }
        return operator;
    }

    /** The deal scans' prices, plus each followed gift link's prices under its pack's name. */
    private static ScanIndex allScans(Path workspaceRoot) throws IOException {
        ScanIndex scans = scanIndex(workspaceRoot.resolve("data").resolve("deals"));
        Map<String, TreeSet<Double>> names = new HashMap<>(scans.names());
        PackLinks.pricesByPack(workspaceRoot.resolve("data").resolve("alliance-spend"))
                .forEach((k, v) -> names.computeIfAbsent(k, x -> new TreeSet<>()).addAll(v));
        return new ScanIndex(names, scans.families());
    }

    /**
     * Corrections for names OCR got wrong, kept in {@code data/alliance-spend/aliases.json} as
     * {@code {"buyers": {"une": "Lune"}, "packs": {...}}}; keys are compared ignoring case and
     * punctuation. The nightly review adds to it after checking the frames.
     */
    public record Aliases(Map<String, String> buyers, Map<String, String> packs) {
        static final Aliases EMPTY = new Aliases(Map.of(), Map.of());

        String buyer(String read) {
            return lookup(buyers, read);
        }

        String pack(String read) {
            return lookup(packs, read);
        }

        private static String lookup(Map<String, String> map, String read) {
            if (read == null || map == null) {
                return read;
            }
            String key = normalise(read);
            for (Map.Entry<String, String> e : map.entrySet()) {
                if (normalise(e.getKey()).equals(key)) {
                    return e.getValue();
                }
            }
            return read;
        }
    }

    static AllianceSpendReport build(List<JsonNode> rows, Map<String, Double> operator, ScanIndex scans,
                                     Aliases aliases) {
        List<String> anchors = new ArrayList<>(operator.keySet());
        anchors.addAll(scans.names().keySet());
        anchors.addAll(scans.families().keySet());
        Map<String, String> buyerNames = fold(rows, "buyer", aliases::buyer, List.of());
        Map<String, String> packNames = fold(rows, "pack", aliases::pack, anchors);
        List<Purchase> out = new ArrayList<>();
        for (JsonNode row : rows) {
            String tier = row.path("tier").asText(null);
            String pack = packNames.get(row.path("pack").asText());
            Priced priced = price(pack, tier, operator, scans);
            out.add(new Purchase(LocalDateTime.parse(row.path("capturedAt").asText()), tier,
                    row.path("buyer").asText("").isBlank() ? null : buyerNames.get(row.path("buyer").asText()),
                    row.path("anonymous").asBoolean(false), pack, row.path("backlog").asBoolean(false),
                    priced.usd, priced.source, priced.note,
                    row.path("buyer").asText(null), row.path("pack").asText(), row.path("frame").asText(null)));
        }
        out.sort(Comparator.comparing(Purchase::at).reversed());
        return new AllianceSpendReport(out, 0);
    }

    private record Priced(Double usd, PriceSource source, String note) {
    }

    static Priced price(String pack, String tier, Map<String, Double> operator, ScanIndex scans) {
        String norm = normalise(pack);
        String opKey = canonical(norm, new ArrayList<>(operator.keySet()));
        if (operator.containsKey(opKey)) {
            return new Priced(operator.get(opKey), PriceSource.OPERATOR, null);
        }
        double[] band = tier == null ? null : TIER_BAND.get(tier);
        String nameKey = canonical(norm, new ArrayList<>(scans.names().keySet()));
        String familyKey = canonical(norm, new ArrayList<>(scans.families().keySet()));
        TreeSet<Double> seen = scans.names().containsKey(nameKey) ? scans.names().get(nameKey)
                : scans.families().get(familyKey);
        if (seen != null) {
            TreeSet<Double> fits = new TreeSet<>();
            for (Double p : seen) {
                if (band == null || p > band[0] && p <= band[1]) {
                    fits.add(p);
                }
            }
            if (fits.size() == 1) {
                return new Priced(fits.first(), PriceSource.DEAL_SCAN, null);
            }
            if (fits.size() > 1) {
                return new Priced(fits.first(), PriceSource.SCAN_RANGE,
                        "lowest of " + money(fits.first()) + "-" + money(fits.last()));
            }
            if (band != null) {
                return new Priced(band[2], PriceSource.TIER_ESTIMATE,
                        "scanned " + money(seen.first()) + (seen.size() > 1 ? "+" : "") + " does not fit " + tier);
            }
        }
        if (band != null) {
            return new Priced(band[2], PriceSource.TIER_ESTIMATE, null);
        }
        return new Priced(null, PriceSource.NONE, "no tier and no scanned price");
    }

    private static String money(double usd) {
        return String.format(Locale.ROOT, "$%.2f", usd);
    }

    /**
     * Maps every spelling of a field to the one it should be shown as.
     *
     * <p>Aliases apply first. Spellings equal apart from punctuation or spacing are one name. A
     * pack spelling one edit from a more frequent one or a scanned title (same first three letters,
     * same digits) folds into it. Player names fold only across OCR's confusable glyphs (l, I, 1, |)
     * or a lone trailing glyph ("AthenaRyu 1"), never across a real extra letter, since "Evelyn" and
     * "Evelyne" can be two players.</p>
     *
     * @param anchors normalised spellings that always win, e.g. scanned pack titles
     */
    static Map<String, String> fold(List<JsonNode> rows, String field,
                                    java.util.function.UnaryOperator<String> alias, List<String> anchors) {
        boolean players = "buyer".equals(field);
        Map<String, Integer> counts = new HashMap<>();
        Map<String, String> aliased = new HashMap<>();
        for (JsonNode row : rows) {
            String read = row.path(field).asText("");
            if (read.isBlank()) {
                continue;
            }
            String named = alias.apply(read);
            aliased.put(read, named);
            counts.merge(named, 1, Integer::sum);
        }
        Map<String, String> sameLetters = new HashMap<>();
        for (String name : counts.keySet().stream().sorted(Comparator.comparingInt(AllianceSpendReport::oddMarks)
                .thenComparing(n -> -counts.get(n)).thenComparing(n -> n)).toList()) {
            sameLetters.putIfAbsent(normalise(name), name);
        }
        Map<String, Integer> merged = new HashMap<>();
        counts.forEach((name, n) -> merged.merge(sameLetters.get(normalise(name)), n, Integer::sum));
        aliased.replaceAll((read, named) -> sameLetters.get(normalise(named)));
        counts.clear();
        counts.putAll(merged);
        List<String> byFrequency = new ArrayList<>(counts.keySet());
        byFrequency.sort(Comparator.comparing((String n) -> -counts.get(n)).thenComparing(n -> n));
        Map<String, String> folded = new HashMap<>();
        for (String name : byFrequency) {
            String target = name;
            String norm = normalise(name);
            String bare = name.replaceFirst("\\s+\\S$", "");
            if (players && !bare.equals(name) && counts.getOrDefault(bare, 0) > counts.get(name)) {
                folded.put(name, folded.getOrDefault(bare, bare));
                continue;
            }
            if (norm.length() >= 4 && !anchors.contains(norm)) {
                for (String other : byFrequency) {
                    if (other.equals(name) || !folded.getOrDefault(other, other).equals(other)) {
                        continue;
                    }
                    boolean moreSeen = counts.get(other) > counts.get(name) || anchors.contains(normalise(other));
                    boolean close = players ? confusableOnly(norm, normalise(other)) : sameThing(norm, normalise(other));
                    if (moreSeen && close) {
                        target = other;
                        break;
                    }
                }
            }
            folded.put(name, target);
        }
        Map<String, String> out = new HashMap<>();
        aliased.forEach((read, named) -> out.put(read, folded.getOrDefault(named, named)));
        return out;
    }

    /** Marks OCR adds that pack and player names rarely contain. */
    static int oddMarks(String name) {
        return (int) name.chars().filter(c -> c == '!' || c == '|' || c == '"' || c == '\'' || c == '`').count();
    }

    private static final String CONFUSABLE = "il1|";

    /** Same length, and every difference is one OCR glyph read as another (l / I / 1 / |). */
    static boolean confusableOnly(String a, String b) {
        if (a.length() != b.length() || a.equals(b)) {
            return false;
        }
        for (int i = 0; i < a.length(); i++) {
            char x = a.charAt(i);
            char y = b.charAt(i);
            if (x != y && (CONFUSABLE.indexOf(x) < 0 || CONFUSABLE.indexOf(y) < 0)) {
                return false;
            }
        }
        return true;
    }

    /**
     * One stray letter apart, same start, same digits. Digits must match exactly: "Hero Rally 2
     * Unlock" and "Hero Rally Unlock" are different packs, and a digit OCR invented mid-name
     * ("Legend 1 Transcend Pack") goes through the alias file instead.
     */
    static boolean sameThing(String a, String b) {
        if (a.length() < 3 || b.length() < 3 || Math.abs(a.length() - b.length()) > FUZZY_EDITS
                || !a.substring(0, 3).equals(b.substring(0, 3))
                || !a.replaceAll("\\D", "").equals(b.replaceAll("\\D", ""))) {
            return false;
        }
        return edits(a, b) <= FUZZY_EDITS;
    }

    /**
     * Prices from the deal scanner. The nightly reports' {@code packs[]} is the cleaned list (store
     * prices only); older days without a report fall back to the raw scan's priced packs. Runs
     * superseded later the same day are skipped. A name with a price suffix ("Daily Deals - $0.99",
     * the scanner's title for an untitled tier) also indexes its family "Daily Deals", and the tab a
     * pack sits on ("Gem Shop &gt; Mix &amp; Match") indexes as a family too.
     */
    static ScanIndex scanIndex(Path deals) throws IOException {
        Map<String, TreeSet<Double>> names = new HashMap<>();
        Map<String, TreeSet<Double>> families = new HashMap<>();
        java.util.Set<String> reported = new java.util.HashSet<>();
        for (Path report : jsonFiles(deals.resolve("reports"))) {
            JsonNode doc = readOrNull(report);
            if (doc == null) {
                continue;
            }
            reported.add(report.getFileName().toString());
            for (JsonNode p : doc.path("packs")) {
                add(names, families, p.path("name").asText(""), lastSegment(p.path("where").asText("")),
                        p.path("price").asDouble(0));
            }
        }
        for (Path scan : jsonFiles(deals.resolve("scans"))) {
            if (reported.contains(scan.getFileName().toString())) {
                continue;
            }
            JsonNode doc = readOrNull(scan);
            if (doc == null) {
                continue;
            }
            for (JsonNode o : doc.path("offers")) {
                if (o.path("pricedPack").asBoolean(false)) {
                    add(names, families, o.path("title").asText(""), o.path("tab").asText(""),
                            o.path("priceUsd").asDouble(0));
                }
            }
        }
        return new ScanIndex(names, families);
    }

    private static void add(Map<String, TreeSet<Double>> names, Map<String, TreeSet<Double>> families,
                            String name, String tab, double price) {
        if (price <= 0 || name.isBlank()) {
            return;
        }
        String family = name.replaceFirst("\\s*-\\s*\\$[\\d.,]+\\s*$", "");
        names.computeIfAbsent(normalise(name), k -> new TreeSet<>()).add(price);
        if (!family.equals(name)) {
            families.computeIfAbsent(normalise(family), k -> new TreeSet<>()).add(price);
        }
        if (!tab.isBlank()) {
            families.computeIfAbsent(normalise(tab), k -> new TreeSet<>()).add(price);
        }
    }

    private static String lastSegment(String where) {
        int at = where.lastIndexOf('>');
        return (at < 0 ? where : where.substring(at + 1)).strip();
    }

    private static List<Path> jsonFiles(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> {
                String n = p.getFileName().toString();
                return n.endsWith(".json") && !n.contains("superseded");
            }).sorted().toList();
        }
    }

    private static JsonNode readOrNull(Path file) {
        try {
            return MAPPER.readTree(file.toFile());
        } catch (IOException unreadable) {
            return null;
        }
    }

    /** The known title a misread is closest to, or the read itself when none is close enough. */
    static String canonical(String read, List<String> known) {
        if (known.contains(read)) {
            return read;
        }
        String best = read;
        int bestEdits = FUZZY_EDITS + 1;
        for (String title : known) {
            if (!sameThing(read, title)) {
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

    /** Known dollars only, or known plus estimated. Purchases with no price at all add nothing. */
    public static double usd(List<Purchase> list, boolean estimatesToo) {
        return list.stream()
                .filter(p -> p.usd() != null && (estimatesToo || p.source().known()))
                .mapToDouble(Purchase::usd).sum();
    }

    /** Purchases that could not be priced at all (no tier read and no scanned price). */
    public static long unpriceable(List<Purchase> list) {
        return list.stream().filter(p -> p.usd() == null).count();
    }

    /** Spend per buyer, biggest first; anonymous purchases are one row. */
    public static List<BuyerTotal> byBuyer(List<Purchase> list) {
        Map<String, double[]> totals = new LinkedHashMap<>();
        for (Purchase p : list) {
            String who = p.anonymous() || p.buyer() == null ? "(anonymous)" : p.buyer();
            double[] t = totals.computeIfAbsent(who, k -> new double[3]);
            t[0]++;
            double usd = p.usd() == null ? 0 : p.usd();
            t[p.source().known() ? 1 : 2] += usd;
        }
        List<BuyerTotal> out = new ArrayList<>();
        totals.forEach((who, t) -> out.add(new BuyerTotal(who, (int) t[0], t[1], t[2])));
        out.sort(Comparator.comparingDouble(BuyerTotal::usd).reversed().thenComparing(BuyerTotal::buyer));
        return out;
    }

    /** Pack names no operator entry or scan has priced, most bought first: what to price next. */
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
