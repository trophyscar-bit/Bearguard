package dev.frostguard.tasks.shop;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.engine.diagnostics.DiagnosticSnapshotStore;

class BuildingDailyChestSnapshotTest {

    @TempDir
    Path workspace;

    @Test
    void theNoBubbleFrameIsKeptUnderTheBuildingChestSnapshotFolder() {
        RawImageData frame = RawImageData.capture(new byte[4 * 2 * 4], 4, 2, 32);

        String saved = new DiagnosticSnapshotStore(workspace).write(
                frame,
                BuildingDailyChestRoutine.SNAPSHOT_ACTIVITY,
                BuildingDailyChestRoutine.SNAPSHOT_NO_BUBBLE,
                Instant.parse("2026-11-13T20:08:00.000Z")).orElseThrow();

        assertEquals("logs/snapshot/buildingchest/20261113T200800.000Z-nobubble.png", saved);
    }
}
