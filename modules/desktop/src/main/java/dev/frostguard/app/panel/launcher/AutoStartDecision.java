package dev.frostguard.app.panel.launcher;

import dev.frostguard.api.configs.ConfigurationKeyEnum;

import java.util.Map;

/**
 * Whether the launcher's auto-start countdown should run, and why not when it should not.
 *
 * @param seconds    the countdown length, meaningful only when {@code skipReason} is null
 * @param skipReason the setting that stopped the countdown from arming
 */
record AutoStartDecision(int seconds, String skipReason) {

    private static final int DEFAULT_SECONDS = 30;

    static AutoStartDecision from(Map<String, String> settings, boolean startup) {
        if (settings == null) {
            return skipped("the global settings could not be read");
        }
        if (!Boolean.parseBoolean(settings.getOrDefault(ConfigurationKeyEnum.AUTO_START_ENABLED_BOOL.name(), "false"))) {
            return skipped("auto-start is off");
        }
        String mode = settings.getOrDefault(ConfigurationKeyEnum.AUTO_START_MODE_STRING.name(), "Continuous");
        if ("Startup Only".equalsIgnoreCase(mode) && !startup) {
            return skipped("mode is Startup Only and the app has already been through startup");
        }
        int seconds = readSeconds(settings);
        return seconds > 0 ? new AutoStartDecision(seconds, null) : skipped("the delay is " + seconds + " seconds");
    }

    /**
     * A stopped-state event ends the startup window only after something actually ran or was tried.
     * The engine also reports "not running" while the app is still opening, and treating that as
     * the end of startup cancelled a Startup Only countdown with nothing logged or shown.
     */
    static boolean stopEndsStartup(boolean wasRunning, boolean startAttempted) {
        return wasRunning || startAttempted;
    }

    boolean arms() {
        return skipReason == null;
    }

    private static int readSeconds(Map<String, String> settings) {
        try {
            return Integer.parseInt(settings.getOrDefault(
                    ConfigurationKeyEnum.AUTO_START_DELAY_SECONDS_INT.name(), String.valueOf(DEFAULT_SECONDS)));
        } catch (NumberFormatException unreadable) {
            return DEFAULT_SECONDS;
        }
    }

    private static AutoStartDecision skipped(String reason) {
        return new AutoStartDecision(0, reason);
    }
}
