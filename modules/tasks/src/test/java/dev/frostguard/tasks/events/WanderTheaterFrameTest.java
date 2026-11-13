package dev.frostguard.tasks.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.tasks.events.WanderTheaterRoutine.ButtonState;
import dev.frostguard.vision.match.OpenCvPatternLocator;
import dev.frostguard.vision.ocr.OcrEngine;

/**
 * Live 720x1280 captures of Wander Theater from 2026-11-09: the Wanderer Missions tab with the troop
 * mission claimable, the same tab right after that claim, the theater page (Floors 1 to 3), and
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
        assertTrue(find("theater-floor1.png", TemplatesEnum.WANDER_THEATER_TITLE).isFound());
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

    // ── Exploration: Floor-1 roll, coin cash-out ────────────────────────────────────────────

    @Test
    void floorOneIsRecognisedOnlyOnFloorOne() throws Exception {
        assertTrue(findIn("theater-floor1.png", TemplatesEnum.WANDER_THEATER_FLOOR_ONE,
                WanderTheaterRoutine.FLOOR_ONE_TOP_LEFT, WanderTheaterRoutine.FLOOR_ONE_BOTTOM_RIGHT).isFound());
        for (String other : new String[] {"theater-floor2-guaranteed.png", "theater-floor3.png", "theater-floor2-14.png"}) {
            assertFalse(findIn(other, TemplatesEnum.WANDER_THEATER_FLOOR_ONE,
                    WanderTheaterRoutine.FLOOR_ONE_TOP_LEFT, WanderTheaterRoutine.FLOOR_ONE_BOTTOM_RIGHT).isFound(), other);
        }
    }

    @Test
    void theLeftmostSelectIsTheCoinTile() throws Exception {
        List<ImageSearchResultData> floor3 = selects("theater-floor3.png");
        assertEquals(2, floor3.size());
        ImageSearchResultData leftmost = floor3.stream().min(Comparator.comparingInt(r -> r.getPoint().getX())).orElseThrow();
        assertEquals(409, leftmost.getPoint().getX(), 12);
        assertEquals(797, leftmost.getPoint().getY(), 12);

        List<ImageSearchResultData> floor2 = selects("theater-floor2-14.png");
        assertEquals(1, floor2.size());
        assertTrue(selects("theater-floor1.png").isEmpty());
    }

    @Test
    void theDialogIsRecognisedAndNamesTheCoins() throws Exception {
        assertTrue(findIn("select-coins-dialog.png", TemplatesEnum.WANDER_THEATER_SELECT_REWARDS,
                WanderTheaterRoutine.DIALOG_TITLE_TOP_LEFT, WanderTheaterRoutine.DIALOG_TITLE_BOTTOM_RIGHT).isFound());
        assertTrue(findIn("select-coins-dialog.png", TemplatesEnum.WANDER_THEATER_COIN_REWARD,
                WanderTheaterRoutine.REWARD_NAME_TOP_LEFT, WanderTheaterRoutine.REWARD_NAME_BOTTOM_RIGHT).isFound());
        assertFalse(findIn("theater-floor3.png", TemplatesEnum.WANDER_THEATER_SELECT_REWARDS,
                WanderTheaterRoutine.DIALOG_TITLE_TOP_LEFT, WanderTheaterRoutine.DIALOG_TITLE_BOTTOM_RIGHT).isFound());
        assertFalse(findIn("theater-floor3.png", TemplatesEnum.WANDER_THEATER_COIN_REWARD,
                WanderTheaterRoutine.REWARD_NAME_TOP_LEFT, WanderTheaterRoutine.REWARD_NAME_BOTTOM_RIGHT).isFound());
    }

    @Test
    void theTalismanCountReads() throws Exception {
        assertEquals(0, talismans("theater-floor1.png"));
        assertEquals(0, talismans("theater-floor3.png"));
        assertEquals(14, talismans("theater-floor2-14.png"));
        assertEquals(1, talismans("theater-floor2-1.png"));
        assertEquals(7, talismans("theater-floor2-guaranteed.png"));
    }

    /** Mirrors the routine: both boxes, read the same way, reduced by the same consensus. */
    private Integer talismans(String name) throws Exception {
        Integer wide = box(name, WanderTheaterRoutine.TALISMANS_TOP_LEFT, WanderTheaterRoutine.TALISMANS_BOTTOM_RIGHT);
        Integer tight = box(name, WanderTheaterRoutine.TALISMANS_TIGHT_TOP_LEFT,
                WanderTheaterRoutine.TALISMANS_TIGHT_BOTTOM_RIGHT);
        return WanderTheaterRoutine.talismanConsensus(wide, tight);
    }

    private Integer box(String name, PointData topLeft, PointData bottomRight) throws Exception {
        return WanderTheaterRoutine.talismanCount(OcrEngine.recognizeText(raw(name), topLeft, bottomRight,
                WanderTheaterRoutine.TALISMAN_DIGITS));
    }

    @Test
    void theTightBoxMayShowPartOfTheWideRead() {
        assertEquals(40, WanderTheaterRoutine.talismanConsensus(40, 0));
        assertEquals(40, WanderTheaterRoutine.talismanConsensus(40, 4));
        assertEquals(40, WanderTheaterRoutine.talismanConsensus(40, 40));
        assertEquals(14, WanderTheaterRoutine.talismanConsensus(14, 14));
        assertEquals(null, WanderTheaterRoutine.talismanConsensus(40, 6));
        assertEquals(null, WanderTheaterRoutine.talismanConsensus(40, null));
        assertEquals(null, WanderTheaterRoutine.talismanConsensus(null, 40));
        assertEquals(null, WanderTheaterRoutine.talismanConsensus(0, 6));
        assertEquals(null, WanderTheaterRoutine.talismanConsensus(5, 50));
    }

    @Test
    void onlyPlainDigitsCountAsATalismanReading() {
        assertEquals(14, WanderTheaterRoutine.talismanCount(" 14\n"));
        assertEquals(null, WanderTheaterRoutine.talismanCount("6)"));
        assertEquals(null, WanderTheaterRoutine.talismanCount(""));
        assertEquals(null, WanderTheaterRoutine.talismanCount(null));
    }

    private List<ImageSearchResultData> selects(String name) throws IOException {
        return OpenCvPatternLocator.locateAllPatterns(raw(name), TemplatesEnum.WANDER_THEATER_SELECT.getTemplate(),
                WanderTheaterRoutine.SELECT_TOP_LEFT, WanderTheaterRoutine.SELECT_BOTTOM_RIGHT, 90, 4)
                .stream().filter(ImageSearchResultData::isFound).toList();
    }

    private ImageSearchResultData findIn(String name, TemplatesEnum template, PointData topLeft, PointData bottomRight)
            throws IOException {
        return OpenCvPatternLocator.locatePattern(bytes(name), template.getTemplate(), topLeft, bottomRight, 90);
    }

    private RawImageData raw(String name) throws IOException {
        BufferedImage image = image(name);
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
