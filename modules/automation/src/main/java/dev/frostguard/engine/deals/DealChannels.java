package dev.frostguard.engine.deals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The Deal Tracker tab's content: per tracked item, what the Discord deal channels post. Reads the same files as
 * {@code discord-translate/deals-post.py} and {@code material-post.py} (the newest report, {@code fixes.json} and
 * the channels' history) and applies their rules, so the tab and the posts agree.
 */
public final class DealChannels {

    /** Within this share of the day's best a pack is marked close to it. */
    private static final double CLOSE_TO_BEST = 1.25;
    private static final double SAME_AS_BEST = 1.001;
    private static final double SAME_AS_AVERAGE = 0.01;
    private static final int AVERAGE_DAYS = 10;
    /** The tracker's first day; earlier scans were patchy. */
    private static final LocalDate TRACK_START = LocalDate.of(2026, 10, 22);

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern REPORT_FILE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}\\.json");
    private static final Pattern JUNK_MARKS = Pattern.compile("[|‘’\"_;:?]");
    private static final Set<String> SMALL_WORDS = Set.of("of", "the", "and", "a", "to", "x", "for", "in", "on");
    private static final Set<String> NAME_ENDS = Set.of("pack", "card", "chest", "bundle", "offer");
    private static final Pattern PRICE_SUFFIX = Pattern.compile("\\s*-?\\s*\\$[\\d,.]+\\s*$");
    private static final Pattern INTEL_TIER = Pattern.compile("(?i)(intel monthly card) \\((\\w+)\\)");
    private static final Map<Double, String> INTEL_TIERS = Map.of(9.99, "Mythic", 4.99, "Epic");
    private static final Map<String, String> WHERE_FIXES = Map.of(
            "Fire Crystal Lap Pack", "Fire Crystal Leap Pack",
            "Fire crate Lp Pack", "Fire Crystal Leap Pack",
            "Tundra SupplyStation", "Tundra Supply Station");
    private static final List<Map.Entry<Pattern, String>> LIMITS = List.of(
            Map.entry(Pattern.compile("(?i)event remaining:\\s*(\\d+)"), "$1 left"),
            Map.entry(Pattern.compile("(?i)weekly remaining:\\s*(\\d+)"), "$1/week"),
            Map.entry(Pattern.compile("(?i)daily limit:\\s*(\\d+)"), "$1/day"),
            Map.entry(Pattern.compile("(?i)lifetime limit:\\s*(\\d+)"), "$1 total"),
            Map.entry(Pattern.compile("(?i)monthly stock:\\s*(\\d+)"), "$1/month"),
            Map.entry(Pattern.compile("(?i)remaining:\\s*(\\d+)"), "$1 left"));

    public static final List<Channel> CHANNELS = List.of(
            new Channel("fire-crystals", "Fire Crystals", "Fire Crystal", 1, 3, "Price per crystal", "Crystals", "Each",
                    "crystal", "fc-history.json", "usd_per_fc"),
            new Channel("hardened-alloy", "Hardened Alloy", "Hardened Alloy", 1000, 2, "Price per 1,000 alloy", "Alloy",
                    "Per 1K", "alloy", "hardened-alloy/history.json", "usd_per_1000"),
            new Channel("design-plans", "Design Plans", "Design Plans", 1, 2, "Price per plan", "Plans", "Each", "plan",
                    "design-plans/history.json", "usd_each"),
            new Channel("charm-design", "Charm Design", "Charm Design", 1, 2, "Price per design", "Designs", "Each",
                    "design", "charm-design/history.json", "usd_each"),
            new Channel("charm-guide", "Charm Guide", "Charm Guide", 1, 2, "Price per guide", "Guides", "Each", "guide",
                    "charm-guide/history.json", "usd_each"),
            new Channel("essence-stones", "Essence Stones", "Essence Stones", 1, 2, "Price per stone", "Stones", "Each",
                    "stone", "essence-stones/history.json", "usd_each"));

    public enum Mark { BEST, CLOSE, OTHER }

    public enum Trend { CHEAPER, USUAL, PRICIER }

    public record Channel(String id, String title, String item, int per, int places, String heading, String countColumn,
            String perColumn, String keyWord, String historyFile, String historyKey) {
        private boolean fireCrystals() {
            return "fire-crystals".equals(id);
        }
    }

    public record Tracker(double today, Trend trend, double average, double lowest, LocalDate lowestDay) {
    }

    public record PackRow(Mark mark, String label, double price, String count, double perUnit, Trend trend) {
    }

    public record Place(String label, String path) {
    }

    public record Point(LocalDate day, double perUnit, double average) {
    }

    /** @param hold why this channel is not cleared for Discord tonight, or {@code null} when it is */
    public record Board(Channel channel, Tracker tracker, List<PackRow> packs, List<Place> places, List<String> unpriced,
            List<Point> chart, String hold) {
    }

    /** @param day the newest report's day, or {@code null} when there is no report yet */
    public record Snapshot(LocalDate day, int packCount, List<Board> boards, List<String> problems) {
    }

    private record Item(String name, long quantity) {
    }

    private static final class Pack {
        private String name;
        private String where;
        private Double price;
        private String limit;
        private boolean gone;
        private List<Item> items;
    }

    private record Row(String label, Double price, long quantity, String count, String path) {
        private double perUnit(int per) {
            return price / quantity * per;
        }
    }

    private DealChannels() {
    }

    /** Reads the channels from a {@code data/deals} folder. An unreadable file is named in the problems, never hidden. */
    public static Snapshot read(Path dealsRoot) {
        List<String> problems = new ArrayList<>();
        Path discord = dealsRoot.resolve("discord");
        JsonNode fixes = readJson(discord.resolve("fixes.json"), problems).orElse(MAPPER.createObjectNode());
        TreeMap<LocalDate, List<Pack>> reports = readReports(dealsRoot.resolve("reports"), fixes, problems);
        if (reports.isEmpty()) {
            return new Snapshot(null, 0, List.of(), problems);
        }
        LocalDate day = reports.lastKey();
        List<Board> boards = new ArrayList<>();
        for (Channel channel : CHANNELS) {
            boards.add(board(channel, day, reports, discord, problems));
        }
        return new Snapshot(day, reports.get(day).size(), boards, problems);
    }

    private static Board board(Channel channel, LocalDate day, TreeMap<LocalDate, List<Pack>> reports, Path discord,
            List<String> problems) {
        List<Row> rows = rows(channel, reports.get(day));
        List<Row> priced = rows.stream().filter(r -> r.price() != null)
                .sorted(Comparator.comparingDouble(r -> r.perUnit(channel.per()))).toList();
        TreeMap<LocalDate, Double> history = history(channel, reports, discord, problems);
        List<Point> chart = new ArrayList<>();
        history.forEach((d, value) -> chart.add(new Point(d, value, average(history, d))));
        Tracker tracker = history.containsKey(day) ? tracker(history, day) : null;
        Double average = tracker == null ? null : tracker.average();

        List<PackRow> packs = new ArrayList<>();
        Map<String, String> places = new LinkedHashMap<>();
        for (Row row : priced) {
            double per = row.perUnit(channel.per());
            double best = priced.get(0).perUnit(channel.per());
            Mark mark = per <= best * SAME_AS_BEST ? Mark.BEST : per <= best * CLOSE_TO_BEST ? Mark.CLOSE : Mark.OTHER;
            packs.add(new PackRow(mark, row.label(), row.price(), row.count(), per,
                    average == null ? null : trend(per, average)));
            places.putIfAbsent(row.label(), row.path());
        }
        List<String> unpriced = rows.stream().filter(r -> r.price() == null).map(Row::label).toList();
        return new Board(channel, tracker, packs, groupPlaces(places), unpriced, chart,
                hold(channel, day, discord, problems));
    }

    private static List<Row> rows(Channel channel, List<Pack> packs) {
        List<Row> rows = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Pack pack : packs) {
            long exact = quantity(pack, channel.item(), false);
            long picks = channel.fireCrystals() ? 0 : quantity(pack, channel.item(), true);
            long total = exact + picks;
            if (total == 0 || pack.gone || junk(pack.name)) {
                continue;
            }
            Row row = channel.fireCrystals() ? crystalRow(pack, total) : materialRow(channel, pack, total, picks > 0);
            if (seen.add(row.label().toLowerCase(Locale.ROOT) + "|" + row.price() + "|" + total)) {
                rows.add(row);
            }
        }
        return rows;
    }

    private static Row crystalRow(Pack pack, long crystals) {
        String name = PRICE_SUFFIX.matcher(pack.name).replaceAll("").strip();
        String where = cleanWhere(pack.where);
        Matcher intel = INTEL_TIER.matcher(name);
        String tier = intel.matches() ? capitalise(intel.group(2))
                : name.equalsIgnoreCase("intel monthly card") ? INTEL_TIERS.get(pack.price) : null;
        if (tier != null) {
            where = where.replaceAll("(?i)\\s*\\(" + tier + "\\)", "") + " > " + tier;
            name = "Intel Monthly Card " + tier;
        }
        int split = where.indexOf(" > ");
        String tab = split < 0 ? "" : where.substring(split + 3);
        boolean tabNames = tab.toLowerCase(Locale.ROOT).contains("fire crystal") && !name.matches(".*\\d.*");
        String label = crystalShort(tabNames ? tab : name);
        String days = pack.name.toLowerCase(Locale.ROOT).contains("weekly card") ? " (7d)" : "";
        String limit = shortLimit(pack.limit);
        return new Row(label, pack.price, crystals, String.format(Locale.US, "%,d", crystals) + days,
                where + (limit.isEmpty() ? "" : " · " + limit));
    }

    private static Row materialRow(Channel channel, Pack pack, long quantity, boolean picked) {
        String label = pack.name.replaceFirst("(?i)^chief gear\\s+", "CG ").replaceAll("[ ,.]+$", "");
        String limit = shortLimit(pack.limit);
        if (limit.isEmpty() && pack.limit != null) {
            limit = pack.limit;
        }
        String path = cleanWhere(pack.where) + (limit.isEmpty() ? "" : " · " + limit)
                + (picked ? " · pick " + channel.item() + " in every slot" : "");
        return new Row(label, pack.price, quantity, String.format(Locale.US, "%,d", quantity), path);
    }

    private static String crystalShort(String name) {
        String shortened = name.replaceFirst("(?i)^intel monthly card", "Intel Card")
                .replaceFirst("(?i)^dream traveler pack", "Dream Traveler")
                .replaceFirst("(?i)^max level rewards preview$", "Hero Rally");
        return shortened.replaceFirst("(?i)^fire crystal\\s+", "FC ");
    }

    private static long quantity(Pack pack, String item, boolean slotPicks) {
        Pattern pick = Pattern.compile("(?i)" + Pattern.quote(item) + " \\(slot \\d+ choice\\)");
        return pack.items.stream()
                .filter(i -> slotPicks ? pick.matcher(i.name()).matches() : i.name().equalsIgnoreCase(item))
                .mapToLong(Item::quantity).sum();
    }

    /** Tiers of one pack sold at the same place share a line: "FC Leap Pack 3, 4, 5". */
    private static List<Place> groupPlaces(Map<String, String> places) {
        Map<String, List<String>> byPath = new LinkedHashMap<>();
        places.forEach((label, path) -> byPath.computeIfAbsent(path, p -> new ArrayList<>()).add(label));
        List<Place> grouped = new ArrayList<>();
        byPath.forEach((path, labels) -> grouped.add(new Place(joinTiers(labels), path.replace(" > ", " › "))));
        return grouped;
    }

    private static String joinTiers(List<String> labels) {
        boolean tiers = labels.size() > 1 && labels.stream().allMatch(l -> l.matches(".* \\d+"))
                && labels.stream().map(l -> l.substring(0, l.lastIndexOf(' '))).distinct().count() == 1;
        if (!tiers) {
            return String.join(", ", labels);
        }
        String stem = labels.get(0).substring(0, labels.get(0).lastIndexOf(' '));
        return stem + " " + String.join(", ", labels.stream().map(l -> l.substring(l.lastIndexOf(' ') + 1)).toList());
    }

    /**
     * The channel's posted history plus the newest report's night. Other unposted nights stay out, as they do on
     * Discord: no auditor approved them, and one misread would hold "Lowest seen" for good.
     */
    private static TreeMap<LocalDate, Double> history(Channel channel, TreeMap<LocalDate, List<Pack>> reports,
            Path discord, List<String> problems) {
        TreeMap<LocalDate, Double> history = new TreeMap<>();
        Path file = discord.resolve(channel.historyFile());
        if (Files.isRegularFile(file)) {
            readJson(file, problems).ifPresent(points -> points.forEach(point -> {
                if (point.hasNonNull(channel.historyKey()) && point.hasNonNull("day")) {
                    history.put(LocalDate.parse(point.get("day").asText()), point.get(channel.historyKey()).asDouble());
                }
            }));
        }
        LocalDate newest = reports.lastKey();
        if (!history.containsKey(newest)) {
            rows(channel, reports.get(newest)).stream().filter(r -> r.price() != null)
                    .mapToDouble(r -> r.perUnit(channel.per())).min()
                    .ifPresent(best -> history.put(newest, best));
        }
        history.headMap(TRACK_START).clear();
        return history;
    }

    private static Tracker tracker(TreeMap<LocalDate, Double> history, LocalDate day) {
        double today = history.get(day);
        double average = average(history, day);
        Map.Entry<LocalDate, Double> lowest = history.entrySet().stream()
                .min(Map.Entry.<LocalDate, Double>comparingByValue().thenComparing(Map.Entry.comparingByKey()))
                .orElseThrow();
        return new Tracker(today, trend(today, average), average, lowest.getValue(), lowest.getKey());
    }

    /** Over calendar days, not points: after a week with the bot off the last ten points span weeks. */
    private static double average(TreeMap<LocalDate, Double> history, LocalDate end) {
        return history.subMap(end.minusDays(AVERAGE_DAYS - 1L), true, end, true).values().stream()
                .mapToDouble(Double::doubleValue).average().orElseThrow();
    }

    private static Trend trend(double now, double reference) {
        if (now < reference * (1 - SAME_AS_AVERAGE)) {
            return Trend.CHEAPER;
        }
        return now > reference * (1 + SAME_AS_AVERAGE) ? Trend.PRICIER : Trend.USUAL;
    }

    private static String hold(Channel channel, LocalDate day, Path discord, List<String> problems) {
        Path file = discord.resolve("HOLD");
        if (!Files.isRegularFile(file)) {
            return "Not audited yet";
        }
        Optional<JsonNode> verdict = readJson(file, problems);
        if (verdict.isEmpty() || !day.toString().equals(verdict.get().path("day").asText())) {
            return "Not audited yet";
        }
        int flags = verdict.get().path("channels").path(channel.id()).size();
        return flags == 0 ? null : "Held from Discord: " + flags + (flags == 1 ? " flag" : " flags");
    }

    private static TreeMap<LocalDate, List<Pack>> readReports(Path dir, JsonNode fixes, List<String> problems) {
        TreeMap<LocalDate, List<Pack>> reports = new TreeMap<>();
        if (!Files.isDirectory(dir)) {
            return reports;
        }
        List<Path> files;
        try (Stream<Path> listing = Files.list(dir)) {
            files = listing.filter(p -> REPORT_FILE.matcher(p.getFileName().toString()).matches()).toList();
        } catch (IOException unreadable) {
            problems.add("Could not list " + dir + ": " + unreadable.getMessage());
            return reports;
        }
        for (Path file : files) {
            String day = file.getFileName().toString().replace(".json", "");
            readJson(file, problems).map(report -> report.get("packs")).filter(JsonNode::isArray)
                    .ifPresent(packs -> reports.put(LocalDate.parse(day), packs(packs, fixes.path(day))));
        }
        return reports;
    }

    private static List<Pack> packs(JsonNode nodes, JsonNode fixes) {
        List<Pack> packs = new ArrayList<>();
        for (JsonNode node : nodes) {
            Pack pack = new Pack();
            pack.name = node.path("name").asText("");
            pack.where = node.path("where").asText("");
            pack.price = node.hasNonNull("price") ? node.get("price").asDouble() : null;
            pack.limit = node.hasNonNull("limit") ? node.get("limit").asText() : null;
            pack.gone = node.path("purchased").asBoolean() || node.path("unavailable").asBoolean();
            pack.items = new ArrayList<>();
            node.path("items").forEach(i -> pack.items.add(new Item(i.path("name").asText(""), i.path("quantity").asLong())));
            fixes.forEach(fix -> applyFix(pack, fix));
            packs.add(pack);
        }
        return packs;
    }

    private static void applyFix(Pack pack, JsonNode fix) {
        if (!norm(pack.name).equals(norm(fix.path("name").asText())) || pack.price == null
                || pack.price != fix.path("price").asDouble()) {
            return;
        }
        if (fix.has("purchased") || fix.has("unavailable")) {
            pack.gone = fix.path("purchased").asBoolean(pack.gone) || fix.path("unavailable").asBoolean(pack.gone);
        }
        if (fix.has("limit")) {
            pack.limit = fix.get("limit").asText();
        }
        if (fix.hasNonNull("replaces")) {
            pack.items.removeIf(i -> i.name().equals(fix.get("replaces").asText()));
        }
        if (fix.hasNonNull("item")) {
            pack.items.removeIf(i -> i.name().equals(fix.get("item").asText()));
            pack.items.add(new Item(fix.get("item").asText(), fix.path("quantity").asLong()));
        }
    }

    private static Optional<JsonNode> readJson(Path file, List<String> problems) {
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(MAPPER.readTree(file.toFile()));
        } catch (IOException unreadable) {
            problems.add(file.getFileName() + " could not be read: " + unreadable.getMessage());
            return Optional.empty();
        }
    }

    /** The name without OCR debris, cut after its noun: how a fix finds its pack. */
    private static String norm(String title) {
        String[] words = title.toLowerCase(Locale.ROOT).replaceAll("[^a-z ]+", " ").strip().split("\\s+");
        List<String> kept = new ArrayList<>();
        for (String word : words) {
            kept.add(word);
            if (NAME_ENDS.contains(word)) {
                break;
            }
        }
        return String.join(" ", kept);
    }

    /** Shop titles are Title Case; OCR debris shows up as stray lowercase fragments or marks. */
    private static boolean junk(String title) {
        if (title.isEmpty() || JUNK_MARKS.matcher(title).find()) {
            return true;
        }
        for (String word : title.split("[\\s>/-]+")) {
            if (!word.isEmpty() && Character.isLowerCase(word.charAt(0)) && !SMALL_WORDS.contains(word)) {
                return true;
            }
        }
        return false;
    }

    private static String shortLimit(String limit) {
        if (limit == null) {
            return "";
        }
        for (Map.Entry<Pattern, String> rule : LIMITS) {
            Matcher matcher = rule.getKey().matcher(limit);
            if (matcher.find()) {
                return rule.getValue().replace("$1", matcher.group(1));
            }
        }
        return "";
    }

    private static String cleanWhere(String where) {
        String cleaned = where.replaceAll("\\s*\\(variant \\d+ of \\d+\\)", "");
        for (Map.Entry<String, String> fix : WHERE_FIXES.entrySet()) {
            cleaned = cleaned.replace(fix.getKey(), fix.getValue());
        }
        return cleaned.replaceAll("City icon [\\d,]+(?: > City icon)?", "City screen icon");
    }

    private static String capitalise(String word) {
        return word.substring(0, 1).toUpperCase(Locale.ROOT) + word.substring(1).toLowerCase(Locale.ROOT);
    }
}
