package dev.frostguard.engine.alliance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.frostguard.engine.alliance.AllianceSpendReport.Aliases;
import dev.frostguard.engine.alliance.AllianceSpendReport.PriceSource;
import dev.frostguard.engine.alliance.AllianceSpendReport.Purchase;
import dev.frostguard.engine.alliance.AllianceSpendReport.ScanIndex;

class AllianceSpendReportTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode row(String at, String tier, String buyer, String pack) throws Exception {
        Map<String, Object> m = new HashMap<>();
        m.put("capturedAt", at);
        m.put("tier", tier);
        m.put("buyer", buyer == null ? "" : buyer);
        m.put("anonymous", buyer == null);
        m.put("pack", pack);
        m.put("backlog", false);
        return MAPPER.valueToTree(m);
    }

    /** Pack names and prices, as the scan's packs[] would carry them. */
    private static ScanIndex scanned(Object... nameAndPrice) {
        Map<String, TreeSet<Double>> names = new HashMap<>();
        Map<String, TreeSet<Double>> families = new HashMap<>();
        for (int i = 0; i < nameAndPrice.length; i += 2) {
            String name = (String) nameAndPrice[i];
            double price = (Double) nameAndPrice[i + 1];
            names.computeIfAbsent(AllianceSpendReport.normalise(name), k -> new TreeSet<>()).add(price);
            String family = name.replaceFirst("\\s*-\\s*\\$[\\d.,]+\\s*$", "");
            if (!family.equals(name)) {
                families.computeIfAbsent(AllianceSpendReport.normalise(family), k -> new TreeSet<>()).add(price);
            }
        }
        return new ScanIndex(names, families);
    }

    private static AllianceSpendReport build(List<JsonNode> rows, Map<String, Double> operator, ScanIndex scans) {
        return AllianceSpendReport.build(rows, operator, scans, Aliases.EMPTY);
    }

    @Test
    void pricesFromOperatorThenScanThenTier() throws Exception {
        AllianceSpendReport report = build(List.of(
                row("2026-10-26T10:00", "Alliance Gift I", "Braslin", "Daily Deals"),
                row("2026-10-26T09:00", "Alliance Gift I", "Lune", "Conqueror! Pack"),
                row("2026-10-26T08:00", "Alliance Gift II", null, "Mia's Keenness")),
                Map.of("dailydeals", 0.99), scanned("Conqueror Pack", 4.99));
        List<Purchase> p = report.purchases();
        assertEquals(PriceSource.OPERATOR, p.get(0).source());
        assertEquals(0.99, p.get(0).usd());
        assertEquals(PriceSource.DEAL_SCAN, p.get(1).source());
        assertEquals(4.99, p.get(1).usd());
        assertEquals(PriceSource.TIER_ESTIMATE, p.get(2).source());
        assertEquals(9.99, p.get(2).usd());
    }

    @Test
    void aScannedPriceOutsideTheTiersBandIsNotTakenAsKnown() throws Exception {
        AllianceSpendReport report = build(List.of(
                row("2026-10-26T10:00", "Alliance Gift III", "Braslin", "Fire Crystal Pack"),
                row("2026-10-26T09:00", "Alliance Gift II", "Braslin", "Conqueror Pack")),
                Map.of(), scanned("Fire Crystal Pack", 4.99, "Conqueror Pack", 4.99));
        Purchase fireCrystal = report.purchases().get(0);
        assertEquals(PriceSource.TIER_ESTIMATE, fireCrystal.source());
        assertEquals(19.99, fireCrystal.usd());
        assertTrue(fireCrystal.priceNote().contains("does not fit"));
        assertEquals(PriceSource.TIER_ESTIMATE, report.purchases().get(1).source());
    }

    @Test
    void aFamilyNamePicksThePriceOptionsInsideTheTiersBand() throws Exception {
        AllianceSpendReport report = build(List.of(
                row("2026-10-26T10:00", "Alliance Gift I", null, "Daily Deals"),
                row("2026-10-26T09:00", "Alliance Gift II", "Old man", "Fire Crystal Leap Pack")),
                Map.of(), scanned("Daily Deals - $0.99", 0.99, "Daily Deals - $1.99", 1.99,
                        "Daily Deals - $2.99", 2.99, "Fire Crystal Leap Pack - $4.99", 4.99,
                        "Fire Crystal Leap Pack - $9.99", 9.99));
        Purchase daily = report.purchases().get(0);
        assertEquals(PriceSource.SCAN_RANGE, daily.source());
        assertEquals(0.99, daily.usd());
        assertFalse(daily.source().known());
        Purchase leap = report.purchases().get(1);
        assertEquals(PriceSource.DEAL_SCAN, leap.source());
        assertEquals(9.99, leap.usd());
    }

    @Test
    void oneStrayLetterStillMatchesButNeighbouringPacksDoNot() {
        List<String> known = List.of("arcanereverie", "dreamtravelerpack2", "commonluckychippack", "herorallyunlock");
        assertEquals("arcanereverie", AllianceSpendReport.canonical("arcanelreverie", known));
        assertEquals("dreamtravelerpack3", AllianceSpendReport.canonical("dreamtravelerpack3", known));
        assertEquals("uncommonluckychippack", AllianceSpendReport.canonical("uncommonluckychippack", known));
        assertEquals("herorally2unlock", AllianceSpendReport.canonical("herorally2unlock", known));
    }

    @Test
    void foldsMisreadsIntoTheNameSeenMostOftenButKeepsDistinctPlayers() throws Exception {
        AllianceSpendReport report = build(List.of(
                row("2026-10-26T10:00", "Alliance Gift I", "AthenaRyu", "Common Lucky Chip Pack"),
                row("2026-10-26T09:00", "Alliance Gift I", "AthenaRyu", "Common Lucky Chip Pack"),
                row("2026-10-26T08:00", "Alliance Gift I", "AthenaRyu 1", "Common Lucky y Chip Pack"),
                row("2026-10-26T07:00", "Alliance Gift I", "Evelyn", "Uncommon! Lucky Chip Pack"),
                row("2026-10-26T06:00", "Alliance Gift I", "Evelyn", "Uncommon Lucky Chip Pack"),
                row("2026-10-26T05:00", "Alliance Gift I", "Evelyne", "Dream Traveler Pack 3"),
                row("2026-10-26T04:00", "Alliance Gift I", "Archawk", "Daily Deals"),
                row("2026-10-26T03:00", "Alliance Gift I", "Archawk", "Daily Deals"),
                row("2026-10-26T02:00", "Alliance Gift I", "Archawk I", "Daily Deals")),
                Map.of(), scanned("Dream Traveler Pack 2", 4.99));
        List<Purchase> p = report.purchases();
        assertEquals("AthenaRyu", p.get(2).buyer());
        assertEquals("Common Lucky Chip Pack", p.get(2).pack());
        assertEquals("Uncommon Lucky Chip Pack", p.get(3).pack());
        assertEquals("Evelyne", p.get(5).buyer());
        assertEquals("Dream Traveler Pack 3", p.get(5).pack());
        assertEquals("Archawk", p.get(8).buyer());
    }

    @Test
    void theAliasFileFixesNamesTooShortToFoldSafely() throws Exception {
        AllianceSpendReport report = AllianceSpendReport.build(List.of(
                row("2026-10-26T10:00", "Alliance Gift I", "une", "Arcanel Reverie"),
                row("2026-10-26T09:00", "Alliance Gift I", "Lune", "Arcane Reverie")),
                Map.of(), ScanIndex.EMPTY,
                new Aliases(Map.of("une", "Lune"), Map.of("Arcanel Reverie", "Arcane Reverie")));
        assertEquals("Lune", report.purchases().get(0).buyer());
        assertEquals("Arcane Reverie", report.purchases().get(0).pack());
        assertEquals(1, AllianceSpendReport.byBuyer(report.purchases()).size());
    }

    @Test
    void totalsKeepKnownDollarsApartFromEstimates() throws Exception {
        AllianceSpendReport report = build(List.of(
                row("2026-10-26T10:00", "Alliance Gift I", "Braslin", "Conqueror Pack"),
                row("2026-10-26T09:00", "Alliance Gift I", "Braslin", "Daily Deals"),
                row("2026-10-20T09:00", "Alliance Gift II", null, "Mia's Keenness"),
                row("2026-10-20T08:00", null, null, "Mystery Pack")),
                Map.of(), scanned("Conqueror Pack", 4.99));
        List<Purchase> recent = report.since(LocalDateTime.parse("2026-10-25T00:00"));
        assertEquals(2, recent.size());
        assertEquals(4.99, AllianceSpendReport.usd(recent, false), 1e-9);
        assertEquals(9.98, AllianceSpendReport.usd(recent, true), 1e-9);
        var buyers = AllianceSpendReport.byBuyer(report.purchases());
        assertEquals("(anonymous)", buyers.get(0).buyer());
        assertEquals(4.99, buyers.get(1).knownUsd(), 1e-9);
        assertEquals(4.99, buyers.get(1).estimatedUsd(), 1e-9);
        assertEquals(1, AllianceSpendReport.unpriceable(report.purchases()));
        assertNull(report.purchases().get(3).usd());
        assertEquals(Map.of("Daily Deals", 1, "Mia's Keenness", 1, "Mystery Pack", 1),
                AllianceSpendReport.unpricedPacks(report.purchases()));
    }

    @Test
    void readsCleanReportsSkipsSupersededRunsAndSurvivesACorruptLedgerLine(@TempDir Path ws) throws Exception {
        Path reports = Files.createDirectories(ws.resolve("data/deals/reports"));
        Files.writeString(reports.resolve("2026-10-26.json"), """
                {"packs": [
                  {"name": "Daily Deals - $0.99", "where": "Deals > Daily Deals", "price": 0.99},
                  {"name": "Shining Custom Chest", "where": "Gem Shop > Mix & Match", "price": 4.99},
                  {"name": "Majestic Custom Chest", "where": "Gem Shop > Mix & Match", "price": 99.99},
                  {"name": "Exquisite Custom Chest", "where": "Gem Shop > Mix & Match", "price": 49.99}]}""");
        Files.writeString(reports.resolve("2026-10-26-superseded-143315.json"),
                "{\"packs\": [{\"name\": \"Special Offers\", \"where\": \"City\", \"price\": 4.99}]}");
        Path spend = Files.createDirectories(ws.resolve("data/alliance-spend"));
        Files.writeString(spend.resolve("gifts.jsonl"), String.join("\n",
                MAPPER.writeValueAsString(row("2026-10-26T10:00", "Alliance Gift IV", "Braslin", "Mix & Match")),
                "{not json",
                MAPPER.writeValueAsString(row("2026-10-26T09:00", "Alliance Gift II", null, "Special Offers")),
                "{\"pack\": \"Daily Deals\", \"capturedAt\": \"yesterday\"}"));
        AllianceSpendReport report = AllianceSpendReport.load(ws);
        assertEquals(2, report.unreadableLines());
        assertEquals(2, report.purchases().size());
        Purchase chest = report.purchases().get(0);
        assertEquals(PriceSource.SCAN_RANGE, chest.source());
        assertEquals(49.99, chest.usd());
        assertEquals(PriceSource.TIER_ESTIMATE, report.purchases().get(1).source());
    }
}
