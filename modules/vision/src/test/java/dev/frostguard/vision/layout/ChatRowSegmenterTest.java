package dev.frostguard.vision.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

/**
 * Exercises the segmenter against synthesised frames rather than saved captures.
 *
 * <p>Real chat frames carry player names, alliance tags and avatars, so they cannot be committed
 * without redaction that would destroy the very thing under test. The geometry is what matters
 * here -- a flat page with colourful tiles down a left gutter -- and that reproduces exactly.
 *
 * <p>Evidence level: automated tests, with the parameters taken from live 720x1280 captures.
 */
class ChatRowSegmenterTest {

    private static final int W = 720;
    private static final int H = 1280;
    private static final Color PAGE = new Color(23, 42, 71);
    private static final int TILE = 112;

    /** Flat page with nothing on it. */
    private static BufferedImage blankFrame() {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(PAGE);
        g.fillRect(0, 0, W, H);
        g.dispose();
        return img;
    }

    /**
     * Paints a portrait-like tile: noisy multi-coloured art, which is what separates an avatar
     * from the flat page. {@code brightness} scales it so a deliberately dark portrait can be
     * built -- those are the ones a brightness threshold loses.
     */
    private static void paintAvatar(BufferedImage img, int top, double brightness, long seed) {
        Random rnd = new Random(seed);
        for (int y = top; y < top + TILE && y < H; y++) {
            for (int x = 14; x < 116; x++) {
                int r = (int) (rnd.nextInt(256) * brightness);
                int g = (int) (rnd.nextInt(256) * brightness);
                int b = (int) (rnd.nextInt(256) * brightness);
                img.setRGB(x, y, new Color(r, g, b).getRGB());
            }
        }
    }

    private static BufferedImage frameWithAvatarsAt(double brightness, int... tops) {
        BufferedImage img = blankFrame();
        long seed = 1;
        for (int top : tops) {
            paintAvatar(img, top, brightness, seed++);
        }
        return img;
    }

    @Test
    void findsOneRowPerAvatarTile() {
        BufferedImage frame = frameWithAvatarsAt(1.0, 200, 400, 600, 800);

        List<ChatRowSegmenter.Row> rows = ChatRowSegmenter.segment(frame);

        assertEquals(4, rows.size(), "expected one row per avatar tile");
        for (int i = 0; i < rows.size(); i++) {
            assertTrue(Math.abs(rows.get(i).avatarTop() - (200 + i * 200)) <= 4,
                    "row " + i + " did not anchor on its avatar: " + rows.get(i).avatarTop());
        }
    }

    @Test
    void darkPortraitIsStillFoundWhereABrightnessThresholdWouldLoseIt() {
        // Scaled to a quarter brightness, this tile is darker than plenty of page pixels; only a
        // variance test still recognises it as art.
        BufferedImage frame = frameWithAvatarsAt(0.25, 300, 600);

        List<ChatRowSegmenter.Row> rows = ChatRowSegmenter.segment(frame);

        assertEquals(2, rows.size(), "a dark portrait must still register as an avatar");
    }

    @Test
    void rowRunsToTheNextAvatarSoTallContentIsNotClipped() {
        // The gap after the first avatar is far taller than the tile, which is what a gift card or
        // a sticker looks like. The row has to cover it, not stop at the portrait's own bottom.
        BufferedImage frame = frameWithAvatarsAt(1.0, 200, 700);

        List<ChatRowSegmenter.Row> rows = ChatRowSegmenter.segment(frame);

        assertEquals(2, rows.size());
        ChatRowSegmenter.Row first = rows.get(0);
        assertTrue(first.height() > TILE * 2,
                "row should extend to the next avatar, was only " + first.height() + "px");
        assertEquals(699, first.bottom(), "row should end just above the next avatar");
    }

    @Test
    void adjacentAvatarsSeparatedByOnlyAHairlineAreSplitRatherThanMerged() {
        // Two tiles four pixels apart bridge the merge gap, so they arrive as one tall run and
        // would otherwise be reported as a single enormous message.
        BufferedImage frame = frameWithAvatarsAt(1.0, 300, 300 + TILE + 4, 800);

        List<ChatRowSegmenter.Row> rows = ChatRowSegmenter.segment(frame);

        assertEquals(3, rows.size(), "a bridged pair of avatars must be split back apart");
    }

    @Test
    void flatFrameWithNoFeedProducesNoRows() {
        assertTrue(ChatRowSegmenter.segment(blankFrame()).isEmpty(),
                "a page with no avatars has no messages to report");
    }
}
