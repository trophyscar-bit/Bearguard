package dev.frostguard.engine.alliance;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.frostguard.vision.ocr.TextLine;

/**
 * Files under {@code <workspace>/data/alliance-spend}: every Alliance Gift purchase the chest pass
 * saw, one JSON line each, plus the photographs and raw OCR behind each pass.
 *
 * <p>Each row of the Alliance Gifts list reads {@code Alliance Gift II / <buyer> has purchased
 * "<pack>"} with a key value on the right; an anonymous buyer shows as "Your ally". Rows carry no
 * time, so a purchase is told apart from an identical later one by its place in the list, which is
 * newest first: inside a pass, screens are stitched by how far the picture moved; across passes,
 * only the rows above the point where the previous pass's list begins are new.</p>
 */
public final class AllianceGiftLedger {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** Visible list on a 720x1280 frame: below the "Purchasing a gift pack..." banner, above the anonymous checkbox. */
    public static final int LIST_TOP = 548;
    public static final int LIST_BOTTOM = 1108;

    /** Left edge of the row text, right of the item icon; the key value and Claimed sit right of {@link #RIGHT_COLUMN}. */
    public static final int TEXT_LEFT = 165;
    public static final int RIGHT_COLUMN = 540;
    public static final int TEXT_RIGHT = 700;

    /** Lines further apart than this belong to different cards; cards are ~150px apart with a ~65px gap. */
    static final int ROW_GAP_PX = 30;

    /** Two reads of one card from different screens land within this of each other once stitched. */
    static final int SAME_ROW_PX = 45;

    /** How many consecutive rows must match before the previous pass's list is taken to start there. */
    static final int ALIGN_RUN = 3;

    private static final Pattern TIER = Pattern.compile("(?i)alliance\\W*gift\\W*([IVXl1|]+)");
    private static final Pattern PURCHASE =
            Pattern.compile("(?i)^(.*?)\\s*has\\s*purchased\\s*[\"“”']?(.*?)[\"“”']?\\s*$");
    private static final Pattern NUMBER = Pattern.compile("^\\d{1,4}$");

    public record GiftRow(String capturedAt, String tier, String buyer, boolean anonymous, String pack,
                          Integer keys, String rawText, String frame, int listY) {

        /** What makes two rows the same purchase on screen; position decides between equal ones. */
        public String signature() {
            return normalise(tier) + "|" + normalise(buyer) + "|" + normalise(pack);
        }
    }

    private final Path root;

    public AllianceGiftLedger(Path root) {
        this.root = root;
    }

    public static AllianceGiftLedger forWorkspace(Path workspaceRoot) {
        return new AllianceGiftLedger(workspaceRoot.resolve("data").resolve("alliance-spend"));
    }

    public Path root() {
        return root;
    }

    public Path passDir(LocalDateTime startedAt) {
        return root.resolve("passes").resolve(startedAt.truncatedTo(ChronoUnit.SECONDS)
                .toString().replace(':', '-'));
    }

    /**
     * Reads one screen's cards, top to bottom, at their position in the whole list.
     *
     * <p>A card cut off by the top or bottom of the list is dropped: the overlap between screens
     * means it is read whole on the neighbouring one.</p>
     *
     * @param scrolledBy how far this screen has scrolled from the top of the list, in pixels
     */
    public static List<GiftRow> parseFrame(List<TextLine> lines, LocalDateTime capturedAt, String frame,
                                           int scrolledBy) {
        List<TextLine> sorted = new ArrayList<>();
        for (TextLine line : lines) {
            if (line.text() != null && !line.text().isBlank()) {
                sorted.add(line);
            }
        }
        sorted.sort(Comparator.comparingInt(TextLine::top).thenComparingInt(TextLine::left));
        List<List<TextLine>> cards = new ArrayList<>();
        int lastBottom = Integer.MIN_VALUE;
        for (TextLine line : sorted) {
            if (cards.isEmpty() || line.top() - lastBottom > ROW_GAP_PX) {
                cards.add(new ArrayList<>());
                lastBottom = Integer.MIN_VALUE;
            }
            cards.get(cards.size() - 1).add(line);
            lastBottom = Math.max(lastBottom, line.bottom());
        }
        List<GiftRow> rows = new ArrayList<>();
        for (List<TextLine> card : cards) {
            GiftRow row = parseCard(card, capturedAt, frame, scrolledBy);
            if (row != null) {
                rows.add(row);
            }
        }
        return rows;
    }

    static GiftRow parseCard(List<TextLine> card, LocalDateTime capturedAt, String frame, int scrolledBy) {
        StringBuilder left = new StringBuilder();
        StringBuilder raw = new StringBuilder();
        Integer keys = null;
        for (TextLine line : readingOrder(card)) {
            String text = line.text().strip();
            raw.append(raw.length() > 0 ? " | " : "").append(text);
            if (line.left() >= RIGHT_COLUMN) {
                String digits = text.replaceAll("\\D", "");
                if (keys == null && NUMBER.matcher(digits).matches() && TIER_BY_KEYS.containsKey(Integer.parseInt(digits))) {
                    keys = Integer.parseInt(digits);
                }
                continue;
            }
            left.append(left.length() > 0 ? " " : "").append(text);
        }
        Matcher tierMatch = TIER.matcher(left);
        if (!tierMatch.find()) {
            return null;
        }
        String tier = romanTier(tierMatch.group(1));
        String rest = left.substring(tierMatch.end()).strip();
        Matcher purchase = PURCHASE.matcher(rest);
        if (!purchase.find() || purchase.group(2).isBlank() || !closesQuote(rest)) {
            return null;
        }
        String buyer = purchase.group(1).strip();
        boolean anonymous = buyer.toLowerCase(Locale.ROOT).replace(" ", "").contains("yourally");
        String keyTier = keys == null ? null : TIER_BY_KEYS.get(keys);
        if (keyTier != null) {
            tier = keyTier;
        } else {
            keys = KEYS_BY_TIER.get(tier);
        }
        return new GiftRow(capturedAt.truncatedTo(ChronoUnit.SECONDS).toString(), tier,
                anonymous ? null : buyer, anonymous, cleanPack(purchase.group(2)), keys, raw.toString(), frame,
                card.get(0).top() + scrolledBy);
    }

    /**
     * Keys each tier gives, as printed on the card. Digits read far more reliably than the tier's
     * numeral (a III read as "I" beside a clean "120" on the first live pass), so a valid key value
     * decides the tier, and the tier fills in a misread key value.
     */
    private static final Map<Integer, String> TIER_BY_KEYS = Map.of(
            30, "Alliance Gift I", 60, "Alliance Gift II", 120, "Alliance Gift III", 300, "Alliance Gift IV");
    private static final Map<String, Integer> KEYS_BY_TIER = Map.of(
            "Alliance Gift I", 30, "Alliance Gift II", 60, "Alliance Gift III", 120, "Alliance Gift IV", 300);

    private static final String QUOTES = "\"“”'‘’";

    /** The pack name between its quotes, without the stray glyph the reader puts before the opening one. */
    static String cleanPack(String read) {
        String pack = read.strip();
        for (int i = 0; i < Math.min(3, pack.length()); i++) {
            if (QUOTES.indexOf(pack.charAt(i)) >= 0) {
                pack = pack.substring(i + 1);
                break;
            }
        }
        while (!pack.isEmpty() && QUOTES.indexOf(pack.charAt(pack.length() - 1)) >= 0) {
            pack = pack.substring(0, pack.length() - 1);
        }
        // A wrapped link's underline is read as a capital I glued to the end of the first line's
        // last word ("ArcaneI Reverie"); a real word never ends lowercase-then-I.
        return pack.replaceAll("(?<=\\p{Ll})I(?=\\s)", "").strip();
    }

    /** Pieces whose tops are this close sit on one printed line. */
    static final int SAME_LINE_PX = 14;

    /**
     * The card's pieces in reading order.
     *
     * <p>The reader splits one printed line into pieces whose tops differ by a pixel or two
     * ("Alliance" at 567, "Gift II" at 566), so ordering by top alone reads them backwards.
     * Pieces are gathered into printed lines first, then read left to right.</p>
     */
    static List<TextLine> readingOrder(List<TextLine> card) {
        List<TextLine> byTop = new ArrayList<>(card);
        byTop.sort(Comparator.comparingInt(TextLine::top));
        List<List<TextLine>> printed = new ArrayList<>();
        for (TextLine piece : byTop) {
            List<TextLine> line = printed.isEmpty() ? null : printed.get(printed.size() - 1);
            if (line == null || piece.top() - line.get(0).top() > SAME_LINE_PX) {
                line = new ArrayList<>();
                printed.add(line);
            }
            line.add(piece);
        }
        List<TextLine> ordered = new ArrayList<>();
        for (List<TextLine> line : printed) {
            line.sort(Comparator.comparingInt(TextLine::left));
            ordered.addAll(line);
        }
        return ordered;
    }

    /** A pack name that runs off the bottom of the list has no closing quote yet. */
    private static boolean closesQuote(String text) {
        String t = text.strip();
        return !t.isEmpty() && QUOTES.indexOf(t.charAt(t.length() - 1)) >= 0;
    }

    /** OCR reads the tier's I as l, 1 or |; IV and III must survive that. */
    static String romanTier(String read) {
        String r = read.toUpperCase(Locale.ROOT).replace('L', 'I').replace('1', 'I').replace('|', 'I');
        return "Alliance Gift " + r;
    }

    /**
     * How far the list moved between two screens, in pixels, or -1 when no shift lines them up.
     *
     * <p>Found by sliding one picture over the other and keeping the shift with the smallest
     * difference. Measured rather than assumed from the swipe, because a drag's travel and the
     * list's travel differ by however much it coasts.</p>
     */
    public static int scrollShift(BufferedImage before, BufferedImage after) {
        int height = LIST_BOTTOM - LIST_TOP;
        int bestShift = -1;
        double bestScore = Double.MAX_VALUE;
        for (int dy = 0; dy <= height - 120; dy++) {
            long diff = 0;
            int samples = 0;
            for (int y = LIST_TOP; y + dy < LIST_BOTTOM; y += 3) {
                for (int x = TEXT_LEFT - 140; x < TEXT_RIGHT; x += 6) {
                    diff += Math.abs(grey(after.getRGB(x, y)) - grey(before.getRGB(x, y + dy)));
                    samples++;
                }
            }
            double score = diff / (double) samples;
            if (score < bestScore) {
                bestScore = score;
                bestShift = dy;
            }
        }
        return bestScore <= MAX_ALIGNED_DIFF ? bestShift : -1;
    }

    /**
     * Mean grey difference at which two screens count as the same pixels shifted.
     *
     * <p>The cards repeat every ~150px, so a wrong shift still lines up card backgrounds: on the
     * first live pass (2026-10-22, screens that shared no rows) the best wrong shift scored 6 to 7.
     * A true overlap is the same rendering and scores near zero.</p>
     */
    static final double MAX_ALIGNED_DIFF = 3.0;

    private static int grey(int rgb) {
        return (((rgb >> 16) & 0xFF) * 3 + ((rgb >> 8) & 0xFF) * 6 + (rgb & 0xFF)) / 10;
    }

    /** Merges the screens of one pass into the list as it stands, newest first. */
    public static List<GiftRow> stitch(List<GiftRow> rows) {
        List<GiftRow> sorted = new ArrayList<>(rows);
        sorted.sort(Comparator.comparingInt(GiftRow::listY));
        List<GiftRow> list = new ArrayList<>();
        for (GiftRow row : sorted) {
            GiftRow last = list.isEmpty() ? null : list.get(list.size() - 1);
            if (last != null && Math.abs(row.listY() - last.listY()) <= SAME_ROW_PX) {
                continue;
            }
            list.add(row);
        }
        return list;
    }

    /**
     * How many rows at the top of {@code current} arrived since {@code previous} was read.
     *
     * <p>The previous list's first rows are looked for inside the current one; everything above
     * where they start is new. -1 when they cannot be found, which means more was bought than one
     * pass can see, or the list was misread -- either way the caller must not guess.</p>
     */
    static int newRowCount(List<String> previous, List<String> current) {
        if (previous.isEmpty()) {
            return current.size();
        }
        int run = Math.min(ALIGN_RUN, previous.size());
        for (int k = 0; k + run <= current.size(); k++) {
            boolean match = true;
            for (int i = 0; i < run; i++) {
                if (!current.get(k + i).equals(previous.get(i))) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return k;
            }
        }
        return -1;
    }

    static String normalise(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", "");
    }

    /** Stable stand-in for a name, so one buyer's purchases still group together. */
    public static String anonymise(String buyer) {
        if (buyer == null) {
            return null;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(buyer.strip().getBytes(StandardCharsets.UTF_8));
            return "anon-" + HexFormat.of().formatHex(digest).substring(0, 10);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** What one pass added to the ledger. */
    public record PassResult(int listed, int fresh, boolean firstPass, boolean aligned) {
    }

    /**
     * Appends the purchases that arrived since the last pass.
     *
     * <p>The first pass logs the whole list, marked as a backlog since those purchases happened
     * before logging began. If the previous list cannot be found in this one, nothing is logged
     * and the list is kept as the new reference, so one bad read costs one pass rather than
     * double-counting a whole list.</p>
     *
     * @param list the stitched list, newest first, and reaching its end
     */
    public PassResult recordPass(List<GiftRow> list, boolean anonymiseBuyers) throws IOException {
        Files.createDirectories(root);
        Path last = root.resolve("last-list.json");
        List<String> previous = Files.exists(last)
                ? MAPPER.readValue(last.toFile(), new TypeReference<List<String>>() { })
                : List.of();
        List<String> current = list.stream().map(GiftRow::signature).toList();
        boolean firstPass = !Files.exists(last);
        int fresh = newRowCount(previous, current);
        boolean aligned = fresh >= 0;
        if (aligned && fresh > 0) {
            StringBuilder out = new StringBuilder();
            for (GiftRow row : list.subList(0, fresh)) {
                GiftRow logged = anonymiseBuyers && row.buyer() != null
                        ? new GiftRow(row.capturedAt(), row.tier(), anonymise(row.buyer()), row.anonymous(),
                                row.pack(), row.keys(), row.rawText().replace(row.buyer(), anonymise(row.buyer())),
                                row.frame(), row.listY())
                        : row;
                Map<String, Object> json = MAPPER.convertValue(logged, new TypeReference<Map<String, Object>>() { });
                json.put("backlog", firstPass);
                out.append(MAPPER.writeValueAsString(json)).append('\n');
            }
            Files.writeString(root.resolve("gifts.jsonl"), out.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        MAPPER.writeValue(last.toFile(), current);
        return new PassResult(list.size(), Math.max(fresh, 0), firstPass, aligned);
    }

    /** Raw OCR and the stitched list of a pass, kept beside its photographs. */
    public void writePassEvidence(Path passDir, Map<String, List<TextLine>> linesByFrame, List<GiftRow> list)
            throws IOException {
        Files.createDirectories(passDir);
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(passDir.resolve("lines.json").toFile(), linesByFrame);
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(passDir.resolve("list.json").toFile(), list);
    }

    /** Drops pass photographs older than {@code keepDays}; the ledger itself is kept. */
    public int prunePasses(LocalDateTime now, int keepDays) throws IOException {
        Path passes = root.resolve("passes");
        if (!Files.isDirectory(passes)) {
            return 0;
        }
        int removed = 0;
        try (var dirs = Files.list(passes)) {
            for (Path dir : dirs.toList()) {
                if (Files.getLastModifiedTime(dir).toInstant()
                        .isBefore(now.minusDays(keepDays).atZone(java.time.ZoneId.systemDefault()).toInstant())) {
                    try (var files = Files.walk(dir)) {
                        for (Path p : files.sorted(Comparator.reverseOrder()).toList()) {
                            Files.deleteIfExists(p);
                        }
                    }
                    removed++;
                }
            }
        }
        return removed;
    }
}
