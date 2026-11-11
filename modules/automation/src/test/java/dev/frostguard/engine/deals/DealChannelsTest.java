package dev.frostguard.engine.deals;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DealChannelsTest {

    private static final String FC_WEEKLY = """
            {"name":"Fire Crystal Weekly Card","where":"Gem Shop > Weekly/Monthly Cards","price":4.99,
             "items":[{"name":"Fire Crystal","quantity":52}]}""";

    @TempDir
    Path root;

    @Test
    void ranksTodaysPacksByWholePackPriceAndMarksTheBest() throws IOException {
        report("2026-11-09", FC_WEEKLY, """
                {"name":"Sign-in","where":"Deals > Sign-in","price":0.99,"items":[{"name":"Fire Crystal","quantity":10}]}""",
                """
                {"name":"Fire Crystal Pack","where":"Gem Shop > Regular Pack","price":4.99,"limit":"Weekly Remaining: 1",
                 "items":[{"name":"Fire Crystal","quantity":15}]}""");

        DealChannels.Board board = board("fire-crystals");

        assertEquals(List.of("Fire Crystal Weekly Card", "Sign-in", "Fire Crystal Pack"),
                board.packs().stream().map(DealChannels.PackRow::label).toList());
        assertEquals(List.of(DealChannels.Mark.BEST, DealChannels.Mark.CLOSE, DealChannels.Mark.OTHER),
                board.packs().stream().map(DealChannels.PackRow::mark).toList());
        assertEquals("52 (7d)", board.packs().get(0).count());
        assertEquals(4.99 / 52, board.packs().get(0).perUnit(), 1e-9);
        assertTrue(board.places().contains(new DealChannels.Place("Fire Crystal Pack","Gem Shop › Regular Pack · 1/week")));
    }

    @Test
    void tracksTodayAgainstTheTenDayAverageAndTheLowestSeen() throws IOException {
        Files.createDirectories(root.resolve("discord/hardened-alloy"));
        Files.writeString(root.resolve("discord/hardened-alloy/history.json"),
                "[{\"day\":\"2026-11-08\",\"usd_per_1000\":5.0}]");
        report("2026-11-09", """
                {"name":"Alloy Pack","where":"Gem Shop > Regular Pack","price":8.0,"items":[{"name":"Hardened Alloy","quantity":2000}]}""");

        DealChannels.Tracker tracker = board("hardened-alloy").tracker();

        assertEquals(4.0, tracker.today(), 1e-9);
        assertEquals(4.5, tracker.average(), 1e-9);
        assertEquals(DealChannels.Trend.CHEAPER, tracker.trend());
        assertEquals(LocalDate.of(2026, 11, 9), tracker.lowestDay());
    }

    @Test
    void keepsThePostedHistoryOverAReportOfTheSameDay() throws IOException {
        Files.createDirectories(root.resolve("discord"));
        Files.writeString(root.resolve("discord/fc-history.json"), "[{\"day\":\"2026-11-08\",\"usd_per_fc\":0.05}]");
        report("2026-11-08", """
                {"name":"Sign-in","where":"Deals > Sign-in","price":0.99,"items":[{"name":"Fire Crystal","quantity":3}]}""");
        report("2026-11-09", FC_WEEKLY);

        DealChannels.Tracker tracker = board("fire-crystals").tracker();

        assertEquals(0.05, tracker.lowest(), 1e-9);
        assertEquals(LocalDate.of(2026, 11, 8), tracker.lowestDay());
    }

    @Test
    void anUnpostedEarlierNightDoesNotReachTheTracker() throws IOException {
        report("2026-11-08", """
                {"name":"Misread Pack","where":"Deals > Misread","price":0.99,"items":[{"name":"Fire Crystal","quantity":900}]}""");
        report("2026-11-09", FC_WEEKLY);

        DealChannels.Tracker tracker = board("fire-crystals").tracker();

        assertEquals(LocalDate.of(2026, 11, 9), tracker.lowestDay());
    }

    @Test
    void leavesOutPacksThatCannotBeBoughtOrAreOcrJunk() throws IOException {
        report("2026-11-09", FC_WEEKLY, """
                {"name":"Owned Pack","where":"Deals > Owned","price":4.99,"purchased":true,"items":[{"name":"Fire Crystal","quantity":9}]}""",
                """
                {"name":"Lhris}ne pack","where":"Gem Shop > Leap","price":99.99,"items":[{"name":"Fire Crystal","quantity":9}]}""");

        assertEquals(1, board("fire-crystals").packs().size());
    }

    @Test
    void anUnreadPriceIsNamedNeverRanked() throws IOException {
        report("2026-11-09", FC_WEEKLY, """
                {"name":"Mystery Pack","where":"Deals > Mystery","price":null,"items":[{"name":"Fire Crystal","quantity":9}]}""");

        DealChannels.Board board = board("fire-crystals");

        assertEquals(1, board.packs().size());
        assertEquals(List.of("Mystery Pack"), board.unpriced());
    }

    @Test
    void appliesTheAuditorsFixesToThatDay() throws IOException {
        Files.createDirectories(root.resolve("discord"));
        Files.writeString(root.resolve("discord/fixes.json"), """
                {"2026-11-09":[{"name":"Mastery Forging","price":4.99,"item":"Essence Stones","quantity":10,"limit":"Weekly Remaining: 1"}]}""");
        report("2026-11-09", """
                {"name":"Mastery Forging","where":"Gem Shop > Weekly","price":4.99,"items":[{"name":"Gems","quantity":100}]}""");

        DealChannels.Board board = board("essence-stones");

        assertEquals("10", board.packs().get(0).count());
        assertEquals(List.of(new DealChannels.Place("Mastery Forging", "Gem Shop › Weekly · 1/week")), board.places());
    }

    @Test
    void countsEverySlotOfAPickYourOwnChestAndSaysSo() throws IOException {
        report("2026-11-09", """
                {"name":"Custom Chest","where":"Deals > Custom","price":9.99,"items":[
                 {"name":"Design Plans (slot 1 choice)","quantity":20},{"name":"Design Plans (slot 2 choice)","quantity":30}]}""");

        DealChannels.Board board = board("design-plans");

        assertEquals("50", board.packs().get(0).count());
        assertTrue(board.places().get(0).path().endsWith("pick Design Plans in every slot"), board.places().toString());
    }

    @Test
    void tiersOfOnePackShareAWhereToBuyLine() throws IOException {
        report("2026-11-09", """
                {"name":"Fire Crystal Leap Pack 3","where":"Gem Shop > Fire Crystal Leap Pack","price":19.99,"limit":"Lifetime Limit: 1","items":[{"name":"Fire Crystal","quantity":100}]}""",
                """
                {"name":"Fire Crystal Leap Pack 4","where":"Gem Shop > Fire Crystal Leap Pack","price":49.99,"limit":"Lifetime Limit: 1","items":[{"name":"Fire Crystal","quantity":250}]}""");

        assertEquals("Fire Crystal Leap Pack 3, 4", board("fire-crystals").places().get(0).label());
    }

    @Test
    void aChannelWithNoVerdictForTheNewestReportIsNotAudited() throws IOException {
        Files.createDirectories(root.resolve("discord"));
        Files.writeString(root.resolve("discord/HOLD"), "{\"day\":\"2026-11-08\",\"channels\":{}}");
        report("2026-11-09", FC_WEEKLY);

        assertEquals("Unverified: not audited yet", board("fire-crystals").hold());
    }

    @Test
    void anApprovedChannelCarriesNoHold() throws IOException {
        Files.createDirectories(root.resolve("discord"));
        Files.writeString(root.resolve("discord/HOLD"), "{\"day\":\"2026-11-09\",\"channels\":{}}");
        report("2026-11-09", FC_WEEKLY);

        assertNull(board("fire-crystals").hold());
    }

    @Test
    void namesAnUnreadableReportInsteadOfHidingIt() throws IOException {
        Files.createDirectories(root.resolve("reports"));
        Files.writeString(root.resolve("reports/2026-11-08.json"), "{not json");
        report("2026-11-09", FC_WEEKLY);

        DealChannels.Snapshot snapshot = DealChannels.read(root);

        assertEquals(LocalDate.of(2026, 11, 9), snapshot.day());
        assertEquals(1, snapshot.problems().size());
        assertTrue(snapshot.problems().get(0).startsWith("2026-11-08.json"), snapshot.problems().toString());
    }

    @Test
    void saysHowMuchOfTheShopTheScanReached() throws IOException {
        Files.createDirectories(root.resolve("reports"));
        Files.writeString(root.resolve("reports/2026-11-09.json"), """
                {"packs":[],"coverage":[{"surface":"Login Offers","visited":false,"fullyScrolled":true},
                 {"surface":"Gem Shop","visited":true,"fullyScrolled":true},
                 {"surface":"Events","visited":true,"fullyScrolled":false}]}""");

        assertEquals("2/3 shops checked; not checked: Login Offers; cut off: Events", DealChannels.read(root).coverage());
    }

    private DealChannels.Board board(String id) {
        return DealChannels.read(root).boards().stream().filter(b -> b.channel().id().equals(id)).findFirst().orElseThrow();
    }

    private void report(String day, String... packs) throws IOException {
        Files.createDirectories(root.resolve("reports"));
        Files.writeString(root.resolve("reports/" + day + ".json"), "{\"packs\":[" + String.join(",", packs) + "]}");
    }
}
