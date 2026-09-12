package dev.frostguard.data.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The point of the store: a question about a time span is a subtraction of two readings taken
 * inside it.
 *
 * <p>What it replaces answered that question by subtracting two rows of a file whose values were
 * copies of a cache, so the arithmetic measured when a value was believed rather than when it
 * changed. These pin the properties that stop that happening again.</p>
 */
class MetricStoreTest {

    private static final long PROFILE = 1L;
    private static final Instant ELEVEN_PM = Instant.parse("2026-09-11T03:00:00Z");
    private static final Instant EIGHT_THIRTY_AM = Instant.parse("2026-09-11T12:30:00Z");

    private static Instant at(String iso) {
        return Instant.parse(iso);
    }

    @Test
    void aSpanIsTheDifferenceBetweenItsFirstAndLastReading(@TempDir Path dir) {
        MetricStore store = new MetricStore(dir.resolve("metrics.db"));
        store.record(PROFILE, "sp_training", at("2026-09-11T03:05:00Z"), 3061);
        store.record(PROFILE, "sp_training", at("2026-09-11T07:00:00Z"), 3250);
        store.record(PROFILE, "sp_training", at("2026-09-11T12:22:00Z"), 3524);

        Optional<MetricStore.Delta> night = store.delta(PROFILE, "sp_training", ELEVEN_PM, EIGHT_THIRTY_AM);

        assertTrue(night.isPresent());
        assertEquals(463L, night.get().change(), "3524 - 3061, and nothing else");
        assertEquals(at("2026-09-11T03:05:00Z"), night.get().from());
        assertEquals(at("2026-09-11T12:22:00Z"), night.get().to());
    }

    /** A reading outside the span is not evidence about the span. */
    @Test
    void readingsOutsideTheSpanAreNotUsed(@TempDir Path dir) {
        MetricStore store = new MetricStore(dir.resolve("metrics.db"));
        store.record(PROFILE, "sp_construction", at("2026-09-10T20:00:00Z"), 751);   // before
        store.record(PROFILE, "sp_construction", at("2026-09-11T05:00:00Z"), 3402);  // inside
        store.record(PROFILE, "sp_construction", at("2026-09-11T06:06:00Z"), 3888);  // inside
        store.record(PROFILE, "sp_construction", at("2026-09-11T20:00:00Z"), 9999);  // after

        MetricStore.Delta night = store.delta(PROFILE, "sp_construction", ELEVEN_PM, EIGHT_THIRTY_AM).orElseThrow();

        assertEquals(486L, night.change(), "3888 - 3402; neither the earlier 751 nor the later 9999");
    }

    /** One reading is not a change, and none is not an answer. */
    @Test
    void aSpanWithoutTwoReadingsHasNoAnswer(@TempDir Path dir) {
        MetricStore store = new MetricStore(dir.resolve("metrics.db"));
        store.record(PROFILE, "power", at("2026-09-11T04:00:00Z"), 40_000_000);

        assertTrue(store.delta(PROFILE, "power", ELEVEN_PM, EIGHT_THIRTY_AM).isEmpty(),
                "a single reading cannot describe a change");
        assertTrue(store.delta(PROFILE, "gems", ELEVEN_PM, EIGHT_THIRTY_AM).isEmpty(),
                "a metric with no readings at all has nothing to say");
    }

    /** A reading that did not resolve is not stored as though it had. */
    @Test
    void unresolvedReadingsAreNotRecorded(@TempDir Path dir) {
        MetricStore store = new MetricStore(dir.resolve("metrics.db"));
        Map<String, Long> cycle = new HashMap<>();
        cycle.put("meat", 301_200_000L);
        cycle.put("coal", null);
        store.recordAll(PROFILE, at("2026-09-11T04:00:00Z"), cycle);

        assertEquals(1L, store.count(PROFILE));
        assertEquals(java.util.List.of("meat"), store.metrics(PROFILE));
    }

    @Test
    void recordingTheSameMomentTwiceKeepsTheLaterValue(@TempDir Path dir) {
        MetricStore store = new MetricStore(dir.resolve("metrics.db"));
        store.record(PROFILE, "steel", at("2026-09-11T04:00:00Z"), 2_200_000);
        store.record(PROFILE, "steel", at("2026-09-11T04:00:00Z"), 2_260_000);

        assertEquals(1L, store.count(PROFILE));
        assertEquals(2_260_000L,
                store.lastAtOrBefore(PROFILE, "steel", at("2026-09-11T05:00:00Z")).orElseThrow().value());
    }

    @Test
    void profilesDoNotSeeEachOthersReadings(@TempDir Path dir) {
        MetricStore store = new MetricStore(dir.resolve("metrics.db"));
        store.record(1L, "power", at("2026-09-11T04:00:00Z"), 100);
        store.record(2L, "power", at("2026-09-11T04:00:00Z"), 999);

        assertEquals(100L, store.lastAtOrBefore(1L, "power", at("2026-09-11T05:00:00Z")).orElseThrow().value());
        assertEquals(999L, store.lastAtOrBefore(2L, "power", at("2026-09-11T05:00:00Z")).orElseThrow().value());
    }

    @Test
    void readingsComeBackInOrder(@TempDir Path dir) {
        MetricStore store = new MetricStore(dir.resolve("metrics.db"));
        store.record(PROFILE, "wood", at("2026-09-11T06:00:00Z"), 2);
        store.record(PROFILE, "wood", at("2026-09-11T04:00:00Z"), 1);
        store.record(PROFILE, "wood", at("2026-09-11T08:00:00Z"), 3);

        assertEquals(java.util.List.of(1L, 2L, 3L),
                store.between(PROFILE, "wood", ELEVEN_PM, EIGHT_THIRTY_AM)
                        .stream().map(MetricStore.Observation::value).toList());
    }
}
