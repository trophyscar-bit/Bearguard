package dev.frostguard.tasks.exploration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.engine.nav.CommonGameAreas;
import dev.frostguard.engine.nav.CommonOCRSettings;
import dev.frostguard.vision.match.OpenCvPatternLocator;
import dev.frostguard.vision.ocr.OcrEngine;

/**
 * Saved frames for LabyrinthRaidRoutine's entry: a Friday map with Research Center and Gear Forge
 * open, a Monday map with both closed, and the hero Skills screen the old blind left-menu tap reached
 * on 2026-11-12. Only the regions the routine reads are kept -- the map title and the two zone
 * banners -- and they are put back at their original positions on a black 720x1280 frame, so the
 * routine's own coordinates and the full-frame template search are exercised unchanged.
 */
class LabyrinthRaidMapFrameTest {

    private static final PointData ORIGIN = new PointData(0, 0);
    private static final PointData FULL = new PointData(720, 1280);
    private static final int SEARCH_THRESHOLD = 90;

    /** Crop suffix and its top-left position in the original frame. */
    private static final String[][] CROPS = {
            {"title", "0", "0"},
            {"research-center", "0", "740"},
            {"gear-forge", "400", "850"},
    };

    @BeforeAll
    static void loadOpenCv() throws IOException {
        try {
            OpenCvPatternLocator.loadNativeLibrary();
        } catch (UnsatisfiedLinkError ignored) {
            // Another frame test may already have loaded OpenCV in this JVM.
        }
    }

    @Test
    void fridayMapIsTheLabyrinthAndShowsBothRaidZones() throws Exception {
        BufferedImage frame = frame("labyrinth-map-friday");

        assertTrue(CommonOCRSettings.isLabyrinthMapTitle(title(frame)));
        assertTrue(found(frame, LabyrinthRaidRoutine.RESEARCH_CENTER_BANNER));
        assertTrue(found(frame, LabyrinthRaidRoutine.GEAR_FORGE_BANNER));
    }

    @Test
    void mondayMapIsTheLabyrinthButNeitherRaidZoneIsOffered() throws Exception {
        BufferedImage frame = frame("labyrinth-map-monday");

        assertTrue(CommonOCRSettings.isLabyrinthMapTitle(title(frame)));
        assertFalse(found(frame, LabyrinthRaidRoutine.RESEARCH_CENTER_BANNER));
        assertFalse(found(frame, LabyrinthRaidRoutine.GEAR_FORGE_BANNER));
    }

    @Test
    void heroSkillsScreenIsNotTheLabyrinthMap() throws Exception {
        BufferedImage frame = frame("hero-skills-not-labyrinth");

        assertFalse(CommonOCRSettings.isLabyrinthMapTitle(title(frame)));
        assertFalse(found(frame, LabyrinthRaidRoutine.RESEARCH_CENTER_BANNER));
        assertFalse(found(frame, LabyrinthRaidRoutine.GEAR_FORGE_BANNER));
    }

    private static boolean found(BufferedImage frame, TemplatesEnum template) throws IOException {
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(frame, "png", png);
        return OpenCvPatternLocator.locatePattern(png.toByteArray(), template, ORIGIN, FULL, SEARCH_THRESHOLD)
                .isFound();
    }

    private static String title(BufferedImage frame) throws Exception {
        return OcrEngine.recognizeText(rgbaFrame(frame),
                CommonGameAreas.LABYRINTH_MAP_TITLE.topLeft(),
                CommonGameAreas.LABYRINTH_MAP_TITLE.bottomRight(),
                CommonOCRSettings.LABYRINTH_MAP_LABEL_SETTINGS);
    }

    private static BufferedImage frame(String name) throws IOException {
        BufferedImage frame = new BufferedImage(720, 1280, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = frame.createGraphics();
        for (String[] crop : CROPS) {
            g.drawImage(load("/exploration/" + name + "-" + crop[0] + ".png"),
                    Integer.parseInt(crop[1]), Integer.parseInt(crop[2]), null);
        }
        g.dispose();
        return frame;
    }

    private static BufferedImage load(String path) throws IOException {
        try (InputStream stream = LabyrinthRaidMapFrameTest.class.getResourceAsStream(path)) {
            return ImageIO.read(Objects.requireNonNull(stream, "Missing " + path));
        }
    }

    private static RawImageData rgbaFrame(BufferedImage image) {
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
