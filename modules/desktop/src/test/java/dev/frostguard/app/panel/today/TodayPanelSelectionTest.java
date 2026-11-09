package dev.frostguard.app.panel.today;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TodayPanelSelectionTest {

    private static final Set<String> KNOWN = Set.of("a", "b", "first");

    private static void reload(TodayPanelSelection selection, String running, boolean editing) {
        selection.onReload(running, editing, KNOWN::contains, () -> "first");
    }

    @Test
    void followingMovesToTheRunningTask() {
        TodayPanelSelection selection = new TodayPanelSelection();
        reload(selection, "a", false);
        assertEquals("a", selection.selectedKey());
        reload(selection, "b", false);
        assertEquals("b", selection.selectedKey());
    }

    @Test
    void aPinnedTaskStaysWhileAnotherStarts() {
        TodayPanelSelection selection = new TodayPanelSelection();
        selection.pin("a");
        reload(selection, "b", false);
        assertEquals("a", selection.selectedKey());
        assertFalse(selection.following());
    }

    @Test
    void clickingATaskPinsItAndClickingTheRunningOneFollows() {
        TodayPanelSelection selection = new TodayPanelSelection();
        selection.clicked("a", "b");
        reload(selection, "b", false);
        assertEquals("a", selection.selectedKey());

        selection.clicked("b", "b");
        assertTrue(selection.following());
    }

    @Test
    void resumingFollowingMovesToTheRunningTask() {
        TodayPanelSelection selection = new TodayPanelSelection();
        selection.pin("a");
        selection.resumeFollowing("b");
        assertEquals("b", selection.selectedKey());
        assertTrue(selection.following());
    }

    @Test
    void followingWaitsForAnEditAndThenResumes() {
        TodayPanelSelection selection = new TodayPanelSelection();
        reload(selection, "a", false);

        reload(selection, "b", true);
        assertEquals("a", selection.selectedKey());
        assertTrue(selection.following());

        reload(selection, "b", false);
        assertEquals("b", selection.selectedKey());
    }

    @Test
    void aTaskThatIsGoneFallsBackEvenDuringAnEdit() {
        TodayPanelSelection selection = new TodayPanelSelection();
        selection.pin("removed");
        reload(selection, null, true);
        assertEquals("first", selection.selectedKey());
    }

    @Test
    void resetReturnsToFollowing() {
        TodayPanelSelection selection = new TodayPanelSelection();
        selection.pin("a");
        selection.reset();
        reload(selection, "b", false);
        assertEquals("b", selection.selectedKey());
    }
}
