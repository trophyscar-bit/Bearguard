package dev.frostguard.engine.squads;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import dev.frostguard.api.squads.SquadSnapshot;

/** Publishes Squads scan progress to the Squads panel and carries "Scan now" presses to the scan. */
public final class SquadScanRegistry {

    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static final Set<Long> MANUAL_REQUESTS = ConcurrentHashMap.newKeySet();

    private SquadScanRegistry() {
    }

    public static void addListener(Listener listener) {
        if (listener != null) LISTENERS.add(listener);
    }

    public static void publish(Event event) {
        LISTENERS.forEach(listener -> listener.onSquadScanEvent(event));
    }

    /** Marks the next scan of this profile as a "Scan now" press, which always runs in full. */
    public static void requestManualScan(Long profileId) {
        if (profileId != null) MANUAL_REQUESTS.add(profileId);
    }

    /** True once per press: whether the scan about to run was asked for by hand. */
    public static boolean takeManualRequest(Long profileId) {
        return profileId != null && MANUAL_REQUESTS.remove(profileId);
    }

    public enum State {
        RUNNING,
        SUCCEEDED,
        FAILED
    }

    public record Event(Long profileId, State state, String message, SquadSnapshot snapshot) {
    }

    @FunctionalInterface
    public interface Listener {
        void onSquadScanEvent(Event event);
    }
}
