package dev.frostguard.tasks.city;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Objects;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import dev.frostguard.api.domain.PointData;

/**
 * Pins the piece-grid missing-card scan to live frames.
 *
 * <p>My Requests' Request button opens a scene's piece grid with an animated hand pointing at a
 * missing piece, not a piece card. The routine used to back out of that screen on every pass, so no
 * request was ever filed. Both fixtures are native 720x1280 captures from 2026-10-28 and show only
 * puzzle art: no player names, aliases or account identifiers.
 *
 * <p>Evidence level: saved real-frame verification.
 */
class MonumentPieceGridFrameTest {

    private static BufferedImage frame(String name) throws Exception {
        return ImageIO.read(Objects.requireNonNull(
                MonumentPieceGridFrameTest.class.getResourceAsStream("/monument/" + name),
                "missing fixture " + name));
    }

    private static boolean inside(PointData p, int x0, int y0, int x1, int y1) {
        return p.getX() >= x0 && p.getX() <= x1 && p.getY() >= y0 && p.getY() <= y1;
    }

    @Test
    void findsEveryMissingCardIncludingTheOneUnderTheHand() throws Exception {
        List<PointData> cells = MonumentRoutine.findMissingPieceCells(
                frame("piece-grid-hand-pointer-20261028.png"));

        // Grey cards on this frame: left column rows 3 and 4 (the hand covers row 3), right column row 4.
        assertEquals(3, cells.size(), "cells: " + cells);
        assertTrue(inside(cells.get(0), 78, 715, 248, 857), "first cell is the hand-pointed card: " + cells);
        assertTrue(inside(cells.get(1), 78, 900, 248, 1042), "cells: " + cells);
        assertTrue(inside(cells.get(2), 476, 900, 646, 1042), "cells: " + cells);
    }

    @Test
    void findsTheSingleMissingCardWhenTheGridSitsLower() throws Exception {
        List<PointData> cells = MonumentRoutine.findMissingPieceCells(
                frame("piece-grid-one-missing-20261028.png"));

        // This scene's rows sit ~40px lower; only the bottom-right card is missing.
        assertEquals(1, cells.size(), "cells: " + cells);
        assertTrue(inside(cells.get(0), 476, 935, 646, 1064), "cells: " + cells);
    }

    @Test
    void reportsNoMissingCardOnTheAlbumsHub() throws Exception {
        assertTrue(MonumentRoutine.findMissingPieceCells(frame("tundra-albums-hub-20261028.png")).isEmpty());
    }

    @Test
    void reportsNoMissingCardOnAFrameOfTheWrongSize() {
        assertTrue(MonumentRoutine.findMissingPieceCells(
                new BufferedImage(1080, 1920, BufferedImage.TYPE_INT_RGB)).isEmpty());
    }
}
