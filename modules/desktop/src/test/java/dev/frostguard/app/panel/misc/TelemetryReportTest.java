package dev.frostguard.app.panel.misc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import dev.frostguard.engine.schedule.TelemetrySnapshotSchedule;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;

/**
 * Covers the review fixes: workspace-relative loading, per-profile isolation,
 * malformed-line tolerance, and per-metric (not per-sample) delta anchoring.
 */
class TelemetryReportTest {

    @TempDir
    Path workspace;

    private Path historyFile(long profileId) throws IOException {
        Path dir = workspace.resolve("data").resolve("telemetry")
                .resolve("profiles").resolve(String.valueOf(profileId));
        Files.createDirectories(dir);
        return dir.resolve("history.jsonl");
    }

    private static String row(String capturedAt, String extraFields) {
        return "{\"capturedAt\":\"" + capturedAt + "\",\"profile\":\"Default\"" + extraFields + "}";
    }

    /** Builds a one-sample {@link TelemetryReport} exposing {@code metric} at {@code value} via
     *  {@link TelemetryReport#latestValueOf}. Used by {@code StatisticsLayoutControllerTest} to
     *  drive {@code computeViewState} without needing a live FXML scene graph -- see that test's
     *  class javadoc for why. Own temp directory (not the {@code @TempDir} field) so it works from
     *  a different test class instance. */
    static TelemetryReport reportWithLatest(String metric, long value) {
        try {
            Path tempWorkspace = Files.createTempDirectory("stats-view-state-test");
            Path dir = tempWorkspace.resolve("data").resolve("telemetry").resolve("profiles").resolve("1");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("history.jsonl"),
                    row("2026-08-18T08:00:00Z", ",\"" + metric + "\":" + value) + "\n");
            return TelemetryReport.load(tempWorkspace, 1L);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void loadsFromTheWorkspaceRelativePerProfileFile() throws IOException {
        Files.writeString(historyFile(1L),
                row("2026-08-18T08:00:00Z", ",\"power\":100") + "\n");

        TelemetryReport report = TelemetryReport.load(workspace, 1L);

        assertEquals(1, report.size());
        assertEquals(100L, report.latest().get("power"));
    }

    @Test
    void neverReadsAnotherProfilesFile() throws IOException {
        Files.writeString(historyFile(1L), row("2026-08-18T08:00:00Z", ",\"power\":100") + "\n");
        Files.writeString(historyFile(2L), row("2026-08-18T08:00:00Z", ",\"power\":999") + "\n");

        TelemetryReport profile1 = TelemetryReport.load(workspace, 1L);
        TelemetryReport profile2 = TelemetryReport.load(workspace, 2L);

        assertEquals(100L, profile1.latest().get("power"));
        assertEquals(999L, profile2.latest().get("power"));
    }

    @Test
    void missingProfileFileLoadsEmptyRatherThanThrowing() {
        TelemetryReport report = TelemetryReport.load(workspace, 404L);

        assertTrue(report.isEmpty());
        assertEquals(0, report.size());
    }

    @Test
    void oneCorruptLineNeverSinksTheWholeHistory() throws IOException {
        Files.writeString(historyFile(1L),
                row("2026-08-18T08:00:00Z", ",\"power\":100") + "\n"
                        + "{this is not valid json at all\n"
                        + row("2026-08-18T09:00:00Z", ",\"power\":150") + "\n");

        TelemetryReport report = TelemetryReport.load(workspace, 1L);

        assertEquals(2, report.size());
        assertEquals(150L, report.latest().get("power"));
    }

    @Test
    void deltaAnchorsEachMetricToItsOwnLatestCarryingSample() throws IOException {
        // power is present in every row; gems only in the middle row, so the newest row omits it.
        // Anchoring every metric's end value to that one newest row would drop gems from the window
        // even though an earlier in-window sample carries it.
        Files.writeString(historyFile(1L),
                row("2026-08-18T06:00:00Z", ",\"power\":100,\"gems\":5000") + "\n"
                        + row("2026-08-18T07:00:00Z", ",\"power\":110,\"gems\":5200") + "\n"
                        + row("2026-08-18T08:00:00Z", ",\"power\":120") + "\n");

        TelemetryReport report = TelemetryReport.load(workspace, 1L);
        List<TelemetryReport.Delta> deltas = report.deltaOverWindow(
                Instant.parse("2026-08-18T00:00:00Z"), Instant.parse("2026-08-18T23:59:59Z"));

        TelemetryReport.Delta power = findMetric(deltas, "power");
        assertEquals(100L, power.start());
        assertEquals(120L, power.end());
        assertEquals(20L, power.change());

        TelemetryReport.Delta gems = findMetric(deltas, "gems");
        assertEquals(5000L, gems.start());
        assertEquals(5200L, gems.end());
        assertEquals(200L, gems.change());
    }

    @Test
    void unchangedMetricStillProducesAZeroChangeDeltaRatherThanBeingOmitted() throws IOException {
        // A genuine zero-change measurement must not vanish -- that is indistinguishable from "no
        // data ever captured for this metric". A real start AND end sample both existing (even with
        // equal values) is measured coverage and must say so; only a genuinely missing pair should
        // produce no Delta at all.
        Files.writeString(historyFile(1L),
                row("2026-08-18T06:00:00Z", ",\"power\":100") + "\n"
                        + row("2026-08-18T08:00:00Z", ",\"power\":100") + "\n");

        TelemetryReport report = TelemetryReport.load(workspace, 1L);
        List<TelemetryReport.Delta> deltas = report.deltaOverWindow(
                Instant.parse("2026-08-18T00:00:00Z"), Instant.parse("2026-08-18T23:59:59Z"));

        assertEquals(1, deltas.size());
        TelemetryReport.Delta d = deltas.get(0);
        assertEquals("power", d.metric());
        assertEquals(100L, d.start());
        assertEquals(100L, d.end());
        assertEquals(0L, d.change());
    }

    @Test
    void metricWithNoUsableSamplePairProducesNoDeltaAtAll() throws IOException {
        // The genuine "no data" case: a metric that was never captured at all must still be
        // omitted -- distinct from unchangedMetricStillProducesAZeroChangeDeltaRatherThanBeingOmitted
        // above, which has real samples that just happen to be equal.
        Files.writeString(historyFile(1L),
                row("2026-08-18T06:00:00Z", ",\"power\":100") + "\n");

        TelemetryReport report = TelemetryReport.load(workspace, 1L);
        List<TelemetryReport.Delta> deltas = report.deltaOverWindow(
                Instant.parse("2026-08-19T00:00:00Z"), Instant.parse("2026-08-19T23:59:59Z"));

        assertTrue(deltas.isEmpty(), "a metric with no sample inside or before the window has nothing to report");
    }

    @Test
    void changedMetricStillProducesTheRealDelta() throws IOException {
        // Guards against a regression that makes everything look "unchanged" -- a real change must
        // still come through with its real start/end/change values, not just zero-change coverage.
        Files.writeString(historyFile(1L),
                row("2026-08-18T06:00:00Z", ",\"power\":100") + "\n"
                        + row("2026-08-18T08:00:00Z", ",\"power\":150") + "\n");

        TelemetryReport report = TelemetryReport.load(workspace, 1L);
        List<TelemetryReport.Delta> deltas = report.deltaOverWindow(
                Instant.parse("2026-08-18T00:00:00Z"), Instant.parse("2026-08-18T23:59:59Z"));

        assertEquals(1, deltas.size());
        TelemetryReport.Delta d = deltas.get(0);
        assertEquals(100L, d.start());
        assertEquals(150L, d.end());
        assertEquals(50L, d.change());
    }

    @Test
    void narrowWindowUsesTheLastValueBeforeItOpenedAsBaseline() throws IOException {
        // "Past Hour" against an hourly writer normally contains exactly one
        // in-window sample, so the old first-in-window baseline made start==end and the delta
        // always read as zero -- even though a real earlier sample (just outside the window)
        // proves the value actually changed. One sample well before the window, one sample
        // inside a narrow 1-hour window -- this is exactly that shape.
        Files.writeString(historyFile(1L),
                row("2026-08-18T05:00:00Z", ",\"power\":100") + "\n"   // well before the window
                        + row("2026-08-18T08:50:00Z", ",\"power\":140") + "\n"); // the only in-window sample

        TelemetryReport report = TelemetryReport.load(workspace, 1L);
        List<TelemetryReport.Delta> deltas = report.deltaOverWindow(
                Instant.parse("2026-08-18T08:00:00Z"), Instant.parse("2026-08-18T09:00:00Z"));

        TelemetryReport.Delta power = findMetric(deltas, "power");
        assertEquals(100L, power.start(), "baseline should be the last known value before the window opened");
        assertEquals(140L, power.end());
        assertEquals(40L, power.change());
    }

    @Test
    void metricWithNoPreWindowValueFallsBackToFirstInWindowSample() throws IOException {
        // A metric that simply didn't exist before the window (e.g. newly added capture) has no
        // pre-window baseline to anchor to -- falls back to the original first-in-window behavior
        // instead of never showing a delta at all.
        Files.writeString(historyFile(1L),
                row("2026-08-18T08:10:00Z", ",\"gems\":5000") + "\n"
                        + row("2026-08-18T08:50:00Z", ",\"gems\":5300") + "\n");

        TelemetryReport report = TelemetryReport.load(workspace, 1L);
        List<TelemetryReport.Delta> deltas = report.deltaOverWindow(
                Instant.parse("2026-08-18T08:00:00Z"), Instant.parse("2026-08-18T09:00:00Z"));

        TelemetryReport.Delta gems = findMetric(deltas, "gems");
        assertEquals(5000L, gems.start());
        assertEquals(5300L, gems.end());
        assertEquals(300L, gems.change());
    }

    @Test
    void sampleExactlyAtWindowStartIsUsedAsTheBaseline() throws IOException {
        // A baseline accepting only samples STRICTLY before `from` would skip a sample landing
        // exactly on the boundary -- e.g. the writer firing right at the window's start anchor --
        // in favour of an older one, overcounting activity from before the window opened.
        Files.writeString(historyFile(1L),
                row("2026-08-18T07:00:00Z", ",\"power\":50") + "\n"    // older, should be ignored
                        + row("2026-08-18T08:00:00Z", ",\"power\":100") + "\n" // exactly at the boundary
                        + row("2026-08-18T08:50:00Z", ",\"power\":140") + "\n");

        TelemetryReport report = TelemetryReport.load(workspace, 1L);
        List<TelemetryReport.Delta> deltas = report.deltaOverWindow(
                Instant.parse("2026-08-18T08:00:00Z"), Instant.parse("2026-08-18T09:00:00Z"));

        TelemetryReport.Delta power = findMetric(deltas, "power");
        assertEquals(100L, power.start(), "the exact-boundary sample should be the baseline, not an older one");
        assertEquals(140L, power.end());
        assertEquals(40L, power.change());
    }

    @Test
    void coverageForWindowReportsThePerMetricTimestampsActuallyUsed() throws IOException {
        // power has a pre-window baseline; gems doesn't (first-ever sample lands inside the
        // window) -- coverage should span the widest range actually used across both metrics, not
        // some unrelated global first/last sample pair.
        Files.writeString(historyFile(1L),
                row("2026-08-18T07:00:00Z", ",\"power\":100") + "\n"                  // power baseline
                        + row("2026-08-18T08:10:00Z", ",\"power\":110,\"gems\":5000") + "\n" // gems baseline (fallback)
                        + row("2026-08-18T08:50:00Z", ",\"power\":140,\"gems\":5300") + "\n"); // end for both

        TelemetryReport report = TelemetryReport.load(workspace, 1L);
        TelemetryReport.Coverage coverage = report.coverageForWindow(
                Instant.parse("2026-08-18T08:00:00Z"), Instant.parse("2026-08-18T09:00:00Z"));

        assertEquals(Instant.parse("2026-08-18T07:00:00Z"), coverage.actualFrom(),
                "should reflect power's real pre-window baseline, not the in-window gems baseline");
        assertEquals(Instant.parse("2026-08-18T08:50:00Z"), coverage.actualTo());
    }

    @Test
    void coverageForWindowIsNullWhenThereIsNoUsableSamplePair() throws IOException {
        // The genuine "no data" case -- nothing in or before the window at all.
        Files.writeString(historyFile(1L), row("2026-08-19T08:10:00Z", ",\"power\":100") + "\n");

        TelemetryReport report = TelemetryReport.load(workspace, 1L);
        TelemetryReport.Coverage coverage = report.coverageForWindow(
                Instant.parse("2026-08-18T08:00:00Z"), Instant.parse("2026-08-18T09:00:00Z"));

        assertEquals(null, coverage);
    }

    @Test
    void coverageForWindowIsNotNullForAGenuinelyUnchangedButMeasuredWindow() throws IOException {
        // coverageForWindow() derives its answer from deltaOverWindow(), so a window holding two
        // real, valid samples that happen to carry the same value must not report null coverage --
        // on the Statistics tab that is indistinguishable from "not enough data", and the UI's
        // fallback for THAT case (a raw current value with no delta framing) is what reads as
        // "gained 24 million power".
        Files.writeString(historyFile(1L),
                row("2026-08-18T08:10:00Z", ",\"power\":100") + "\n"
                        + row("2026-08-18T08:50:00Z", ",\"power\":100") + "\n"); // unchanged, but real

        TelemetryReport report = TelemetryReport.load(workspace, 1L);
        TelemetryReport.Coverage coverage = report.coverageForWindow(
                Instant.parse("2026-08-18T08:00:00Z"), Instant.parse("2026-08-18T09:00:00Z"));

        assertEquals(Instant.parse("2026-08-18T08:10:00Z"), coverage.actualFrom());
        assertEquals(Instant.parse("2026-08-18T08:50:00Z"), coverage.actualTo());
    }

    @Test
    void coverageForWindowReportsTheRealRangeForAChangedWindow() throws IOException {
        Files.writeString(historyFile(1L),
                row("2026-08-18T08:10:00Z", ",\"power\":100") + "\n"
                        + row("2026-08-18T08:50:00Z", ",\"power\":150") + "\n");

        TelemetryReport report = TelemetryReport.load(workspace, 1L);
        TelemetryReport.Coverage coverage = report.coverageForWindow(
                Instant.parse("2026-08-18T08:00:00Z"), Instant.parse("2026-08-18T09:00:00Z"));

        assertEquals(Instant.parse("2026-08-18T08:10:00Z"), coverage.actualFrom());
        assertEquals(Instant.parse("2026-08-18T08:50:00Z"), coverage.actualTo());
    }

    @Test
    void activityDeltaUsesTheLastActivityBearingSampleAtOrBeforeTheWindowAsBaseline() throws IOException {
        // ActivityOverWindow() only ever looked at samples AT OR AFTER
        // `from`, so with the hourly writer, a narrow window (e.g. "Past Hour") normally contains
        // exactly one activity-bearing sample -- start and end are the same row, and every activity
        // count reads as zero even though real activity happened just before the window opened.
        Files.writeString(historyFile(1L),
                row("2026-08-18T07:00:00Z", ",\"ctr.Gather Marches Deployed\":3") + "\n" // pre-window baseline
                        + row("2026-08-18T08:50:00Z", ",\"ctr.Gather Marches Deployed\":7") + "\n"); // the only in-window sample

        TelemetryReport report = TelemetryReport.load(workspace, 1L);
        List<TelemetryReport.Activity> activity = report.activityOverWindow(
                Instant.parse("2026-08-18T08:00:00Z"), Instant.parse("2026-08-18T09:00:00Z"));

        TelemetryReport.Activity gather = activity.stream()
                .filter(a -> a.label().equals("Gather marches")).findFirst()
                .orElseThrow(() -> new AssertionError("expected a Gather marches delta"));
        assertEquals(4L, gather.change(), "baseline should be the pre-window sample (3), not the in-window one (7)");
    }

    @Test
    void activityDeltaFallsBackToTheEarliestInWindowSampleWhenNothingPredatesTheWindow() throws IOException {
        // No activity-bearing sample exists before the window at all (activity capture just
        // started, or these are the very first rows ever) -- falls back to the earliest in-window
        // sample so the count isn't withheld indefinitely.
        Files.writeString(historyFile(1L),
                row("2026-08-18T08:10:00Z", ",\"ctr.Gather Marches Deployed\":3") + "\n"
                        + row("2026-08-18T08:50:00Z", ",\"ctr.Gather Marches Deployed\":7") + "\n");

        TelemetryReport report = TelemetryReport.load(workspace, 1L);
        List<TelemetryReport.Activity> activity = report.activityOverWindow(
                Instant.parse("2026-08-18T08:00:00Z"), Instant.parse("2026-08-18T09:00:00Z"));

        TelemetryReport.Activity gather = activity.stream()
                .filter(a -> a.label().equals("Gather marches")).findFirst()
                .orElseThrow(() -> new AssertionError("expected a Gather marches delta"));
        assertEquals(4L, gather.change());
    }

    @Test
    void latestValueOfFindsAMetricsOwnMostRecentValueIndependentlyOfTheGlobalLatestSample() throws IOException {
        // Reading the Statistics tab's "current" card values off the single latest overall sample
        // loses any metric missing from just that one row (gems, here), even though an earlier
        // sample carries a perfectly good value.
        Files.writeString(historyFile(1L),
                row("2026-08-18T07:00:00Z", ",\"power\":100,\"gems\":5000") + "\n"
                        + row("2026-08-18T08:00:00Z", ",\"power\":120") + "\n"); // gems missing from the newest row

        TelemetryReport report = TelemetryReport.load(workspace, 1L);

        assertEquals(120L, report.latestValueOf("power"));
        assertEquals(5000L, report.latestValueOf("gems"), "should find gems' own last value, not fall through to null");
    }

    @Test
    void latestValueOfIsNullForAMetricNeverCaptured() throws IOException {
        Files.writeString(historyFile(1L), row("2026-08-18T08:00:00Z", ",\"power\":100") + "\n");

        TelemetryReport report = TelemetryReport.load(workspace, 1L);

        assertEquals(null, report.latestValueOf("gems"));
    }

    @Test
    void activityCounterMissingFromTheChosenBaselineSampleIsStillFoundIndependently() throws IOException {
        // One shared start/end sample pair across every counter breaks here: the sample nearest the
        // window start doesn't carry "ctr.Arena Battles Won" at all (a transient write gap), but an
        // EARLIER sample does. The counter must find its own real baseline rather than being
        // silently omitted because the row nearest the boundary happened to be missing it.
        Files.writeString(historyFile(1L),
                row("2026-08-18T06:00:00Z", ",\"ctr.Arena Battles Won\":10,\"ctr.Gather Marches Deployed\":1") + "\n"
                        + row("2026-08-18T08:05:00Z", ",\"ctr.Gather Marches Deployed\":2") + "\n" // Arena missing here
                        + row("2026-08-18T08:50:00Z", ",\"ctr.Arena Battles Won\":14,\"ctr.Gather Marches Deployed\":3") + "\n");

        TelemetryReport report = TelemetryReport.load(workspace, 1L);
        List<TelemetryReport.Activity> activity = report.activityOverWindow(
                Instant.parse("2026-08-18T08:00:00Z"), Instant.parse("2026-08-18T09:00:00Z"));

        TelemetryReport.Activity arena = activity.stream()
                .filter(a -> a.label().equals("Arena wins")).findFirst()
                .orElseThrow(() -> new AssertionError("Arena wins should still be found despite the gap"));
        assertEquals(4L, arena.change(), "baseline 10 -> end 14, found independently of the counter that has a gap");
    }

    @Test
    void newlyAppearingCounterWithNoPreWindowValueFallsBackToTheEarliestInWindowSample() throws IOException {
        // A counter that genuinely didn't exist before the window opened (feature just started
        // being tracked) -- same fallback deltaOverWindow already gives resource metrics.
        Files.writeString(historyFile(1L),
                row("2026-08-18T08:10:00Z", ",\"ctr.Pet Adventure Chests\":2") + "\n"
                        + row("2026-08-18T08:50:00Z", ",\"ctr.Pet Adventure Chests\":5") + "\n");

        TelemetryReport report = TelemetryReport.load(workspace, 1L);
        List<TelemetryReport.Activity> activity = report.activityOverWindow(
                Instant.parse("2026-08-18T08:00:00Z"), Instant.parse("2026-08-18T09:00:00Z"));

        TelemetryReport.Activity pet = activity.stream()
                .filter(a -> a.label().equals("Pet chests")).findFirst()
                .orElseThrow(() -> new AssertionError("expected a Pet chests delta"));
        assertEquals(3L, pet.change());
    }

    @Test
    void counterNeverCapturedAtAllIsOmittedRatherThanErroring() throws IOException {
        Files.writeString(historyFile(1L),
                row("2026-08-18T08:10:00Z", ",\"ctr.Gather Marches Deployed\":1") + "\n"
                        + row("2026-08-18T08:50:00Z", ",\"ctr.Gather Marches Deployed\":2") + "\n");

        TelemetryReport report = TelemetryReport.load(workspace, 1L);
        List<TelemetryReport.Activity> activity = report.activityOverWindow(
                Instant.parse("2026-08-18T08:00:00Z"), Instant.parse("2026-08-18T09:00:00Z"));

        assertTrue(activity.stream().noneMatch(a -> a.label().equals("Arena wins")),
                "a counter with no data anywhere must not appear at all");
    }

    @Test
    void resetCounterReportsTheRealPostResetActivityInsteadOfSilenceOrANegativeNumber() throws IOException {
        // "decreasing/reset counters are silently dropped." A counter
        // that resets mid-window (game-side rollover, a re-seeded save) produces end < start; the
        // real, known amount of activity since the reset is the end value itself -- reporting that
        // is more honest than a misleading negative number or hiding real activity entirely.
        Files.writeString(historyFile(1L),
                row("2026-08-18T08:10:00Z", ",\"ctr.Storehouse Chests Opened\":50") + "\n" // pre-reset baseline
                        + row("2026-08-18T08:50:00Z", ",\"ctr.Storehouse Chests Opened\":10") + "\n"); // reset, then 10 more

        TelemetryReport report = TelemetryReport.load(workspace, 1L);
        List<TelemetryReport.Activity> activity = report.activityOverWindow(
                Instant.parse("2026-08-18T08:00:00Z"), Instant.parse("2026-08-18T09:00:00Z"));

        TelemetryReport.Activity storehouse = activity.stream()
                .filter(a -> a.label().equals("Storehouse chests")).findFirst()
                .orElseThrow(() -> new AssertionError("a reset counter with real post-reset activity must still be reported"));
        assertEquals(10L, storehouse.change(), "should report the real post-reset count, not -40 and not silence");
    }

    @Test
    void coverageForTotalReflectsTheRealPerMetricRangeNotTheGlobalFirstLastSample() throws IOException {
        // Returning the report's global first/last sample unconditionally overstates the range for
        // a metric not captured at either boundary -- gems here exists only in the middle sample,
        // so its real evidence range is narrower than the report's overall span.
        Files.writeString(historyFile(1L),
                row("2026-08-18T06:00:00Z", ",\"power\":100") + "\n" // no gems
                        + row("2026-08-18T07:00:00Z", ",\"power\":110,\"gems\":5000") + "\n"
                        + row("2026-08-18T08:00:00Z", ",\"power\":120") + "\n"); // no gems

        TelemetryReport report = TelemetryReport.load(workspace, 1L);
        TelemetryReport.Coverage coverage = report.coverageForTotal();

        // power spans the full report range, so the envelope across all metrics' deltas still
        // reaches the true global bounds here -- the real behavioral change is that this now comes
        // from the same per-metric envelope coverageForWindow already uses, not a hardcoded pair.
        assertEquals(Instant.parse("2026-08-18T06:00:00Z"), coverage.actualFrom());
        assertEquals(Instant.parse("2026-08-18T08:00:00Z"), coverage.actualTo());
    }

    @Test
    void coverageForTotalIsNullWithFewerThanTwoSamples() throws IOException {
        Files.writeString(historyFile(1L), row("2026-08-18T08:00:00Z", ",\"power\":100") + "\n");

        TelemetryReport report = TelemetryReport.load(workspace, 1L);

        assertEquals(null, report.coverageForTotal());
    }

    private static TelemetryReport.Delta findMetric(List<TelemetryReport.Delta> deltas, String metric) {
        return deltas.stream().filter(d -> d.metric().equals(metric)).findFirst()
                .orElseThrow(() -> new AssertionError("No delta for metric " + metric));
    }

    @Test
    void theWakeAnchorSnapshotLandsInsideTheLastNightWindow() throws IOException {
        // The writer takes a snapshot at TelemetrySnapshotSchedule.WAKE_ANCHOR (08:30), but a real
        // capture was observed landing at 08:30:43 -- past an exact 08:30:00 cutoff. If the
        // reader's window ended precisely on the anchor, that snapshot would fall outside the very
        // window it exists to bookend, and "last night" would silently end at the prior hourly
        // sample instead. That is a wrong answer rather than a visible failure, so it is asserted
        // behaviourally here rather than by reading the grace constant.
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        String bedtime = today.minusDays(1) + "T23:00:00Z";
        String wakeCapture = today + "T08:30:43Z";

        Files.writeString(historyFile(1L),
                row(bedtime, ",\"power\":100") + "\n"
                        + row(wakeCapture, ",\"power\":160") + "\n");

        TelemetryReport report = TelemetryReport.load(workspace, 1L);
        List<TelemetryReport.Delta> deltas =
                report.lastNight(ZoneOffset.UTC, LocalTime.of(23, 0), TelemetrySnapshotSchedule.WAKE_ANCHOR);

        assertEquals(1, deltas.size(),
                "the 08:30:43 wake snapshot fell outside the last-night window -- the reader's grace "
                        + "window no longer covers the writer's wake anchor");
        assertEquals(60L, deltas.get(0).change());
    }

}
