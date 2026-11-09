package dev.frostguard.engine.helper;

import java.util.function.BooleanSupplier;

/**
 * Opens the sidebar from Home when it will not open on World, then puts the caller back on World.
 *
 * <p>From 2026-11-08 22:00 to 2026-11-09 12:26 the production log shows the sidebar opening from
 * Home 135 times out of 135, and from World only 11 times out of 71. The panel itself is the same
 * on both screens, so a task that only needs to read or use it can take the Home route.</p>
 */
final class SidebarHomeFallback {

    interface Screens {
        /** Saves what the failed attempt left on screen, so the cause can be read off the frame. */
        void recordFailedOpen();

        boolean sidebarClosedOnWorld();

        boolean switchToHome();

        boolean switchToWorld();
    }

    private final Screens screens;
    private boolean worldToRestore;

    SidebarHomeFallback(Screens screens) {
        this.screens = screens;
    }

    boolean open(BooleanSupplier attempt) {
        worldToRestore = false;
        if (attempt.getAsBoolean()) {
            return true;
        }
        screens.recordFailedOpen();
        if (!screens.sidebarClosedOnWorld() || !screens.switchToHome()) {
            return false;
        }
        worldToRestore = true;
        if (attempt.getAsBoolean()) {
            return true;
        }
        restoreWorld();
        return false;
    }

    /** Called once the panel is closed again. */
    void restoreWorld() {
        if (worldToRestore) {
            worldToRestore = false;
            screens.switchToWorld();
        }
    }

    /** The caller followed a sidebar row to another screen, so World no longer applies. */
    void forget() {
        worldToRestore = false;
    }
}
