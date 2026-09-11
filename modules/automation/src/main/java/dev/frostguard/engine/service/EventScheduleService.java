package dev.frostguard.engine.service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import dev.frostguard.data.entity.EventScheduleEntry;
import dev.frostguard.data.repository.EventScheduleRepository;

/**
 * Thin façade over {@link EventScheduleRepository} for the "Upcoming Events" calendar --
 * kept dependency-free of JavaFX so both {@code modules/tasks} (writing observations) and
 * {@code modules/desktop} (reading them for display) can depend on it without pulling UI
 * concerns into the engine layer.
 *
 * <p>Every timestamp this service writes or reads is a naive {@link LocalDateTime} representing
 * UTC wall-clock, never the JVM's default zone -- Bear Trap's window is computed from a UTC
 * {@code Instant}, and the rotating-event observations must land on the same clock or every
 * comparison between them silently drifts by the machine's own UTC offset.</p>
 */
public final class EventScheduleService {

    private static volatile EventScheduleService holder;

    private final EventScheduleRepository repository = EventScheduleRepository.getRepository();

    private EventScheduleService() {}

    public static EventScheduleService obtain() {
        EventScheduleService service = holder;
        if (service != null) {
            return service;
        }
        synchronized (EventScheduleService.class) {
            if (holder == null) {
                holder = new EventScheduleService();
            }
            return holder;
        }
    }

    /** Records a presence/absence read for a rotating Events-tab entry. */
    public void recordObservation(String eventKey, String eventLabel, boolean activeNow) {
        repository.recordObservation(eventKey, eventLabel, activeNow, LocalDateTime.now(ZoneOffset.UTC));
    }

    /** Overwrites Bear Trap's deterministic window, computed fresh each scan. */
    public void recordWindow(String eventKey, String eventLabel, boolean activeNow,
                              LocalDateTime windowStart, LocalDateTime windowEnd) {
        repository.recordWindow(eventKey, eventLabel, activeNow, windowStart, windowEnd, LocalDateTime.now(ZoneOffset.UTC));
    }

    /** All cached entries, for the sidebar widget to render. */
    public List<EventScheduleEntry> findAll() {
        return repository.findAll();
    }
}
