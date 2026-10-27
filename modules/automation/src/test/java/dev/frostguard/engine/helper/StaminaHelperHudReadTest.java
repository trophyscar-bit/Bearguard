package dev.frostguard.engine.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.engine.diagnostics.DiagnosticSnapshotStore;
import dev.frostguard.engine.service.StaminaService;

class StaminaHelperHudReadTest {

    @TempDir
    Path workspace;

    @Test
    void aReadableHudPersistsTheReadingWithBoundedRetries() {
        StaminaService service = StaminaService.getServices();
        RecordingHud hud = new RecordingHud(320);
        StaminaHelper helper = new StaminaHelper(service, 9301L, hud);

        helper.updateStaminaFromHud();

        assertEquals(List.of("read"), hud.events);
        assertEquals(5, hud.attempts);
        assertEquals(200L, hud.retryMs);
        assertEquals(320, service.findCurrentStamina(9301L).orElseThrow());
    }

    @Test
    void anUnreadableHudLeavesStaminaUnknown() {
        StaminaService service = StaminaService.getServices();
        StaminaHelper helper = new SnapshotStaminaHelper(service, 9302L, new RecordingHud(null), workspace);

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
        assertEquals(List.of("read"), hud.events);
    }

    @Test
    void exhaustedOcrCapturesStaminaFailureSnapshot() throws IOException {
        StaminaService service = StaminaService.getServices();
        RecordingHud hud = new RecordingHud(null);
        SnapshotStaminaHelper helper = new SnapshotStaminaHelper(service, 9304L, hud, workspace);

        helper.updateStaminaFromHud();

        assertTrue(service.findCurrentStamina(9304L).isEmpty());
        assertEquals(List.of("read", "snapshot"), hud.events);
        assertEquals(1, helper.captureCount);
        assertTrue(helper.snapshotResult.startsWith("snapshot=logs/snapshot/stamina/"));
        Path captures = workspace.resolve("logs/snapshot/stamina");
        try (var files = Files.list(captures)) {
            List<Path> saved = files.toList();
            assertEquals(1, saved.size());
            assertTrue(saved.getFirst().getFileName().toString().endsWith("-ocr-failed.png"));
        }
    }

    @Test
    void disabledDiagnosticsDoNotCaptureOrWriteAFile() {
        StaminaService service = StaminaService.getServices();
        RecordingHud hud = new RecordingHud(null);
        SnapshotStaminaHelper helper = new SnapshotStaminaHelper(service, 9305L, hud, workspace);
        helper.snapshotsEnabled = false;

        helper.updateStaminaFromHud();

        assertTrue(service.findCurrentStamina(9305L).isEmpty());
        assertEquals(List.of("read"), hud.events);
        assertEquals(0, helper.captureCount);
        assertEquals("snapshot=disabled", helper.snapshotResult);
        assertTrue(Files.notExists(workspace.resolve("logs/snapshot/stamina")));
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
        private final List<String> events = new ArrayList<>();
        private boolean fail;
        private int attempts;
        private long retryMs;

        private RecordingHud(Integer reading) {
            this.reading = reading;
        }

        @Override
        public Integer readStamina(int attempts, long retryMs) {
            events.add("read");
            this.attempts = attempts;
            this.retryMs = retryMs;
            if (fail) {
                throw new IllegalStateException("simulated OCR failure");
            }
            return reading;
        }
    }

    private static final class SnapshotStaminaHelper extends StaminaHelper {
        private final RecordingHud hud;
        private final DiagnosticSnapshotStore snapshots;
        private boolean snapshotsEnabled = true;
        private int captureCount;
        private String snapshotResult;

        private SnapshotStaminaHelper(StaminaService persistence, Long accountKey,
                                      RecordingHud hud, Path workspace) {
            super(persistence, accountKey, hud);
            this.hud = hud;
            this.snapshots = new DiagnosticSnapshotStore(workspace);
        }

        @Override
        RawImageData captureStaminaDiagnosticFrame() {
            captureCount++;
            hud.events.add("snapshot");
            return RawImageData.capture(new byte[2 * 2 * 4], 2, 2, 32);
        }

        @Override
        DiagnosticSnapshotStore diagnosticSnapshotStore() {
            return snapshots;
        }

        @Override
        boolean diagnosticSnapshotsEnabled() {
            return snapshotsEnabled;
        }

        @Override
        String retainStaminaOcrFailureSnapshot() {
            snapshotResult = super.retainStaminaOcrFailureSnapshot();
            return snapshotResult;
        }
    }
}
