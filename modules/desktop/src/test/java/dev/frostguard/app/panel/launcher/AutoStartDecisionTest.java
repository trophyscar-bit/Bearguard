package dev.frostguard.app.panel.launcher;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static dev.frostguard.api.configs.ConfigurationKeyEnum.AUTO_START_DELAY_SECONDS_INT;
import static dev.frostguard.api.configs.ConfigurationKeyEnum.AUTO_START_ENABLED_BOOL;
import static dev.frostguard.api.configs.ConfigurationKeyEnum.AUTO_START_MODE_STRING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoStartDecisionTest {

    private static Map<String, String> settings(String enabled, String mode, String seconds) {
        Map<String, String> settings = new HashMap<>();
        settings.put(AUTO_START_ENABLED_BOOL.name(), enabled);
        settings.put(AUTO_START_MODE_STRING.name(), mode);
        settings.put(AUTO_START_DELAY_SECONDS_INT.name(), seconds);
        return settings;
    }

    @Test
    void armsForTheConfiguredDelayAtStartup() {
        AutoStartDecision decision = AutoStartDecision.from(settings("true", "Startup Only", "45"), true);

        assertTrue(decision.arms());
        assertEquals(45, decision.seconds());
    }

    @Test
    void everyReasonNotToArmIsNamed() {
        assertNotNull(AutoStartDecision.from(null, true).skipReason());
        assertNotNull(AutoStartDecision.from(settings("false", "Continuous", "45"), true).skipReason());
        assertNotNull(AutoStartDecision.from(settings("true", "Startup Only", "45"), false).skipReason());
        assertNotNull(AutoStartDecision.from(settings("true", "Continuous", "0"), true).skipReason());
    }

    @Test
    void continuousModeArmsAfterStartup() {
        assertTrue(AutoStartDecision.from(settings("true", "Continuous", "45"), false).arms());
    }

    @Test
    void anUnreadableDelayFallsBackInsteadOfDisabling() {
        assertEquals(30, AutoStartDecision.from(settings("true", "Continuous", "soon"), true).seconds());
    }

    @Test
    void aNotRunningEventWhileTheAppOpensDoesNotEndStartup() {
        assertFalse(AutoStartDecision.stopEndsStartup(false, false));
        assertTrue(AutoStartDecision.stopEndsStartup(true, false));
        assertTrue(AutoStartDecision.stopEndsStartup(false, true));
    }
}
