package dev.frostguard.app.panel.misc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.frostguard.api.runtime.WorkspacePaths;

/**
 * Reads the telemetry history that {@code bg_telemetry} appends to
 * {@code data/telemetry/profiles/<id>/history.jsonl} and turns it into the "what did the bot
 * earn" reports the Statistics tab shows.
 *
 * <p>He wants the Statistics page to answer real questions —
 * "how many resources did I gather overnight", "how much power / how many gems
 * did botting earn me today / this week / total" — instead of run counts. Each
 * report is a delta between the snapshot at the start of a window and the most
 * recent one, so it reads directly off the same history the bot already logs.</p>
 *
 * <p>previously read {@code telemetry/history.jsonl} off
 * {@code user.dir} and filtered by profile NAME within one shared file -- and a row with no
 * "profile" field (or a null caller-supplied name) was accepted for every profile, not rejected.
 * Now that {@code bg_telemetry} writes one file per profile ID under the workspace, {@link #load}
 * just opens that profile's own file directly. There is nothing left to filter, so that bug class
 * is gone by construction rather than patched.</p>
 */
public final class TelemetryReport {

    /**
     * The tracked metrics, in display order. Key matches the JSONL field name.
     * The five {@code sp_*} entries are speedup durations in MINUTES (not counts) —
     * StatisticsLayoutController formats those back to "6d 3h" for display.
     */
    public static final List<String> METRICS = List.of("power", "gems", "meat", "wood", "coal", "iron",
            "steel", "sp_general", "sp_training", "sp_construction", "sp_research", "sp_healing");

    /** One telemetry sample. Any metric may be null when that read was unavailable. */
    public record Sample(Instant at, Map<String, Long> values, Map<String, Long> activity) {
        public Long get(String metric) { return values.get(metric); }
    }

    /** A named activity total's change over a window, e.g. "Intel missions": +27. */
    public record Activity(String label, long change) {}

    /** A start→end change in one metric over a window. */
    public record Delta(String metric, Long start, Long end, Long change) {}

    private final List<Sample> samples;

    private TelemetryReport(List<Sample> samples) {
        this.samples = samples;
    }

    /**
     * Loads and sorts one profile's samples (oldest first) from its own workspace-local file.
     * Never throws — returns empty on any problem (no file yet, unreadable, all lines corrupt).
     */
    public static TelemetryReport load(long profileId) {
        return load(WorkspacePaths.current().root(), profileId);
    }

    /** Overload taking an explicit workspace root, for tests that don't want a real installed
     *  workspace on disk. */
    public static TelemetryReport load(Path workspaceRoot, long profileId) {
        List<Sample> out = new ArrayList<>();
        Path file = workspaceRoot.resolve("data").resolve("telemetry")
                .resolve("profiles").resolve(String.valueOf(profileId)).resolve("history.jsonl");
        if (!Files.isReadable(file)) {
            return new TelemetryReport(out);
        }
        ObjectMapper mapper = new ObjectMapper();
        try {
            for (String line : Files.readAllLines(file)) {
                if (line == null || line.isBlank()) continue;
                JsonNode node;
                try {
                    node = mapper.readTree(line);
                } catch (IOException badLine) {
                    continue; // one corrupt line never sinks the whole history
                }
                Instant at = parseInstant(node.path("capturedAt").asText(null));
                if (at == null) continue;
                Map<String, Long> values = new LinkedHashMap<>();
                for (String metric : METRICS) {
                    JsonNode v = node.get(metric);
                    if (v != null && v.isNumber()) {
                        values.put(metric, v.asLong());
                    }
                }
                // Activity fields are flattened as "run.<Task>" / "ctr.<Counter>".
                Map<String, Long> activity = new LinkedHashMap<>();
                node.fields().forEachRemaining(f -> {
                    String k = f.getKey();
                    if ((k.startsWith("run.") || k.startsWith("ctr.")) && f.getValue().isNumber()) {
                        activity.put(k, f.getValue().asLong());
                    }
                });
                out.add(new Sample(at, values, activity));
            }
        } catch (IOException e) {
            return new TelemetryReport(new ArrayList<>());
        }
        out.sort((a, b) -> a.at().compareTo(b.at()));
        return new TelemetryReport(despike(out));
    }

    /**
     * Discards single-sample readings that a metric's own neighbours contradict.
     *
     * <p>Every metric here is OCR'd off the game screen, and OCR occasionally returns one bad
     * frame. On 9/1 the 22:21 snapshot read general speedups as 30 minutes, sitting between
     * readings of 2434 and 2664 -- a value that would mean spending forty hours of speedups and
     * regaining forty-four in the following thirty-nine minutes. A single bad frame is normally
     * harmless noise in the middle of a series, but window anchoring reads endpoints, and that
     * row happened to be the last one before 23:00, so it became the baseline for the whole night
     * and reported "+1d 21h 21m" of general speedup gained.</p>
     *
     * <p>The test is deliberately narrow: a reading is dropped only when it disagrees sharply with
     * BOTH neighbours AND those neighbours agree with each other. That is the signature of a
     * misread -- an excursion that immediately reverts. Genuinely spending a stockpile does not
     * revert, so a real drop is held by the reading after it and survives untouched. Only the one
     * offending field is dropped; the rest of that row is still good data.</p>
     */
    private static List<Sample> despike(List<Sample> ordered) {
        if (ordered.size() < 3) {
            return ordered;
        }
        List<Sample> cleaned = new ArrayList<>(ordered);
        for (String metric : METRICS) {
            for (int i = 1; i < cleaned.size() - 1; i++) {
                Long value = cleaned.get(i).get(metric);
                Long previous = previousValue(cleaned, i, metric);
                Long next = nextValue(cleaned, i, metric);
                if (value == null || previous == null || next == null) continue;
                if (!contradicts(value, previous) || !contradicts(value, next)) continue;
                if (!agree(previous, next)) continue;
                Sample bad = cleaned.get(i);
                Map<String, Long> values = new LinkedHashMap<>(bad.values());
                values.remove(metric);
                cleaned.set(i, new Sample(bad.at(), values, bad.activity()));
            }
        }
        return cleaned;
    }

    private static Long previousValue(List<Sample> samples, int index, String metric) {
        for (int i = index - 1; i >= 0; i--) {
            Long v = samples.get(i).get(metric);
            if (v != null) return v;
        }
        return null;
    }

    private static Long nextValue(List<Sample> samples, int index, String metric) {
        for (int i = index + 1; i < samples.size(); i++) {
            Long v = samples.get(i).get(metric);
            if (v != null) return v;
        }
        return null;
    }

    /** A reading is contradicted by a neighbour when it is off by more than half of it. The
     *  absolute floor keeps small honest movements (a few minutes of speedup) from ever counting
     *  as a contradiction. */
    private static boolean contradicts(long value, long neighbour) {
        long tolerance = Math.max(60L, Math.abs(neighbour) / 2);
        return Math.abs(value - neighbour) > tolerance;
    }

    /** Neighbours agree when they are within a quarter of each other -- i.e. the series is
     *  continuous across the suspect reading, so the suspect reading is the odd one out. */
    private static boolean agree(long before, long after) {
        long tolerance = Math.max(60L, Math.abs(before) / 4);
        return Math.abs(after - before) <= tolerance;
    }

    private static Instant parseInstant(String raw) {
        if (raw == null || raw.isBlank()) return null;
        // bg_telemetry writes an ISO local-datetime with a trailing 'Z' already on
        // a UTC value, occasionally producing "...ZZ". Normalise to a single Z.
        String s = raw.trim();
        while (s.endsWith("ZZ")) s = s.substring(0, s.length() - 1);
        try {
            return Instant.parse(s);
        } catch (Exception e) {
            try {
                // Fallback: treat a bare local-datetime as UTC.
                return java.time.LocalDateTime.parse(s.replace("Z", "")).toInstant(java.time.ZoneOffset.UTC);
            } catch (Exception e2) {
                return null;
            }
        }
    }

    public boolean isEmpty() { return samples.isEmpty(); }

    public int size() { return samples.size(); }

    public Sample latest() { return samples.isEmpty() ? null : samples.get(samples.size() - 1); }

    public List<Sample> samples() { return samples; }

    /** Latest sample whose time is at or before {@code cutoff}, or null. */
    private Sample latestAtOrBefore(Instant cutoff) {
        Sample found = null;
        for (Sample s : samples) {
            if (!s.at().isAfter(cutoff)) found = s; else break;
        }
        return found;
    }

    /** Earliest sample whose time is at or after {@code from}, or null. */
    private Sample earliestAtOrAfter(Instant from) {
        for (Sample s : samples) {
            if (!s.at().isBefore(from)) return s;
        }
        return null;
    }

    /**
     * How far BEFORE a window its baseline reading may be taken from.
     *
     * <p>bg_telemetry snapshots roughly every two hours, so a short window routinely holds a
     * reading or two but no reading at its opening edge. Measuring from the first sample INSIDE
     * the window then undercounts, because whatever happened between the window opening and that
     * first sample is silently dropped. Reaching back to the last reading before the window fixes
     * that, and is the ordinary way to value a window against a sparse series.</p>
     *
     * <p>Deliberately one-directional. An earlier revision reached forward past the window's END
     * too, and that is not the same operation: a baseline taken slightly early still describes the
     * window, whereas an end reading taken late folds in work that happened AFTER it. Last night
     * is the case that proved it -- the window held nothing at all, the forward reach grabbed the
     * 11:24 AM reading, and an hour of this morning's botting (+25,845 power, 3 beasts, 4 gather
     * marches) was reported as having happened overnight while the machine was in fact crashed.
     * Bracketing may refine the edges of a window that has data. It must never manufacture one out
     * of the periods either side. A window with no readings in it is reported as what it is --
     * see {@link #silenceAround}.</p>
     */
    private static final Duration BRACKET_REACH = Duration.ofHours(3);

    /** Latest sample at/before {@code at} that carries {@code metric}, or null. */
    private Sample metricAtOrBefore(String metric, Instant at) {
        Sample found = null;
        for (Sample s : samples) {
            if (s.at().isAfter(at)) break;
            if (s.get(metric) != null) found = s;
        }
        return found;
    }

    /** Earliest sample at/after {@code at} that carries {@code metric}, or null. */
    private Sample metricAtOrAfter(String metric, Instant at) {
        for (Sample s : samples) {
            if (!s.at().isBefore(at) && s.get(metric) != null) return s;
        }
        return null;
    }

    /**
     * The reading that opens the window for {@code metric}: the last one taken at/before
     * {@code from} when there is one within {@link #BRACKET_REACH}, otherwise the earliest one
     * inside the window. Only ever consulted once {@link #endAnchor} has confirmed the window
     * actually contains data.
     */
    private Sample startAnchor(String metric, Instant from, Instant to) {
        Sample before = metricAtOrBefore(metric, from);
        if (before != null && !before.at().isBefore(from.minus(BRACKET_REACH))) return before;
        Sample inside = metricAtOrAfter(metric, from);
        if (inside != null && !inside.at().isAfter(to)) return inside;
        return null;
    }

    /**
     * The reading that closes the window for {@code metric}: the last one taken INSIDE it. Never
     * a later reading -- see {@link #BRACKET_REACH}. Null when the window holds none, which is
     * also what makes a window with no data report no data rather than borrowing the next one.
     */
    private Sample endAnchor(String metric, Instant from, Instant to) {
        Sample inside = null;
        for (Sample s : samples) {
            if (s.at().isAfter(to)) break;
            if (s.at().isBefore(from)) continue;
            if (s.get(metric) != null) inside = s;
        }
        return inside;
    }

    /**
     * Change in every metric across {@code [from, to]}, anchored to the nearest reading at each
     * edge (see {@link #BRACKET_REACH}).
     *
     * <p>this used to anchor every metric's END value to the single latest
     * overall sample, so a metric simply missing from THAT one row (a transient OCR miss, or a
     * row written before that metric existed) vanished from the whole window even though earlier
     * in-window samples had it. Each metric now finds its own anchors independently, so a
     * transient gap in one field no longer hides every field.</p>
     *
     * <p>A Delta is emitted whenever both anchors exist and differ in time -- including
     * change == 0. "Measured, and it did not move" is a real answer; the caller renders it as
     * "steady". Only a genuinely unanchorable metric is omitted, which is what lets the caller
     * distinguish no-coverage from no-change instead of showing a raw stockpile for both.</p>
     */
    public List<Delta> deltaOverWindow(Instant from, Instant to) {
        List<Delta> deltas = new ArrayList<>();
        for (String metric : METRICS) {
            // End first: no in-window reading means the window was never observed, and nothing
            // outside it may stand in. Only then is a baseline worth looking for.
            Sample endS = endAnchor(metric, from, to);
            if (endS == null) continue;
            Sample startS = startAnchor(metric, from, to);
            if (startS == null || !endS.at().isAfter(startS.at())) continue;
            Long start = startS.get(metric);
            Long end = endS.get(metric);
            if (start == null || end == null) continue;
            deltas.add(new Delta(metric, start, end, end - start));
        }
        return deltas;
    }

    // ---- named windows ------------------------------------------------------

    /**
     * The 08:30 "wake" snapshot fires AT 08:30 but timestamps a bit later once
     * navigation + OCR finish (observed 08:30:43). Without grace, that purpose-built end-of-night
     * reading lands just past an exact 08:30:00 cutoff and gets excluded, so "last night" ended at the
     * prior hourly sample instead. Extend the window end by this grace so the wake anchor is always in.
     */
    private static final long WAKE_ANCHOR_GRACE_MINUTES = 20;

    public List<Delta> lastNight(ZoneId zone, LocalTime sleepStart, LocalTime wakeEnd) {
        LocalDate today = LocalDate.now(zone);
        Instant from = today.minusDays(1).atTime(sleepStart).atZone(zone).toInstant();
        Instant to = today.atTime(wakeEnd).plusMinutes(WAKE_ANCHOR_GRACE_MINUTES).atZone(zone).toInstant();
        return deltaOverWindow(from, to);
    }

    public List<Delta> last(long amount, ChronoUnit unit) {
        Instant now = Instant.now();
        return deltaOverWindow(now.minus(amount, unit), now);
    }

    public List<Delta> total() {
        if (samples.size() < 2) return new ArrayList<>();
        return deltaOverWindow(samples.get(0).at(), samples.get(samples.size() - 1).at());
    }

    // ---- real recorded coverage  ---------------------------
    // "I just don't trust these statistics... put like at the top the timeframe that it was
    // recorded." A window LABEL like "Last night (23:00-08:30)" describes the intended window,
    // not what was actually captured -- if bg_telemetry was disabled, gapped, or only caught two
    // samples three hours apart, the label alone hides that. This exposes the REAL first/last
    // sample timestamps a window's delta was actually built from, so the UI can show both.

    /** The actual [firstSampleAt, lastSampleAt] a window's delta was built from -- not the
     *  requested window bounds, the real timestamps of the samples used. Null when there's
     *  nothing to show (matches deltaOverWindow's own "not enough data" case). */
    public record Coverage(Instant actualFrom, Instant actualTo) {}

    /**
     * The real span the window's figures were built from. Mirrors {@link #deltaOverWindow}'s
     * anchoring exactly -- earliest start anchor to latest end anchor across the metrics that
     * actually resolved -- so the header can never claim a span the tiles did not use. This is the
     * line that makes a bracketed window honest: "recorded 8/29 10:52 PM -> 8/30 11:24 AM" states
     * plainly that the readings either side of the night are what the numbers came from.
     */
    public Coverage coverageForWindow(Instant from, Instant to) {
        Instant first = null;
        Instant last = null;
        for (String metric : METRICS) {
            Sample endS = endAnchor(metric, from, to);
            if (endS == null) continue;
            Sample startS = startAnchor(metric, from, to);
            if (startS == null || !endS.at().isAfter(startS.at())) continue;
            if (first == null || startS.at().isBefore(first)) first = startS.at();
            if (last == null || endS.at().isAfter(last)) last = endS.at();
        }
        if (first == null) {
            return null;
        }
        return new Coverage(first, last);
    }

    /**
     * When a window contains no reading whatsoever, the readings that bracket the silence -- so
     * the page can name the outage instead of shrugging at it.
     *
     * <p>"not enough samples yet" is the wrong thing to say about a night the bot spent crashed.
     * The useful answer is when it last reported and when it next did, which is exactly what a
     * reader needs to know the run died rather than that the page is still warming up.</p>
     *
     * <p>Either endpoint may be null (nothing recorded before, or nothing since). Null overall
     * means the window is NOT silent -- it has readings, and the normal delta path applies.</p>
     */
    public Coverage silenceAround(Instant from, Instant to) {
        for (Sample s : samples) {
            if (s.at().isAfter(to)) break;
            if (!s.at().isBefore(from)) return null; // a reading inside the window
        }
        Sample before = null;
        for (Sample s : samples) {
            if (!s.at().isBefore(from)) break;
            before = s;
        }
        Sample after = null;
        for (Sample s : samples) {
            if (s.at().isAfter(to)) { after = s; break; }
        }
        if (before == null && after == null) {
            return null; // no history at all -- "still warming up" really is the right message
        }
        return new Coverage(before == null ? null : before.at(), after == null ? null : after.at());
    }

    public Coverage silenceForLastNight(ZoneId zone, LocalTime sleepStart, LocalTime wakeEnd) {
        LocalDate today = LocalDate.now(zone);
        Instant from = today.minusDays(1).atTime(sleepStart).atZone(zone).toInstant();
        Instant to = today.atTime(wakeEnd).plusMinutes(WAKE_ANCHOR_GRACE_MINUTES).atZone(zone).toInstant();
        return silenceAround(from, to);
    }

    public Coverage silenceForLast(long amount, ChronoUnit unit) {
        Instant now = Instant.now();
        return silenceAround(now.minus(amount, unit), now);
    }

    public Coverage silenceForTotal() {
        return null; // all-time is every reading there is; it cannot have a gap around it
    }

    public Coverage coverageForLastNight(ZoneId zone, LocalTime sleepStart, LocalTime wakeEnd) {
        LocalDate today = LocalDate.now(zone);
        Instant from = today.minusDays(1).atTime(sleepStart).atZone(zone).toInstant();
        Instant to = today.atTime(wakeEnd).plusMinutes(WAKE_ANCHOR_GRACE_MINUTES).atZone(zone).toInstant();
        return coverageForWindow(from, to);
    }

    public Coverage coverageForLast(long amount, ChronoUnit unit) {
        Instant now = Instant.now();
        return coverageForWindow(now.minus(amount, unit), now);
    }

    public Coverage coverageForTotal() {
        if (samples.size() < 2) {
            return null;
        }
        return new Coverage(samples.get(0).at(), samples.get(samples.size() - 1).at());
    }

    // ---- activity ("what the bot did") --------------------------------------

    /** Human-readable names for the activity keys worth surfacing, in display order. */
    private static final Map<String, String> ACTIVITY_LABELS = new LinkedHashMap<>();
    static {
        // Accomplishments ONLY — never scan/run tallies ("I only care about things
        // accomplished"). Every tile is a ctr.* counter the game code increments when the thing actually
        // happens. run.* task-execution counts were all removed. Labels kept short so they don't truncate.
        ACTIVITY_LABELS.put("ctr.Intel Beast", "Beasts hunted");
        ACTIVITY_LABELS.put("ctr.Intel Journeys", "Journeys scouted");
        ACTIVITY_LABELS.put("ctr.Intel Survivor Camps", "Survivor camps");
        ACTIVITY_LABELS.put("ctr.Gather Marches Deployed", "Gather marches");
        ACTIVITY_LABELS.put("ctr.Daily Missions Claimed", "Daily missions");
        ACTIVITY_LABELS.put("ctr.Growth Missions Claimed", "Growth missions");
        ACTIVITY_LABELS.put("ctr.Mail Rewards Claimed", "Mail rewards");
        ACTIVITY_LABELS.put("ctr.Exploration Fights Won", "Exploration wins");
        ACTIVITY_LABELS.put("ctr.Arena Battles Won", "Arena wins");
        ACTIVITY_LABELS.put("ctr.Beast Attacks Sent", "Beast attacks");
        ACTIVITY_LABELS.put("ctr.Storehouse Chests Opened", "Storehouse chests");
        ACTIVITY_LABELS.put("ctr.Alliance Gifts Collected", "Alliance chests");
        ACTIVITY_LABELS.put("ctr.Pet Adventure Chests", "Pet chests");
        ACTIVITY_LABELS.put("ctr.Alliance Triumph Rewards", "Triumph rewards");
    }

    /** Latest sample at/before cutoff that actually carries activity fields. */
    private Sample latestActivityAtOrBefore(Instant cutoff) {
        Sample found = null;
        for (Sample s : samples) {
            if (s.at().isAfter(cutoff)) break;
            if (!s.activity().isEmpty()) found = s;
        }
        return found;
    }

    /** Earliest sample at/after from that actually carries activity fields. */
    private Sample earliestActivityAtOrAfter(Instant from) {
        for (Sample s : samples) {
            if (!s.at().isBefore(from) && !s.activity().isEmpty()) return s;
        }
        return null;
    }

    /** Opening-edge activity reading, bracketed exactly like {@link #startAnchor}. */
    private Sample activityStartAnchor(Instant from, Instant to) {
        Sample before = latestActivityAtOrBefore(from);
        if (before != null && !before.at().isBefore(from.minus(BRACKET_REACH))) return before;
        Sample inside = earliestActivityAtOrAfter(from);
        if (inside != null && !inside.at().isAfter(to)) return inside;
        return null;
    }

    /** Closing-edge activity reading: the last one INSIDE the window, exactly like
     *  {@link #endAnchor}. Never a later one -- counters are cumulative, so borrowing a reading
     *  from after the window credits it with work done after the window closed. */
    private Sample activityEndAnchor(Instant from, Instant to) {
        Sample inside = null;
        for (Sample s : samples) {
            if (s.at().isAfter(to)) break;
            if (s.at().isBefore(from)) continue;
            if (!s.activity().isEmpty()) inside = s;
        }
        return inside;
    }

    private List<Activity> activityOverWindow(Instant from, Instant to) {
        List<Activity> out = new ArrayList<>();
        // Anchored to the nearest activity-bearing reading either side of the window, for the same
        // reason the resource deltas are: a two-hour sample cadence means a short window often
        // contains no snapshot at all, and "the bot did nothing" is the wrong answer to that.
        Sample endS = activityEndAnchor(from, to);
        if (endS == null) {
            return out; // window never observed; nothing outside it may speak for it
        }
        Sample startS = activityStartAnchor(from, to);
        if (startS == null || !startS.at().isBefore(endS.at())) {
            return out;
        }
        for (Map.Entry<String, String> entry : ACTIVITY_LABELS.entrySet()) {
            Long s = startS.activity().get(entry.getKey());
            Long e = endS.activity().get(entry.getKey());
            if (s == null || e == null) continue;
            long change = e - s;
            if (change > 0) out.add(new Activity(entry.getValue(), change));
        }
        return out;
    }

    public List<Activity> activityLastNight(ZoneId zone, LocalTime sleepStart, LocalTime wakeEnd) {
        LocalDate today = LocalDate.now(zone);
        Instant from = today.minusDays(1).atTime(sleepStart).atZone(zone).toInstant();
        Instant to = today.atTime(wakeEnd).plusMinutes(WAKE_ANCHOR_GRACE_MINUTES).atZone(zone).toInstant();
        return activityOverWindow(from, to);
    }

    public List<Activity> activityLast(long amount, ChronoUnit unit) {
        Instant now = Instant.now();
        return activityOverWindow(now.minus(amount, unit), now);
    }

    /**
     * All-time accomplishments. The activity counters are cumulative, so the latest snapshot's
     * values ARE the totals — show them directly rather than diffing, which also means this
     * populates immediately (a window delta needs two activity-bearing samples).
     */
    public List<Activity> activityTotal() {
        Sample latest = latestActivityAtOrBefore(Instant.now());
        List<Activity> out = new ArrayList<>();
        if (latest == null) return out;
        for (Map.Entry<String, String> entry : ACTIVITY_LABELS.entrySet()) {
            Long v = latest.activity().get(entry.getKey());
            if (v != null && v > 0) out.add(new Activity(entry.getValue(), v));
        }
        return out;
    }
}
