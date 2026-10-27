package dev.frostguard.engine.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.engine.diagnostics.DiagnosticSnapshotStore;
import dev.frostguard.engine.nav.SidebarDestination;
import dev.frostguard.engine.schedule.LaunchPoint;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CrystalLaboratoryNavigationTest {

    private static final AccountDescriptor PROFILE =
            new AccountDescriptor(999L, "Synthetic Crystal Lab", "review", true, 0L, 0L);

    @TempDir
    Path workspace;

    @Test
    void opensThePersistentAnchorAndValidatesTheCrystalLabInterface() {
        RecordingNavigation navigation = new RecordingNavigation(workspace, true, true);

        assertTrue(navigation.navigateToSidebarDestination(SidebarDestination.CRYSTAL_LABORATORY));
        assertEquals(1, navigation.sidebarAttempts);
        assertEquals(1, navigation.tutorialDismissals);
        assertEquals(1, navigation.anchorSearches);
        assertEquals(1, navigation.anchorTaps);
        assertEquals(1, navigation.interfaceSearches);
        assertEquals(0, navigation.homeRecoveries);
    }

    @Test
    void savesTheMissingAnchorEvidenceAfterTwoBoundedAttempts() throws IOException {
        RecordingNavigation navigation = new RecordingNavigation(workspace, false, true);

        assertFalse(navigation.navigateToSidebarDestination(SidebarDestination.CRYSTAL_LABORATORY));
        assertEquals(2, navigation.sidebarAttempts);
        assertEquals(2, navigation.anchorSearches);
        assertEquals(0, navigation.anchorTaps);
        assertEquals(1, navigation.homeRecoveries);
        assertEquals("building-anchor-missing", navigation.snapshotType);
        assertTrue(Files.exists(navigation.snapshotPath));
    }

    @Test
    void savesTheMissingInterfaceEvidenceAfterTappingTheAnchor() throws IOException {
        RecordingNavigation navigation = new RecordingNavigation(workspace, true, false);

        assertFalse(navigation.navigateToSidebarDestination(SidebarDestination.CRYSTAL_LABORATORY));
        assertEquals(2, navigation.anchorTaps);
        assertEquals(6, navigation.interfaceSearches);
        assertEquals("screen-validation-failed", navigation.snapshotType);
        assertTrue(Files.exists(navigation.snapshotPath));
    }

    @Test
    void givesUpAfterExactlyTwoFailedSidebarAttempts() {
        RecordingNavigation navigation = new RecordingNavigation(workspace, true, true);
        navigation.sidebarAvailable = false;

        assertFalse(navigation.navigateToSidebarDestination(SidebarDestination.CRYSTAL_LABORATORY));
        assertEquals(2, navigation.sidebarAttempts);
        assertEquals(0, navigation.tutorialDismissals);
        assertEquals(1, navigation.homeRecoveries);
        assertNull(navigation.snapshotType);
    }

    private static final class RecordingNavigation extends NavigationHelper {
        private final Path workspace;
        private final boolean anchorFound;
        private final boolean interfaceVisible;
        private boolean sidebarAvailable = true;
        private int sidebarAttempts;
        private int tutorialDismissals;
        private int anchorSearches;
        private int anchorTaps;
        private int interfaceSearches;
        private int homeRecoveries;
        private String snapshotType;
        private Path snapshotPath;

        private RecordingNavigation(Path workspace, boolean anchorFound, boolean interfaceVisible) {
            super(null, "review", PROFILE);
            this.workspace = workspace;
            this.anchorFound = anchorFound;
            this.interfaceVisible = interfaceVisible;
        }

        @Override
        boolean navigateToDirectSidebarDestination(SidebarDestination destination) {
            assertEquals(SidebarDestination.CRYSTAL_LABORATORY, destination);
            sidebarAttempts++;
            return sidebarAvailable;
        }

        @Override
        public void ensureCorrectScreenLocation(LaunchPoint target) {
            if (target == LaunchPoint.HOME) {
                homeRecoveries++;
            }
        }

        @Override
        ImageSearchResultData locateCrystalLabBuildingAnchor() {
            anchorSearches++;
            return anchorFound ? ImageSearchResultData.hit(421, 678, 100.0, 44, 48)
                    : ImageSearchResultData.miss();
        }

        @Override
        ImageSearchResultData locateCrystalLabInterface() {
            interfaceSearches++;
            return interfaceVisible ? ImageSearchResultData.hit(360, 640, 100.0)
                    : ImageSearchResultData.miss();
        }

        @Override
        void tapCrystalLabTutorialDismissal() {
            tutorialDismissals++;
        }

        @Override
        void tapCrystalLabBuildingAnchor(ImageSearchResultData anchor) {
            anchorTaps++;
        }

        @Override
        void waitForCrystalLabTransition(long milliseconds) {
            // Keep the bounded state-machine test deterministic and instant.
        }

        @Override
        String retainCrystalLabDiagnosticSnapshot(String type) {
            snapshotType = type;
            String result = super.retainCrystalLabDiagnosticSnapshot(type);
            snapshotPath = result.startsWith("snapshot=logs/")
                    ? workspace.resolve(result.substring("snapshot=".length())) : null;
            return result;
        }

        @Override
        RawImageData captureCrystalLabDiagnosticFrame() {
            return RawImageData.capture(new byte[2 * 2 * 4], 2, 2, 32);
        }

        @Override
        DiagnosticSnapshotStore diagnosticSnapshotStore() {
            return new DiagnosticSnapshotStore(workspace);
        }
    }
}
