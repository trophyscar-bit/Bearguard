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
import com.fasterxml.jackson.databind.JsonNode;
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

    /** Left edge of the item icon, where scroll matching starts. */
    static final int ICON_LEFT = 40;

    /** Lines further apart than this belong to different cards; cards are ~150px apart with a ~65px gap. */
    static final int ROW_GAP_PX = 30;

    /** Two reads of one card from different screens land within this of each other once stitched. */
    static final int SAME_ROW_PX = 45;

    /** How many consecutive rows must match before the previous pass's list is taken to start there. */
    static final int ALIGN_RUN = 3;

    /**
     * "Alliance Gift" and its numeral. The numeral is optional and must end at a word boundary:
     * when OCR drops it, "Alliance Gift Lune has purchased" must not read "L" as the numeral and
     * leave the buyer as "une". The key value then decides the tier.
     */
    private static final Pattern TIER =
            Pattern.compile("(?i)alliance\\W*gift\\b(?:\\W*([IVXl1|]{1,4})(?![\\p{L}\\p{N}]))?");
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
        String tier = tierMatch.group(1) == null ? null : romanTier(tierMatch.group(1));
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
        } else if (tier != null) {
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
        return scrollMatch(before, after, 0).shift();
    }

    /** How far the list moved ({@code -1} when unknown) and how well the best shift matched. */
    public record ShiftMatch(int shift, double score) {

        /** Nothing lines up at all: the list was redrawn (it reloads when a gift arrives), not scrolled. */
        public boolean redrawn() {
            return shift < 0 && score > REDRAWN_SHARE;
        }
    }

    /**
     * How far one 160px drag moves the list: 184 to 188px on every pass of 2026-10-26 to 10-28 that
     * lined up, so 185.
     */
    public static final int EXPECTED_TRAVEL_PX = 185;

    /** A shift this far from the expected travel is a different card; cards sit ~152px apart. */
    static final int TRAVEL_WINDOW_PX = 45;

    /** Best scores of real redraws were 0.09 to 0.19; true shifts never passed 0.05. */
    static final double REDRAWN_SHARE = 0.08;

    /**
     * Like {@link #scrollShift}, but when two shifts a card apart match about equally well (a run of
     * identical cards, icons and all), takes the one within {@link #TRAVEL_WINDOW_PX} of
     * {@code expected}, the distance a drag is known to move the list. {@code expected <= 0} means no
     * expectation.
     */
    public static ShiftMatch scrollMatch(BufferedImage before, BufferedImage after, int expected) {
        int height = LIST_BOTTOM - LIST_TOP;
        int[][] was = greys(before);
        int[][] now = greys(after);
        double[] changed = new double[height - MIN_OVERLAP_PX + 1];
        int best = 0;
        for (int dy = 0; dy < changed.length; dy++) {
            int differing = 0;
            int samples = 0;
            for (int y = 0; y + dy < height; y += 3) {
                for (int x = 0; x < now[y].length; x++) {
                    if (Math.abs(now[y][x] - was[y + dy][x]) > PIXEL_CHANGE) {
                        differing++;
                    }
                    samples++;
                }
            }
            changed[dy] = differing / (double) samples;
            if (changed[dy] < changed[best]) {
                best = dy;
            }
        }
        double runnerUp = Double.MAX_VALUE;
        for (int dy = 0; dy < changed.length; dy++) {
            if (Math.abs(dy - best) > 30) {
                runnerUp = Math.min(runnerUp, changed[dy]);
            }
        }
        boolean close = changed[best] <= MAX_CHANGED_SHARE;
        boolean unique = changed[best] <= CERTAIN_SHARE || runnerUp - changed[best] >= MIN_MARGIN;
        if (close && unique) {
            return new ShiftMatch(best, changed[best]);
        }
        if (close && expected > 0) {
            int pick = -1;
            int from = Math.max(0, expected - TRAVEL_WINDOW_PX);
            int to = Math.min(changed.length - 1, expected + TRAVEL_WINDOW_PX);
            for (int dy = from; dy <= to; dy++) {
                if (pick < 0 || changed[dy] < changed[pick]) {
                    pick = dy;
                }
            }
            if (pick >= 0 && changed[pick] <= MAX_CHANGED_SHARE && changed[pick] - changed[best] < MIN_MARGIN) {
                return new ShiftMatch(pick, changed[pick]);
            }
        }
        return new ShiftMatch(-1, changed[best]);
    }

    /** Grey levels of the list's icon and text columns, every 6th pixel across. */
    private static int[][] greys(BufferedImage image) {
        int height = LIST_BOTTOM - LIST_TOP;
        int[][] g = new int[height][];
        for (int y = 0; y < height; y++) {
            g[y] = new int[(TEXT_RIGHT - ICON_LEFT + 5) / 6];
            for (int i = 0, x = ICON_LEFT; x < TEXT_RIGHT; x += 6, i++) {
                g[y][i] = grey(image.getRGB(x, LIST_TOP + y));
            }
        }
        return g;
    }

    /**
     * How screens are lined up, measured on the saved passes of 2026-10-22 to 10-26.
     *
     * <p>The icon is compared along with the text. Runs of the same purchase ("Jong GyU has
     * purchased Daily Deals" three times) read alike, so text alone matched one card off as well
     * as the true shift; the reward icons under them differ. An unclaimed gift's live countdown
     * on its icon is a few glyphs, which the score below shrugs off: it is the share of samples
     * that changed clearly, not the mean difference. True shifts scored 0.000 to 0.046; cards
     * repeat every ~152px, so a wrong shift can score nearly as well, and a best that does not
     * beat every other shift by {@link #MIN_MARGIN} is refused rather than guessed.</p>
     */
    static final int PIXEL_CHANGE = 24;
    static final double MAX_CHANGED_SHARE = 0.05;
    static final double CERTAIN_SHARE = 0.003;
    static final double MIN_MARGIN = 0.015;

    /** Shifts that leave less than one card of overlap cannot be told apart from a wrong one. */
    static final int MIN_OVERLAP_PX = 150;

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
                // Same card seen on two screens. Position is measured, so it is the same purchase;
                // if the two reads disagree, keep the one that read more of the card.
                if (completeness(row) > completeness(last)) {
                    list.set(list.size() - 1, row);
                }
                continue;
            }
            list.add(row);
        }
        return list;
    }

    private static int completeness(GiftRow row) {
        return (row.tier() != null ? 2 : 0) + (row.keys() != null ? 1 : 0) + (row.anonymous() || row.buyer() != null ? 1 : 0)
                - (int) row.pack().chars().filter(c -> "!|`".indexOf(c) >= 0).count();
    }

    /**
     * How many rows at the top of {@code current} arrived since {@code previous} was read.
     *
     * <p>The previous list's first rows are looked for inside the current one; everything above
     * where they start is new. -1 when they cannot be found, which means more was bought than one
     * pass can see, or the list was misread -- either way the caller must not guess.</p>
     */
    static int newRowCount(List<String> previous, List<String> current) {
        return newRowCount(previous, current, false);
    }

    /**
     * The list is newest first: new purchases appear at the top and expired ones leave the bottom.
     * So the current list is {@code new + previous[0..m)}. The whole overlap is compared, not a
     * short run, because identical purchases repeat: with a three-row run, previous [a,a,a,b] and
     * current [a,a,a,a,b] lined up at 0 and lost the new "a".
     *
     * @param previousReachedEnd whether the previous pass saw the bottom of the list; if it did,
     *        everything below the overlap must have expired, so the current list may not run past
     *        it, which is what tells [a] then [a,a] apart from an unchanged list
     */
    static int newRowCount(List<String> previous, List<String> current, boolean previousReachedEnd) {
        if (previous.isEmpty()) {
            return current.size();
        }
        boolean bounded = previousReachedEnd;
        for (int tolerance = 0; tolerance <= 1; tolerance++) {
            for (int k = 0; k < current.size(); k++) {
                int remaining = current.size() - k;
                if (bounded && remaining > previous.size()) {
                    continue;
                }
                int overlap = Math.min(remaining, previous.size());
                if (overlap < Math.min(ALIGN_RUN, Math.min(previous.size(), current.size()))) {
                    break;
                }
                // A second, tolerant try allows one misread card per eight compared, but never on
                // a short overlap, where one mismatch is all that separates two alignments.
                int allowed = tolerance == 0 ? 0 : overlap / 8;
                if (tolerance == 1 && allowed == 0) {
                    continue;
                }
                int mismatches = 0;
                for (int i = 0; i < overlap && mismatches <= allowed; i++) {
                    if (!sameCard(current.get(k + i), previous.get(i))) {
                        mismatches++;
                    }
                }
                if (mismatches <= allowed) {
                    return k;
                }
            }
        }
        return looseRowCount(previous, current);
    }

    /**
     * The fallback when cards do not line up one for one: a pass drops or adds a card now and then
     * (the 2026-10-26 11:22 pass read one "Mia's Keenness" more than the 12:27 one), which shifts
     * every later row. Takes the first k at which the current list's remainder is, but for one card
     * in eight, a common subsequence of the previous list's head, starting at its first or second
     * card. Only reached after the one-for-one match fails, so identical repeats still count.
     */
    static int looseRowCount(List<String> previous, List<String> current) {
        for (int k = 0; k < current.size(); k++) {
            int remaining = current.size() - k;
            if (remaining < LOOSE_MIN_OVERLAP || previous.size() < LOOSE_MIN_OVERLAP) {
                break;
            }
            if (!sameCard(current.get(k), previous.get(0))
                    && !(previous.size() > 1 && sameCard(current.get(k), previous.get(1)))) {
                continue;
            }
            int slack = 1 + remaining / 8;
            List<String> tail = current.subList(k, current.size());
            List<String> head = previous.subList(0, Math.min(previous.size(), remaining + slack));
            if (commonRun(tail, head) >= Math.min(tail.size(), head.size()) - slack) {
                return k;
            }
        }
        return -1;
    }

    /** Below this many cards a loose match is too easy to make by chance. */
    static final int LOOSE_MIN_OVERLAP = 6;

    /** Length of the longest common subsequence of two card lists. */
    private static int commonRun(List<String> a, List<String> b) {
        int[][] t = new int[a.size() + 1][b.size() + 1];
        for (int i = 1; i <= a.size(); i++) {
            for (int j = 1; j <= b.size(); j++) {
                t[i][j] = sameCard(a.get(i - 1), b.get(j - 1)) ? t[i - 1][j - 1] + 1 : Math.max(t[i - 1][j], t[i][j - 1]);
            }
        }
        return t[a.size()][b.size()];
    }

    static String normalise(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", "");
    }

    /**
     * Whether two signatures ({@code tier|buyer|pack}) are one card read twice. OCR reads the same
     * card differently from pass to pass ("fishina"/"fishing", "oldman1"/"oldmanl", "wvishful"/
     * "wishful"), so names match on their letters with lookalikes folded and a small edit allowance;
     * tiers must agree and digits must match exactly ("pack2" is never "pack3").
     */
    static boolean sameCard(String a, String b) {
        if (a.equals(b)) {
            return true;
        }
        String[] x = a.split("\\|", -1);
        String[] y = b.split("\\|", -1);
        if (x.length != 3 || y.length != 3 || !x[0].equals(y[0])) {
            return false;
        }
        return sameName(x[1], y[1]) && sameName(x[2], y[2]);
    }

    /** Two reads of one name: the same digits, and letters within one edit per eight once lookalikes fold. */
    public static boolean sameName(String a, String b) {
        String p = normalise(a);
        String q = normalise(b);
        if (p.equals(q)) {
            return true;
        }
        // 1, l and I are one glyph to OCR ("oldman1" / "oldmanl"), so they fold before digits compare.
        String fp = foldLookalikes(p);
        String fq = foldLookalikes(q);
        if (p.isEmpty() || q.isEmpty() || !fp.replaceAll("\\D", "").equals(fq.replaceAll("\\D", ""))) {
            return false;
        }
        fp = fp.replaceAll("\\d", "");
        fq = fq.replaceAll("\\d", "");
        return AllianceSpendReport.edits(fp, fq) <= 1 + Math.min(fp.length(), fq.length()) / 8;
    }

    private static String foldLookalikes(String s) {
        return s.replace('1', 'i').replace('l', 'i').replace("vv", "w").replace("rn", "m");
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
    public record PassResult(int listed, int fresh, boolean firstPass, boolean aligned, boolean afterGap) {
    }

    /**
     * A reference at least this old, none of whose cards are still listed, has scrolled off the
     * bottom of the gift list: everything listed now came after it. The list keeps more than a day
     * (50 cards on 2026-10-28), so age alone proves nothing; the cards must be gone too.
     */
    static final long STALE_REFERENCE_HOURS = 6;

    /**
     * Appends the purchases that arrived since the last pass.
     *
     * <p>The first pass logs the whole list, marked as a backlog since those purchases happened
     * before logging began. If the previous list cannot be found in this one, nothing is logged
     * and the list is kept as the new reference, so one bad read costs one pass rather than
     * double-counting a whole list. The exception is a stale reference: at least
     * {@link #STALE_REFERENCE_HOURS} old with not one of its cards still listed. Its purchases have
     * left the list, so every row now listed is newer and is logged, marked {@code afterGap}:
     * purchases may be missing in between.</p>
     *
     * @param list the stitched list, newest first, and reaching its end
     */
    public PassResult recordPass(List<GiftRow> list, boolean anonymiseBuyers) throws IOException {
        return recordPass(list, anonymiseBuyers, true, java.time.Instant.now());
    }

    public PassResult recordPass(List<GiftRow> list, boolean anonymiseBuyers, boolean reachedEnd) throws IOException {
        return recordPass(list, anonymiseBuyers, reachedEnd, java.time.Instant.now());
    }

    /**
     * @param reachedEnd whether this pass saw the bottom of the list; stored with the reference so
     *        the next pass knows how to line up against it
     */
    public PassResult recordPass(List<GiftRow> list, boolean anonymiseBuyers, boolean reachedEnd,
                                 java.time.Instant now) throws IOException {
        Files.createDirectories(root);
        Path last = root.resolve("last-list.json");
        boolean firstPass = !Files.exists(last);
        List<String> previous = List.of();
        boolean previousReachedEnd = false;
        boolean referenceReadable = true;
        java.time.Instant savedAt = null;
        if (!firstPass) {
            try {
                JsonNode ref = MAPPER.readTree(last.toFile());
                JsonNode items = ref.isArray() ? ref : ref.path("list");
                previous = MAPPER.convertValue(items, new TypeReference<List<String>>() { });
                previousReachedEnd = !ref.isArray() && ref.path("reachedEnd").asBoolean(false);
                savedAt = ref.hasNonNull("savedAt") ? java.time.Instant.parse(ref.path("savedAt").asText())
                        : Files.getLastModifiedTime(last).toInstant();
            } catch (IOException | IllegalArgumentException | java.time.DateTimeException unreadable) {
                referenceReadable = false;
            }
        }
        List<String> current = list.stream().map(GiftRow::signature).toList();
        int fresh = referenceReadable ? newRowCount(previous, current, previousReachedEnd) : -1;
        boolean aligned = fresh >= 0;
        boolean afterGap = !aligned && referenceReadable && reachedEnd && stale(savedAt, now, previous, current);
        if (afterGap) {
            fresh = current.size();
        }
        if (fresh > 0) {
            StringBuilder out = new StringBuilder();
            for (GiftRow row : list.subList(0, fresh)) {
                GiftRow logged = anonymiseBuyers && row.buyer() != null
                        ? new GiftRow(row.capturedAt(), row.tier(), anonymise(row.buyer()), row.anonymous(),
                                row.pack(), row.keys(), row.rawText().replace(row.buyer(), anonymise(row.buyer())),
                                row.frame(), row.listY())
                        : row;
                Map<String, Object> json = MAPPER.convertValue(logged, new TypeReference<Map<String, Object>>() { });
                json.put("backlog", firstPass);
                if (afterGap) {
                    json.put("afterGap", true);
                }
                out.append(MAPPER.writeValueAsString(json)).append('\n');
            }
            Files.writeString(root.resolve("gifts.jsonl"), out.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        if (!aligned && !afterGap && !reachedEnd && referenceReadable && !firstPass) {
            // A partial list that did not line up says nothing about where it sits; keep the old
            // reference so the next pass can still line up against it.
            return new PassResult(list.size(), 0, false, false, false);
        }
        Map<String, Object> reference = new java.util.LinkedHashMap<>();
        reference.put("reachedEnd", reachedEnd);
        reference.put("savedAt", now.toString());
        reference.put("list", current);
        Path temp = root.resolve("last-list.json.tmp");
        MAPPER.writeValue(temp.toFile(), reference);
        Files.move(temp, last, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        return new PassResult(list.size(), Math.max(fresh, 0), firstPass, aligned, afterGap);
    }

    static boolean stale(java.time.Instant savedAt, java.time.Instant now, List<String> previous, List<String> current) {
        if (savedAt == null) {
            return false;
        }
        if (savedAt.plus(java.time.Duration.ofHours(STALE_REFERENCE_HOURS)).isAfter(now)) {
            return false;
        }
        for (String was : previous) {
            for (String is : current) {
                if (sameCard(was, is)) {
                    return false;
                }
            }
        }
        return true;
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
