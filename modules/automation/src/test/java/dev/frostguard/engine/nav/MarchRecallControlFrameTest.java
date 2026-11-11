package dev.frostguard.engine.nav;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.vision.match.OpenCvPatternLocator;

/**
 * Which march panel actually carries a recall control.
 *
 * <p>Gather recall searched the sidebar March Queue and reported "controls missing" in every log
 * back to 2026-10-06. These two frames, taken seconds apart on 2026-11-11, show why: the same six
 * marches in the same order, recall controls on one panel and none on the other.
 */
class MarchRecallControlFrameTest {

    private static final String DIR = "/navigation/march-recall-20261111/";
    private static final int THRESHOLD = 90;

    @BeforeAll
    static void loadOpenCv() throws IOException {
        try {
            OpenCvPatternLocator.loadNativeLibrary();
        } catch (UnsatisfiedLinkError ignored) {
            // Another frame test may already have loaded it in this JVM.
        }
    }

    @Test
    void findsARecallControlOnEveryGatheringRowOfTheWorldList() throws IOException {
        List<ImageSearchResultData> hits = locate("world-marching-list.png");

        assertEquals(5, hits.size(), () -> "Five gathering rows carry recall: " + hits);
        List<Integer> rows = new ArrayList<>();
        for (ImageSearchResultData hit : hits) {
            rows.add(WorldMarchingList.rowAt(hit.getPoint().getY()));
        }
        Collections.sort(rows);
        assertEquals(List.of(0, 1, 2, 3, 4), rows,
                () -> "Each control lands on its own row, top five: " + hits);
    }

    @Test
    void theReturningRowOffersNoRecall() throws IOException {
        // Row 6 is a returning march; it shows a view control instead. Recalling it is not on
        // offer, and the row must not inherit a neighbour's control.
        for (ImageSearchResultData hit : locate("world-marching-list.png")) {
            assertTrue(WorldMarchingList.rowAt(hit.getPoint().getY()) != 5,
                    () -> "No recall belongs to the returning row: " + hit);
        }
    }

    @Test
    void findsNoRecallControlAnywhereOnTheSidebarMarchQueue() throws IOException {
        // The panel the code used to open. Its rows carry jump arrows, so the search that looked
        // here could only ever come back empty.
        List<ImageSearchResultData> hits = locate("sidebar-march-queue.png");

        assertTrue(hits.isEmpty(), () -> "The sidebar March Queue has no recall control: " + hits);
    }

    @Test
    void readsGatheringOnExactlyTheFiveGatheringRows() throws IOException {
        List<Integer> rows = labelRows("world-marching-list.png");

        assertEquals(List.of(0, 1, 2, 3, 4), rows,
                "Only a row that reads Gathering may ever be recalled");
    }

    @Test
    void doesNotReadTheReturningRowAsGathering() throws IOException {
        // Row 6 is a returning march. Rally and reinforcement rows look like it rather than like a
        // gather, and recalling one of those would throw away a battle, not a pile of ore.
        assertTrue(!labelRows("world-marching-list.png").contains(5),
                "The returning row must never read as Gathering");
    }

    @Test
    void readsNoGatheringRowOnTheSidebarMarchQueue() throws IOException {
        assertTrue(labelRows("sidebar-march-queue.png").isEmpty(),
                "Nothing on the sidebar panel may be mistaken for a recallable gather row");
    }

    private List<Integer> labelRows(String frame) throws IOException {
        List<ImageSearchResultData> hits = OpenCvPatternLocator.locateAllPatterns(resource(frame),
                TemplatesEnum.MARCHES_ROW_GATHERING_LABEL.getTemplate(),
                CommonGameAreas.WORLD_MARCHING_ROW_LABELS.topLeft(),
                CommonGameAreas.WORLD_MARCHING_ROW_LABELS.bottomRight(),
                THRESHOLD, WorldMarchingList.ROWS);
        List<Integer> rows = new ArrayList<>();
        for (ImageSearchResultData hit : hits) {
            int row = WorldMarchingList.rowAt(hit.getPoint().getY());
            if (row >= 0) {
                rows.add(row);
            }
        }
        Collections.sort(rows);
        return rows;
    }

    private List<ImageSearchResultData> locate(String frame) throws IOException {
        return OpenCvPatternLocator.locateAllPatterns(resource(frame),
                TemplatesEnum.MARCHES_AREA_RECALL_BUTTON.getTemplate(),
                CommonGameAreas.WORLD_MARCHING_CONTROLS.topLeft(),
                CommonGameAreas.WORLD_MARCHING_CONTROLS.bottomRight(),
                THRESHOLD, WorldMarchingList.ROWS);
    }

    private byte[] resource(String name) throws IOException {
        try (InputStream stream = getClass().getResourceAsStream(DIR + name)) {
            return Objects.requireNonNull(stream, "Missing test resource: " + DIR + name).readAllBytes();
        }
    }
}
