package dev.frostguard.tasks.city;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Objects;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import dev.frostguard.api.domain.PointData;

/**
 * Pins the Fragment Backpack pack finder and list-end check to live 720x1280 captures.
 *
 * <p>Written after a live pass reported "Opened 0" with a Mythic General pack (count "1") on
 * screen: the old fixed-slot OCR count never read the lone digit. The panel shows no player names.
 *
 * <p>Evidence level: saved real-frame verification.
 */
class MonumentFragmentBackpackFrameTest {

    private static BufferedImage frame(String name) throws Exception {
        return ImageIO.read(Objects.requireNonNull(
                MonumentFragmentBackpackFrameTest.class.getResourceAsStream("/monument/" + name),
                "missing fixture " + name));
    }

    private static void assertInside(PointData p, int x0, int y0, int x1, int y1) {
        assertTrue(p.getX() >= x0 && p.getX() <= x1 && p.getY() >= y0 && p.getY() <= y1,
                "point " + p + " outside (" + x0 + "," + y0 + ")-(" + x1 + "," + y1 + ")");
    }

    @Test
    void findsTheSingleGeneralAlbumPackWithAOneCount() throws Exception {
        List<PointData> packs = MonumentRoutine.findPackIcons(frame("fragment-backpack-general-pack-20261028.png"));
        assertEquals(1, packs.size(), "packs: " + packs);
        assertInside(packs.get(0), 300, 215, 415, 320);
    }

    @Test
    void findsPurpleAndGoldPacksBelowAnEmptyGeneralAlbum() throws Exception {
        List<PointData> packs = MonumentRoutine.findPackIcons(frame("fragment-backpack-two-packs-20260823.png"));
        assertEquals(2, packs.size(), "packs: " + packs);
        assertInside(packs.get(0), 295, 520, 415, 630);
        assertInside(packs.get(1), 295, 770, 415, 890);
    }

    @Test
    void obtainPanelsBannersAndPlanksAreNotPacks() throws Exception {
        assertTrue(MonumentRoutine.findPackIcons(frame("fragment-backpack-no-packs-20261028.png")).isEmpty());
        assertTrue(MonumentRoutine.findPackIcons(frame("fragment-backpack-scrolled-20261028.png")).isEmpty());
        assertTrue(MonumentRoutine.findPackIcons(frame("fragment-backpack-list-end-b-20261028.png")).isEmpty());
        assertTrue(MonumentRoutine.findPackIcons(frame("tundra-albums-hub-20261028.png")).isEmpty());
    }

    @Test
    void listEndIsSeenOnlyWhenASwipeNoLongerMovesTheList() throws Exception {
        assertTrue(MonumentRoutine.listUnchanged(
                frame("fragment-backpack-list-end-a-20261028.png"), frame("fragment-backpack-list-end-b-20261028.png")));
        assertFalse(MonumentRoutine.listUnchanged(
                frame("fragment-backpack-scrolled-20261028.png"), frame("fragment-backpack-no-packs-20261028.png")));
    }
}
