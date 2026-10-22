package dev.frostguard.engine.alliance;

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
import java.util.LinkedHashMap;
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
 * Files under {@code <workspace>/data/alliance-spend}: every Alliance Gift the chest pass saw,
 * one JSON line each, plus the photographs and raw OCR behind each pass.
 *
 * <p>A gift is seen again on every pass until it expires, and twice or three times inside one
 * pass where scroll steps overlap. It is logged once: the key is gift + sender + the moment it
 * expires, which the row's countdown pins to within a few minutes and which does not change
 * between passes the way the countdown itself does.</p>
 */
public final class AllianceGiftLedger {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** Lines further apart than this start a new gift row. */
    static final int ROW_GAP_PX = 34;

    /** Two reads of one countdown, a pass apart, land within this of each other. */
    private static final long EXPIRY_BUCKET_MINUTES = 10;

    /** Keys older than this are forgotten; no gift lives that long. */
    private static final long SEEN_RETENTION_DAYS = 8;

    private static final Pattern SENDER = Pattern.compile("(?i)\\bfrom\\b\\s*[:：]?\\s*(.+)");
    private static final Pattern HMS = Pattern.compile("(\\d{1,2}):(\\d{2}):(\\d{2})");
    private static final Pattern HOURS_MINUTES = Pattern.compile("(?i)(\\d{1,2})\\s*h\\s*(\\d{1,2})\\s*m");
    private static final Pattern BUTTON = Pattern.compile("(?i)^(claim(ed)?( all)?|collect(ed)?|open)$");

    public record GiftRow(String capturedAt, String gift, String sender, boolean anonymous,
                          String expiresAt, String rawText, String frame, int top) {
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
     * Splits one screen's OCR into gift rows, top to bottom.
     *
     * <p>Lines are grouped by vertical gap rather than a fixed row pitch, because the pitch has not
     * been measured and a wrong pitch would merge or split every row on the screen. Rows with no
     * readable gift name are still returned, with the raw text, so a misread shows up in the log
     * instead of disappearing.</p>
     */
    public static List<GiftRow> parseFrame(List<TextLine> lines, LocalDateTime capturedAt, String frame) {
        List<TextLine> sorted = new ArrayList<>(lines);
        sorted.sort(Comparator.comparingInt(TextLine::top).thenComparingInt(TextLine::left));
        List<List<TextLine>> blocks = new ArrayList<>();
        int lastBottom = Integer.MIN_VALUE;
        for (TextLine line : sorted) {
            if (line.text() == null || line.text().isBlank()) {
                continue;
            }
            if (blocks.isEmpty() || line.top() - lastBottom > ROW_GAP_PX) {
                blocks.add(new ArrayList<>());
            }
            blocks.get(blocks.size() - 1).add(line);
            lastBottom = Math.max(lastBottom, line.bottom());
        }
        List<GiftRow> rows = new ArrayList<>();
        for (List<TextLine> block : blocks) {
            rows.add(parseBlock(block, capturedAt, frame));
        }
        return rows;
    }

    static GiftRow parseBlock(List<TextLine> block, LocalDateTime capturedAt, String frame) {
        String sender = null;
        String gift = null;
        LocalDateTime expires = null;
        StringBuilder raw = new StringBuilder();
        for (TextLine line : block) {
            String text = line.text().strip();
            if (raw.length() > 0) {
                raw.append(" | ");
            }
            raw.append(text);
            Matcher from = SENDER.matcher(text);
            if (sender == null && from.find()) {
                sender = from.group(1).strip();
                continue;
            }
            LocalDateTime countdown = expiryFrom(text, capturedAt);
            if (countdown != null) {
                expires = expires == null ? countdown : expires;
                continue;
            }
            if (BUTTON.matcher(text).matches()) {
                continue;
            }
            if (gift == null) {
                gift = text;
            }
        }
        boolean anonymous = sender != null && sender.toLowerCase(Locale.ROOT).contains("anonym");
        return new GiftRow(capturedAt.truncatedTo(ChronoUnit.SECONDS).toString(), gift, sender, anonymous,
                expires == null ? null : expires.truncatedTo(ChronoUnit.MINUTES).toString(),
                raw.toString(), frame, block.get(0).top());
    }

    static LocalDateTime expiryFrom(String text, LocalDateTime capturedAt) {
        Matcher hms = HMS.matcher(text);
        if (hms.find()) {
            return capturedAt.plusHours(Integer.parseInt(hms.group(1)))
                    .plusMinutes(Integer.parseInt(hms.group(2)))
                    .plusSeconds(Integer.parseInt(hms.group(3)));
        }
        Matcher hm = HOURS_MINUTES.matcher(text);
        if (hm.find()) {
            return capturedAt.plusHours(Integer.parseInt(hm.group(1)))
                    .plusMinutes(Integer.parseInt(hm.group(2)));
        }
        return null;
    }

    /** The identity of a gift across overlapping screens and across passes. */
    static String keyOf(GiftRow row) {
        String gift = normalise(row.gift());
        String sender = normalise(row.sender());
        if (row.expiresAt() != null) {
            LocalDateTime at = LocalDateTime.parse(row.expiresAt());
            long bucket = at.toEpochSecond(java.time.ZoneOffset.UTC) / 60 / EXPIRY_BUCKET_MINUTES;
            return gift + "|" + sender + "|" + bucket;
        }
        return gift + "|" + sender + "|raw:" + normalise(row.rawText());
    }

    private static String normalise(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", "");
    }

    /** Stable stand-in for a name, so one buyer's gifts still group together. */
    public static String anonymise(String sender) {
        if (sender == null || sender.toLowerCase(Locale.ROOT).contains("anonym")) {
            return sender;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(sender.strip().getBytes(StandardCharsets.UTF_8));
            return "anon-" + HexFormat.of().formatHex(digest).substring(0, 10);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Appends the rows not logged before and returns them.
     *
     * <p>Duplicates inside {@code rows} (scroll overlap) and against earlier passes are dropped.</p>
     */
    public List<GiftRow> recordNew(List<GiftRow> rows, boolean anonymiseSenders, LocalDateTime now)
            throws IOException {
        Files.createDirectories(root);
        Map<String, String> seen = loadSeen();
        List<GiftRow> fresh = new ArrayList<>();
        for (GiftRow row : rows) {
            String key = keyOf(row);
            if (seen.containsKey(key)) {
                continue;
            }
            seen.put(key, now.toString());
            fresh.add(anonymiseSenders && row.sender() != null
                    ? new GiftRow(row.capturedAt(), row.gift(), anonymise(row.sender()), row.anonymous(),
                            row.expiresAt(), row.rawText().replace(row.sender(), anonymise(row.sender())),
                            row.frame(), row.top())
                    : row);
        }
        if (!fresh.isEmpty()) {
            StringBuilder out = new StringBuilder();
            for (GiftRow row : fresh) {
                out.append(MAPPER.writeValueAsString(row)).append('\n');
            }
            Files.writeString(root.resolve("gifts.jsonl"), out.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        LocalDateTime cutoff = now.minusDays(SEEN_RETENTION_DAYS);
        seen.values().removeIf(firstSeen -> LocalDateTime.parse(firstSeen).isBefore(cutoff));
        MAPPER.writeValue(root.resolve("seen.json").toFile(), seen);
        return fresh;
    }

    private Map<String, String> loadSeen() throws IOException {
        Path file = root.resolve("seen.json");
        if (!Files.exists(file)) {
            return new LinkedHashMap<>();
        }
        return MAPPER.readValue(file.toFile(), new TypeReference<LinkedHashMap<String, String>>() { });
    }

    /** Raw OCR of every screen in a pass, kept beside the photographs for calibration. */
    public void writePassLines(Path passDir, Map<String, List<TextLine>> linesByFrame) throws IOException {
        Files.createDirectories(passDir);
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(passDir.resolve("lines.json").toFile(), linesByFrame);
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
