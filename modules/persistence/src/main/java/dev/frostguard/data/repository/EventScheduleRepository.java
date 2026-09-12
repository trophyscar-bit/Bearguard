package dev.frostguard.data.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import dev.frostguard.data.access.DataStore;
import dev.frostguard.data.entity.EventScheduleEntry;

/** Persistence gateway for the "Upcoming Events" calendar cache. */
public final class EventScheduleRepository {

    private static EventScheduleRepository instance;
    private final DataStore store;

    /** Public for tests, which need an isolated {@link DataStore} rather than the shared instance. */
    public EventScheduleRepository(DataStore store) {
        this.store = store;
    }

    public static synchronized EventScheduleRepository getRepository() {
        if (instance == null) {
            instance = new EventScheduleRepository(DataStore.getInstance());
        }
        return instance;
    }

    public Optional<EventScheduleEntry> find(String eventKey) {
        List<EventScheduleEntry> results = store.executeQuery(
                "SELECT e FROM EventScheduleEntry e WHERE e.eventKey = :eventKey",
                EventScheduleEntry.class, Map.of("eventKey", eventKey));
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public List<EventScheduleEntry> findAll() {
        return store.readOnly(entityManager -> entityManager.createQuery(
                        "SELECT e FROM EventScheduleEntry e ORDER BY e.eventLabel",
                        EventScheduleEntry.class)
                .getResultList());
    }

    /**
     * Drops every cached entry whose key starts with {@code keyPrefix}, and returns how many went.
     *
     * <p>For scans that read a whole screen at once rather than one event at a time: that read is a
     * complete snapshot, so the previous snapshot has to go with it. Without this, a bar the game
     * stopped showing, or one whose OCR'd name came out differently on a later pass, stays behind
     * forever as a second row beside its replacement.</p>
     */
    public int deleteByKeyPrefix(String keyPrefix) {
        return store.withinTransaction(entityManager -> entityManager.createQuery(
                        "DELETE FROM EventScheduleEntry e WHERE e.eventKey LIKE :prefix")
                .setParameter("prefix", keyPrefix + "%")
                .executeUpdate());
    }

    /**
     * Records one scan's presence/absence read for an event and derives the
     * active/inactive transition from it -- the first scan that disagrees with the
     * previously stored state resets the corresponding "since" timestamp to {@code scannedAt};
     * a scan that agrees with the stored state only advances {@code lastScannedAt}.
     */
    public void recordObservation(String eventKey, String eventLabel, boolean activeNow, LocalDateTime scannedAt) {
        store.runInTransaction(entityManager -> {
            List<EventScheduleEntry> existing = entityManager.createQuery(
                            "SELECT e FROM EventScheduleEntry e WHERE e.eventKey = :eventKey",
                            EventScheduleEntry.class)
                    .setParameter("eventKey", eventKey)
                    .setMaxResults(1)
                    .getResultList();

            if (existing.isEmpty()) {
                EventScheduleEntry entry = new EventScheduleEntry(eventKey, eventLabel, activeNow,
                        activeNow ? scannedAt : null, activeNow ? null : scannedAt, scannedAt);
                entityManager.persist(entry);
                return;
            }

            EventScheduleEntry entry = existing.get(0);
            entry.setEventLabel(eventLabel);
            if (entry.isCurrentlyActive() != activeNow) {
                if (activeNow) {
                    entry.setActiveSince(scannedAt);
                } else {
                    entry.setInactiveSince(scannedAt);
                }
                entry.setCurrentlyActive(activeNow);
            }
            entry.setLastScannedAt(scannedAt);
            entityManager.merge(entry);
        });
    }

    /**
     * Overwrites a deterministic window (Bear Trap) directly rather than inferring it from
     * presence/absence -- its start/end are already exact from configured anchor math.
     */
    public void recordWindow(String eventKey, String eventLabel, boolean activeNow,
                              LocalDateTime windowStart, LocalDateTime windowEnd, LocalDateTime scannedAt) {
        store.runInTransaction(entityManager -> {
            List<EventScheduleEntry> existing = entityManager.createQuery(
                            "SELECT e FROM EventScheduleEntry e WHERE e.eventKey = :eventKey",
                            EventScheduleEntry.class)
                    .setParameter("eventKey", eventKey)
                    .setMaxResults(1)
                    .getResultList();

            if (existing.isEmpty()) {
                entityManager.persist(new EventScheduleEntry(eventKey, eventLabel, activeNow,
                        windowStart, windowEnd, scannedAt));
                return;
            }

            EventScheduleEntry entry = existing.get(0);
            entry.setEventLabel(eventLabel);
            entry.setCurrentlyActive(activeNow);
            entry.setActiveSince(windowStart);
            entry.setInactiveSince(windowEnd);
            entry.setLastScannedAt(scannedAt);
            entityManager.merge(entry);
        });
    }
}
