package dev.frostguard.tasks.diagnostics;

import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.engine.diagnostics.DiagnosticSnapshotStore;
import dev.frostguard.engine.emulator.EmulatorController;

import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;

/** Captures terminal task failures to the workspace diagnostic snapshot store. */
public final class TaskDiagnosticSnapshots {

    private TaskDiagnosticSnapshots() {
    }

    public static String capture(EmulatorController emulator, String emulatorNumber, String activity, String type) {
        return capture(() -> emulator.captureScreen(emulatorNumber), activity, type);
    }

    /** For a failure that cannot be diagnosed without its frame, whatever the snapshot setting says. */
    public static String captureAlways(EmulatorController emulator, String emulatorNumber, String activity, String type) {
        return capture(() -> emulator.captureScreen(emulatorNumber), activity, type,
                DiagnosticSnapshotStore.alwaysOnForCurrentWorkspace());
    }

    /** Saves a frame already in hand, whatever the snapshot setting says; nothing is captured or tapped. */
    public static String saveAlways(RawImageData frame, String activity, String type) {
        return capture(() -> frame, activity, type, DiagnosticSnapshotStore.alwaysOnForCurrentWorkspace());
    }

    /**
     * For an unreadable OCR region: saves the frame and that region of the same frame
     * under one timestamp, whatever the snapshot setting says. The store keeps the
     * newest {@value DiagnosticSnapshotStore#MAX_RETAINED_CAPTURES_PER_ACTIVITY} files
     * per activity, so this cannot grow without bound.
     */
    public static String captureAlwaysWithRegion(EmulatorController emulator, String emulatorNumber,
            String activity, String type, AreaData region) {
        return captureWithRegion(() -> emulator.captureScreen(emulatorNumber), activity, type, region,
                DiagnosticSnapshotStore.alwaysOnForCurrentWorkspace());
    }

    static String captureWithRegion(Supplier<RawImageData> takeFrame, String activity, String type,
            AreaData region, DiagnosticSnapshotStore store) {
        try {
            if (!store.isEnabled()) {
                return "snapshot=disabled";
            }
            RawImageData frame = takeFrame.get();
            if (frame == null) {
                return "snapshot=unavailable; reason=no-frame";
            }
            Instant capturedAt = Instant.now();
            Optional<String> full = store.write(frame, activity, type, capturedAt);
            if (full.isEmpty()) {
                return "snapshot=unavailable; reason=write-failed";
            }
            String crop = crop(frame, region)
                    .flatMap(part -> store.write(part, activity, type + "-crop", capturedAt))
                    .map(path -> "crop=" + path)
                    .orElse("crop=unavailable");
            return "snapshot=" + full.get() + "; " + crop;
        } catch (RuntimeException failure) {
            TaskControlSignals.rethrowControlSignal(failure);
            return "snapshot=unavailable; reason=" + failure.getClass().getSimpleName();
        }
    }

    /** Copies {@code region} out of {@code frame}, clamped to the frame; empty if nothing is left. */
    static Optional<RawImageData> crop(RawImageData frame, AreaData region) {
        if (frame == null || region == null || !frame.isValid()) {
            return Optional.empty();
        }
        int x1 = Math.max(0, Math.min(region.topLeft().getX(), region.bottomRight().getX()));
        int y1 = Math.max(0, Math.min(region.topLeft().getY(), region.bottomRight().getY()));
        int x2 = Math.min(frame.getWidth(), Math.max(region.topLeft().getX(), region.bottomRight().getX()));
        int y2 = Math.min(frame.getHeight(), Math.max(region.topLeft().getY(), region.bottomRight().getY()));
        int width = x2 - x1;
        int height = y2 - y1;
        if (width <= 0 || height <= 0) {
            return Optional.empty();
        }
        int bytesPerPixel = frame.stride() / frame.getWidth();
        int rowBytes = width * bytesPerPixel;
        byte[] pixels = new byte[rowBytes * height];
        for (int row = 0; row < height; row++) {
            System.arraycopy(frame.getData(), (y1 + row) * frame.stride() + x1 * bytesPerPixel,
                    pixels, row * rowBytes, rowBytes);
        }
        return Optional.of(RawImageData.capture(pixels, width, height, frame.getColorDepth()));
    }

    static String capture(Supplier<RawImageData> takeFrame, String activity, String type) {
        return capture(takeFrame, activity, type, DiagnosticSnapshotStore.forCurrentWorkspace());
    }

    static String capture(Supplier<RawImageData> takeFrame, String activity, String type,
            DiagnosticSnapshotStore store) {
        try {
            if (!store.isEnabled()) {
                return "snapshot=disabled";
            }
            RawImageData frame = takeFrame.get();
            if (frame == null) {
                return "snapshot=unavailable; reason=no-frame";
            }
            return store.write(frame, activity, type, Instant.now())
                    .map(path -> "snapshot=" + path)
                    .orElse("snapshot=unavailable; reason=write-failed");
        } catch (RuntimeException failure) {
            TaskControlSignals.rethrowControlSignal(failure);
            return "snapshot=unavailable; reason=" + failure.getClass().getSimpleName();
        }
    }
}
