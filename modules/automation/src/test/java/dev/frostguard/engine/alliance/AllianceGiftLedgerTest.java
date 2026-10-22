package dev.frostguard.engine.alliance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.frostguard.engine.alliance.AllianceGiftLedger.GiftRow;
import dev.frostguard.vision.ocr.OnnxOcrReader;
import dev.frostguard.vision.ocr.TextLine;

class AllianceGiftLedgerTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 22, 12, 0, 0);

    /**
     * The first live pass (2026-10-22 17:48). Not committed because every row names a player; the
     * real-frame tests run where prod's data folder exists and are skipped elsewhere.
     */
    private static final Path LIVE_PASS = Path.of("C:/Bearguard/data/alliance-spend/passes/2026-10-22T17-48-33");
    private static final Path OCR_MODELS = Path.of("C:/Bearguard/tools/ocr/onnx");

    private static TextLine left(String text, int top) {
        return new TextLine(text, 178, top, 300, 26, 0.95f);
    }

    private static TextLine right(String text, int top) {
        return new TextLine(text, 600, top, 60, 26, 0.95f);
    }

    /** One card as it sits on screen: tier, buyer line(s), key value and Claimed on the right. */
    private static List<TextLine> card(int top, String tier, String... body) {
        List<TextLine> lines = new ArrayList<>();
        lines.add(left(tier, top));
        lines.add(right(tier.endsWith("II") ? "60" : "30", top + 10));
        lines.add(right("Claimed", top + 48));
        for (int i = 0; i < body.length; i++) {
            lines.add(left(body[i], top + 30 * (i + 1)));
        }
        return lines;
    }

    private static List<GiftRow> rows(int scrolledBy, List<List<TextLine>> cards) {
        List<TextLine> all = new ArrayList<>();
        cards.forEach(all::addAll);
        return AllianceGiftLedger.parseFrame(all, T0, "000.png", scrolledBy);
    }

    @Test
    void readsBuyerPackTierAndKeysFromACard() {
        List<GiftRow> rows = rows(0, List.of(
                card(560, "Alliance Gift II", "Braslin has purchased \"Custom", "Armament Chest\""),
                card(712, "Alliance Gift l", "Your ally has purchased \"Daily", "Deals\"")));
        assertEquals(2, rows.size());
        assertEquals("Alliance Gift II", rows.get(0).tier());
        assertEquals("Braslin", rows.get(0).buyer());
        assertEquals("Custom Armament Chest", rows.get(0).pack());
        assertEquals(60, rows.get(0).keys());
        assertEquals("Alliance Gift I", rows.get(1).tier());
        assertTrue(rows.get(1).anonymous());
        assertNull(rows.get(1).buyer());
        assertEquals("Daily Deals", rows.get(1).pack());
    }

    @Test
    void dropsACardCutOffBeforeItsPackNameEnds() {
        List<GiftRow> rows = rows(0, List.of(card(1040, "Alliance Gift I", "Lune has purchased \"Common")));
        assertTrue(rows.isEmpty());
    }

    @Test
    void stripsTheStrayGlyphBeforeAPackNamesOpeningQuote() {
        assertEquals("Custom Armament Chest", AllianceGiftLedger.cleanPack("1\"Custom Armament Chest’"));
        assertEquals("Troops Training Pack", AllianceGiftLedger.cleanPack("H\"Troops Training Pack\""));
        assertEquals("Daily Deals", AllianceGiftLedger.cleanPack("Daily Deals"));
        assertEquals("Arcane Reverie", AllianceGiftLedger.cleanPack("\"ArcaneI Reverie\""));
        assertEquals("Rare Lucky Chip Pack", AllianceGiftLedger.cleanPack("\"Rare Lucky Chip Pack\""));
    }

    @Test
    void trustsTheKeyValueOverAMisreadTierNumeral() {
        List<TextLine> lines = new ArrayList<>(card(560, "Alliance Gift I", "Braslin has purchased \"Custom Armament Chest\""));
        lines.set(1, right("120", 570));
        GiftRow row = AllianceGiftLedger.parseFrame(lines, T0, "000.png", 0).get(0);
        assertEquals("Alliance Gift III", row.tier());
        assertEquals(120, row.keys());
    }

    @Test
    void readsTierFourAndThreeThroughOcrLookalikes() {
        assertEquals("Alliance Gift IV", AllianceGiftLedger.romanTier("lV"));
        assertEquals("Alliance Gift III", AllianceGiftLedger.romanTier("1l|"));
    }

    @Test
    void stitchesOverlappingScreensIntoOneListKeepingRepeatPurchases() {
        List<GiftRow> rows = new ArrayList<>();
        rows.addAll(rows(0, List.of(
                card(560, "Alliance Gift I", "Old man has purchased \"Daily Deals\""),
                card(712, "Alliance Gift I", "Old man has purchased \"Daily Deals\""),
                card(864, "Alliance Gift I", "Evelyn has purchased \"Epic Sign-in Pass\""))));
        rows.addAll(rows(300, List.of(
                card(564, "Alliance Gift I", "Evelyn has purchased \"Epic Sign-in Pass\""),
                card(716, "Alliance Gift II", "Braslin has purchased \"Troops Training Pack\""))));
        List<GiftRow> list = AllianceGiftLedger.stitch(rows);
        assertEquals(4, list.size());
        assertEquals("Old man", list.get(0).buyer());
        assertEquals("Old man", list.get(1).buyer());
        assertEquals("Evelyn", list.get(2).buyer());
        assertEquals("Braslin", list.get(3).buyer());
    }

    @Test
    void onlyRowsAboveThePreviousListsHeadAreNew() {
        List<String> previous = List.of("a", "b", "c", "d");
        assertEquals(2, AllianceGiftLedger.newRowCount(previous, List.of("x", "y", "a", "b", "c", "d")));
        assertEquals(0, AllianceGiftLedger.newRowCount(previous, List.of("a", "b", "c")));
        assertEquals(1, AllianceGiftLedger.newRowCount(previous, List.of("a", "a", "b", "c")));
        assertEquals(-1, AllianceGiftLedger.newRowCount(previous, List.of("x", "y", "z")));
        assertEquals(3, AllianceGiftLedger.newRowCount(List.of(), List.of("x", "y", "z")));
    }

    @Test
    void logsFirstPassAsBacklogThenOnlyNewPurchases(@TempDir Path dir) throws Exception {
        AllianceGiftLedger ledger = new AllianceGiftLedger(dir);
        List<GiftRow> first = AllianceGiftLedger.stitch(rows(0, List.of(
                card(560, "Alliance Gift I", "Old man has purchased \"Daily Deals\""),
                card(712, "Alliance Gift II", "Braslin has purchased \"Troops Training Pack\""),
                card(864, "Alliance Gift I", "Evelyn has purchased \"Epic Sign-in Pass\""))));
        AllianceGiftLedger.PassResult one = ledger.recordPass(first, false);
        assertTrue(one.firstPass());
        assertEquals(3, one.fresh());

        List<GiftRow> second = AllianceGiftLedger.stitch(rows(0, List.of(
                card(560, "Alliance Gift I", "Old man has purchased \"Daily Deals\""),
                card(712, "Alliance Gift I", "Old man has purchased \"Daily Deals\""),
                card(864, "Alliance Gift II", "Braslin has purchased \"Troops Training Pack\""),
                card(1016, "Alliance Gift I", "Evelyn has purchased \"Epic Sign-in Pass\""))));
        AllianceGiftLedger.PassResult two = ledger.recordPass(second, false);
        assertFalse(two.firstPass());
        assertEquals(1, two.fresh());
        List<String> logged = Files.readAllLines(dir.resolve("gifts.jsonl"));
        assertEquals(4, logged.size());
        assertTrue(logged.get(0).contains("\"backlog\":true"));
        assertTrue(logged.get(3).contains("\"backlog\":false"));
    }

    @Test
    void anonymisingKeepsOneBuyersPurchasesTogetherWithoutTheName(@TempDir Path dir) throws Exception {
        AllianceGiftLedger ledger = new AllianceGiftLedger(dir);
        List<GiftRow> list = AllianceGiftLedger.stitch(rows(0, List.of(
                card(560, "Alliance Gift I", "Braslin has purchased \"Daily Deals\""),
                card(712, "Alliance Gift II", "Braslin has purchased \"Troops Training Pack\""))));
        ledger.recordPass(list, true);
        String written = Files.readString(dir.resolve("gifts.jsonl"));
        assertFalse(written.contains("Braslin"));
        List<String> logged = Files.readAllLines(dir.resolve("gifts.jsonl"));
        String hash = AllianceGiftLedger.anonymise("Braslin");
        assertTrue(logged.get(0).contains(hash) && logged.get(1).contains(hash));
    }

    @Test
    void findsAKnownScrollOnARealScreen() throws Exception {
        assumeTrue(Files.exists(LIVE_PASS.resolve("000.png")), "live pass frames not on this machine");
        BufferedImage top = ImageIO.read(LIVE_PASS.resolve("000.png").toFile());
        BufferedImage scrolled = new BufferedImage(top.getWidth(), top.getHeight(), BufferedImage.TYPE_INT_RGB);
        int shift = 230;
        for (int y = 0; y < top.getHeight(); y++) {
            for (int x = 0; x < top.getWidth(); x++) {
                int from = y + shift;
                scrolled.setRGB(x, y, from < top.getHeight() ? top.getRGB(x, from) : 0);
            }
        }
        assertEquals(shift, AllianceGiftLedger.scrollShift(top, scrolled));
    }

    @Test
    void refusesToStitchRealScreensThatShareNoRows() throws Exception {
        assumeTrue(Files.exists(LIVE_PASS.resolve("001.png")), "live pass frames not on this machine");
        BufferedImage a = ImageIO.read(LIVE_PASS.resolve("000.png").toFile());
        BufferedImage b = ImageIO.read(LIVE_PASS.resolve("001.png").toFile());
        assertEquals(-1, AllianceGiftLedger.scrollShift(a, b));
    }

    @Test
    void readsTheCardsOnARealScreen() throws Exception {
        assumeTrue(Files.exists(LIVE_PASS.resolve("000.png")), "live pass frames not on this machine");
        assumeTrue(OnnxOcrReader.isAvailable(OCR_MODELS), "OCR models not on this machine");
        BufferedImage frame = ImageIO.read(LIVE_PASS.resolve("000.png").toFile());
        try (OnnxOcrReader reader = new OnnxOcrReader(OCR_MODELS)) {
            List<TextLine> lines = reader.read(frame, AllianceGiftLedger.TEXT_LEFT, AllianceGiftLedger.LIST_TOP,
                    AllianceGiftLedger.TEXT_RIGHT, AllianceGiftLedger.LIST_BOTTOM, "en", 0.60);
            List<GiftRow> rows = AllianceGiftLedger.parseFrame(lines, T0, "000.png", 0);
            lines.forEach(l -> System.out.println("OCR " + l.top() + " " + l.left() + " " + l.text()));
            rows.forEach(r -> System.out.println("ROW " + r));
            assertEquals(4, rows.size(), "expected all four cards, got " + rows);
            assertEquals("Alliance Gift II", rows.get(0).tier());
            assertTrue(rows.get(0).anonymous());
            assertEquals("dreamtravelerpack3", AllianceGiftLedger.normalise(rows.get(0).pack()));
            assertEquals(60, rows.get(0).keys());
            assertEquals("MatthewScars", rows.get(1).buyer());
            assertEquals("Classic Dreams", rows.get(1).pack());
            assertEquals("Archawk", rows.get(2).buyer());
            assertEquals("March Queue Pack", rows.get(2).pack());
            assertTrue(rows.get(3).anonymous());
            assertEquals("Common Lucky Chip Pack", rows.get(3).pack());
        }
    }
}
