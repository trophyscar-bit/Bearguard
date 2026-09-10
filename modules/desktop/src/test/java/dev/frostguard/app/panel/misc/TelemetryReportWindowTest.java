package dev.frostguard.app.panel.misc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins how {@link TelemetryReport} values a window against a sparse sample series.
 *
 * <p>Three regressions live here, and the first two pull in opposite directions.</p>
 *
 * <p>bg_telemetry snapshots roughly every two hours, so a window often holds readings but none at
 * its opening edge. Measuring from the first reading INSIDE the window silently drops whatever
 * happened before it, so a reading just outside may stand in for the edge.</p>
 *
 * <p>That reach is one-directional. An earlier revision also reached FORWARD past the window's
 * end, and applied to a night the machine spent crashed it grabbed the 11:24 AM reading and
 * reported an hour of that morning's botting as having happened overnight. A window with no
 * readings in it must report that it has none.</p>
 *
 * <p>And "last night" has to mean a night that finished. Before the wake time the night in
 * progress is not the subject -- the one that ended yesterday morning is.</p>
 *
 * <p>Fixtures are placed relative to the window the production rule actually resolves, so these
 * behave the same whether they run at noon or at ten past midnight -- which is exactly the hour
 * the night-window fault only ever showed up at.</p>
 */
class TelemetryReportWindowTest {

    private static final ZoneId ZONE = ZoneId.of("America/New_York");
    private static final LocalTime SLEEP_START = LocalTime.of(23, 0);
    private static final LocalTime WAKE_END = LocalTime.of(8, 30);
    private static final long PROFILE = 1L;

    private static TelemetryReport.Window night() {
        return TelemetryReport.nightWindow(ZONE, SLEEP_START, WAKE_END, LocalDateTime.now(ZONE));
    }

    /** {@code minutes} before the night opens. */
    private static Instant before(long minutes) {
        return night().from().minus(minutes, ChronoUnit.MINUTES);
    }

    /** {@code minutes} after the night opens (and so inside it, for anything under 9.5 hours). */
    private static Instant into(long minutes) {
        return night().from().plus(minutes, ChronoUnit.MINUTES);
    }

    /** {@code minutes} after the night closes. */
    private static Instant after(long minutes) {
        return night().to().plus(minutes, ChronoUnit.MINUTES);
    }

    // ---- which night "last night" means -------------------------------------

    /**
     * Opened at ten past midnight, the old rule ran the window from seventy minutes ago to eight
     * hours into the future and reported the handful of minutes since 23:00 as the whole night.
     * Checked live at 00:02 it measured seventeen minutes and every figure read steady.
     */
    @Test
    void beforeTheWakeTimeTheNightInProgressIsNotTheSubject() {
        LocalDateTime tenPastMidnight = LocalDateTime.of(2026, 9, 10, 0, 10);

        TelemetryReport.Window w = TelemetryReport.nightWindow(ZONE, SLEEP_START, WAKE_END, tenPastMidnight);

        assertEquals(LocalDateTime.of(2026, 9, 8, 23, 0).atZone(ZONE).toInstant(), w.from(),
                "the night that finished, not the one an hour old");
        assertEquals(LocalDateTime.of(2026, 9, 9, 8, 50).atZone(ZONE).toInstant(), w.to());
    }

    /** After the wake time, the night that just ended is today's. */
    @Test
    void afterTheWakeTimeTheNightThatJustEndedIsTodays() {
        LocalDateTime lunchtime = LocalDateTime.of(2026, 9, 10, 12, 0);

        TelemetryReport.Window w = TelemetryReport.nightWindow(ZONE, SLEEP_START, WAKE_END, lunchtime);

        assertEquals(LocalDateTime.of(2026, 9, 9, 23, 0).atZone(ZONE).toInstant(), w.from());
        assertEquals(LocalDateTime.of(2026, 9, 10, 8, 50).atZone(ZONE).toInstant(), w.to());
    }

    /** The wake time plus its grace is the changeover, not the wake time itself. */
    @Test
    void theGraceMinutesCountAsStillBeingInTheNight() {
        LocalDateTime justInsideGrace = LocalDateTime.of(2026, 9, 10, 8, 40);

        TelemetryReport.Window w = TelemetryReport.nightWindow(ZONE, SLEEP_START, WAKE_END, justInsideGrace);

        assertEquals(LocalDateTime.of(2026, 9, 9, 8, 50).atZone(ZONE).toInstant(), w.to(),
                "08:40 is before the 08:50 close, so that night has not finished");
    }

    // ---- what the window measures -------------------------------------------

    /** The real Aug 29->30 shape: the bot died at 23:00 and nothing was recorded until 11:24 AM. */
    @Test
    void nightWithNoReadingsInItReportsNothingRatherThanTheMorningAfter(@TempDir Path root) throws IOException {
        write(root, sample(before(8), 34_108_399L), sample(after(154), 34_134_244L));

        TelemetryReport report = TelemetryReport.load(root, PROFILE);

        assertTrue(report.lastNight(ZONE, SLEEP_START, WAKE_END).isEmpty(),
                "a reading taken after the window closed describes the morning, not the night");
        assertNull(report.coverageForLastNight(ZONE, SLEEP_START, WAKE_END));
        assertTrue(report.activityLastNight(ZONE, SLEEP_START, WAKE_END).isEmpty());
    }

    /** And it can say WHY there is nothing, rather than "not enough samples yet". */
    @Test
    void aSilentWindowNamesWhenTheBotLastAndNextReported(@TempDir Path root) throws IOException {
        Instant lastReport = before(8);
        Instant nextReport = after(154);
        write(root, sample(lastReport, 34_108_399L), sample(nextReport, 34_134_244L));

        TelemetryReport.Coverage silence = TelemetryReport.load(root, PROFILE)
                .silenceForLastNight(ZONE, SLEEP_START, WAKE_END);

        assertNotNull(silence);
        assertEquals(lastReport, silence.actualFrom());
        assertEquals(nextReport, silence.actualTo());
    }

    /** A reading just outside the opening edge stands in for it. */
    @Test
    void baselineComesFromTheReadingBeforeTheWindowOpened(@TempDir Path root) throws IOException {
        Instant baseline = before(8);
        Instant duringNight = into(420);
        write(root, sample(baseline, 1_000L), sample(duringNight, 1_500L));

        TelemetryReport report = TelemetryReport.load(root, PROFILE);
        TelemetryReport.Delta power = power(report.lastNight(ZONE, SLEEP_START, WAKE_END));

        assertNotNull(power, "one in-window reading plus a baseline just before it is measurable");
        assertEquals(1_000L, power.start(), "the pre-window reading is the baseline");
        assertEquals(1_500L, power.end());
        assertEquals(500L, power.change());

        TelemetryReport.Coverage coverage = report.coverageForLastNight(ZONE, SLEEP_START, WAKE_END);
        assertNotNull(coverage);
        assertEquals(baseline, coverage.actualFrom());
        assertEquals(duringNight, coverage.actualTo());
        assertNull(report.silenceForLastNight(ZONE, SLEEP_START, WAKE_END),
                "a window with data in it is not silent");
    }

    /**
     * Whichever reading sits nearer the opening edge wins. Taking the earlier one regardless is
     * what made last night begin at 9:06 PM: the readings either side of 23:00 were 21:06 and
     * 23:01, and nearly two hours of the evening were counted as part of the night.
     */
    @Test
    void theBaselineIsWhicheverReadingSitsNearerTheOpeningEdge(@TempDir Path root) throws IOException {
        Instant nearlyTwoHoursEarly = before(114);
        Instant oneMinuteIn = into(1);
        Instant morning = into(560);
        write(root, sample(nearlyTwoHoursEarly, 39_074_126L), sample(oneMinuteIn, 39_163_526L),
                sample(morning, 39_279_506L));

        TelemetryReport.Delta power = power(
                TelemetryReport.load(root, PROFILE).lastNight(ZONE, SLEEP_START, WAKE_END));

        assertNotNull(power);
        assertEquals(39_163_526L, power.start(), "the 23:01 reading, not the 21:06 one");
        assertEquals(115_980L, power.change());
    }

    /** The last in-window reading closes the window, never a later one. */
    @Test
    void endNeverBorrowsAReadingTakenAfterTheWindowClosed(@TempDir Path root) throws IOException {
        write(root, sample(before(10), 1_000L), sample(into(420), 1_500L), sample(after(70), 9_999L));

        TelemetryReport.Delta power = power(
                TelemetryReport.load(root, PROFILE).lastNight(ZONE, SLEEP_START, WAKE_END));

        assertNotNull(power);
        assertEquals(1_500L, power.end(), "the reading after the wake time is outside the night");
        assertEquals(500L, power.change());
    }

    /** A baseline hours older than the window is too stale to stand in for its opening edge. */
    @Test
    void staleBaselineBeyondTheReachIsNotUsed(@TempDir Path root) throws IOException {
        write(root, sample(before(660), 1_000L), sample(into(420), 5_000L));

        TelemetryReport.Delta power = power(
                TelemetryReport.load(root, PROFILE).lastNight(ZONE, SLEEP_START, WAKE_END));

        assertNull(power, "an eleven-hour-old reading does not describe the start of last night");
    }

    /** A measured window whose value did not move is "steady", not "no data". */
    @Test
    void unchangedMetricStillProducesADelta(@TempDir Path root) throws IOException {
        write(root, sample(before(8), 5_000L), sample(into(300), 5_000L));

        TelemetryReport.Delta power = power(
                TelemetryReport.load(root, PROFILE).lastNight(ZONE, SLEEP_START, WAKE_END));

        assertNotNull(power, "measured-and-unchanged must be distinguishable from no-coverage");
        assertEquals(0L, power.change());
    }

    // ---- misreads -----------------------------------------------------------

    /**
     * The 9/1 general-speedup row: 2434, then a single frame reading 30, then 2664. Dropping
     * forty hours of speedups and regaining forty-four in the next thirty-nine minutes is not a
     * thing that happens; it is one bad OCR frame, and it landed on the window's baseline.
     */
    @Test
    void anIsolatedMisreadIsNotUsedAsAWindowBaseline(@TempDir Path root) throws IOException {
        write(root,
                speedup(before(254), 2434L), speedup(before(39), 30L),
                speedup(into(1), 2664L), speedup(into(560), 2751L));

        TelemetryReport.Delta general = metric("sp_general",
                TelemetryReport.load(root, PROFILE).lastNight(ZONE, SLEEP_START, WAKE_END));

        assertNotNull(general);
        assertEquals(2664L, general.start(), "the 30 reading must not anchor the night");
        assertEquals(87L, general.change());
    }

    /** A real drop is held by the readings after it, so it is kept. */
    @Test
    void aSustainedDropIsRealAndSurvives(@TempDir Path root) throws IOException {
        write(root, speedup(before(20), 2268L), speedup(into(120), 155L), speedup(into(300), 160L));

        TelemetryReport.Delta general = metric("sp_general",
                TelemetryReport.load(root, PROFILE).lastNight(ZONE, SLEEP_START, WAKE_END));

        assertNotNull(general);
        assertEquals(2268L, general.start());
        assertEquals(160L, general.end(), "spending a stockpile does not revert, so it is real");
    }

    /** Only the offending field is dropped -- the rest of that row is still good data. */
    @Test
    void despikingDropsOneFieldNotTheWholeReading(@TempDir Path root) throws IOException {
        write(root,
                "{\"capturedAt\":\"" + before(60) + "\",\"power\":1000,\"sp_general\":2400}",
                "{\"capturedAt\":\"" + into(120) + "\",\"power\":1200,\"sp_general\":30}",
                "{\"capturedAt\":\"" + into(300) + "\",\"power\":1400,\"sp_general\":2500}");

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
