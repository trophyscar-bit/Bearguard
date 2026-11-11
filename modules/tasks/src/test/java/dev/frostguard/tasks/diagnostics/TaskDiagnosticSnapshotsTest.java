package dev.frostguard.tasks.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.engine.error.ADBConnectionException;
import dev.frostguard.engine.error.StopExecutionException;
import dev.frostguard.engine.diagnostics.DiagnosticSnapshotStore;

class TaskDiagnosticSnapshotsTest {
    @TempDir
    Path workspace;

    @Test
    void rethrowsAWrappedAdbFailureInsteadOfRecordingADomainMiss() {
        ADBConnectionException adb = new ADBConnectionException("offline");

        assertThrows(ADBConnectionException.class, () -> TaskDiagnosticSnapshots.capture(
                () -> {
                    throw new RuntimeException("screencap failed", adb);
                },
                "crystallaboratory",
                "ocr-failed", new DiagnosticSnapshotStore(workspace)));
    }

    @Test
    void rethrowsAStopSignalFromTheCaptureItself() {
        assertThrows(StopExecutionException.class, () -> TaskDiagnosticSnapshots.capture(
                () -> {
                    throw new StopExecutionException("halt");
                },
                "mercenaryevent",
                "task-error", new DiagnosticSnapshotStore(workspace)));
    }

    @Test
    void reportsAnOrdinaryCaptureFailureAsUnavailable() {
        assertEquals("snapshot=unavailable; reason=RuntimeException",
                TaskDiagnosticSnapshots.capture(() -> {
                    throw new RuntimeException("io");
                }, "research", "queue-ocr", new DiagnosticSnapshotStore(workspace)));
        assertEquals("snapshot=unavailable; reason=no-frame",
                TaskDiagnosticSnapshots.capture(() -> null, "research", "queue-ocr",
                        new DiagnosticSnapshotStore(workspace)));
    }

    @Test
    void retainsNormalThirtyTwoBitScreencapWhenValidityUsesBytesPerPixel()
            throws IOException {
        RawImageData frame = RawImageData.capture(new byte[] {
                (byte) 0xff, 0, 0, (byte) 0xff,
                0, (byte) 0xff, 0, (byte) 0xff,
                0, 0, (byte) 0xff, (byte) 0xff,
                (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff
        }, 2, 2, 32);
        assertEquals(32, frame.getColorDepth());
        assertEquals(4, frame.getFrameBytes().length / frame.pixelCount());
        assertTrue(frame.isValid());

        String result = TaskDiagnosticSnapshots.capture(
                () -> frame, "nomadicmerchant", "resource-claim", new DiagnosticSnapshotStore(workspace));

        assertTrue(result.startsWith("snapshot=logs/snapshot/nomadicmerchant/"), result);
        String relativePath = result.substring("snapshot=".length());
        var snapshot = workspace.resolve(relativePath);
        assertTrue(Files.isRegularFile(snapshot), snapshot.toString());
        BufferedImage saved = ImageIO.read(snapshot.toFile());
        assertNotNull(saved, "saved diagnostic must be a readable PNG");
        assertEquals(2, saved.getWidth());
        assertEquals(2, saved.getHeight());
    }

    @Test
    void cropCopiesExactlyTheRegionPixels() {
        RawImageData frame = numberedFrame(6, 5);

        RawImageData part = TaskDiagnosticSnapshots.crop(frame, AreaData.of(2, 1, 5, 3)).orElseThrow();

        assertEquals(3, part.getWidth());
        assertEquals(2, part.getHeight());
        for (int y = 0; y < 2; y++) {
            for (int x = 0; x < 3; x++) {
                int source = ((1 + y) * 6 + (2 + x)) * 4;
                int target = (y * 3 + x) * 4;
                assertEquals(frame.getData()[source], part.getData()[target], "pixel " + x + "," + y);
            }
        }
    }

    @Test
    void cropIsClampedToTheFrameAndEmptyWhenOutside() {
        RawImageData frame = numberedFrame(6, 5);

        RawImageData clamped = TaskDiagnosticSnapshots.crop(frame, AreaData.of(4, 3, 50, 50)).orElseThrow();
        assertEquals(2, clamped.getWidth());
        assertEquals(2, clamped.getHeight());
        assertTrue(TaskDiagnosticSnapshots.crop(frame, AreaData.of(10, 10, 20, 20)).isEmpty());
    }

    @Test
    void savesTheFrameAndItsRegionUnderOneTimestamp() throws IOException {
        RawImageData frame = numberedFrame(6, 5);

        String result = TaskDiagnosticSnapshots.captureWithRegion(() -> frame, "herorecruitment",
                "Epic-timer-unread", AreaData.of(1, 1, 4, 3), new DiagnosticSnapshotStore(workspace));

        Matcher paths = Pattern.compile("^snapshot=(\\S+); crop=(\\S+)$").matcher(result);
        assertTrue(paths.matches(), result);
        String full = paths.group(1);
        String crop = paths.group(2);
        assertTrue(full.startsWith("logs/snapshot/herorecruitment/") && full.endsWith("-epic-timer-unread.png"), full);
        assertEquals(full.replace(".png", "-crop.png"), crop);
        BufferedImage savedFull = ImageIO.read(workspace.resolve(full).toFile());
        BufferedImage savedCrop = ImageIO.read(workspace.resolve(crop).toFile());
        assertEquals(6, savedFull.getWidth());
        assertEquals(3, savedCrop.getWidth());
        assertEquals(2, savedCrop.getHeight());
    }

    @Test
    void repeatedFailuresKeepOnlyTheNewestCaptures() throws IOException {
        DiagnosticSnapshotStore store = new DiagnosticSnapshotStore(workspace);
        RawImageData frame = numberedFrame(6, 5);

        for (int failure = 0; failure < 15; failure++) {
            TaskDiagnosticSnapshots.captureWithRegion(() -> frame, "herorecruitment",
                    "advanced-timer-unread", AreaData.of(0, 0, 3, 3), store);
        }

        try (Stream<Path> files = Files.list(store.directory().resolve("herorecruitment"))) {
            assertEquals(DiagnosticSnapshotStore.MAX_RETAINED_CAPTURES_PER_ACTIVITY, files.count());
        }
    }

    private static RawImageData numberedFrame(int width, int height) {
        byte[] rgba = new byte[width * height * 4];
        for (int pixel = 0; pixel < width * height; pixel++) {
            rgba[pixel * 4] = (byte) pixel;
            rgba[pixel * 4 + 1] = (byte) (pixel * 3);
            rgba[pixel * 4 + 2] = (byte) (pixel * 7);
            rgba[pixel * 4 + 3] = (byte) 0xff;
        }
        return RawImageData.capture(rgba, width, height, 32);
    }
}
