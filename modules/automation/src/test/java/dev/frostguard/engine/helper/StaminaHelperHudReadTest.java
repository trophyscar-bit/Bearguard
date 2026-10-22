package dev.frostguard.engine.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import dev.frostguard.engine.service.StaminaService;

class StaminaHelperHudReadTest {

    @Test
    void aReadableHudPersistsTheReadingWithBoundedRetries() {
        StaminaService service = StaminaService.getServices();
        RecordingHud hud = new RecordingHud(320);
        StaminaHelper helper = new StaminaHelper(service, 9301L, hud);

        helper.updateStaminaFromHud();

        assertEquals(1, hud.reads);
        assertEquals(5, hud.attempts);
        assertEquals(200L, hud.retryMs);
        assertEquals(320, service.findCurrentStamina(9301L).orElseThrow());
    }

    @Test
    void anUnreadableHudLeavesStaminaUnknown() {
        StaminaService service = StaminaService.getServices();
        StaminaHelper helper = new StaminaHelper(service, 9302L, new RecordingHud(null));

        helper.updateStaminaFromHud();

        assertTrue(service.findCurrentStamina(9302L).isEmpty());
    }

    @Test
    void aReadFailureLeavesStaminaUnknown() {
        StaminaService service = StaminaService.getServices();
        RecordingHud hud = new RecordingHud(null);
        hud.fail = true;
        StaminaHelper helper = new StaminaHelper(service, 9303L, hud);

        helper.updateStaminaFromHud();

        assertTrue(service.findCurrentStamina(9303L).isEmpty());
    }

    /** What OCR returns off the HUD bar: plain digits, with a thousands comma past 999. */
    @Test
    void parsesTheHudNumberIncludingAThousandsComma() {
        assertTrue(StaminaHelper.isHudStaminaText("320"));
        assertTrue(StaminaHelper.isHudStaminaText("1,240"));
        assertEquals(320, StaminaHelper.parseHudStamina("320"));
        assertEquals(1240, StaminaHelper.parseHudStamina("1,240"));
    }

    /** The old dialog format and stray glyphs are not a HUD reading. */
    @Test
    void rejectsTextThatIsNotAHudReading() {
        assertFalse(StaminaHelper.isHudStaminaText(""));
        assertFalse(StaminaHelper.isHudStaminaText("320/200"));
        assertFalse(StaminaHelper.isHudStaminaText(null));
        assertNull(StaminaHelper.parseHudStamina(""));
    }

    private static final class RecordingHud implements StaminaHelper.HudStaminaUi {
        private final Integer reading;
        private boolean fail;
        private int reads;
        private int attempts;
        private long retryMs;

        private RecordingHud(Integer reading) {
            this.reading = reading;
        }

        @Override
        public Integer readStamina(int attempts, long retryMs) {
            reads++;
            this.attempts = attempts;
            this.retryMs = retryMs;
            if (fail) {
                throw new IllegalStateException("simulated OCR failure");
            }
            return reading;
        }
    }
}
