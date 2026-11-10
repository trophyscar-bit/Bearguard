package dev.frostguard.tasks.combat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import dev.frostguard.api.domain.PointData;

/** Checks both Go boxes against a real Special Buildings frame, so a tap lands on the right trap's button. */
class BearTrapGoButtonTest {

    @Test
    void eachTrapsBoxSitsOnItsOwnGoButton() throws IOException {
        BufferedImage frame = frame();
        for (int trap = 1; trap <= 2; trap++) {
            PointData[] box = BearTrapRoutine.goButtonFor(trap);
            int onButton = 0;
            int total = 0;
            for (int x = box[0].getX(); x <= box[1].getX(); x += 2) {
                for (int y = box[0].getY(); y <= box[1].getY(); y += 2) {
                    total++;
                    if (isGoButton(frame.getRGB(x, y))) {
                        onButton++;
                    }
                }
            }
            // The label's dark outline is part of the button too, but is not tested for; most of the box
            // must still be the blue face or the white label, which the pale card around it never is.
            assertTrue(onButton * 100 / total >= 75,
                    "Trap " + trap + " box is only " + (onButton * 100 / total) + "% on its Go button");
        }
    }

    @Test
    void anythingButTwoMeansTrapOne() {
        assertArrayEquals(BearTrapRoutine.goButtonFor(1), BearTrapRoutine.goButtonFor(0));
        assertArrayEquals(BearTrapRoutine.goButtonFor(1), BearTrapRoutine.goButtonFor(3));
    }

    /** The Go button is a saturated blue, or its white label; the card around it is pale blue-grey. */
    private static boolean isGoButton(int rgb) {
        int r = (rgb >> 16) & 0xff;
        int g = (rgb >> 8) & 0xff;
        int b = rgb & 0xff;
        boolean blue = b > 200 && r < 120 && b - r > 120;
        boolean label = r > 235 && g > 235 && b > 235;
        return blue || label;
    }

    private static BufferedImage frame() throws IOException {
        try (InputStream in = BearTrapGoButtonTest.class.getResourceAsStream("/beartrap/special-buildings.png")) {
            return ImageIO.read(in);
        }
    }
}
