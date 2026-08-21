package dev.frostguard.engine.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TelemetryHistoryStoreTest {

    @TempDir
    Path workspace;

    @Test
    void appendsHistoryAndAtomicallyReplacesLatestForOneProfile() throws IOException {
        TelemetryHistoryStore store = new TelemetryHistoryStore(workspace, 42L);
        Map<String, Object> first = sample(100L, null);
        Map<String, Object> second = sample(125L, 7L);

        store.append(first);
        store.append(second);

        assertEquals(2, Files.readAllLines(store.directory().resolve("history.jsonl")).size());
        assertEquals(125L, store.readLatestNumericFields().get("power"));
        assertEquals(7L, store.readLatestNumericFields().get("run.Intel"));
        assertNull(store.readLatestNumericFields().get("gems"));
        assertFalse(Files.exists(store.directory().resolve("latest.json.tmp")));
    }

    @Test
    void missingLatestReturnsNoPreviousSample() throws IOException {
        assertNull(new TelemetryHistoryStore(workspace, 99L).readLatestNumericFields());
    }

    private static Map<String, Object> sample(long power, Long runs) {
        Map<String, Object> sample = new LinkedHashMap<>();
        sample.put("capturedAt", "2026-08-21T08:30:00Z");
        sample.put("power", power);
        sample.put("gems", null);
        sample.put("run.Intel", runs);
        return sample;
    }
}
