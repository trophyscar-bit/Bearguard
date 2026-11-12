package dev.frostguard.engine.squads;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * "Run now" on the Heroes page. The nightly only runs inside its slot and a finished night is closed, so a manual
 * run is flagged here and the routine then treats the moment it starts as a slot of its own.
 */
public final class GearManualRun {

    private static final Set<Long> REQUESTED = ConcurrentHashMap.newKeySet();

    private GearManualRun() {
    }

    public static void request(long profileId) {
        REQUESTED.add(profileId);
    }

    /** True once for each request, then false until the next. */
    public static boolean consume(long profileId) {
        return REQUESTED.remove(profileId);
    }
}
