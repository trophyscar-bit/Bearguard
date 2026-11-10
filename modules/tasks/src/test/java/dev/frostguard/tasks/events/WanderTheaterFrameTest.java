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
import dev.frostguard.tasks.events.WanderTheaterRoutine.ButtonState;
import dev.frostguard.vision.match.OpenCvPatternLocator;

/**
 * Live 720x1280 captures of Wander Theater from 2026-11-09: the Wanderer Missions tab with the troop
 * mission claimable, the same tab right after that claim, the theater page (tutorial and Floor 2), and
 * the City rail with the event icon. The rail frame is blacked out except around the icon, because the
 * world map shows other players.
 */
class WanderTheaterFrameTest {

    private static final String DIR = "/wander/";

    @BeforeAll
    static void loadOpenCv() throws IOException {
        try {
            OpenCvPatternLocator.loadNativeLibrary();
        } catch (UnsatisfiedLinkError ignored) {
            // Another saved-frame test may already have loaded OpenCV in this JVM.
        }
    }

    @Test
    void theGreenClaimButtonIsFoundOnTheTroopRow() throws Exception {
        Optional<Integer> y = WanderTheaterRoutine.firstClaimButton(image("missions-claimable.png"));
        assertTrue(y.isPresent());
        assertEquals(746, y.get(), 10);
    }

    @Test
    void noClaimButtonOnceTheOnlyFinishedMissionIsClaimed() throws Exception {
        assertTrue(WanderTheaterRoutine.firstClaimButton(image("missions-claimed-one.png")).isEmpty());
    }

    @Test
    void goButtonsAreNeverReadAsClaim() throws Exception {
        BufferedImage img = image("missions-claimable.png");
        assertEquals(ButtonState.CLAIM, WanderTheaterRoutine.buttonState(img, 746));
        assertEquals(ButtonState.GO, WanderTheaterRoutine.buttonState(img, 917));
        assertEquals(ButtonState.GO, WanderTheaterRoutine.buttonState(img, 1088));
    }

    @Test
    void eachMissionRowIsFoundAndItsButtonSitsBelowIt() throws Exception {
        ImageSearchResultData polar = find("missions-claimable.png", TemplatesEnum.WANDER_THEATER_POLAR_ROW);
        assertTrue(polar.isFound());
        assertEquals(ButtonState.GO, WanderTheaterRoutine.buttonState(image("missions-claimable.png"),
                polar.getPoint().getY() + WanderTheaterRoutine.BUTTON_BELOW_TITLE));

        ImageSearchResultData troops = find("missions-claimable.png", TemplatesEnum.WANDER_THEATER_TROOP_ROW);
        assertTrue(troops.isFound());
        assertEquals(ButtonState.CLAIM, WanderTheaterRoutine.buttonState(image("missions-claimable.png"),
                troops.getPoint().getY() + WanderTheaterRoutine.BUTTON_BELOW_TITLE));

        ImageSearchResultData donations = find("missions-claimed-one.png", TemplatesEnum.WANDER_THEATER_DONATE_ROW);
        assertTrue(donations.isFound());
        assertEquals(ButtonState.GO, WanderTheaterRoutine.buttonState(image("missions-claimed-one.png"),
                donations.getPoint().getY() + WanderTheaterRoutine.BUTTON_BELOW_TITLE));
    }

    @Test
    void titlesIdentifyTheEventAndTheMissionsTab() throws Exception {
        assertTrue(find("theater-tutorial.png", TemplatesEnum.WANDER_THEATER_TITLE).isFound());
        assertTrue(find("theater-floor2.png", TemplatesEnum.WANDER_THEATER_TITLE).isFound());
        assertTrue(find("missions-claimable.png", TemplatesEnum.WANDER_THEATER_TITLE).isFound());
        assertTrue(find("missions-claimed-one.png", TemplatesEnum.WANDER_THEATER_MISSIONS_TITLE).isFound());
        assertFalse(find("theater-floor2.png", TemplatesEnum.WANDER_THEATER_MISSIONS_TITLE).isFound());
        assertFalse(find("rail-world.png", TemplatesEnum.WANDER_THEATER_TITLE).isFound());
    }

    @Test
    void theRailIconIsFoundOnlyOnTheRail() throws Exception {
        assertTrue(find("rail-world.png", TemplatesEnum.WANDER_THEATER_CITY_ICON).isFound());
        // The City view, with a red notification dot on the icon's corner: the first live pass missed it.
        assertTrue(find("rail-city-dot.png", TemplatesEnum.WANDER_THEATER_CITY_ICON).isFound());
        assertFalse(find("theater-floor2.png", TemplatesEnum.WANDER_THEATER_CITY_ICON).isFound());
        assertFalse(find("missions-claimable.png", TemplatesEnum.WANDER_THEATER_CITY_ICON).isFound());
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
