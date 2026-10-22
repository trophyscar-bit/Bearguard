package dev.frostguard.engine.alliance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.frostguard.engine.alliance.AllianceGiftLedger.GiftRow;
import dev.frostguard.vision.ocr.TextLine;

class AllianceGiftLedgerTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 22, 12, 0, 0);

    private static TextLine line(String text, int top) {
        return new TextLine(text, 150, top, 300, 26, 0.95f);
    }

    @Test
    void splitsScreenIntoRowsAndReadsSenderAndCountdown() {
        List<TextLine> screen = List.of(
                line("Rare Gift", 450), line("From: Braslin", 480), line("23:10:05", 510), line("Claim", 480),
                line("Epic Gift", 600), line("From: Anonymous", 630), line("05:00:00", 660));
        List<GiftRow> rows = AllianceGiftLedger.parseFrame(screen, T0, "000.png");
        assertEquals(2, rows.size());
        assertEquals("Rare Gift", rows.get(0).gift());
        assertEquals("Braslin", rows.get(0).sender());
        assertFalse(rows.get(0).anonymous());
        assertEquals("2026-10-23T11:10", rows.get(0).expiresAt());
        assertEquals("Epic Gift", rows.get(1).gift());
        assertTrue(rows.get(1).anonymous());
    }

    @Test
    void keepsRowsWithoutAGiftNameSoMisreadsStayVisible() {
        List<GiftRow> rows = AllianceGiftLedger.parseFrame(List.of(line("From: Someone", 450)), T0, "000.png");
        assertEquals(1, rows.size());
        assertNull(rows.get(0).gift());
        assertEquals("From: Someone", rows.get(0).rawText());
    }

    @Test
    void logsAGiftOnceAcrossOverlappingScreensAndLaterPasses(@TempDir Path dir) throws Exception {
        AllianceGiftLedger ledger = new AllianceGiftLedger(dir);
        List<GiftRow> first = new ArrayList<>(AllianceGiftLedger.parseFrame(
                List.of(line("Rare Gift", 450), line("From: Braslin", 480), line("23:10:05", 510)), T0, "000.png"));
        first.addAll(AllianceGiftLedger.parseFrame(
                List.of(line("Rare Gift", 200), line("From: Braslin", 230), line("23:10:03", 260)),
                T0.plusSeconds(2), "001.png"));
        assertEquals(1, ledger.recordNew(first, false, T0).size());

        List<GiftRow> fourHoursLater = AllianceGiftLedger.parseFrame(
                List.of(line("Rare Gift", 450), line("From: Braslin", 480), line("19:10:04", 510)),
                T0.plusHours(4), "000.png");
        assertEquals(0, ledger.recordNew(fourHoursLater, false, T0.plusHours(4)).size());
        assertEquals(1, Files.readAllLines(dir.resolve("gifts.jsonl")).size());
    }

    @Test
    void sameGiftFromSameSenderBoughtLaterIsANewPurchase(@TempDir Path dir) throws Exception {
        AllianceGiftLedger ledger = new AllianceGiftLedger(dir);
        List<GiftRow> rows = AllianceGiftLedger.parseFrame(List.of(
                line("Rare Gift", 450), line("From: Braslin", 480), line("23:10:05", 510),
                line("Rare Gift", 600), line("From: Braslin", 630), line("21:00:00", 660)), T0, "000.png");
        assertEquals(2, ledger.recordNew(rows, false, T0).size());
    }

    @Test
    void anonymisingKeepsOneBuyersGiftsTogetherWithoutTheName(@TempDir Path dir) throws Exception {
        AllianceGiftLedger ledger = new AllianceGiftLedger(dir);
        List<GiftRow> rows = AllianceGiftLedger.parseFrame(List.of(
                line("Rare Gift", 450), line("From: Braslin", 480), line("23:10:05", 510),
                line("Epic Gift", 600), line("From: Braslin", 630), line("21:00:00", 660)), T0, "000.png");
        List<GiftRow> logged = ledger.recordNew(rows, true, T0);
        assertEquals(logged.get(0).sender(), logged.get(1).sender());
        assertTrue(logged.get(0).sender().startsWith("anon-"));
        assertFalse(Files.readString(dir.resolve("gifts.jsonl")).contains("Braslin"));
        assertEquals("Anonymous", AllianceGiftLedger.anonymise("Anonymous"));
    }
}
