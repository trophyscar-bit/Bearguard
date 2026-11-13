package dev.frostguard.engine.squads;

import java.awt.image.BufferedImage;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Objects;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import dev.frostguard.api.domain.RawImageData;

class HeroUpgradeButtonFrameTest {

    static RawImageData frame(String name) throws Exception {
        BufferedImage image = ImageIO.read(Objects.requireNonNull(HeroUpgradeButtonFrameTest.class.getResourceAsStream("/squads/" + name + ".png"), name));
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

    // Saved off the live account 2026-11-10: Mia Lv 78, the balance 2.3M in green over a cost of 2.1M.
    @Test
    void anAffordableUpgradeShowsItsBalanceGreenAndTheGameCallsItAffordable() throws Exception {
        HeroScreenReader.Upgrade upgrade = HeroScreenReader.readUpgrade(frame("hero-upgrade-mia-affordable"));

        assertEquals(new HeroScreenReader.Upgrade(new HeroScreenReader.HeroXp(2_300_000, 2_100_000), true), upgrade);
        assertEquals(78, HeroScreenReader.readDetail(frame("hero-upgrade-mia-affordable")).level());
        assertTrue(HeroScreenReader.isStatsTab(frame("hero-upgrade-mia-affordable")));
    }

    @Test
    void aShortUpgradeShowsItsBalanceRedAndIsNotAffordable() throws Exception {
        HeroScreenReader.Upgrade upgrade = HeroScreenReader.readUpgrade(frame("scan-mia"));

        assertEquals(new HeroScreenReader.Upgrade(new HeroScreenReader.HeroXp(382_377, 2_400_000), false), upgrade);
    }

    @Test
    void aMaxedHeroHasNoUpgradeButton() throws Exception {
        assertNull(HeroScreenReader.readUpgrade(frame("hero-upgrade-molly-maxed")));
        assertEquals(80, HeroScreenReader.readDetail(frame("hero-upgrade-molly-maxed")).level());
    }

    @Test
    void aFrameThatIsNotAStatsTabHasNoUpgrade() throws Exception {
        assertNull(HeroScreenReader.readUpgrade(frame("quickequip-h01")));
    }

    @Test
    void theGreenBalanceIsReadFromText() {
        assertEquals(new HeroScreenReader.Upgrade(new HeroScreenReader.HeroXp(2_300_000, 2_100_000), true), HeroScreenReader.upgrade("/2.1M", "", "2.3M"));
        assertEquals(new HeroScreenReader.Upgrade(new HeroScreenReader.HeroXp(382_377, 2_400_000), false), HeroScreenReader.upgrade("/2.4M", "382,377", ""));
        assertEquals(new HeroScreenReader.Upgrade(new HeroScreenReader.HeroXp(2_300_000, 2_400_000), false), HeroScreenReader.upgrade("/2.4M", "2.3M", ""),
                "a red balance over a million is rounded too, and reads as 2.3 million, not 23");
        assertNull(HeroScreenReader.upgrade("/2.1M", "", "1.9M"), "a green balance below the cost does not add up, so nothing is pressed on it");
        assertNull(HeroScreenReader.upgrade("", "", "2.3M"));
        assertNull(HeroScreenReader.upgrade("/2.1M", "", ""));
    }
}
