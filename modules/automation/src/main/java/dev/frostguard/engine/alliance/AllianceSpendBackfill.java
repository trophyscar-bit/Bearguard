package dev.frostguard.engine.alliance;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.frostguard.vision.ocr.OnnxOcrReader;
import dev.frostguard.vision.ocr.TextLine;

/**
 * Rebuilds the purchases that saved passes saw but did not log, by replaying their photographs
 * through the current stitching and cross-pass alignment, in time order, into a scratch ledger.
 *
 * <p>Usage: {@code AllianceSpendBackfill <workspace> <after-pass> [--write]}. {@code after-pass} is
 * the folder name of the last pass whose purchases are in the ledger; its list seeds the alignment.
 * Without {@code --write} it only prints what it would add. With it, the rows are appended to
 * {@code gifts.jsonl} marked {@code "recovered": true}.</p>
 */
public final class AllianceSpendBackfill {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private AllianceSpendBackfill() {
    }

    public static void main(String[] args) throws Exception {
        Path workspace = Path.of(args[0]);
        String after = args[1];
        boolean write = args.length > 2 && "--write".equals(args[2]);
        AllianceGiftLedger live = AllianceGiftLedger.forWorkspace(workspace);
        File[] dirs = live.root().resolve("passes").toFile().listFiles(File::isDirectory);
        Arrays.sort(dirs);
        // Every card seen so far, newest first; each pass is lined up against its head.
        List<AllianceGiftLedger.GiftRow> master = new ArrayList<>();
        List<Map<String, Object>> found = new ArrayList<>();
        Map<String, List<String>> fullPasses = new LinkedHashMap<>();
        try (OnnxOcrReader reader = new OnnxOcrReader(workspace.resolve("tools").resolve("ocr").resolve("onnx"))) {
            for (File dir : dirs) {
                if (dir.getName().compareTo(after) < 0) {
                    continue;
                }
                Pass pass = replay(dir.toPath(), reader);
                if (master.isEmpty()) {
                    master.addAll(pass.list);
                    System.out.printf("seed %s: %d cards (already in the ledger)%n", dir.getName(), pass.list.size());
                    continue;
                }
                List<String> was = signatures(master.subList(0, Math.min(master.size(), HEAD)));
                List<String> now = signatures(pass.list);
                if (now.size() >= 40) {
                    fullPasses.put(dir.getName(), now);
                }
                int fresh = now.isEmpty() ? 0 : AllianceGiftLedger.newRowCount(was, now, false);
                String note;
                boolean gap = false;
                if (fresh < 0) {
                    if (overlaps(was, now)) {
                        System.out.printf("%s: %d cards, misread (shares cards with the history but does not line up), skipped%n",
                                dir.getName(), now.size());
                        continue;
                    }
                    // Too short an overlap to line up on still shows at the end: a pass's last cards
                    // that are the history's newest are not new (13:49 on 10-27 ended on two of them).
                    fresh = now.size() - sharedEdge(was, now);
                    gap = true;
                }
                note = gap ? fresh + " new after a gap (purchases between may be missing)" : fresh + " new";
                System.out.printf("%s: %d screens, %d cards, %s%n", dir.getName(), pass.screens, now.size(), note);
                for (AllianceGiftLedger.GiftRow row : pass.list.subList(0, fresh)) {
                    Map<String, Object> json = MAPPER.convertValue(row, new TypeReference<LinkedHashMap<String, Object>>() { });
                    json.put("backlog", false);
                    json.put("recovered", true);
                    if (gap) {
                        json.put("afterGap", true);
                    }
                    found.add(json);
                }
                master.addAll(0, pass.list.subList(0, fresh));
            }
        }
        StringBuilder out = new StringBuilder();
        for (Map<String, Object> json : found) {
            out.append(MAPPER.writeValueAsString(json)).append('\n');
            System.out.println("  + " + json.get("capturedAt") + " " + json.get("tier") + " | "
                    + (json.get("buyer") == null ? "(ally)" : json.get("buyer")) + " | " + json.get("pack")
                    + (json.containsKey("afterGap") ? "  [after gap]" : ""));
        }
        System.out.println(found.size() + " purchases recovered" + (write ? ", appended to gifts.jsonl" : " (dry run)"));
        // Check: each long pass should read back, card for card, from the rebuilt history.
        List<String> history = signatures(master);
        fullPasses.forEach((name, cards) -> System.out.printf("check %s: %d of its %d cards found in order in the history%n",
                name, common(cards, history), cards.size()));
        if (write && !found.isEmpty()) {
            Files.writeString(live.root().resolve("gifts.jsonl"), out.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            Map<String, Object> reference = new LinkedHashMap<>();
            reference.put("reachedEnd", false);
            reference.put("savedAt", java.time.Instant.now().toString());
            reference.put("list", signatures(master.subList(0, Math.min(master.size(), HEAD))));
            MAPPER.writeValue(live.root().resolve("last-list.json").toFile(), reference);
        }
    }

    private static int common(List<String> a, List<String> b) {
        int[][] t = new int[a.size() + 1][b.size() + 1];
        for (int i = 1; i <= a.size(); i++) {
            for (int j = 1; j <= b.size(); j++) {
                t[i][j] = AllianceGiftLedger.sameCard(a.get(i - 1), b.get(j - 1)) ? t[i - 1][j - 1] + 1
                        : Math.max(t[i - 1][j], t[i][j - 1]);
            }
        }
        return t[a.size()][b.size()];
    }

    /** The most cards at the end of {@code pass} that are, in order, the history's newest. */
    static int sharedEdge(List<String> history, List<String> pass) {
        for (int t = Math.min(history.size(), pass.size()); t > 0; t--) {
            boolean all = true;
            for (int i = 0; i < t && all; i++) {
                all = AllianceGiftLedger.sameCard(pass.get(pass.size() - t + i), history.get(i));
            }
            if (all) {
                return t;
            }
        }
        return 0;
    }

    /** How much of the history's newest end a pass is lined up against. */
    static final int HEAD = 80;

    private static List<String> signatures(List<AllianceGiftLedger.GiftRow> rows) {
        return rows.stream().map(AllianceGiftLedger.GiftRow::signature).toList();
    }

    /**
     * Whether a pass that did not line up still shares a run of three consecutive cards with the
     * history's head: then it is a misread, not a gap, and logging it would count cards twice.
     */
    static boolean overlaps(List<String> history, List<String> pass) {
        int head = Math.min(history.size(), 15);
        for (int i = 0; i + 2 < head; i++) {
            for (int j = 0; j + 2 < pass.size(); j++) {
                if (AllianceGiftLedger.sameCard(history.get(i), pass.get(j))
                        && AllianceGiftLedger.sameCard(history.get(i + 1), pass.get(j + 1))
                        && AllianceGiftLedger.sameCard(history.get(i + 2), pass.get(j + 2))) {
                    return true;
                }
            }
        }
        return false;
    }

    private record Pass(List<AllianceGiftLedger.GiftRow> list, boolean reachedEnd, int screens) {
    }

    /** One saved pass through today's stitching: screens chained until one does not line up. */
    static Pass replay(Path dir, OnnxOcrReader reader) throws IOException {
        File[] frames = dir.toFile().listFiles((d, n) -> n.endsWith(".png") && !n.startsWith("r"));
        Arrays.sort(frames);
        Map<String, List<TextLine>> saved = new LinkedHashMap<>();
        Path lines = dir.resolve("lines.json");
        if (Files.exists(lines)) {
            JsonNode node = MAPPER.readTree(lines.toFile());
            node.fields().forEachRemaining(e -> saved.put(e.getKey(),
                    MAPPER.convertValue(e.getValue(), new TypeReference<List<TextLine>>() { })));
        }
        LocalDateTime at = passTime(dir.getFileName().toString());
        List<AllianceGiftLedger.GiftRow> rows = new ArrayList<>();
        BufferedImage previous = null;
        int scrolledBy = 0;
        int screens = 0;
        for (File frame : frames) {
            BufferedImage image = ImageIO.read(frame);
            if (previous != null) {
                AllianceGiftLedger.ShiftMatch m = AllianceGiftLedger.scrollMatch(previous, image,
                        AllianceGiftLedger.EXPECTED_TRAVEL_PX);
                if (m.shift() < 0) {
                    break;
                }
                scrolledBy += m.shift();
            }
            List<TextLine> read = saved.get(frame.getName());
            if (read == null) {
                read = reader.read(image, AllianceGiftLedger.TEXT_LEFT, AllianceGiftLedger.LIST_TOP,
                        AllianceGiftLedger.TEXT_RIGHT, AllianceGiftLedger.LIST_BOTTOM, "en", 0.60);
            }
            rows.addAll(AllianceGiftLedger.parseFrame(read, at, frame.getName(), scrolledBy));
            previous = image;
            screens++;
        }
        // A saved pass cannot prove it saw the bottom (one drag was ignored at 13:49 on 10-27 and the
        // pass stopped at 7 of 50 cards; others stopped at the 40-screen cap), so none is treated so.
        return new Pass(AllianceGiftLedger.stitch(rows), false, screens);
    }

    /** "2026-10-27T13-49-39" or "2026-10-26T10-21" as a time. */
    static LocalDateTime passTime(String name) {
        String[] parts = name.split("T");
        String[] hms = parts[1].split("-");
        return LocalDateTime.parse(parts[0] + "T" + hms[0] + ":" + hms[1] + (hms.length > 2 ? ":" + hms[2] : ":00"));
    }
}
