package dev.frostguard.app.panel.misc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins how {@link TelemetryReport} values a window against a sparse sample series.
 *
 * <p>Two regressions live here, and they pull in opposite directions.</p>
 *
 * <p>The first: bg_telemetry snapshots roughly every two hours, so a window often holds readings
 * but none at its opening edge. Measuring from the first reading INSIDE the window silently drops
 * whatever happened before it, so the baseline is allowed to come from just before the window.</p>
 *
 * <p>The second, and the reason that reach is one-directional: an earlier revision also reached
 * FORWARD past the window's end. Applied to a night the machine spent crashed, it grabbed the
 * 11:24 AM reading and reported an hour of that morning's botting -- +25,845 power, 3 beasts,
 * 4 gather marches -- as having happened overnight. A window with no readings in it must report
 * that it has none.</p>
 */
class TelemetryReportWindowTest {

    private static final ZoneId ZONE = ZoneId.of("America/New_York");
    private static final LocalTime SLEEP_START = LocalTime.of(23, 0);
    private static final LocalTime WAKE_END = LocalTime.of(8, 30);
    private static final long PROFILE = 1L;

    /** The real Aug 29->30 shape: the bot died at 23:00 and nothing was recorded until 11:24 AM. */
    @Test
    void nightWithNoReadingsInItReportsNothingRatherThanTheMorningAfter(@TempDir Path root) throws IOException {
        LocalDate today = LocalDate.now(ZONE);
        Instant beforeNight = today.minusDays(1).atTime(22, 52).atZone(ZONE).toInstant();
        Instant nextMorning = today.atTime(11, 24).atZone(ZONE).toInstant();
        write(root, sample(beforeNight, 34_108_399L), sample(nextMorning, 34_134_244L));

        TelemetryReport report = TelemetryReport.load(root, PROFILE);

        assertTrue(report.lastNight(ZONE, SLEEP_START, WAKE_END).isEmpty(),
                "a reading taken after the window closed describes the morning, not the night");
        assertNull(report.coverageForLastNight(ZONE, SLEEP_START, WAKE_END));
        assertTrue(report.activityLastNight(ZONE, SLEEP_START, WAKE_END).isEmpty());
    }

    /** And it can say WHY there is nothing, rather than "not enough samples yet". */
    @Test
    void aSilentWindowNamesWhenTheBotLastAndNextReported(@TempDir Path root) throws IOException {
        LocalDate today = LocalDate.now(ZONE);
        Instant beforeNight = today.minusDays(1).atTime(22, 52).atZone(ZONE).toInstant();
        Instant nextMorning = today.atTime(11, 24).atZone(ZONE).toInstant();
        write(root, sample(beforeNight, 34_108_399L), sample(nextMorning, 34_134_244L));

        TelemetryReport.Coverage silence = TelemetryReport.load(root, PROFILE)
                .silenceForLastNight(ZONE, SLEEP_START, WAKE_END);

        assertNotNull(silence);
        assertEquals(beforeNight, silence.actualFrom());
        assertEquals(nextMorning, silence.actualTo());
    }

    /** A window that DOES hold a reading takes its baseline from just before the window. */
    @Test
    void baselineComesFromTheReadingBeforeTheWindowOpened(@TempDir Path root) throws IOException {
        LocalDate today = LocalDate.now(ZONE);
        Instant beforeNight = today.minusDays(1).atTime(22, 52).atZone(ZONE).toInstant();
        Instant duringNight = today.atTime(6, 0).atZone(ZONE).toInstant();
        write(root, sample(beforeNight, 1_000L), sample(duringNight, 1_500L));

        TelemetryReport report = TelemetryReport.load(root, PROFILE);
        TelemetryReport.Delta power = power(report.lastNight(ZONE, SLEEP_START, WAKE_END));

        assertNotNull(power, "one in-window reading plus a baseline just before it is measurable");
        assertEquals(1_000L, power.start(), "the pre-window reading is the baseline");
        assertEquals(1_500L, power.end());
        assertEquals(500L, power.change());

        TelemetryReport.Coverage coverage = report.coverageForLastNight(ZONE, SLEEP_START, WAKE_END);
        assertNotNull(coverage);
        assertEquals(beforeNight, coverage.actualFrom());
        assertEquals(duringNight, coverage.actualTo());
        assertNull(report.silenceForLastNight(ZONE, SLEEP_START, WAKE_END),
                "a window with data in it is not silent");
    }

    /** The last in-window reading closes the window, never a later one. */
    @Test
    void endNeverBorrowsAReadingTakenAfterTheWindowClosed(@TempDir Path root) throws IOException {
        LocalDate today = LocalDate.now(ZONE);
        Instant beforeNight = today.minusDays(1).atTime(22, 50).atZone(ZONE).toInstant();
        Instant duringNight = today.atTime(6, 0).atZone(ZONE).toInstant();
        Instant afterNight = today.atTime(10, 0).atZone(ZONE).toInstant();
        write(root, sample(beforeNight, 1_000L), sample(duringNight, 1_500L), sample(afterNight, 9_999L));

        TelemetryReport.Delta power = power(
                TelemetryReport.load(root, PROFILE).lastNight(ZONE, SLEEP_START, WAKE_END));

        assertNotNull(power);
        assertEquals(1_500L, power.end(), "the 10:00 AM reading is outside the night");
        assertEquals(500L, power.change());
    }

    /** A baseline hours older than the window is too stale to stand in for its opening edge. */
    @Test
    void staleBaselineBeyondTheReachIsNotUsed(@TempDir Path root) throws IOException {
        LocalDate today = LocalDate.now(ZONE);
        Instant longBefore = today.minusDays(2).atTime(12, 0).atZone(ZONE).toInstant();
        Instant duringNight = today.atTime(6, 0).atZone(ZONE).toInstant();
        write(root, sample(longBefore, 1_000L), sample(duringNight, 5_000L));

        TelemetryReport.Delta power = power(
                TelemetryReport.load(root, PROFILE).lastNight(ZONE, SLEEP_START, WAKE_END));

        assertNull(power, "a two-day-old reading does not describe the start of last night");
    }

    /** A measured window whose value did not move is "steady", not "no data". */
    @Test
    void unchangedMetricStillProducesADelta(@TempDir Path root) throws IOException {
        LocalDate today = LocalDate.now(ZONE);
        Instant beforeNight = today.minusDays(1).atTime(22, 52).atZone(ZONE).toInstant();
        Instant duringNight = today.atTime(4, 0).atZone(ZONE).toInstant();
        write(root, sample(beforeNight, 5_000L), sample(duringNight, 5_000L));

        TelemetryReport.Delta power = power(
                TelemetryReport.load(root, PROFILE).lastNight(ZONE, SLEEP_START, WAKE_END));

        assertNotNull(power, "measured-and-unchanged must be distinguishable from no-coverage");
        assertEquals(0L, power.change());
    }

    /**
     * The 9/1 general-speedup row: 2434, then a single frame reading 30, then 2664. Dropping
     * forty hours of speedups and regaining forty-four in the next thirty-nine minutes is not a
     * thing that happens; it is one bad OCR frame, and it landed on the window's baseline.
     */
    @Test
    void anIsolatedMisreadIsNotUsedAsAWindowBaseline(@TempDir Path root) throws IOException {
        LocalDate today = LocalDate.now(ZONE);
        Instant evening = today.minusDays(1).atTime(18, 46).atZone(ZONE).toInstant();
        Instant misread = today.minusDays(1).atTime(22, 21).atZone(ZONE).toInstant();
        Instant nightStart = today.minusDays(1).atTime(23, 0, 49).atZone(ZONE).toInstant();
        Instant morning = today.atTime(8, 32).atZone(ZONE).toInstant();
        write(root,
                speedup(evening, 2434L), speedup(misread, 30L),
                speedup(nightStart, 2664L), speedup(morning, 2751L));

        TelemetryReport.Delta general = metric("sp_general",
                TelemetryReport.load(root, PROFILE).lastNight(ZONE, SLEEP_START, WAKE_END));

        assertNotNull(general);
        assertEquals(2664L, general.start(), "the 30 reading must not anchor the night");
        assertEquals(87L, general.change());
    }

    /** A real drop is held by the readings after it, so it is kept. */
    @Test
    void aSustainedDropIsRealAndSurvives(@TempDir Path root) throws IOException {
        LocalDate today = LocalDate.now(ZONE);
        Instant before = today.minusDays(1).atTime(22, 40).atZone(ZONE).toInstant();
        Instant spend = today.atTime(1, 0).atZone(ZONE).toInstant();
        Instant after = today.atTime(4, 0).atZone(ZONE).toInstant();
        write(root, speedup(before, 2268L), speedup(spend, 155L), speedup(after, 160L));

        TelemetryReport.Delta general = metric("sp_general",
                TelemetryReport.load(root, PROFILE).lastNight(ZONE, SLEEP_START, WAKE_END));

        assertNotNull(general);
        assertEquals(2268L, general.start());
        assertEquals(160L, general.end(), "spending a stockpile does not revert, so it is real");
    }

    /** Only the offending field is dropped -- the rest of that row is still good data. */
    @Test
    void despikingDropsOneFieldNotTheWholeReading(@TempDir Path root) throws IOException {
        LocalDate today = LocalDate.now(ZONE);
        Instant before = today.minusDays(1).atTime(22, 0).atZone(ZONE).toInstant();
        Instant suspect = today.atTime(1, 0).atZone(ZONE).toInstant();
        Instant after = today.atTime(4, 0).atZone(ZONE).toInstant();
        write(root,
                "{\"capturedAt\":\"" + before + "\",\"power\":1000,\"sp_general\":2400}",
                "{\"capturedAt\":\"" + suspect + "\",\"power\":1200,\"sp_general\":30}",
                "{\"capturedAt\":\"" + after + "\",\"power\":1400,\"sp_general\":2500}");

        List<TelemetryReport.Delta> night = TelemetryReport.load(root, PROFILE)
                .lastNight(ZONE, SLEEP_START, WAKE_END);

        assertNotNull(metric("power", night), "power in that row was fine and must be kept");
        assertEquals(400L, metric("power", night).change());
        assertEquals(100L, metric("sp_general", night).change(), "2400 -> 2500, the 30 dropped");
    }

    // ---- helpers ------------------------------------------------------------

    private static TelemetryReport.Delta metric(String name, List<TelemetryReport.Delta> deltas) {
        return deltas.stream().filter(d -> name.equals(d.metric())).findFirst().orElse(null);
    }

    private static String speedup(Instant at, long minutes) {
        return "{\"capturedAt\":\"" + at + "\",\"profile\":\"Default\",\"sp_general\":" + minutes + "}";
    }

    private static TelemetryReport.Delta power(List<TelemetryReport.Delta> deltas) {
        Optional<TelemetryReport.Delta> found = deltas.stream()
                .filter(d -> "power".equals(d.metric())).findFirst();
        return found.orElse(null);
    }

    private static String sample(Instant at, long power) {
        return "{\"capturedAt\":\"" + at + "\",\"profile\":\"Default\",\"power\":" + power + "}";
    }

    private static void write(Path root, String... lines) throws IOException {
        Path dir = root.resolve("data").resolve("telemetry")
                .resolve("profiles").resolve(String.valueOf(PROFILE));
        Files.createDirectories(dir);
        Files.write(dir.resolve("history.jsonl"), new ArrayList<>(List.of(lines)));
    }
}
