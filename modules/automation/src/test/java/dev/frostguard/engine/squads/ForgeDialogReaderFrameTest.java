package dev.frostguard.engine.squads;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.util.Objects;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.engine.squads.ForgeDialogReader.ForgeDialog;

/** Expected values were read by eye from each saved frame. */
class ForgeDialogReaderFrameTest {

    @ParameterizedTest
    @CsvSource({
            "Bahiti-goggles, Sniper Goggles, 50, 52, 13, 12",
            "Bahiti-gauntlets, Sniper Gauntlets, 10, 20, 13, 20",
            "Bahiti-belt, Sniper Belt, 10, 20, 13, 20",
            "Bahiti-boots, Sniper Boots, 40, 42, 13, 10",
            "Mia-goggles, Saboteur Goggles, 60, 62, 13, 14",
            "Mia-gauntlets, Saboteur Gauntlets, 10, 20, 13, 20",
            "Mia-belt, Saboteur Belt, 0, 10, 13, 10",
            "Mia-boots, Saboteur Boots, 50, 52, 13, 12",
            "Flint-goggles, Guardian Goggles, 0, 10, 13, 10",
            "Flint-gauntlets, Guardian Gauntlets, 58, 60, 13, 12",
            "Flint-belt, Guardian Belt, 50, 52, 13, 12",
            "Flint-boots, Guardian Boots, 10, 20, 13, 20",
            "Molly-goggles, Saboteur Goggles, 30, 40, 13, 40",
            "Molly-gauntlets, Saboteur Gauntlets, 0, 10, 13, 10",
            "Molly-belt, Saboteur Belt, 0, 10, 13, 10",
            "Molly-boots, Saboteur Boots, 30, 40, 13, 40",
            "Sergey-goggles, Guardian Goggles, 0, 10, 13, 10",
            "Sergey-gauntlets, Guardian Gauntlets, 30, 40, 13, 40",
            "Sergey-belt, Guardian Belt, 46, 48, 13, 10",
            "Sergey-boots, Guardian Boots, 0, 10, 13, 10",
            "after-forge, Sniper Boots, 42, 44, 3, 10",
    })
    void readsEveryFieldOfTheDialog(String file, String piece, int mastery, int next, int have, int needed) throws Exception {
        assertEquals(new ForgeDialog(piece, mastery, next, have, needed),
                ForgeDialogReader.read(frame("forge-" + file + ".png")));
    }

    @Test
    void aFrameWithoutTheDialogReadsAsNull() throws Exception {
        assertNull(ForgeDialogReader.read(frame("forge-not-open.png")));
    }

    @ParameterizedTest
    @CsvSource({ "Bahiti-boots, 984", "Molly-goggles, 962" })
    void findsTheForgeButtonInBothDialogHeights(String file, int y) throws Exception {
        assertEquals(new PointData(360, y), ForgeDialogReader.forgeButton(frame("forge-" + file + ".png")));
    }

    @Test
    void tellsGearDetailsFromTheForgeDialog() throws Exception {
        assertTrue(ForgeDialogReader.isGearDetails(frame("forge-not-open.png")));
        assertFalse(ForgeDialogReader.isGearDetails(frame("forge-Bahiti-boots.png")));
    }

    private static RawImageData frame(String name) throws Exception {
        BufferedImage image = ImageIO.read(Objects.requireNonNull(
                ForgeDialogReaderFrameTest.class.getResourceAsStream("/squads/" + name), name));
        byte[] rgba = new byte[image.getWidth() * image.getHeight() * 4];
        int offset = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int rgb = image.getRGB(x, y);
                rgba[offset++] = (byte) ((rgb >> 16) & 0xFF);
                rgba[offset++] = (byte) ((rgb >> 8) & 0xFF);
                rgba[offset++] = (byte) (rgb & 0xFF);
                rgba[offset++] = (byte) 0xFF;
            }
        }
        return RawImageData.capture(rgba, image.getWidth(), image.getHeight(), 32);
    }
}
