package dev.frostguard.tasks.pets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.awt.image.BufferedImage;
import java.time.Duration;
import java.util.Objects;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.engine.nav.CommonOCRSettings;
import dev.frostguard.vision.convert.GameTimeUtils;
import dev.frostguard.vision.ocr.OcrEngine;

/**
 * Burden Bearer's cooldown, read the way {@code readAndTrackCooldown} reads it: the green pass
 * first, which must find nothing on a cooldown clock, then the red pass through
 * {@link PetSkillsRoutine#extractTileTimer}.
 *
 * <p>Every Gathering run from 10 to 11 November logged "Could not read cooldown" because the band
 * stopped at x 460 and clipped the clock. Each saved panel with a Gathering clock is pinned here,
 * plus one where the skill was ready and must read as no timer at all.
 */
class PetSkillGatheringClockFrameTest {

    /** The band the routine used until the fix. */
    private static final AreaData OLD_BAND = new AreaData(new PointData(380, 282), new PointData(460, 338));

    @ParameterizedTest
    @CsvSource({
            "pet-skill-four-combat-20261109.png, PT18H49M31S",
            "pet-skill-combat-selected-20261110.png, PT3H47M56S",
            "pet-skill-day-cooldown-20260810.png, PT34H14M57S",
            "pet-skill-selected-oncooldown-20260907.png, PT22H27M34S",
            "pet-skill-active-effect-20260907.png, PT22H6M20S",
            "pet-skill-six-tiles-20260913.png, PT16H42M21S",
    })
    void readsTheGatheringClockOnEverySavedPanel(String file, String expected) throws Exception {
        RawImageData frame = frame(file);
        AreaData band = PetSkillsRoutine.PetSkill.GATHERING.cooldownArea();

        assertNull(PetSkillsRoutine.extractTileTimer(read(frame, band, true)),
                file + ": a red cooldown clock read as a running green effect");
        assertEquals(Duration.parse(expected), cooldown(frame, band), file);
    }

    @Test
    void aReadyGatheringTileReadsNoTimer() throws Exception {
        RawImageData frame = frame("pet-skill-tile-cooldown-20260907.png");
        AreaData band = PetSkillsRoutine.PetSkill.GATHERING.cooldownArea();

        assertNull(PetSkillsRoutine.extractTileTimer(read(frame, band, true)));
        assertNull(cooldown(frame, band));
    }

    @Test
    void theOldBandReadNothingOnTheNovemberPanels() throws Exception {
        assertNull(cooldown(frame("pet-skill-four-combat-20261109.png"), OLD_BAND));
        assertNull(cooldown(frame("pet-skill-combat-selected-20261110.png"), OLD_BAND));
    }

    private static Duration cooldown(RawImageData frame, AreaData band) throws Exception {
        String timer = PetSkillsRoutine.extractTileTimer(read(frame, band, false));
        return timer == null ? null : GameTimeUtils.parseDuration(timer);
    }

    private static String read(RawImageData frame, AreaData band, boolean green) throws Exception {
        return OcrEngine.recognizeText(frame, band.topLeft(), band.bottomRight(), green
                ? CommonOCRSettings.GREEN_ACTIVE_DURATION_SETTINGS
                : CommonOCRSettings.RED_MULTILINE_DURATION_SETTINGS);
    }

    private RawImageData frame(String name) throws Exception {
        BufferedImage image = ImageIO.read(Objects.requireNonNull(getClass().getResourceAsStream("/pets/" + name)));
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
