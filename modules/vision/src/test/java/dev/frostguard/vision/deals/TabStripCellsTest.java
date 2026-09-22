package dev.frostguard.vision.deals;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Objects;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

class TabStripCellsTest {

    @Test
    void findsEachWholeTabWhenTheStripStartsAtItsFirstTab() throws Exception {
        BufferedImage frame = load("tab-strip-first-tab-selected.png");

        List<TabStripCells.Cell> cells = TabStripCells.locate(frame);

        assertEquals(3, cells.size(), cells.toString());
        assertNear(cells.get(0), 39, 233);
        assertNear(cells.get(1), 238, 432);
        assertNear(cells.get(2), 437, 631);
        assertTrue(TabStripCells.isSelected(frame, cells.get(0)));
        assertFalse(TabStripCells.isSelected(frame, cells.get(1)));
    }

    @Test
    void recognisesAnOpenIconOnlyTabAsSelected() throws Exception {
        BufferedImage frame = load("tab-strip-icon-tab-selected.png");

        List<TabStripCells.Cell> cells = TabStripCells.locate(frame);

        assertTrue(TabStripCells.isSelected(frame, cells.get(0)), "the open Dawn Market tab has only artwork");
        assertFalse(TabStripCells.isSelected(frame, cells.get(1)));
    }

    @Test
    void dropsTabsClippedByEitherEdgeAfterTheStripScrolled() throws Exception {
        List<TabStripCells.Cell> scrolledOnce = TabStripCells.locate(load("tab-strip-scrolled-one.png"));
        List<TabStripCells.Cell> scrolledTwice = TabStripCells.locate(load("tab-strip-scrolled-two.png"));

        assertEquals(3, scrolledOnce.size(), scrolledOnce.toString());
        assertNear(scrolledOnce.get(0), 46, 240);
        assertEquals(3, scrolledTwice.size(), scrolledTwice.toString());
        assertNear(scrolledTwice.get(0), 51, 246);
    }

    @Test
    void findsTheSelectedMiddleTabOnTheDealsPanel() throws Exception {
        BufferedImage frame = load("tab-strip-deals-panel.png");

        List<TabStripCells.Cell> cells = TabStripCells.locate(frame);

        assertEquals(3, cells.size(), cells.toString());
        assertFalse(TabStripCells.isSelected(frame, cells.get(0)));
        assertTrue(TabStripCells.isSelected(frame, cells.get(1)));
    }

    @Test
    void keepsTheLastTabAtTheEndOfTheDealsStrip() throws Exception {
        List<TabStripCells.Cell> cells = TabStripCells.locate(load("tab-strip-deals-end.png"));

        assertEquals(3, cells.size(), cells.toString());
        assertNear(cells.get(2), 487, 681);
    }

    private static BufferedImage load(String name) throws Exception {
        return ImageIO.read(Objects.requireNonNull(TabStripCellsTest.class.getResourceAsStream("/deals/" + name), name));
    }

    private static void assertNear(TabStripCells.Cell cell, int left, int right) {
        assertTrue(Math.abs(cell.left() - left) <= 3 && Math.abs(cell.right() - right) <= 3, cell.toString());
    }
}
