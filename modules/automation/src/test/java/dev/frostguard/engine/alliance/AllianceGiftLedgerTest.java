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
    private static final Path REPEATS_PASS = Path.of("C:/Bearguard/data/alliance-spend/passes/2026-10-26T17-56-10");
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
                card(864, "Alliance Gift I", "Sigrun has purchased \"Epic Sign-in Pass\""))));
        rows.addAll(rows(300, List.of(
                card(564, "Alliance Gift I", "Sigrun has purchased \"Epic Sign-in Pass\""),
                card(716, "Alliance Gift II", "Braslin has purchased \"Troops Training Pack\""))));
        List<GiftRow> list = AllianceGiftLedger.stitch(rows);
        assertEquals(4, list.size());
        assertEquals("Old man", list.get(0).buyer());
        assertEquals("Old man", list.get(1).buyer());
        assertEquals("Sigrun", list.get(2).buyer());
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
    void countsAnIdenticalRepeatPurchaseAboveTheOldList() {
        // The review's case: three anonymous Daily Deals then one more; a three-row match lost it.
        assertEquals(1, AllianceGiftLedger.newRowCount(List.of("a", "a", "a", "b"), List.of("a", "a", "a", "a", "b"), true));
        assertEquals(1, AllianceGiftLedger.newRowCount(List.of("a", "a", "a", "b"), List.of("a", "a", "a", "a", "b"), false));
        assertEquals(1, AllianceGiftLedger.newRowCount(List.of("a"), List.of("a", "a"), true));
        assertEquals(0, AllianceGiftLedger.newRowCount(List.of("a", "b"), List.of("a"), true));
    }

    @Test
    void toleratesOneMisreadCardOnlyOnALongOverlap() {
        List<String> previous = List.of("a", "b", "c", "d", "e", "f", "g", "h");
        assertEquals(1, AllianceGiftLedger.newRowCount(previous, List.of("n", "a", "b", "c", "X", "e", "f", "g", "h"), true));
        assertEquals(-1, AllianceGiftLedger.newRowCount(List.of("a", "b", "c"), List.of("n", "a", "X", "c"), true));
    }

    @Test
    void aMissingTierNumeralDoesNotEatTheBuyersName() {
        List<TextLine> lines = new ArrayList<>(List.of(
                left("Alliance Gift", 560), right("30", 570), left("Lune has purchased \"Common Lucky Chip Pack\"", 590)));
        GiftRow row = AllianceGiftLedger.parseFrame(lines, T0, "000.png", 0).get(0);
        assertEquals("Lune", row.buyer());
        assertEquals("Alliance Gift I", row.tier());
    }

    @Test
    void anUnreadableReferenceLogsNothingAndIsReplaced(@TempDir Path dir) throws Exception {
        AllianceGiftLedger ledger = new AllianceGiftLedger(dir);
        Files.writeString(dir.resolve("last-list.json"), "{\"reachedEnd\": tr");
        List<GiftRow> list = AllianceGiftLedger.stitch(rows(0, List.of(
                card(560, "Alliance Gift I", "Old man has purchased \"Daily Deals\""))));
        AllianceGiftLedger.PassResult result = ledger.recordPass(list, false, true);
        assertFalse(result.aligned());
        assertFalse(Files.exists(dir.resolve("gifts.jsonl")));
        assertTrue(Files.readString(dir.resolve("last-list.json")).contains("\"reachedEnd\":true"));
    }

    @Test
    void logsFirstPassAsBacklogThenOnlyNewPurchases(@TempDir Path dir) throws Exception {
        AllianceGiftLedger ledger = new AllianceGiftLedger(dir);
        List<GiftRow> first = AllianceGiftLedger.stitch(rows(0, List.of(
                card(560, "Alliance Gift I", "Old man has purchased \"Daily Deals\""),
                card(712, "Alliance Gift II", "Braslin has purchased \"Troops Training Pack\""),
                card(864, "Alliance Gift I", "Sigrun has purchased \"Epic Sign-in Pass\""))));
        AllianceGiftLedger.PassResult one = ledger.recordPass(first, false);
        assertTrue(one.firstPass());
        assertEquals(3, one.fresh());

        List<GiftRow> second = AllianceGiftLedger.stitch(rows(0, List.of(
                card(560, "Alliance Gift I", "Old man has purchased \"Daily Deals\""),
                card(712, "Alliance Gift I", "Old man has purchased \"Daily Deals\""),
                card(864, "Alliance Gift II", "Braslin has purchased \"Troops Training Pack\""),
                card(1016, "Alliance Gift I", "Sigrun has purchased \"Epic Sign-in Pass\""))));
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
    void linesUpARunOfIdenticalPurchasesByTheirIcons() throws Exception {
        // Three "Jong GyU has purchased Daily Deals" cards in a row: text alone could not tell 186 from 34 or 338.
        assumeTrue(Files.exists(REPEATS_PASS.resolve("007-unaligned.png")), "live pass frames not on this machine");
        BufferedImage a = ImageIO.read(REPEATS_PASS.resolve("006.png").toFile());
        BufferedImage b = ImageIO.read(REPEATS_PASS.resolve("007-unaligned.png").toFile());
        assertEquals(186, AllianceGiftLedger.scrollShift(a, b));
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
            assertEquals("ChiefExample", rows.get(1).buyer());
            assertEquals("Classic Dreams", rows.get(1).pack());
            assertEquals("Archawk", rows.get(2).buyer());
            assertEquals("March Queue Pack", rows.get(2).pack());
            assertTrue(rows.get(3).anonymous());
            assertEquals("Common Lucky Chip Pack", rows.get(3).pack());
        }
    }

    @Test
    void aReferenceThatHasLeftTheListLogsTheWholeListAfterAGap(@TempDir Path dir) throws Exception {
        AllianceGiftLedger ledger = new AllianceGiftLedger(dir);
        List<GiftRow> yesterday = AllianceGiftLedger.stitch(rows(0, List.of(
                card(560, "Alliance Gift I", "Lune has purchased \"Daily Deals\""),
                card(712, "Alliance Gift II", "Braslin has purchased \"Path of Honor\""))));
        java.time.Instant then = java.time.Instant.parse("2026-10-26T15:23:00Z");
        ledger.recordPass(yesterday, false, true, then);
        List<GiftRow> today = AllianceGiftLedger.stitch(rows(0, List.of(
                card(560, "Alliance Gift II", "Your ally has purchased \"Scarlet Count\""),
                card(712, "Alliance Gift I", "Your ally has purchased \"Ghoul Baron\""))));
        AllianceGiftLedger.PassResult result = ledger.recordPass(today, false, true, then.plus(java.time.Duration.ofHours(26)));
        assertTrue(result.afterGap());
        assertEquals(2, result.fresh());
        assertTrue(Files.readString(dir.resolve("gifts.jsonl")).contains("\"afterGap\":true"));
    }

    @Test
    void aFreshMisalignedReferenceStillLogsNothing() {
        java.time.Instant then = java.time.Instant.parse("2026-10-27T15:00:00Z");
        // Same UTC day and under 24h: a misread, not a gone list.
        assertFalse(AllianceGiftLedger.stale(then, then.plusSeconds(3600), List.of("a"), List.of("b")));
        assertTrue(AllianceGiftLedger.stale(then, then.plusSeconds(10 * 3600), List.of("a"), List.of("b")),
                "crossed 00:00 UTC and nothing of the old list remains");
        assertFalse(AllianceGiftLedger.stale(then, then.plusSeconds(10 * 3600), List.of("a"), List.of("a", "b")));
    }

    @Test
    void identicalUnclaimedCardsLineUpByTheDragsKnownTravel() throws Exception {
        // Three "Daily Deals" with the same icon and countdown: a card's shift and two cards' score alike.
        Path pass = Path.of("C:/Bearguard/data/alliance-spend/passes/2026-10-28T02-05-49");
        assumeTrue(Files.exists(pass.resolve("018-unaligned.png")), "live pass frames not on this machine");
        BufferedImage a = ImageIO.read(pass.resolve("017.png").toFile());
        BufferedImage b = ImageIO.read(pass.resolve("018-unaligned.png").toFile());
        assertEquals(-1, AllianceGiftLedger.scrollShift(a, b));
        int shift = AllianceGiftLedger.scrollMatch(a, b, AllianceGiftLedger.EXPECTED_TRAVEL_PX).shift();
        assertTrue(Math.abs(shift - 185) <= 5, "shift " + shift);
    }

    @Test
    void aRedrawnListIsToldApartFromAnAmbiguousScroll() throws Exception {
        Path pass = Path.of("C:/Bearguard/data/alliance-spend/passes/2026-10-27T20-22-46");
        assumeTrue(Files.exists(pass.resolve("006-unaligned.png")), "live pass frames not on this machine");
        AllianceGiftLedger.ShiftMatch m = AllianceGiftLedger.scrollMatch(ImageIO.read(pass.resolve("005.png").toFile()),
                ImageIO.read(pass.resolve("006-unaligned.png").toFile()), AllianceGiftLedger.EXPECTED_TRAVEL_PX);
        assertTrue(m.redrawn(), m.toString());
    }

    @Test
    void oneCardReadTwoWaysIsOneCardButNeighbouringPacksAreNot() {
        assertTrue(AllianceGiftLedger.sameCard("alliancegifti||fishinaproset", "alliancegifti||fishingproset"));
        assertTrue(AllianceGiftLedger.sameCard("alliancegifti|oldman1|epicsigninpass", "alliancegifti|oldmanl|epicsigninpass"));
        assertFalse(AllianceGiftLedger.sameCard("alliancegifti||wishfulpack1", "alliancegifti||wishfulpack2"));
        assertTrue(AllianceGiftLedger.sameCard("alliancegifti|ata|wvishfulpack1", "alliancegifti|ata|wishfulpack1"));
        assertFalse(AllianceGiftLedger.sameCard("alliancegifti||nightofwonderspack2", "alliancegifti||nightofwonderspack3"));
        assertFalse(AllianceGiftLedger.sameCard("alliancegiftii||fishingproset", "alliancegifti||fishingproset"));
        assertFalse(AllianceGiftLedger.sameCard("alliancegifti||fishingpro", "alliancegifti||fishingproset"));
    }

    @Test
    void linesUpThePreviousListDespiteMisreadNames() {
        List<String> previous = List.of("alliancegifti|ata|wvishfulpack1", "alliancegifti|oldman|ridersofthetundra",
                "alliancegiftii||nightofwonderspack3", "alliancegifti||fishinaproset", "alliancegifti|oldman1|epicsigninpass",
                "alliancegifti|brimdalslucifer|marchqueuepack");
        List<String> current = List.of("alliancegiftiii||nightofwonderspack4", "alliancegifti||wvishfulpack1",
                "alliancegiftii|ata|wvishfulpack2", "alliancegifti|ata|wishfulpack1", "alliancegifti|oldman|ridersofthetundra",
                "alliancegiftii||nightofwonderspack3", "alliancegifti||fishingproset", "alliancegifti|oldmanl|epicsigninpass",
                "alliancegifti|brimdalslucifer|marchqueuepack");
        assertEquals(3, AllianceGiftLedger.newRowCount(previous, current, true));
    }

    /** A list screen showing {@code tall} from {@code from} down in the list area; the rest is blank. */
    private static BufferedImage window(BufferedImage tall, int from) {
        BufferedImage screen = new BufferedImage(720, 1280, BufferedImage.TYPE_INT_RGB);
        for (int y = AllianceGiftLedger.LIST_TOP; y < AllianceGiftLedger.LIST_BOTTOM; y++) {
            for (int x = 0; x < 720; x++) {
                screen.setRGB(x, y, tall.getRGB(x, from + y - AllianceGiftLedger.LIST_TOP));
            }
        }
        return screen;
    }

    @Test
    void aScreenBackAtTheTopIsARedrawNotAMisread() {
        java.util.Random random = new java.util.Random(7);
        BufferedImage tall = new BufferedImage(720, 3000, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < tall.getHeight(); y++) {
            for (int x = 0; x < 720; x++) {
                int g = random.nextInt(256);
                tall.setRGB(x, y, g << 16 | g << 8 | g);
            }
        }
        BufferedImage top = window(tall, 0);
        assertTrue(AllianceGiftLedger.backAtTop(top, window(tall, 0)), "the first screen again");
        assertTrue(AllianceGiftLedger.backAtTop(top, window(tall, 186)), "a drag below the top");
        assertTrue(AllianceGiftLedger.backAtTop(window(tall, 300), window(tall, 148)), "a new gift pushed the old top down");
        assertFalse(AllianceGiftLedger.backAtTop(top, window(tall, 1500)), "deep in the list");
    }

    private static List<GiftRow> purchases(String... buyers) {
        List<GiftRow> list = new ArrayList<>();
        for (int i = 0; i < buyers.length; i++) {
            list.add(new GiftRow(T0.toString(), "Alliance Gift I", buyers[i], false, "Daily Deals", 30,
                    buyers[i] + " has purchased \"Daily Deals\"", "000.png", 560 + 152 * i));
        }
        return list;
    }

    @Test
    void aPassCutShortLogsItsNewRowsOnlyWhenEnoughOfItReachesTheLastList(@TempDir Path dir) throws Exception {
        AllianceGiftLedger ledger = new AllianceGiftLedger(dir);
        java.time.Instant then = java.time.Instant.parse("2026-11-10T22:00:00Z");
        ledger.recordPass(purchases("Ann", "Bob", "Cy", "Dee", "Eve", "Fay", "Gus", "Hal", "Ivy", "Jo"), false, true, then);
        assertEquals(10, Files.readAllLines(dir.resolve("gifts.jsonl")).size());

        // The list redrew after four of the old cards: too little to line up on, so nothing is
        // logged and the old reference stays.
        AllianceGiftLedger.PassResult cutShort = ledger.recordPass(purchases("Kai", "Lu", "Ann", "Bob", "Cy", "Dee"),
                false, false, then.plusSeconds(3600));
        assertFalse(cutShort.aligned());
        assertEquals(10, Files.readAllLines(dir.resolve("gifts.jsonl")).size());
        assertTrue(Files.readString(dir.resolve("last-list.json")).contains("\"reachedEnd\":true"));

        // Six old cards read before the redraw: the two above them are new.
        AllianceGiftLedger.PassResult enough = ledger.recordPass(
                purchases("Kai", "Lu", "Ann", "Bob", "Cy", "Dee", "Eve", "Fay"), false, false, then.plusSeconds(3700));
        assertTrue(enough.aligned());
        assertEquals(2, enough.fresh());
        assertEquals(12, Files.readAllLines(dir.resolve("gifts.jsonl")).size());
    }

    /** Distinct buyers: digits decide, so no two of them are one card read twice (and no 0 or 1, which fold). */
    private static String[] buyers(String letters, int from, int count) {
        String[] out = new String[count];
        int n = from;
        for (int i = 0; i < count; i++) {
            while (String.valueOf(n).matches(".*[01].*")) {
                n++;
            }
            out[i] = letters + n++;
        }
        return out;
    }

    private static String[] concat(String[]... parts) {
        return java.util.Arrays.stream(parts).flatMap(java.util.Arrays::stream).toArray(String[]::new);
    }

    @Test
    void aPassCutShortLeavesTheOldListBelowItsNewRowsAsTheReference(@TempDir Path dir) throws Exception {
        // 2026-11-11 23:41: a pass cut short read its top right but stitched its lower half wrong
        // (five cards dropped, others repeated). Its top lined up, so its 2 new rows were logged; kept
        // whole as the reference, it left the 00:46 pass unable to line up, and 4 purchases went unlogged.
        AllianceGiftLedger ledger = new AllianceGiftLedger(dir);
        java.time.Instant then = java.time.Instant.parse("2026-11-12T03:35:00Z");
        String[] old = buyers("b", 222, 40);
        ledger.recordPass(purchases(old), false, true, then);

        String[] firstNew = buyers("b", 2222, 2);
        String[] junk = buyers("b", 3222, 5);
        String[] cutShort = concat(firstNew, java.util.Arrays.copyOfRange(old, 0, 21),
                java.util.Arrays.copyOfRange(old, 26, 32), junk);
        AllianceGiftLedger.PassResult partial = ledger.recordPass(purchases(cutShort), false, false, then.plusSeconds(3960));
        assertTrue(partial.aligned());
        assertEquals(2, partial.fresh());

        // The next whole pass: four more on top, everything else as before but two cards misread.
        String[] next = concat(buyers("b", 4222, 4), firstNew, old);
        next[4 + 2 + 5] = "Zyxwv" + old[5].substring(1);
        next[4 + 2 + 12] = "Zyxwv" + old[12].substring(1);
        AllianceGiftLedger.PassResult whole = ledger.recordPass(purchases(next), false, true, then.plusSeconds(7860));
        assertTrue(whole.aligned());
        assertEquals(4, whole.fresh());
    }

    @Test
    void aNewCardLikeTheOldSecondJustAboveTheOldTopIsNew() {
        // 2026-11-12 00:46: four new purchases, the last a "Snow Warrior Pack 3" like the old second
        // card, then the old top two. The loose match took that new card for the old second one.
        List<String> previous = new ArrayList<>(List.of("Alliance Gift I||Daily Deals", "Alliance Gift I||Snow Warrior Pack 3",
                "Alliance Gift V|Ann|Mythic Wonder Adventure Pack"));
        for (String b : buyers("b", 222, 12)) {
            previous.add("Alliance Gift I|" + b + "|Daily Deals");
        }
        List<String> current = new ArrayList<>(List.of("Alliance Gift I|Bob|Daily Deals", "Alliance Gift I||Snow Warrior Pack 2",
                "Alliance Gift I||Snow Warrior Pack 4", "Alliance Gift I||Snow Warrior Pack 3"));
        current.addAll(previous);
        current.remove(4 + 9); // the new pass drops a card lower down, so only the loose match applies
        assertEquals(4, AllianceGiftLedger.looseRowCount(previous, current));
    }

    /**
     * Screens of 2026-11-11, copied out of the live pass folders (which keep a week) into a folder of
     * their own. Not committed: list screens can show player names.
     */
    private static final Path FRAMES_1111 = Path.of("C:/Frostguard-tools/test-frames/alliance-spend/2026-11-11");

    private static BufferedImage frame1111(String name) throws Exception {
        return ImageIO.read(FRAMES_1111.resolve(name).toFile());
    }

    @Test
    void tellsAMisreadScreenFromARedrawAndFromAnotherScreen() throws Exception {
        assumeTrue(Files.isDirectory(FRAMES_1111), "2026-11-11 frames not on this machine");
        // 12:54, second try: screen 2 neither lines up nor is back at the top. The pass used to stop
        // here after 5 purchases; a misread screen now starts the pass again from the top.
        BufferedImage top = frame1111("r1-000.png");
        BufferedImage misread = frame1111("r1-002-unaligned.png");
        AllianceGiftLedger.ShiftMatch match = AllianceGiftLedger.scrollMatch(frame1111("r1-001.png"), misread,
                AllianceGiftLedger.EXPECTED_TRAVEL_PX);
        assertTrue(match.shift() < 0);
        assertEquals(AllianceGiftLedger.Loss.MISALIGNED, AllianceGiftLedger.classify(top, misread, match));

        // 14:00, screen 9: the list jumped back to the top.
        BufferedImage top1359 = frame1111("1359-000.png");
        BufferedImage jumped = frame1111("1359-009-unaligned.png");
        AllianceGiftLedger.ShiftMatch jump = AllianceGiftLedger.scrollMatch(frame1111("1359-008.png"), jumped,
                AllianceGiftLedger.EXPECTED_TRAVEL_PX);
        assertEquals(AllianceGiftLedger.Loss.REDRAWN, AllianceGiftLedger.classify(top1359, jumped, jump));

        // 15:13: Back from an event page's link showed the Alliance screen.
        BufferedImage alliance = frame1111("link-back1-alliance-screen.png");
        assertEquals(AllianceGiftLedger.Loss.LEFT_THE_LIST, AllianceGiftLedger.classify(top1359, alliance,
                AllianceGiftLedger.scrollMatch(frame1111("1359-008.png"), alliance, AllianceGiftLedger.EXPECTED_TRAVEL_PX)));
    }
}
