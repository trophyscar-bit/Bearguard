package dev.frostguard.tasks.pets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * Reading a tile clock tolerates OCR noise beside the timer, but never a plausible wrong answer.
 *
 * <p>The clock is drawn over illustrated tile art, so a read commonly comes back as the timer plus
 * one character scraped off the border. Requiring the whole string to parse discarded those, which
 * is how Natural Intuition reported "could not read cooldown" from a tile whose timer was clearly
 * legible in the captured frame.
 *
 * <p>The opposite mistake is the dangerous one and is pinned here too. Pulling the first
 * timestamp-shaped run out of "0:22:24:23" gives "0:22:24" -- 22 minutes for a 22 hour wait, the
 * exact sixty-fold under-read the old description-panel crop would have produced. A match that
 * leaves that much of the string behind is refused, not guessed at.
 */
class PetSkillTileTimerExtractionTest {

    @Test
    void aCleanTimerReadsAsItself() {
        assertEquals("22:24:23", PetSkillsRoutine.extractTileTimer("22:24:23"));
        assertEquals("1d 10:14:57", PetSkillsRoutine.extractTileTimer("1d 10:14:57"));
    }

    @Test
    void oneStrayGlyphFromTheTileArtIsTolerated() {
        // Both observed live on slot 4 while the timer itself was perfectly legible.
        assertEquals("22:24:23", PetSkillsRoutine.extractTileTimer("22:24:231"));
        assertEquals("22:03:09", PetSkillsRoutine.extractTileTimer("22:03:091"));
    }

    @Test
    void theLabelUnderReadIsRefusedRatherThanGuessed() {
        // "0:22:24" would parse happily and be wrong by a factor of sixty.
        assertNull(PetSkillsRoutine.extractTileTimer("0:22:24:23"));
    }

    @Test
    void tooMuchNoiseIsRefused() {
        assertNull(PetSkillsRoutine.extractTileTimer("5907:3"));
        assertNull(PetSkillsRoutine.extractTileTimer(""));
        assertNull(PetSkillsRoutine.extractTileTimer(null));
        assertNull(PetSkillsRoutine.extractTileTimer("12:34:56 78:90:12"));
    }
}
