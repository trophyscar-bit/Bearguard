package dev.frostguard.engine.alliance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.frostguard.engine.alliance.AllianceSpendReport.PriceSource;
import dev.frostguard.engine.alliance.AllianceSpendReport.Purchase;

class AllianceSpendReportTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode row(String at, String tier, String buyer, String pack) throws Exception {
        return MAPPER.readTree(MAPPER.writeValueAsString(Map.of(
                "capturedAt", at, "tier", tier, "buyer", buyer == null ? "" : buyer,
                "anonymous", buyer == null, "pack", pack, "backlog", false)));
    }

    private static Map<String, TreeSet<Double>> scanned(Object... titleAndPrices) {
        java.util.HashMap<String, TreeSet<Double>> m = new java.util.HashMap<>();
        for (int i = 0; i < titleAndPrices.length; i += 2) {
            m.computeIfAbsent(AllianceSpendReport.normalise((String) titleAndPrices[i]), k -> new TreeSet<>())
                    .add((Double) titleAndPrices[i + 1]);
        }
        return m;
    }

    @Test
    void pricesFromOperatorThenScanThenTier() throws Exception {
        AllianceSpendReport report = AllianceSpendReport.build(List.of(
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
    void oneStrayLetterStillMatchesButNeighbouringPacksDoNot() {
        List<String> known = List.of("arcanereverie", "dreamtravelerpack2", "commonluckychippack");
        assertEquals("arcanereverie", AllianceSpendReport.canonical("arcanelreverie", known));
        assertEquals("dreamtravelerpack3", AllianceSpendReport.canonical("dreamtravelerpack3", known));
        assertEquals("uncommonluckychippack", AllianceSpendReport.canonical("uncommonluckychippack", known));
    }

    @Test
    void foldsOneLetterMisreadsIntoTheNameSeenMostOften() throws Exception {
        AllianceSpendReport report = AllianceSpendReport.build(List.of(
                row("2026-10-26T10:00", "Alliance Gift I", "AthenaRyu", "Common Lucky Chip Pack"),
                row("2026-10-26T09:00", "Alliance Gift I", "AthenaRyu", "Common Lucky Chip Pack"),
                row("2026-10-26T08:00", "Alliance Gift I", "AthenaRyu 1", "Common Lucky y Chip Pack"),
                row("2026-10-26T07:00", "Alliance Gift I", "Lune", "Legend 1 Transcend Pack"),
                row("2026-10-26T06:00", "Alliance Gift I", "Lune", "Legend Transcend Pack"),
                row("2026-10-26T05:00", "Alliance Gift I", "Braslin", "Dream Traveler Pack 3"),
                row("2026-10-26T04:00", "Alliance Gift I", "Braslin", "Legend Transcend Pack")),
                Map.of(), scanned("Dream Traveler Pack 2", 4.99));
        List<Purchase> p = report.purchases();
        assertEquals("AthenaRyu", p.get(2).buyer());
        assertEquals("Common Lucky Chip Pack", p.get(2).pack());
        assertEquals("Legend Transcend Pack", p.get(3).pack());
        assertEquals("Dream Traveler Pack 3", p.get(5).pack());
        assertEquals(PriceSource.TIER_ESTIMATE, p.get(5).source());
    }

    @Test
    void theAliasFileFixesNamesTooShortToFoldSafely() throws Exception {
        AllianceSpendReport report = AllianceSpendReport.build(List.of(
                row("2026-10-26T10:00", "Alliance Gift I", "une", "Arcanel Reverie"),
                row("2026-10-26T09:00", "Alliance Gift I", "Lune", "Arcane Reverie")),
                Map.of(), Map.of(),
                new AllianceSpendReport.Aliases(Map.of("une", "Lune"), Map.of("Arcanel Reverie", "Arcane Reverie")));
        assertEquals("Lune", report.purchases().get(0).buyer());
        assertEquals("Arcane Reverie", report.purchases().get(0).pack());
        assertEquals(1, AllianceSpendReport.byBuyer(report.purchases()).size());
    }

    @Test
    void aTitleScannedAtSeveralPricesUsesTheOneMatchingTheTier() throws Exception {
        AllianceSpendReport report = AllianceSpendReport.build(List.of(
                row("2026-10-26T10:00", "Alliance Gift II", "Old man", "Intel Monthly Card"),
                row("2026-10-26T09:00", "Alliance Gift III", "Old man", "Intel Monthly Card")),
                Map.of(), scanned("Intel Monthly Card", 4.99, "Intel Monthly Card", 9.99));
        assertEquals(9.99, report.purchases().get(0).usd());
        assertEquals(PriceSource.DEAL_SCAN, report.purchases().get(0).source());
        assertEquals(PriceSource.TIER_ESTIMATE, report.purchases().get(1).source());
    }

    @Test
    void totalsKeepMeasuredDollarsApartFromEstimates() throws Exception {
        AllianceSpendReport report = AllianceSpendReport.build(List.of(
                row("2026-10-26T10:00", "Alliance Gift I", "Braslin", "Conqueror Pack"),
                row("2026-10-26T09:00", "Alliance Gift I", "Braslin", "Daily Deals"),
                row("2026-10-20T09:00", "Alliance Gift II", null, "Mia's Keenness")),
                Map.of(), scanned("Conqueror Pack", 4.99));
        List<Purchase> recent = report.since(LocalDateTime.parse("2026-10-25T00:00"));
        assertEquals(2, recent.size());
        assertEquals(4.99, AllianceSpendReport.usd(recent, false), 1e-9);
        assertEquals(9.98, AllianceSpendReport.usd(recent, true), 1e-9);
        var buyers = AllianceSpendReport.byBuyer(report.purchases());
        assertEquals("(anonymous)", buyers.get(0).buyer());
        assertEquals("Braslin", buyers.get(1).buyer());
        assertEquals(2, buyers.get(1).purchases());
        assertEquals(4.99, buyers.get(1).knownUsd(), 1e-9);
        assertEquals(4.99, buyers.get(1).estimatedUsd(), 1e-9);
        assertEquals(Map.of("Daily Deals", 1, "Mia's Keenness", 1), AllianceSpendReport.unpricedPacks(report.purchases()));
        assertNull(report.purchases().get(2).buyer());
    }
}
