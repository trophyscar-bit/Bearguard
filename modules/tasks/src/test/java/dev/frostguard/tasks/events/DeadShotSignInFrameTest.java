package dev.frostguard.tasks.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.engine.nav.CommonGameAreas;
import dev.frostguard.tasks.events.DeadShotSignInRoutine.Outcome;
import dev.frostguard.tasks.events.DeadShotSignInRoutine.Tile;
import dev.frostguard.vision.match.OpenCvPatternLocator;

/**
 * Live 720x1280 captures of Dead Shot from 2026-11-10: Deals open on another tab with Dead Shot third in the
 * strip, the Dead Shot page, its Sign-in Rewards popup with Day 2 glowing, and the popup after Day 2 was
 * claimed. The page frame has the scrolling winner ticker blacked out; the City rail frame keeps only the
 * rail column, because the map around it shows other players.
 */
class DeadShotSignInFrameTest {

    private static final String DIR = "/deadshot/";

    @BeforeAll
    static void loadOpenCv() throws IOException {
        try {
            OpenCvPatternLocator.loadNativeLibrary();
        } catch (UnsatisfiedLinkError ignored) {
            // Another saved-frame test may already have loaded OpenCV in this JVM.
        }
    }

    @Test
    void theDealsButtonIsFoundOnTheRail() throws Exception {
        assertTrue(find("city-rail.png", TemplatesEnum.HOME_DEALS_BUTTON, DeadShotSignInRoutine.HOME_RAIL_AREA).isFound());
    }

    @Test
    void theDealsHeaderShowsOnEveryDealsTab() throws Exception {
        assertTrue(find("deals-other-tab.png", TemplatesEnum.DEALS_TITLE, CommonGameAreas.DEALS_HEADER).isFound());
        assertTrue(find("dead-shot-page.png", TemplatesEnum.DEALS_TITLE, CommonGameAreas.DEALS_HEADER).isFound());
        assertFalse(find("city-rail.png", TemplatesEnum.DEALS_TITLE, CommonGameAreas.DEALS_HEADER).isFound());
    }

    @Test
    void theUnselectedDeadShotTabIsFoundWhereverItSitsInTheStrip() throws Exception {
        ImageSearchResultData tab = find("deals-other-tab.png", TemplatesEnum.DEAD_SHOT_TAB, CommonGameAreas.DEALS_TAB_STRIP);
        assertTrue(tab.isFound());
        assertEquals(385, tab.getX(), 10);
        assertEquals(145, tab.getY(), 10);
        // Selected, the tab loses its label; the page is then known by its title and the selected tab.
        assertFalse(find("dead-shot-page.png", TemplatesEnum.DEAD_SHOT_TAB, CommonGameAreas.DEALS_TAB_STRIP).isFound());
        assertTrue(find("dead-shot-page.png", TemplatesEnum.DEAD_SHOT_TAB_SELECTED, CommonGameAreas.DEALS_TAB_STRIP).isFound());
        assertFalse(find("deals-other-tab.png", TemplatesEnum.DEAD_SHOT_TAB_SELECTED, CommonGameAreas.DEALS_TAB_STRIP).isFound());
    }

    @Test
    void theDeadShotPageIsKnownByItsTitle() throws Exception {
        assertTrue(find("dead-shot-page.png", TemplatesEnum.DEAD_SHOT_TITLE, DeadShotSignInRoutine.DEAD_SHOT_TITLE_AREA).isFound());
        assertFalse(find("deals-other-tab.png", TemplatesEnum.DEAD_SHOT_TITLE, DeadShotSignInRoutine.DEAD_SHOT_TITLE_AREA).isFound());
        assertFalse(find("signin-claimable.png", TemplatesEnum.DEAD_SHOT_TITLE, DeadShotSignInRoutine.DEAD_SHOT_TITLE_AREA).isFound());
    }

    @Test
    void theSignInRewardsButtonAndItsRedDotAreFound() throws Exception {
        ImageSearchResultData button = find("dead-shot-page.png", TemplatesEnum.DEAD_SHOT_SIGN_IN_BUTTON,
                DeadShotSignInRoutine.SIGN_IN_BUTTON_AREA);
        assertTrue(button.isFound());
        assertEquals(72, button.getX(), 12);
        assertEquals(500, button.getY(), 30);
        assertTrue(DeadShotSignInRoutine.hasRedDot(image("dead-shot-page.png"), button.getPoint()));
        assertFalse(find("deals-other-tab.png", TemplatesEnum.DEAD_SHOT_SIGN_IN_BUTTON,
                DeadShotSignInRoutine.SIGN_IN_BUTTON_AREA).isFound());
    }

    @Test
    void theSignInPopupIsKnownByItsTitle() throws Exception {
        assertTrue(find("signin-claimable.png", TemplatesEnum.DEAD_SHOT_SIGN_IN_TITLE, DeadShotSignInRoutine.SIGN_IN_TITLE_AREA).isFound());
        assertTrue(find("signin-claimed.png", TemplatesEnum.DEAD_SHOT_SIGN_IN_TITLE, DeadShotSignInRoutine.SIGN_IN_TITLE_AREA).isFound());
        assertFalse(find("dead-shot-page.png", TemplatesEnum.DEAD_SHOT_SIGN_IN_TITLE, DeadShotSignInRoutine.SIGN_IN_TITLE_AREA).isFound());
    }

    @Test
    void theGlowingTileIsFoundAroundDayTwo() throws Exception {
        Optional<Tile> tile = DeadShotSignInRoutine.claimableTile(image("signin-claimable.png"));
        assertTrue(tile.isPresent());
        PointData center = tile.get().center();
        assertEquals(282, center.getX(), 10);
        assertEquals(432, center.getY(), 15);
    }

    @Test
    void noTileGlowsOnceTheDayIsClaimed() throws Exception {
        assertTrue(DeadShotSignInRoutine.claimableTile(image("signin-claimed.png")).isEmpty());
    }

    @Test
    void theTappedTileReadsClaimedOnlyAfterTheClaim() throws Exception {
        Tile day2 = DeadShotSignInRoutine.claimableTile(image("signin-claimable.png")).orElseThrow();
        assertFalse(DeadShotSignInRoutine.isClaimed(image("signin-claimable.png"), day2));
        assertTrue(DeadShotSignInRoutine.isClaimed(image("signin-claimed.png"), day2));
    }

    @Test
    void aStripThatDidNotMoveReadsStill() throws Exception {
        BufferedImage frozen = image("deals-other-tab.png");
        BufferedImage selected = image("dead-shot-page.png");
        assertFalse(DealsTabStrip.stripMoved(frozen, frozen));
        assertTrue(DealsTabStrip.stripMoved(frozen, selected));
    }

    @Test
    void aSettledDayWaitsForTheRunAnHourAfterTheNextReset() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 10, 20, 5);
        LocalDateTime reset = LocalDateTime.of(2026, 11, 10, 19, 0).plusDays(1);
        LocalDateTime daily = reset.plusHours(1);
        assertEquals(daily, DeadShotSignInRoutine.nextRun(Outcome.CLAIMED, 0, now, reset));
        assertEquals(daily, DeadShotSignInRoutine.nextRun(Outcome.NOTHING_TO_CLAIM, 0, now, reset));
        assertEquals(daily, DeadShotSignInRoutine.nextRun(Outcome.EVENT_ABSENT, 0, now, reset));
    }

    @Test
    void anUnknownOutcomeRetriesSoonButNotPastTheCap() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 10, 20, 5);
        LocalDateTime reset = LocalDateTime.of(2026, 11, 11, 19, 0);
        LocalDateTime daily = reset.plusHours(1);
        assertEquals(now.plusMinutes(30), DeadShotSignInRoutine.nextRun(Outcome.UNKNOWN, 1, now, reset));
        assertEquals(now.plusMinutes(30), DeadShotSignInRoutine.nextRun(Outcome.UNKNOWN,
                DeadShotSignInRoutine.MAX_FAILED_ATTEMPTS - 1, now, reset));
        assertEquals(daily, DeadShotSignInRoutine.nextRun(Outcome.UNKNOWN,
                DeadShotSignInRoutine.MAX_FAILED_ATTEMPTS, now, reset));
        // A retry never lands after the daily run it would duplicate.
        LocalDateTime lateNow = daily.minusMinutes(10);
        assertEquals(daily, DeadShotSignInRoutine.nextRun(Outcome.UNKNOWN, 1, lateNow, reset));
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

    private BufferedImage image(String name) throws IOException {
        return ImageIO.read(Objects.requireNonNull(getClass().getResourceAsStream(DIR + name), name));
    }
}
