package dev.frostguard.data.metrics;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.frostguard.api.runtime.WorkspacePaths;

/**
 * Every number the bot reads off the screen, stored once, when it was read.
 *
 * <h2>Why this exists</h2>
 *
 * <p>The Statistics tab used to answer "what did I gain last night" by subtracting two rows of a
 * JSON Lines file. That sounds right and was not, because the rows were not readings. bg_telemetry
 * sampled every two hours by copying whatever sat in the config cache, and that cache only moved
 * when a reading passed a plausibility guard. So a value read at 02:05 first appeared in the 03:12
 * row, and a value the guard distrusted for six hours appeared nowhere until it was released in a
 * single step. Subtracting two rows measured when the guard changed its mind, not what the account
 * gained. It reported eight hours of construction speedup arriving in one eleven-minute interval,
 * and there was no way to tell from the file whether that was real.</p>
 *
 * <p>A row here is an observation: this profile's {@code metric} was seen to be {@code value} at
 * {@code observed_at}, recorded by whoever read it at the moment they read it. Nothing copies
 * anything forward, so there is nothing to go stale, and a question about a time span is a
 * subtraction of two indexed lookups rather than an algorithm.</p>
 *
 * <h2>Why SQLite</h2>
 *
 * <p>Because Bearguard is downloaded and run by people, not deployed to a cluster. The obvious
 * alternatives for metrics -- Prometheus, InfluxDB, Timescale -- are servers, and every one of them
 * would make a user install and run a service before their statistics worked. SQLite is already a
 * dependency here, ships inside the application, needs no server, and leaves a single file the
 * operator can copy, back up, or open with any SQLite tool to run their own queries. Storage is
 * long format (one row per metric per reading) so a new metric never needs a schema change.</p>
 */
public final class MetricStore {

    private static final Logger log = LoggerFactory.getLogger(MetricStore.class);

    /** One reading: what the value was, and when it was seen. */
    public record Observation(String metric, Instant observedAt, long value) {}

    /**
     * A change measured between two real readings.
     *
     * <p>{@code from} and {@code to} are the readings the change was measured between, not the
     * bounds that were asked for. A caller that wants to know whether a window was actually covered
     * compares them against what it asked for, rather than trusting that the answer describes the
     * span it had in mind.</p>
     */
    public record Delta(String metric, Instant from, Instant to, long start, long end) {
        public long change() {
            return end - start;
        }
    }

    private final Path databaseFile;

    public MetricStore(Path databaseFile) {
        this.databaseFile = databaseFile;
        initialise();
    }

    /** The store for the running workspace: {@code <workspace>/data/telemetry/metrics.db}. */
    public static MetricStore forCurrentWorkspace() {
        return new MetricStore(WorkspacePaths.current().root()
                .resolve("data").resolve("telemetry").resolve("metrics.db"));
    }

    private Connection open() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + databaseFile.toAbsolutePath());
    }

    private void initialise() {
        try {
            Files.createDirectories(databaseFile.toAbsolutePath().getParent());
            try (Connection c = open(); Statement s = c.createStatement()) {
                // WAL so a read from the Statistics tab never blocks a write from a routine.
                s.execute("PRAGMA journal_mode=WAL");
                s.execute("PRAGMA synchronous=NORMAL");
                s.execute("""
                        CREATE TABLE IF NOT EXISTS observation (
                            profile_id  INTEGER NOT NULL,
                            metric      TEXT    NOT NULL,
                            observed_at INTEGER NOT NULL,
                            value       INTEGER NOT NULL,
                            PRIMARY KEY (profile_id, metric, observed_at)
                        )""");
                s.execute("CREATE INDEX IF NOT EXISTS observation_lookup"
                        + " ON observation (profile_id, metric, observed_at)");
                s.execute("CREATE TABLE IF NOT EXISTS store_meta (key TEXT PRIMARY KEY, value TEXT)");
            }
            importLegacyHistoryOnce();
        } catch (Exception e) {
            log.error("Could not open the metric store at {}: {}", databaseFile, e.getMessage());
        }
    }

    // ---- one-time import of the old file --------------------------------------

    /**
     * Brings any existing {@code history.jsonl} into the store, once.
     *
     * <p>Whoever upgrades should keep the trend they already have, and that has to happen by itself
     * rather than by someone running a script against their own machine. The flag in
     * {@code store_meta} makes it a migration rather than a repeated import, and the primary key
     * makes a repeat harmless anyway.</p>
     *
     * <p>What comes in is honest about what it is: those rows were two-hourly copies of a cache, so
     * their timestamps say when the value was copied, not when it was read. They are the best record
     * that exists of the past and are worth keeping; everything recorded from here on is a reading.</p>
     */
    private void importLegacyHistoryOnce() {
        try (Connection c = open()) {
            try (PreparedStatement ps = c.prepareStatement("SELECT value FROM store_meta WHERE key='legacy_import'");
                 ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return;
                }
            }
            Path profiles = databaseFile.toAbsolutePath().getParent().resolve("profiles");
            int imported = 0;
            if (Files.isDirectory(profiles)) {
                try (var dirs = Files.list(profiles)) {
                    for (Path dir : dirs.toList()) {
                        Path history = dir.resolve("history.jsonl");
                        if (!Files.isReadable(history)) {
                            continue;
                        }
                        long profileId;
                        try {
                            profileId = Long.parseLong(dir.getFileName().toString());
                        } catch (NumberFormatException notAProfile) {
                            continue;
                        }
                        imported += importLegacyFile(profileId, history);
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT OR REPLACE INTO store_meta (key, value) VALUES ('legacy_import', ?)")) {
                ps.setString(1, Instant.now() + " rows=" + imported);
                ps.executeUpdate();
            }
            if (imported > 0) {
                log.info("Metric store: imported {} value(s) from the previous history files", imported);
            }
        } catch (Exception e) {
            log.warn("Metric store: could not import the previous history: {}", e.getMessage());
        }
    }

    /** Flat numeric fields of one JSON Lines row, without pulling in a JSON parser. */
    private static final java.util.regex.Pattern FIELD =
            java.util.regex.Pattern.compile("\"([A-Za-z_][\\w.]*)\":(-?\\d+)(?![\\d.])");
    private static final java.util.regex.Pattern CAPTURED_AT =
            java.util.regex.Pattern.compile("\"capturedAt\":\"([^\"]+)\"");

    private int importLegacyFile(long profileId, Path history) throws Exception {
        int rows = 0;
        for (String line : Files.readAllLines(history)) {
            if (line == null || line.isBlank()) {
                continue;
            }
            java.util.regex.Matcher when = CAPTURED_AT.matcher(line);
            if (!when.find()) {
                continue;
            }
            Instant at;
            try {
                String raw = when.group(1).trim();
                while (raw.endsWith("ZZ")) {
                    raw = raw.substring(0, raw.length() - 1);
                }
                at = Instant.parse(raw);
            } catch (Exception badTimestamp) {
                continue;
            }
            Map<String, Long> values = new java.util.LinkedHashMap<>();
            java.util.regex.Matcher field = FIELD.matcher(line);
            while (field.find()) {
                if (!"capturedAt".equals(field.group(1))) {
                    values.put(field.group(1), Long.parseLong(field.group(2)));
                }
            }
            if (!values.isEmpty()) {
                recordAll(profileId, at, values);
                rows += values.size();
            }
        }
        return rows;
    }

    // ---- writing ------------------------------------------------------------

    /** Records one reading. Recording the same metric twice for one instant keeps the later value. */
    public void record(long profileId, String metric, Instant observedAt, long value) {
        recordAll(profileId, observedAt, Map.of(metric, value));
    }

    /**
     * Records every reading taken at one moment, in a single transaction.
     *
     * <p>Null values are skipped rather than stored: a reading that did not resolve is not an
     * observation of anything, and writing a placeholder for it is how the old file came to be full
     * of numbers nobody had actually seen.</p>
     */
    public void recordAll(long profileId, Instant observedAt, Map<String, Long> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        String sql = "INSERT INTO observation (profile_id, metric, observed_at, value) VALUES (?,?,?,?)"
                + " ON CONFLICT(profile_id, metric, observed_at) DO UPDATE SET value = excluded.value";
        try (Connection c = open()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                for (Map.Entry<String, Long> e : values.entrySet()) {
                    if (e.getKey() == null || e.getValue() == null) {
                        continue;
                    }
                    ps.setLong(1, profileId);
                    ps.setString(2, e.getKey());
                    ps.setLong(3, observedAt.toEpochMilli());
                    ps.setLong(4, e.getValue());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            c.commit();
        } catch (SQLException e) {
            log.warn("Could not record {} observation(s): {}", values.size(), e.getMessage());
        }
    }

    // ---- reading ------------------------------------------------------------

    /** The last reading at or before {@code at}. */
    public Optional<Observation> lastAtOrBefore(long profileId, String metric, Instant at) {
        return one(profileId, metric,
                "SELECT observed_at, value FROM observation WHERE profile_id=? AND metric=?"
                        + " AND observed_at <= ? ORDER BY observed_at DESC LIMIT 1", at);
    }

    /** The first reading at or after {@code at}. */
    public Optional<Observation> firstAtOrAfter(long profileId, String metric, Instant at) {
        return one(profileId, metric,
                "SELECT observed_at, value FROM observation WHERE profile_id=? AND metric=?"
                        + " AND observed_at >= ? ORDER BY observed_at ASC LIMIT 1", at);
    }

    /**
     * The change in {@code metric} between the first and last readings inside {@code [from, to]}.
     *
     * <p>Only readings inside the span are used, so the answer never quietly describes a different
     * period than the one asked about. Empty when the span holds fewer than two readings -- there is
     * no change to report between one reading and itself, and none at all between none.</p>
     */
    public Optional<Delta> delta(long profileId, String metric, Instant from, Instant to) {
        Optional<Observation> start = firstAtOrAfter(profileId, metric, from);
        if (start.isEmpty() || start.get().observedAt().isAfter(to)) {
            return Optional.empty();
        }
        Optional<Observation> end = lastAtOrBefore(profileId, metric, to);
        if (end.isEmpty() || !end.get().observedAt().isAfter(start.get().observedAt())) {
            return Optional.empty();
        }
        return Optional.of(new Delta(metric, start.get().observedAt(), end.get().observedAt(),
                start.get().value(), end.get().value()));
    }

    /** Every reading of {@code metric} inside {@code [from, to]}, oldest first. */
    public List<Observation> between(long profileId, String metric, Instant from, Instant to) {
        List<Observation> out = new ArrayList<>();
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT observed_at, value FROM observation WHERE profile_id=? AND metric=?"
                             + " AND observed_at BETWEEN ? AND ? ORDER BY observed_at ASC")) {
            ps.setLong(1, profileId);
            ps.setString(2, metric);
            ps.setLong(3, from.toEpochMilli());
            ps.setLong(4, to.toEpochMilli());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Observation(metric, Instant.ofEpochMilli(rs.getLong(1)), rs.getLong(2)));
                }
            }
        } catch (SQLException e) {
            log.warn("Could not read {} between {} and {}: {}", metric, from, to, e.getMessage());
        }
        return out;
    }

    /** Every metric name this profile has ever recorded. */
    public List<String> metrics(long profileId) {
        List<String> out = new ArrayList<>();
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT DISTINCT metric FROM observation WHERE profile_id=? ORDER BY metric")) {
            ps.setLong(1, profileId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
        } catch (SQLException e) {
            log.warn("Could not list metrics: {}", e.getMessage());
        }
        return out;
    }

    public long count(long profileId) {
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM observation WHERE profile_id=?")) {
            ps.setLong(1, profileId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (SQLException e) {
            return 0L;
        }
    }

    private Optional<Observation> one(long profileId, String metric, String sql, Instant at) {
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, profileId);
            ps.setString(2, metric);
            ps.setLong(3, at.toEpochMilli());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next()
                        ? Optional.of(new Observation(metric, Instant.ofEpochMilli(rs.getLong(1)), rs.getLong(2)))
                        : Optional.empty();
            }
        } catch (SQLException e) {
            log.warn("Could not read {}: {}", metric, e.getMessage());
            return Optional.empty();
        }
    }
}
