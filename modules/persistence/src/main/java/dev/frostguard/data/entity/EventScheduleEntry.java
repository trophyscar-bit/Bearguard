package dev.frostguard.data.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * Cached observation of one Whiteout Survival event's active window, feeding the desktop
 * sidebar's "Upcoming Events" calendar.
 *
 * <p>Most events here (the rotating Events-tab set) have no on-screen countdown to read --
 * only whether the tab is currently showing -- so {@code activeSince}/{@code inactiveSince}
 * are transition timestamps recorded the first time a scan notices a change, not exact game
 * clock values. Resolution is bounded by the scan interval. Bear Trap is the exception: its
 * window is computed deterministically each scan from its configured anchor, so its
 * timestamps are exact.</p>
 */
@Entity
@Table(name = "event_schedule_entry",
        uniqueConstraints = @UniqueConstraint(name = "uk_event_schedule_entry_key",
                columnNames = {"event_key"}))
@Access(AccessType.FIELD)
public class EventScheduleEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, unique = true)
    private Long id;

    /** Stable identifier -- an {@code EventKind} name, or {@code "BEAR_TRAP"}. */
    @Column(name = "event_key", nullable = false)
    private String eventKey;

    @Column(name = "event_label", nullable = false)
    private String eventLabel;

    @Column(name = "currently_active", nullable = false)
    private boolean currentlyActive;

    @Column(name = "active_since")
    private LocalDateTime activeSince;

    @Column(name = "inactive_since")
    private LocalDateTime inactiveSince;

    @Column(name = "last_scanned_at", nullable = false)
    private LocalDateTime lastScannedAt;

    public EventScheduleEntry() {}

    public EventScheduleEntry(String eventKey, String eventLabel, boolean currentlyActive,
                               LocalDateTime activeSince, LocalDateTime inactiveSince,
                               LocalDateTime lastScannedAt) {
        this.eventKey = eventKey;
        this.eventLabel = eventLabel;
        this.currentlyActive = currentlyActive;
        this.activeSince = activeSince;
        this.inactiveSince = inactiveSince;
        this.lastScannedAt = lastScannedAt == null ? LocalDateTime.now() : lastScannedAt;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getEventKey() { return eventKey; }
    public void setEventKey(String eventKey) { this.eventKey = eventKey; }
    public String getEventLabel() { return eventLabel; }
    public void setEventLabel(String eventLabel) { this.eventLabel = eventLabel; }
    public boolean isCurrentlyActive() { return currentlyActive; }
    public void setCurrentlyActive(boolean currentlyActive) { this.currentlyActive = currentlyActive; }
    public LocalDateTime getActiveSince() { return activeSince; }
    public void setActiveSince(LocalDateTime activeSince) { this.activeSince = activeSince; }
    public LocalDateTime getInactiveSince() { return inactiveSince; }
    public void setInactiveSince(LocalDateTime inactiveSince) { this.inactiveSince = inactiveSince; }
    public LocalDateTime getLastScannedAt() { return lastScannedAt; }
    public void setLastScannedAt(LocalDateTime lastScannedAt) { this.lastScannedAt = lastScannedAt; }
}
