package dev.frostguard.tasks.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.Optional;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.vision.match.OpenCvPatternLocator;

/**
 * Live 720x1280 captures of Sign-in &amp; Earn It on its second day: the page with Day 2 glowing, the same
 * page after the claim, and the track scrolled to days 4-7. The two rail frames are blacked out except
 * for the icon column, because the world map around it shows other players.
 */
class SignInEarnFrameTest {

    private static final String DIR = "/signin/";

    @BeforeAll
    static void loadOpenCv() throws IOException {
        try {
            OpenCvPatternLocator.loadNativeLibrary();
        } catch (UnsatisfiedLinkError ignored) {
            // Another saved-frame test may already have loaded OpenCV in this JVM.
        }
    }

    @Test
    void theGlowingFreeTileIsFoundOnItsRow() throws Exception {
        Optional<Integer> row = SignInEarnRoutine.claimableTileY(image("day2-claimable.png"));
        assertTrue(row.isPresent());
        assertEquals(826, row.get(), 8);
    }

    @Test
    void noTileGlowsOnceClaimedOrOnLockedDays() throws Exception {
        assertTrue(SignInEarnRoutine.claimableTileY(image("day2-claimed.png")).isEmpty());
        assertTrue(SignInEarnRoutine.claimableTileY(image("days-4-7.png")).isEmpty());
    }

    @Test
    void thePageTitleIdentifiesThePage() throws Exception {
        assertTrue(find("day2-claimable.png", TemplatesEnum.SIGN_IN_EARN_TITLE).isFound());
        assertTrue(find("day2-claimed.png", TemplatesEnum.SIGN_IN_EARN_TITLE).isFound());
        assertFalse(find("rail-with-icon.png", TemplatesEnum.SIGN_IN_EARN_TITLE).isFound());
    }

    @Test
    void theRailIconIsOnlyFoundWhileADayIsWaiting() throws Exception {
        assertTrue(find("rail-with-icon.png", TemplatesEnum.SIGN_IN_EARN_CITY_ICON).isFound());
        assertFalse(find("rail-without-icon.png", TemplatesEnum.SIGN_IN_EARN_CITY_ICON).isFound());
    }

    private ImageSearchResultData find(String name, TemplatesEnum template) throws IOException {
        return OpenCvPatternLocator.locatePattern(bytes(name), template.getTemplate(),
                new PointData(0, 0), new PointData(719, 1279), 85);
    }

    private byte[] bytes(String name) throws IOException {
        try (InputStream in = Objects.requireNonNull(getClass().getResourceAsStream(DIR + name), name)) {
            return in.readAllBytes();
        }
    }

    private BufferedImage image(String name) throws IOException {
        return ImageIO.read(Objects.requireNonNull(getClass().getResourceAsStream(DIR + name), name));
    }
}
