package dev.frostguard.tasks.heroes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.api.runtime.WorkspacePaths;
import dev.frostguard.engine.diagnostics.DiagnosticSnapshotStore;

class GearFailureFramesTest {

    private static final RawImageData FIRST = frame((byte) 0x10);
    private static final RawImageData SECOND = frame((byte) 0x80);

    @TempDir
    Path workspace;

    private String previousWorkspace;

    @BeforeEach
    void useWorkspace() {
        previousWorkspace = System.getProperty(WorkspacePaths.WORKSPACE_PROPERTY);
        System.setProperty(WorkspacePaths.WORKSPACE_PROPERTY, workspace.toString());
    }

    @AfterEach
    void restoreWorkspace() {
        if (previousWorkspace == null) System.clearProperty(WorkspacePaths.WORKSPACE_PROPERTY);
        else System.setProperty(WorkspacePaths.WORKSPACE_PROPERTY, previousWorkspace);
    }

    @Test
    void aFailedReadIsSavedUnderHeroGearWithItsReasonWhateverTheSnapshotSetting() throws IOException {
        GearFailureFrames frames = GearFailureFrames.alwaysOn();

        String saved = frames.readFailed("scan", FIRST, "Gear Details did not read for Boots");

        assertTrue(saved.matches("snapshot=logs/snapshot/herogear/\\d{8}T\\d{6}\\.\\d{3}Z-scan-gear-details-did-not-read-for-boots\\.png"), saved);
        Path file = workspace.resolve(saved.substring("snapshot=".length()));
        assertNotNull(ImageIO.read(file.toFile()), "a readable PNG");
        assertEquals(saved, frames.stopped("scan", "Gear Details did not read for Boots"), "the stop points at the same frame");
        assertEquals(1, pngs().size());
    }

    @Test
    void aStopWithoutAFailedReadSavesTheLastFrameRead() {
        List<RawImageData> savedFrames = new ArrayList<>();
        List<String> types = new ArrayList<>();
        GearFailureFrames frames = new GearFailureFrames((frame, type) -> {
            savedFrames.add(frame);
            types.add(type);
            return "snapshot=" + type;
        });

        frames.seen(FIRST);
        frames.seen(SECOND);
        frames.stopped("forge", "forge dialog unreadable");

        assertEquals(List.of(SECOND), savedFrames);
        assertEquals(List.of("forge-forge dialog unreadable"), types);
    }

    @Test
    void anEarlierStepsFrameIsNotReportedForTheNext() {
        List<String> types = new ArrayList<>();
        GearFailureFrames frames = new GearFailureFrames((frame, type) -> {
            types.add(type);
            return "snapshot=" + type;
        });

        frames.seen(FIRST);
        frames.readFailed("quickequip", FIRST, "Mia's Gear tab did not settle");
        frames.startStep();

        assertEquals("snapshot=enhance-WRONG_LEVEL", frames.stopped("enhance", "WRONG_LEVEL: expected +69"));
        assertSame(FIRST, frames.seen(FIRST));
    }

    @Test
    void textAfterAColonStaysOutOfTheName() {
        assertEquals("scan-could not save the scan", GearFailureFrames.type("scan", "could not save the scan: C:\\Users\\x\\squads"));
    }

    @Test
    void onlyTheNewestTwentyAreKept() throws IOException {
        GearFailureFrames frames = GearFailureFrames.alwaysOn();

        for (int i = 0; i < DiagnosticSnapshotStore.MAX_RETAINED_CAPTURES_PER_ACTIVITY + 3; i++) {
            frames.readFailed("scan", FIRST, "read " + i);
        }

        assertEquals(DiagnosticSnapshotStore.MAX_RETAINED_CAPTURES_PER_ACTIVITY, pngs().size());
    }

    @Test
    void noFrameIsSaidPlainly() {
        assertEquals("snapshot=unavailable; reason=no-frame", GearFailureFrames.alwaysOn().stopped("scan", "Heroes grid did not open"));
    }

    private List<Path> pngs() throws IOException {
        Path dir = workspace.resolve("logs").resolve("snapshot").resolve("herogear");
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.toString().endsWith(".png")).toList();
        }
    }

    private static RawImageData frame(byte shade) {
        return RawImageData.capture(new byte[] {
                shade, 0, 0, (byte) 0xff,
                0, shade, 0, (byte) 0xff,
                0, 0, shade, (byte) 0xff,
                shade, shade, shade, (byte) 0xff
        }, 2, 2, 32);
    }
}
