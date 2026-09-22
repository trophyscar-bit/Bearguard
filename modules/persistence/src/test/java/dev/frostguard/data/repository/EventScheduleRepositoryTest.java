package dev.frostguard.data.repository;

import dev.frostguard.data.access.DataStore;
import dev.frostguard.data.entity.EventScheduleEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventScheduleRepositoryTest {

    private Path database;
    private DataStore store;
    private EventScheduleRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        database = Files.createTempFile("frostguard-event-schedule-test-", ".db");
        store = DataStore.openIsolated(Map.of(
                "jakarta.persistence.jdbc.url", "jdbc:sqlite:" + database,
                "hibernate.hbm2ddl.auto", "create-drop"));
        repository = new EventScheduleRepository(store);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (store != null) {
            store.close();
        }
        Files.deleteIfExists(database);
    }

    @Test
    void recordsActiveSinceOnFirstObservedTransitionToActive() {
        LocalDateTime firstScan = LocalDateTime.of(2026, 9, 1, 12, 0);
        LocalDateTime secondScan = firstScan.plusHours(3);

        repository.recordObservation("HALL_OF_CHIEFS", "Hall of Chiefs", false, firstScan);
        Optional<EventScheduleEntry> afterFirst = repository.find("HALL_OF_CHIEFS");
        assertTrue(afterFirst.isPresent());
        assertFalse(afterFirst.get().isCurrentlyActive());
        assertEquals(firstScan, afterFirst.get().getInactiveSince());

        repository.recordObservation("HALL_OF_CHIEFS", "Hall of Chiefs", true, secondScan);
        EventScheduleEntry afterSecond = repository.find("HALL_OF_CHIEFS").orElseThrow();
        assertTrue(afterSecond.isCurrentlyActive());
        assertEquals(secondScan, afterSecond.getActiveSince());
        assertEquals(secondScan, afterSecond.getLastScannedAt());
    }

    @Test
    void agreeingObservationOnlyAdvancesLastScannedAt() {
        LocalDateTime firstScan = LocalDateTime.of(2026, 9, 1, 12, 0);
        LocalDateTime secondScan = firstScan.plusHours(3);

        repository.recordObservation("BROTHERS_IN_ARMS", "Brothers in Arms", true, firstScan);
        repository.recordObservation("BROTHERS_IN_ARMS", "Brothers in Arms", true, secondScan);

        EventScheduleEntry entry = repository.find("BROTHERS_IN_ARMS").orElseThrow();
        assertTrue(entry.isCurrentlyActive());
        // Unchanged -- still the scan that first saw it active, not the later agreeing one.
        assertEquals(firstScan, entry.getActiveSince());
        assertEquals(secondScan, entry.getLastScannedAt());
    }

    @Test
    void recordsWindowDeterministicallyRegardlessOfPriorObservation() {
        LocalDateTime scan = LocalDateTime.of(2026, 9, 1, 12, 0);
        LocalDateTime windowStart = scan.plusHours(1);
        LocalDateTime windowEnd = windowStart.plusMinutes(40);

        repository.recordWindow("BEAR_TRAP", "Bear Trap", false, windowStart, windowEnd, scan);

        EventScheduleEntry entry = repository.find("BEAR_TRAP").orElseThrow();
        assertFalse(entry.isCurrentlyActive());
        assertEquals(windowStart, entry.getActiveSince());
        assertEquals(windowEnd, entry.getInactiveSince());
    }

    @Test
    void findAllReturnsEveryTrackedEvent() {
        LocalDateTime scan = LocalDateTime.of(2026, 9, 1, 12, 0);
        repository.recordObservation("HALL_OF_CHIEFS", "Hall of Chiefs", true, scan);
        repository.recordObservation("BROTHERS_IN_ARMS", "Brothers in Arms", false, scan);

        List<EventScheduleEntry> all = repository.findAll();
        assertEquals(2, all.size());
    }
}
