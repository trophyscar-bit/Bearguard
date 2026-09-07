package dev.frostguard.tasks.pets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.time.Duration;
import java.util.Objects;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.engine.nav.CommonOCRSettings;
import dev.frostguard.vision.convert.GameTimeUtils;
import dev.frostguard.vision.ocr.OcrEngine;

/**
 * The remaining cooldown is read from the clock drawn on each skill's own tile.
 *
 * <p>The crop this replaces, a shared "On cooldown: HH:MM:SS" line under the description panel,
 * pointed at (200,1070)-(520,1110). That rectangle is the Use button. With a digits-only whitelist
 * it returned nothing on every pass, for every skill, so the routine never once read a real
 * cooldown on this account and silently fell back to a flat 60 minutes each run. The frame here is
 * a live capture taken while the routine had the panel open, and {@link #theOldSharedLineCropReadsTheUseButton()}
 * pins that failure in place so the crop cannot quietly drift back.
 *
 * <p>The panel does print a "Cooldown: 23:00:00" line, and it is a trap: that is the skill's BASE
 * cooldown, rendered identically whether the skill is ready or has twenty hours left. Scheduling
 * from it would look correct and be wrong every time.
 */
class PetSkillTileCooldownFrameTest {

    private static final Pattern TIMER = Pattern.compile("(?:\\d+\\s*d\\s*)?\\d{1,2}:\\d{2}:\\d{2}");

    @Test
    void readsTheRemainingCooldownFromTheSkillsOwnTile() throws Exception {
        RawImageData frame = rgbaFrame(loadFrame());

        // FOOD's tile was on cooldown when this frame was taken: the skill had been used 37
        // minutes earlier and its 23h clock had 22:23:03 left.
        String clock = read(frame, PetSkillsRoutine.PetSkill.FOOD.cooldownArea());

        assertTrue(TIMER.matcher(clock).find(), () -> "Not a timer: " + clock);
        assertEquals(Duration.ofHours(22).plusMinutes(23).plusSeconds(3),
                GameTimeUtils.parseDuration(clock));
    }

    @Test
    void aReadySkillsTileYieldsNoTimerRatherThanAWrongOne() throws Exception {
        RawImageData frame = rgbaFrame(loadFrame());

        // STAMINA and GATHERING were both ready in this frame -- no clock is drawn on a ready
        // tile. The read must come back empty so the skill is excluded, not decorated with a
        // number scavenged from the tile art or the "Lv. N" label beneath it.
        for (PetSkillsRoutine.PetSkill ready : new PetSkillsRoutine.PetSkill[] {
                PetSkillsRoutine.PetSkill.STAMINA, PetSkillsRoutine.PetSkill.GATHERING }) {
            String clock = read(frame, ready.cooldownArea());
            assertFalse(TIMER.matcher(clock).find(),
                    () -> ready + " is ready but produced a timer: " + clock);
        }
    }

    @Test
    void theOldSharedLineCropReadsTheUseButton() throws Exception {
        RawImageData frame = rgbaFrame(loadFrame());

        // The exact rectangle the routine used to read. Kept as a test rather than a comment
        // because the claim that it was "calibrated from 30 live frames" and "read cleanly on
        // every single one" was in the source for weeks while it had never worked once.
        String clock = read(frame, new AreaData(
                new dev.frostguard.api.domain.PointData(200, 1070),
                new dev.frostguard.api.domain.PointData(520, 1110)));

        assertNotNull(clock);
        assertFalse(TIMER.matcher(clock).find(),
                () -> "The old crop produced a timer, so this frame no longer demonstrates the bug: " + clock);
    }

    @Test
    void theCooldownBandStaysInsideItsOwnTile() {
        // The band is derived from the tile so it follows the tile when the roster grows. It must
        // stay within the tile's own bounds, or one skill would read its neighbour's clock.
        for (PetSkillsRoutine.PetSkill skill : PetSkillsRoutine.PetSkill.values()) {
            AreaData tile = new AreaData(skill.getTopLeft(), skill.getBottomRight());
            AreaData band = skill.cooldownArea();

            assertEquals(tile.topLeft().getX(), band.topLeft().getX(), skill + " band x drifted");
            assertEquals(tile.bottomRight().getX(), band.bottomRight().getX(), skill + " band x drifted");
            assertTrue(band.topLeft().getY() > tile.topLeft().getY(), skill + " band starts above its tile");
            assertTrue(band.bottomRight().getY() < tile.bottomRight().getY(),
                    skill + " band runs past the bottom of its tile");
        }
    }

    private String read(RawImageData frame, AreaData area) throws Exception {
        return OcrEngine.recognizeText(frame, area.topLeft(), area.bottomRight(),
                CommonOCRSettings.RED_MULTILINE_DURATION_SETTINGS);
    }

    private BufferedImage loadFrame() throws Exception {
        return ImageIO.read(Objects.requireNonNull(
                getClass().getResourceAsStream("/pets/pet-skill-tile-cooldown-20260907.png")));
    }

    private RawImageData rgbaFrame(BufferedImage image) {
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
