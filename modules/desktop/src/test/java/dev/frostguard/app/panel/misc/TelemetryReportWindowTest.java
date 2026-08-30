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
 * Pins the window-anchoring behaviour of {@link TelemetryReport}.
 *
 * <p>The regression these exist for: bg_telemetry snapshots roughly every two hours, and the
 * "last night" window (23:00-08:30) is short enough that a night can contain no snapshot at all
 * while perfectly good readings sit either side of it. Requiring both endpoints to fall strictly
 * inside the window reported "not enough samples" for a night that was entirely measurable, and
 * the Statistics tab's fallback then printed the raw current stockpile in the "gained" grid --
 * which read as "gained 34.22M power overnight" when the real answer was +25,845.</p>
 */
class TelemetryReportWindowTest {

    private static final ZoneId ZONE = ZoneId.of("America/New_York");
    private static final LocalTime SLEEP_START = LocalTime.of(23, 0);
    private static final LocalTime WAKE_END = LocalTime.of(8, 30);
    private static final long PROFILE = 1L;

    /** The real Aug 29->30 shape: last reading 8 minutes before the window, next 2h34m after it. */
    @Test
    void nightWithNoInWindowSampleIsMeasuredFromTheReadingsEitherSide(@TempDir Path root) throws IOException {
        LocalDate today = LocalDate.now(ZONE);
        Instant beforeNight = today.minusDays(1).atTime(22, 52).atZone(ZONE).toInstant();
        Instant afterNight = today.atTime(11, 24).atZone(ZONE).toInstant();
        write(root, sample(beforeNight, 34_108_399L), sample(afterNight, 34_134_244L));

        TelemetryReport report = TelemetryReport.load(root, PROFILE);
        TelemetryReport.Delta power = power(report.lastNight(ZONE, SLEEP_START, WAKE_END));

        assertNotNull(power, "a night bracketed by two readings is measurable");
        assertEquals(25_845L, power.change());
        assertEquals(34_108_399L, power.start());
        assertEquals(34_134_244L, power.end());

        // The header must state the span the figures actually came from, not the nominal window.
        TelemetryReport.Coverage coverage = report.coverageForLastNight(ZONE, SLEEP_START, WAKE_END);
        assertNotNull(coverage);
        assertEquals(beforeNight, coverage.actualFrom());
        assertEquals(afterNight, coverage.actualTo());
    }

    /** An outage longer than the bracket reach must stay honest and report nothing. */
    @Test
    void outageBeyondTheBracketReachReportsNoCoverage(@TempDir Path root) throws IOException {
        LocalDate today = LocalDate.now(ZONE);
        Instant longBefore = today.minusDays(2).atTime(12, 0).atZone(ZONE).toInstant();
        Instant longAfter = today.atTime(18, 0).atZone(ZONE).toInstant();
        write(root, sample(longBefore, 30_000_000L), sample(longAfter, 34_000_000L));

        TelemetryReport report = TelemetryReport.load(root, PROFILE);

        assertTrue(report.lastNight(ZONE, SLEEP_START, WAKE_END).isEmpty(),
                "readings hours outside the window must not be quoted as if they measured it");
        assertNull(report.coverageForLastNight(ZONE, SLEEP_START, WAKE_END));
    }

    /** Readings inside the window still win over the bracketing ones. */
    @Test
    void inWindowSamplesAnchorTheEndEvenWhenLaterReadingsExist(@TempDir Path root) throws IOException {
        LocalDate today = LocalDate.now(ZONE);
        Instant beforeNight = today.minusDays(1).atTime(22, 50).atZone(ZONE).toInstant();
        Instant duringNight = today.atTime(6, 0).atZone(ZONE).toInstant();
        Instant afterNight = today.atTime(10, 0).atZone(ZONE).toInstant();
        write(root, sample(beforeNight, 1_000L), sample(duringNight, 1_500L), sample(afterNight, 9_999L));

        TelemetryReport.Delta power = power(
                TelemetryReport.load(root, PROFILE).lastNight(ZONE, SLEEP_START, WAKE_END));

        assertNotNull(power);
        assertEquals(1_500L, power.end(), "the in-window reading anchors the end, not the later one");
        assertEquals(500L, power.change());
    }

    /** A measured window whose value did not move is "steady", not "no data". */
    @Test
    void unchangedMetricStillProducesADelta(@TempDir Path root) throws IOException {
        LocalDate today = LocalDate.now(ZONE);
        Instant beforeNight = today.minusDays(1).atTime(22, 52).atZone(ZONE).toInstant();
        Instant afterNight = today.atTime(10, 0).atZone(ZONE).toInstant();
        write(root, sample(beforeNight, 5_000L), sample(afterNight, 5_000L));

        TelemetryReport.Delta power = power(
                TelemetryReport.load(root, PROFILE).lastNight(ZONE, SLEEP_START, WAKE_END));

        assertNotNull(power, "measured-and-unchanged must be distinguishable from no-coverage");
        assertEquals(0L, power.change());
    }

    // ---- helpers ------------------------------------------------------------

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
