package dev.frostguard.tasks.dailies;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.api.runtime.WorkspacePaths;
import dev.frostguard.engine.diagnostics.DiagnosticSnapshotStore;

/**
 * Every Daily Missions run from 19:00 10 Nov to 08:40 11 Nov warned "'Claim All' button not
 * detected": 41 times, each followed by "Claimed 0 rewards". 25 came from the Growth tab, which
 * has no Claim All at all, and 16 from Daily passes with nothing waiting, where the game hides it.
 */
class DailyMissionClaimAllTest {

    @TempDir
    Path workspace;

    @BeforeAll
    static void createTestWorkspace() throws Exception {
        Files.createDirectories(WorkspacePaths.current().root());
    }

    @Test
    void noClaimAllWithNothingWaitingIsNotAWarning() throws Exception {
        TestRoutine routine = new TestRoutine(workspace, false, 0);

        assertEquals(0, routine.redeemAllRewards());

        assertTrue(routine.warnings.isEmpty(), routine.warnings.toString());
        assertTrue(routine.infos.stream().anyMatch(line -> line.contains("nothing is waiting")), routine.infos.toString());
        assertEquals(0, snapshots());
    }

    @Test
    void rowsClaimedWithoutClaimAllMeanTheTemplateMissedAndTheFrameIsKept() throws Exception {
        TestRoutine routine = new TestRoutine(workspace, false, 2);

        assertEquals(2, routine.redeemAllRewards());

        assertEquals(1, routine.warnings.size(), routine.warnings.toString());
        String warning = routine.warnings.get(0);
        assertTrue(warning.contains("2 row(s) were claimable"), warning);
        assertTrue(warning.contains("snapshot=logs/snapshot/dailymission/"), warning);
        assertEquals(1, snapshots());
    }

    @Test
    void aFoundClaimAllIsTappedWithoutSweepingRows() throws Exception {
        TestRoutine routine = new TestRoutine(workspace, true, 5);

        assertEquals(1, routine.redeemAllRewards());

        assertTrue(routine.claimAllTapped);
        assertFalse(routine.sweptRows);
        assertTrue(routine.warnings.isEmpty(), routine.warnings.toString());
    }

    @Test
    void theRowLoopNeverMentionsClaimAllSoTheGrowthTabStaysQuiet() {
        TestRoutine routine = new TestRoutine(workspace, false, 0);

        assertEquals(0, routine.redeemRewardsIndividually());

        assertTrue(routine.warnings.isEmpty(), routine.warnings.toString());
        assertTrue(routine.infos.stream().noneMatch(line -> line.contains("Claim All")), routine.infos.toString());
    }

    private long snapshots() throws Exception {
        Path dir = workspace.resolve("logs").resolve("snapshot");
        if (!Files.exists(dir)) {
            return 0;
        }
        try (Stream<Path> files = Files.walk(dir)) {
            return files.filter(path -> path.toString().endsWith(".png")).count();
        }
    }

    private static final class TestRoutine extends DailyMissionRoutine {
        private final Path workspace;
        private final boolean claimAllShown;
        private final int claimableRows;
        private final List<String> infos = new ArrayList<>();
        private final List<String> warnings = new ArrayList<>();
        private boolean claimAllTapped;
        private boolean sweptRows;

        private TestRoutine(Path workspace, boolean claimAllShown, int claimableRows) {
            super(new AccountDescriptor(1L, "Test", "1", true, 1L, 30L), TpDailyTaskEnum.DAILY_MISSIONS);
            this.workspace = workspace;
            this.claimAllShown = claimAllShown;
            this.claimableRows = claimableRows;
        }

        @Override
        ImageSearchResultData seekForClaimAllButton() {
            return claimAllShown ? new ImageSearchResultData(true, new PointData(360, 1000), 95.0)
                    : ImageSearchResultData.miss();
        }

        @Override
        void redeemAllRewardsAtOnce(ImageSearchResultData claimAllResult) {
            claimAllTapped = true;
        }

        @Override
        int redeemRewardsIndividually() {
            sweptRows = true;
            return claimableRows > 0 ? claimableRows : super.redeemRewardsIndividually();
        }

        @Override
        ImageSearchResultData seekForIndividualClaimButton() {
            return ImageSearchResultData.miss();
        }

        @Override
        RawImageData captureDailyTabFrame() {
            return RawImageData.capture(new byte[4 * 4 * 4], 4, 4, 32);
        }

        @Override
        DiagnosticSnapshotStore diagnosticSnapshotStore() {
            return new DiagnosticSnapshotStore(workspace);
        }

        @Override
        public void logInfo(String message) {
            infos.add(message);
        }

        @Override
        public void logWarning(String message) {
            warnings.add(message);
        }

        @Override
        public void logDebug(String message) {
        }
    }
}
