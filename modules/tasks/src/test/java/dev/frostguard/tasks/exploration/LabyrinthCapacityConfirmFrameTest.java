package dev.frostguard.tasks.exploration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.Objects;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.vision.ocr.OcrEngine;

/**
 * Saved frames from 2026-10-23 (Research Center) and 2026-10-22 (Charm Mine), taken 3s after Deploy.
 * The battle never started on either: the under-capacity confirmation was still up. Header rows that
 * carry account power are blacked out; the popup regions are untouched.
 */
class LabyrinthCapacityConfirmFrameTest {

    @Test
    void recognisesTheCapacityConfirmationOnResearchCenter() throws Exception {
        assertTrue(isCapacityConfirmation("/exploration/capacity-confirm-research-center.png"));
    }

    @Test
    void recognisesTheCapacityConfirmationOnCharmMine() throws Exception {
        assertTrue(isCapacityConfirmation("/exploration/capacity-confirm-charm-mine.png"));
    }

    @Test
    void rejectsABattleInProgress() throws Exception {
        assertFalse(isCapacityConfirmation("/exploration/battle-in-progress.png"));
    }

    @Test
    void rejectsTheTroopScreenBeforeDeploy() throws Exception {
        assertFalse(isCapacityConfirmation("/exploration/research-center-troops.png"));
    }

    @Test
    void rejectsAConfirmationWithADifferentBody() {
        assertFalse(DailyLabyrinthRoutine.isSquadCapacityConfirmation(
                "Confirmation", "Are you sure you want to leave?"));
        assertFalse(DailyLabyrinthRoutine.isSquadCapacityConfirmation("Confirmation", null));
    }

    @Test
    void unknownOutcomeRetriesInAnHourInsteadOfTheDailyStart() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 23, 19, 28, 42);
        LocalDateTime dailyStart = LocalDateTime.of(2026, 10, 24, 12, 0);

        LocalDateTime next = DailyLabyrinthRoutine.nextRunAfterPass(true, now, dailyStart);

        assertEquals(LocalDateTime.of(2026, 10, 23, 20, 28, 42), next);
        assertNotEquals(dailyStart, next);
    }

    @Test
    void knownOutcomesWaitForTheDailyStart() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 23, 19, 28, 42);
        LocalDateTime dailyStart = LocalDateTime.of(2026, 10, 24, 12, 0);

        assertEquals(dailyStart, DailyLabyrinthRoutine.nextRunAfterPass(false, now, dailyStart));
    }

    private static boolean isCapacityConfirmation(String path) throws Exception {
        RawImageData frame = rgbaFrame(load(path));
        String title = OcrEngine.recognizeText(frame,
                DailyLabyrinthRoutine.CAPACITY_CONFIRM_TITLE_TL,
                DailyLabyrinthRoutine.CAPACITY_CONFIRM_TITLE_BR,
                DailyLabyrinthRoutine.WHITE_TITLE_SETTINGS);
        String body = OcrEngine.recognizeText(frame,
                DailyLabyrinthRoutine.CAPACITY_CONFIRM_BODY_TL,
                DailyLabyrinthRoutine.CAPACITY_CONFIRM_BODY_BR,
                DailyLabyrinthRoutine.CAPACITY_CONFIRM_BODY_SETTINGS);
        return DailyLabyrinthRoutine.isSquadCapacityConfirmation(title, body);
    }

    private static BufferedImage load(String path) throws IOException {
        try (InputStream stream = LabyrinthCapacityConfirmFrameTest.class.getResourceAsStream(path)) {
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
