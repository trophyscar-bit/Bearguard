package dev.frostguard.app.panel.events;

import java.time.ZoneId;

import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;

/**
 * The clock the "Upcoming Events" sidebar calendar draws its times against.
 *
 * <p>Recorded event windows are stored as {@code LocalDateTime} in UTC (see
 * {@code EventScheduleScanRoutine}); this only controls what zone they are displayed in. Kept
 * as its own holder -- rather than reusing {@link dev.frostguard.app.panel.social.ChatClock} --
 * since the two panels are configured independently and there is no reason a chat viewer's zone
 * must match a calendar viewer's zone on a shared account.
 */
public final class EventScheduleClock {

    private EventScheduleClock() {
    }

    private static final ReadOnlyObjectWrapper<ZoneId> ZONE =
            new ReadOnlyObjectWrapper<>(ZoneId.systemDefault());

    /** What the machine itself is set to. What the setting means by "same as this computer". */
    public static final String SYSTEM = "";

    /** The zone calendar times are currently drawn in. Never null. */
    public static ZoneId zone() {
        return ZONE.get();
    }

    /** Fires when the zone changes, so an open widget can redraw rather than go stale. */
    public static ReadOnlyObjectProperty<ZoneId> zoneProperty() {
        return ZONE.getReadOnlyProperty();
    }

    /**
     * Points the clock at a stored setting. Blank or unrecognised falls back to the machine's
     * own zone, same convention as {@code ChatClock}.
     */
    public static void useSetting(String stored) {
        ZONE.set(parse(stored));
    }

    private static ZoneId parse(String stored) {
        if (stored == null || stored.isBlank()) {
            return ZoneId.systemDefault();
        }
        try {
            return ZoneId.of(stored.trim());
        } catch (RuntimeException notAZone) {
            return ZoneId.systemDefault();
        }
    }
}
