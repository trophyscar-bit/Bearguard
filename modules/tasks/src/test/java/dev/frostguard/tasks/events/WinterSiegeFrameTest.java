package dev.frostguard.tasks.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.Objects;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.engine.nav.CommonGameAreas;
import dev.frostguard.tasks.events.WinterSiegeRoutine.Outcome;
import dev.frostguard.vision.match.OpenCvPatternLocator;

/**
 * Live 720x1280 captures of Winter Siege in its Preparation Phase: Events open on another tab, the event page
 * before and after garrisoning, the stronghold map, and Event Mission Rewards with and without a green Claim.
 * The map frame has its alliance banner and squad cards blacked out.
 */
class WinterSiegeFrameTest {

    private static final String DIR = "/wintersiege/";
    private static final String OTHER_TAB = "events-other-tab.png";
    private static final String NOT_GARRISONED = "page-not-garrisoned.png";
    private static final String GARRISONED = "page-garrisoned.png";
    private static final String MAP = "map.png";
    private static final String CLAIMABLE = "missions-claimable.png";
    private static final String NONE_CLAIMABLE = "missions-none-claimable.png";

    @BeforeAll
    static void loadOpenCv() throws IOException {
        try {
            OpenCvPatternLocator.loadNativeLibrary();
        } catch (UnsatisfiedLinkError ignored) {
            // Another saved-frame test may already have loaded OpenCV in this JVM.
        }
    }

    @Test
    void theEventsHeaderIsNotTheEventMap() throws Exception {
        assertTrue(find(OTHER_TAB, TemplatesEnum.EVENTS_HEADER, WinterSiegeRoutine.EVENTS_HEADER_AREA).isFound());
        assertTrue(find(GARRISONED, TemplatesEnum.EVENTS_HEADER, WinterSiegeRoutine.EVENTS_HEADER_AREA).isFound());
        assertFalse(find(MAP, TemplatesEnum.EVENTS_HEADER, WinterSiegeRoutine.EVENTS_HEADER_AREA).isFound());
    }

    @Test
    void theUnselectedTabIsFoundByItsLabel() throws Exception {
        ImageSearchResultData tab = find(OTHER_TAB, TemplatesEnum.WINTER_SIEGE_TAB, CommonGameAreas.DEALS_TAB_STRIP);
        assertTrue(tab.isFound());
        assertEquals(160, tab.getX(), 15);
        assertEquals(168, tab.getY(), 15);
        assertFalse(find(GARRISONED, TemplatesEnum.WINTER_SIEGE_TAB, CommonGameAreas.DEALS_TAB_STRIP).isFound());
    }

    @Test
    void thePageIsKnownByItsTitle() throws Exception {
        assertTrue(find(GARRISONED, TemplatesEnum.WINTER_SIEGE_TITLE, WinterSiegeRoutine.TITLE_AREA).isFound());
        assertTrue(find(NOT_GARRISONED, TemplatesEnum.WINTER_SIEGE_TITLE, WinterSiegeRoutine.TITLE_AREA).isFound());
        assertFalse(find(OTHER_TAB, TemplatesEnum.WINTER_SIEGE_TITLE, WinterSiegeRoutine.TITLE_AREA).isFound());
    }

    @Test
    void goPrepareIsFoundWithOrWithoutItsGlow() throws Exception {
        assertTrue(find(GARRISONED, TemplatesEnum.WINTER_SIEGE_GO_PREPARE, WinterSiegeRoutine.GO_PREPARE_AREA).isFound());
        assertTrue(find(NOT_GARRISONED, TemplatesEnum.WINTER_SIEGE_GO_PREPARE, WinterSiegeRoutine.GO_PREPARE_AREA).isFound());
        assertFalse(find(OTHER_TAB, TemplatesEnum.WINTER_SIEGE_GO_PREPARE, WinterSiegeRoutine.GO_PREPARE_AREA).isFound());
    }

    @Test
    void zeroGarrisonedIsToldApartFromTwo() throws Exception {
        assertTrue(find(NOT_GARRISONED, TemplatesEnum.WINTER_SIEGE_NOT_GARRISONED,
                WinterSiegeRoutine.NOT_GARRISONED_AREA).isFound());
        assertFalse(find(GARRISONED, TemplatesEnum.WINTER_SIEGE_NOT_GARRISONED,
                WinterSiegeRoutine.NOT_GARRISONED_AREA).isFound());
    }

    @Test
    void theMissionsButtonIsOnTheMapOnly() throws Exception {
        assertTrue(find(MAP, TemplatesEnum.WINTER_SIEGE_MISSIONS, WinterSiegeRoutine.MISSIONS_BUTTON_AREA).isFound());
        assertFalse(find(GARRISONED, TemplatesEnum.WINTER_SIEGE_MISSIONS, WinterSiegeRoutine.MISSIONS_BUTTON_AREA).isFound());
    }

    @Test
    void theMissionsPopupIsKnownByItsTitle() throws Exception {
        assertTrue(find(CLAIMABLE, TemplatesEnum.WINTER_SIEGE_MISSIONS_TITLE, WinterSiegeRoutine.MISSIONS_TITLE_AREA).isFound());
        assertTrue(find(NONE_CLAIMABLE, TemplatesEnum.WINTER_SIEGE_MISSIONS_TITLE, WinterSiegeRoutine.MISSIONS_TITLE_AREA).isFound());
        assertFalse(find(MAP, TemplatesEnum.WINTER_SIEGE_MISSIONS_TITLE, WinterSiegeRoutine.MISSIONS_TITLE_AREA).isFound());
    }

    @Test
    void onlyAGreenClaimMatches() throws Exception {
        ImageSearchResultData claim = find(CLAIMABLE, TemplatesEnum.EVENTS_CLAIM_BUTTON, WinterSiegeRoutine.CLAIM_AREA);
        assertTrue(claim.isFound());
        assertEquals(568, claim.getX(), 25);
        assertEquals(312, claim.getY(), 25);
        assertFalse(find(NONE_CLAIMABLE, TemplatesEnum.EVENTS_CLAIM_BUTTON, WinterSiegeRoutine.CLAIM_AREA).isFound());
    }

    @Test
    void aGreyClaimFailsTheColourCheck() throws Exception {
        BufferedImage rows;
        try (InputStream in = Objects.requireNonNull(getClass().getResourceAsStream(DIR + CLAIMABLE), CLAIMABLE)) {
            rows = ImageIO.read(in);
        }
        assertTrue(WinterSiegeRoutine.isGreenClaim(rows, new PointData(568, 312)));
        assertFalse(WinterSiegeRoutine.isGreenClaim(rows, new PointData(568, 481)));
    }

    @Test
    void theMainButtonIsFoundByItsBlueFillWhateverItsLabel() throws Exception {
        PointData garrisoned = WinterSiegeRoutine.blueButtonCenter(image(GARRISONED),
                WinterSiegeRoutine.GO_PREPARE_AREA).orElseThrow();
        assertEquals(360, garrisoned.getX(), 10);
        assertEquals(1120, garrisoned.getY(), 10);
        assertTrue(WinterSiegeRoutine.blueButtonCenter(image(NOT_GARRISONED), WinterSiegeRoutine.GO_PREPARE_AREA)
                .isPresent());
        assertFalse(WinterSiegeRoutine.blueButtonCenter(image(OTHER_TAB), WinterSiegeRoutine.GO_PREPARE_AREA)
                .isPresent());
        assertFalse(WinterSiegeRoutine.blueButtonCenter(image(MAP), WinterSiegeRoutine.GO_PREPARE_AREA)
                .isPresent());
    }

    @Test
    void whileTheEventRunsAPassComesEveryFewHours() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 11, 20, 15);
        LocalDateTime reset = LocalDateTime.of(2026, 11, 12, 19, 0);
        for (Outcome outcome : new Outcome[] {Outcome.CLAIMED, Outcome.NOTHING_TO_CLAIM, Outcome.NOT_GARRISONED,
                Outcome.NO_MAIN_BUTTON, Outcome.NO_MISSIONS}) {
            assertEquals(now.plus(WinterSiegeRoutine.EVENT_PASS_INTERVAL),
                    WinterSiegeRoutine.nextRun(outcome, 0, now, reset));
        }
        LocalDateTime lateEvening = LocalDateTime.of(2026, 11, 12, 18, 30);
        assertEquals(reset.plusMinutes(70), WinterSiegeRoutine.nextRun(Outcome.CLAIMED, 0, lateEvening, reset));
    }

    @Test
    void anAbsentEventWaitsForTheRunAfterTheNextReset() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 11, 20, 15);
        LocalDateTime reset = LocalDateTime.of(2026, 11, 12, 19, 0);
        assertEquals(reset.plusMinutes(70), WinterSiegeRoutine.nextRun(Outcome.EVENT_ABSENT, 0, now, reset));
    }

    @Test
    void anUnknownOutcomeRetriesSoonButNotPastTheCap() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 11, 20, 15);
        LocalDateTime reset = LocalDateTime.of(2026, 11, 12, 19, 0);
        assertEquals(now.plusMinutes(30), WinterSiegeRoutine.nextRun(Outcome.UNKNOWN, 1, now, reset));
        assertEquals(now.plus(WinterSiegeRoutine.EVENT_PASS_INTERVAL), WinterSiegeRoutine.nextRun(Outcome.UNKNOWN,
                WinterSiegeRoutine.MAX_FAILED_ATTEMPTS, now, reset));
    }

    private BufferedImage image(String name) throws IOException {
        try (InputStream in = Objects.requireNonNull(getClass().getResourceAsStream(DIR + name), name)) {
            return ImageIO.read(in);
        }
    }

    private ImageSearchResultData find(String name, TemplatesEnum template, AreaData area) throws IOException {
        return OpenCvPatternLocator.locatePattern(bytes(name), template.getTemplate(),
                area.topLeft(), area.bottomRight(), 85);
    }

    private byte[] bytes(String name) throws IOException {
        try (InputStream in = Objects.requireNonNull(getClass().getResourceAsStream(DIR + name), name)) {
            return in.readAllBytes();
        }
    }
}
