package dev.frostguard.tasks.pets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.time.Duration;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.engine.nav.CommonOCRSettings;
import dev.frostguard.vision.convert.GameTimeUtils;
import dev.frostguard.vision.ocr.OcrEngine;

/**
 * The remaining cooldown is read from the clock drawn on each skill's own tile.
 *
 * <p>The crop this replaces, (200,1070)-(520,1110), is not simply wrong about where to look. That
 * rectangle holds the Use button while the selected skill is READY, and the game swaps in an
 * "On cooldown: HH:MM:SS" line in the same place once it is not. So it reads nothing at all on a
 * ready skill, and on a skill that is on cooldown the OCR returns a leading-digit artifact from the
 * label -- "0:22:24:23" rather than "22:24:23". The old extraction regex then matched the FIRST
 * timestamp-shaped run in that string, "0:22:24", and scheduled 22 minutes for a 22 hour wait. The
 * routine never actually got that far on this account (it logged "Could not read cooldown" on every
 * run), but the failure mode was a sixty-fold under-read waiting to happen, not merely a blank.
 *
 * <p>The tile clock has neither problem: it is present exactly when there is a cooldown to read,
 * carries no label to confuse the parse, and is drawn per skill so one skill cannot pick up another
 * skill's timer.
 *
 * <p>Two live 720x1280 captures back this, both committed redacted: one with the selected skill
 * ready, one with it on cooldown.
 */
class PetSkillTileCooldownFrameTest {

    private static final Pattern TIMER = Pattern.compile("(?:\\d+\\s*d\\s*)?\\d{1,2}:\\d{2}:\\d{2}");

    /** The rectangle the routine used to read, kept so the failure cannot creep back. */
    private static final AreaData OLD_SHARED_LINE_CROP =
            new AreaData(new PointData(200, 1070), new PointData(520, 1110));

    /** The extraction the old path used on whatever that crop returned. */
    private static final Pattern OLD_EXTRACTION =
            Pattern.compile("(?:\\d+\\s*d\\s*)?\\d{1,2}:\\d{2}:\\d{2}");

    @Test
    void readsTheRemainingCooldownFromTheSkillsOwnTile() throws Exception {
        RawImageData frame = rgbaFrame(loadReadyFrame());

        // FOOD's tile was on cooldown when this frame was taken: the skill had been used 37
        // minutes earlier and its 23h clock had 22:23:03 left.
        String clock = read(frame, PetSkillsRoutine.PetSkill.FOOD.cooldownArea());

        assertTrue(TIMER.matcher(clock).find(), () -> "Not a timer: " + clock);
        assertEquals(Duration.ofHours(22).plusMinutes(23).plusSeconds(3),
                GameTimeUtils.parseDuration(clock));
    }

    @Test
    void aReadySkillsTileYieldsNoTimerRatherThanAWrongOne() throws Exception {
        RawImageData frame = rgbaFrame(loadReadyFrame());

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
    void theOldCropShowsTheUseButtonWhileTheSkillIsReady() throws Exception {
        // Frame with a ready skill selected: the rectangle the routine used to read holds the Use
        // button, so there is no timer in it to find.
        String clock = readAsTheOldPathDid(rgbaFrame(loadReadyFrame()), OLD_SHARED_LINE_CROP);

        assertNotNull(clock);
        assertFalse(TIMER.matcher(clock).find(),
                () -> "Expected the Use button here, got a timer: " + clock);
    }

    @Test
    void theOldCropUnderReadsByAFactorOfSixtyWhenTheSkillIsOnCooldown() throws Exception {
        // Same rectangle, skill on cooldown: the line IS there, and this is the dangerous case.
        String clock = readAsTheOldPathDid(rgbaFrame(loadOnCooldownFrame()), OLD_SHARED_LINE_CROP);

        Matcher m = OLD_EXTRACTION.matcher(clock);
        assertTrue(m.find(), () -> "Expected the old parse to match something in: " + clock);

        // 22:24:23 remained. The old parse takes "0:22:24" out of "0:22:24:23" and calls it done.
        Duration wouldHaveScheduled = GameTimeUtils.parseDuration(m.group());
        assertEquals(Duration.ofMinutes(22).plusSeconds(24), wouldHaveScheduled,
                "This pins the under-read; if the OCR or the label changes, re-derive the fix rather "
                        + "than loosening this");
        assertTrue(wouldHaveScheduled.compareTo(Duration.ofHours(1)) < 0,
                "the whole point: a 22 hour cooldown parsed as well under an hour");
    }

    @Test
    void theTileClockReadsTheSameCooldownCorrectly() throws Exception {
        // The same frame, read the new way, gets the real remaining time.
        String clock = read(rgbaFrame(loadOnCooldownFrame()),
                PetSkillsRoutine.PetSkill.NATURAL_INTUITION.cooldownArea());

        assertEquals(Duration.ofHours(22).plusMinutes(24).plusSeconds(23),
                GameTimeUtils.parseDuration(clock));
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

    /**
     * Reads the way the replaced code did: the old crop with RED_DURATION_SETTINGS, which is
     * where the leading-digit artifact comes from. RED_MULTILINE, which the tile read uses,
     * happens to render the same pixels as "0d:22:24:23" and survives the parse -- so using it
     * here would quietly hide the bug this test exists to pin.
     */
    private String readAsTheOldPathDid(RawImageData frame, AreaData area) throws Exception {
        return OcrEngine.recognizeText(frame, area.topLeft(), area.bottomRight(),
                CommonOCRSettings.RED_DURATION_SETTINGS);
    }

    private String read(RawImageData frame, AreaData area) throws Exception {
        return OcrEngine.recognizeText(frame, area.topLeft(), area.bottomRight(),
                CommonOCRSettings.RED_MULTILINE_DURATION_SETTINGS);
    }

    private BufferedImage loadReadyFrame() throws Exception {
        return load("pet-skill-tile-cooldown-20260907.png");
    }

    private BufferedImage loadOnCooldownFrame() throws Exception {
        return load("pet-skill-selected-oncooldown-20260907.png");
    }

    private BufferedImage load(String name) throws Exception {
        return ImageIO.read(Objects.requireNonNull(
                getClass().getResourceAsStream("/pets/" + name)));
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
