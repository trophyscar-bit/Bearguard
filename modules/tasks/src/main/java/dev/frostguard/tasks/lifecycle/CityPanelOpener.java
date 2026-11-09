package dev.frostguard.tasks.lifecycle;

import java.util.function.Consumer;

/**
 * Opens the City queue panel for reading, retrying from a clean state.
 *
 * <p>The production log holds 38 sweeps that opened the panel themselves, and all 38 read it. Of 13
 * that found it already left open by the previous task, 8 could not read a single row, so a
 * left-open panel is closed and opened again before anything is read. Retrying goes through the
 * ordinary return-home path, which presses Back only on an unknown screen: Back on a bare Home or
 * World raises the game's quit dialog.</p>
 */
final class CityPanelOpener {

    interface Steps {
        void closeSidebar();

        /** @throws IllegalStateException when the sidebar will not open */
        void openCitySection();

        boolean panelReadable();

        void returnHome();
    }

    private final Steps steps;
    private final Consumer<String> warn;

    CityPanelOpener(Steps steps, Consumer<String> warn) {
        this.steps = steps;
        this.warn = warn;
    }

    /** @return true once a readable panel is on screen */
    boolean open(int attempts) {
        for (int attempt = 1; attempt <= attempts; attempt++) {
            steps.closeSidebar();
            String problem = tryOpen();
            if (problem == null) {
                return true;
            }
            warn.accept(problem + " on attempt " + attempt + "/" + attempts + "; closing it and retrying from Home.");
            steps.closeSidebar();
            steps.returnHome();
        }
        return false;
    }

    private String tryOpen() {
        try {
            steps.openCitySection();
        } catch (IllegalStateException failure) {
            return "Sidebar did not open (" + failure.getMessage() + ")";
        }
        return steps.panelReadable() ? null : "City queue panel not readable";
    }
}
