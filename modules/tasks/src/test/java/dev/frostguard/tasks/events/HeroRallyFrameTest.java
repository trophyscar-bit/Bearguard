package dev.frostguard.tasks.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.engine.nav.CommonGameAreas;
import dev.frostguard.tasks.events.HeroRallyClaimRoutine.ListMove;
import dev.frostguard.tasks.events.RedDotVision.Blob;
import dev.frostguard.vision.match.OpenCvPatternLocator;

/**
 * Live 720x1280 captures, kept only as crops and pasted back at their screen position: the Deals
 * tab strip at several swipe positions (Hero Rally is the seventh of thirteen tabs), the Events strip (no Hero
 * Rally in it), the Hero Rally banner title, and the level rows with two Claims (levels 19 and 20), with one
 * left after claiming 19, and with both claimed (Continue and Obtain Points only).
 */
class HeroRallyFrameTest {

    private static final String DIR = "/herorally/";
    private static final int STRIP_Y = 90;
    private static final int TITLE_Y = 190;
    private static final int ROWS_Y = 625;

    /** The Deals strip from its start to its end, as a swipe toward the end walks it. */
    private static final List<String> DEALS_STRIP = List.of(
            "strip-start.png", "strip-2.png", "strip-3-hero-rally.png", "strip-4.png", "strip-end.png");
    /** The Events strip from its start to its end. */
    private static final List<String> EVENTS_STRIP = List.of(
            "events-strip-2.png", "events-strip-0.png", "events-strip-8.png", "events-strip-10.png");

    @BeforeAll
    static void loadOpenCv() throws IOException {
        try {
            OpenCvPatternLocator.loadNativeLibrary();
        } catch (UnsatisfiedLinkError ignored) {
            // Another saved-frame test may already have loaded OpenCV in this JVM.
        }
    }

    @Test
    void theUnselectedHeroRallyTabIsFoundWhereverItSitsInTheStrip() throws Exception {
        ImageSearchResultData tab = findTab(strip("strip-3-hero-rally.png"));
        assertTrue(tab.isFound());
        assertEquals(226, tab.getX(), 10);
        assertEquals(146, tab.getY(), 10);
        ImageSearchResultData mid = findTab(strip("strip-hero-rally-mid.png"));
        assertTrue(mid.isFound());
        assertEquals(357, mid.getX(), 10);
    }

    @Test
    void noOtherStripPositionShowsTheTab() throws Exception {
        for (String name : List.of("strip-start.png", "strip-2.png", "strip-4.png", "strip-end.png",
                "strip-selected.png")) {
            assertFalse(findTab(strip(name)).isFound(), name);
        }
        for (String name : EVENTS_STRIP) {
            assertFalse(findTab(strip(name)).isFound(), name);
        }
    }

    @Test
    void theHeroRallyPageIsKnownByItsTitle() throws Exception {
        assertTrue(find(at("title-hero-rally.png", TITLE_Y), TemplatesEnum.HERO_RALLY_TITLE,
                HeroRallyClaimRoutine.TITLE_AREA).isFound());
        assertFalse(find(at("title-other-tab.png", TITLE_Y), TemplatesEnum.HERO_RALLY_TITLE,
                HeroRallyClaimRoutine.TITLE_AREA).isFound());
    }

    @Test
    void theWalkFindsTheTabFromTheStartOfTheStrip() throws Exception {
        FakeStrip strip = new FakeStrip(DEALS_STRIP, 0);
        ImageSearchResultData tab = DealsTabStrip.find(strip);
        assertTrue(tab.isFound());
        assertEquals(2, strip.swipes);
        assertEquals(226, tab.getX(), 10);
    }

    @Test
    void theWalkTurnsBackWhenTheStripOpensPastTheTab() throws Exception {
        FakeStrip strip = new FakeStrip(DEALS_STRIP, DEALS_STRIP.size() - 1);
        ImageSearchResultData tab = DealsTabStrip.find(strip);
        assertTrue(tab.isFound());
        // Two still swipes end the walk toward the end, then two swipes back reach the tab.
        assertEquals(DealsTabStrip.STILL_SWIPES_FOR_END + 2, strip.swipes);
    }

    @Test
    void theWalkEndsBothWaysWhenTheTabIsAbsent() throws Exception {
        FakeStrip strip = new FakeStrip(EVENTS_STRIP, 1);
        ImageSearchResultData tab = DealsTabStrip.find(strip);
        assertFalse(tab.isFound());
        assertTrue(strip.reachedStart && strip.reachedEnd);
        assertTrue(strip.swipes <= 2 * (EVENTS_STRIP.size() + DealsTabStrip.STILL_SWIPES_FOR_END), "swipes " + strip.swipes);
    }

    @Test
    void bothReadyLevelsAreFoundAsClaims() throws Exception {
        BufferedImage rows = at("rows-two-claims.png", ROWS_Y);
        List<Blob> pills = HeroRallyClaimRoutine.greenPills(rows);
        assertEquals(2, pills.size());
        assertEquals(602, pills.get(0).centerX(), 8);
        assertEquals(894, pills.get(0).centerY(), 8);
        assertEquals(1074, pills.get(1).centerY(), 8);
        for (Blob pill : pills) {
            assertTrue(find(rows, TemplatesEnum.EVENTS_CLAIM_BUTTON, HeroRallyClaimRoutine.claimBox(pill)).isFound());
        }
    }

    @Test
    void theLevelLeftAfterOneClaimIsStillFound() throws Exception {
        List<Blob> pills = HeroRallyClaimRoutine.greenPills(at("rows-one-claim.png", ROWS_Y));
        assertEquals(1, pills.size());
        assertEquals(894, pills.get(0).centerY(), 8);
    }

    @Test
    void continueAndObtainPointsAreNeverClaims() throws Exception {
        BufferedImage rows = at("rows-all-claimed.png", ROWS_Y);
        assertTrue(HeroRallyClaimRoutine.greenPills(rows).isEmpty());
        assertFalse(find(rows, TemplatesEnum.EVENTS_CLAIM_BUTTON, HeroRallyClaimRoutine.ROW_BUTTON_AREA).isFound());
        // The pills are there, just not green: two Continue rows and an Obtain Points row.
        assertTrue(HeroRallyClaimRoutine.hasPill(rows, HeroRallyClaimRoutine::isContinueOrange));
        assertTrue(HeroRallyClaimRoutine.hasPill(rows, HeroRallyClaimRoutine::isObtainBlue));
        // Nor in a frame that does have Claims: the Continue row above them stays out.
        BufferedImage ready = at("rows-two-claims.png", ROWS_Y);
        AreaData continueRow = AreaData.of(500, 680, 700, 750);
        assertFalse(find(ready, TemplatesEnum.EVENTS_CLAIM_BUTTON, continueRow).isFound());
        assertTrue(HeroRallyClaimRoutine.greenPills(ready).stream().noneMatch(p -> p.centerY() < 800));
    }

    @Test
    void theListStopsOnceClaimedAndUnreachedRowsShowTogether() throws Exception {
        assertEquals(ListMove.DONE, HeroRallyClaimRoutine.nextMove(at("rows-all-claimed.png", ROWS_Y)));
        assertEquals(ListMove.DONE, HeroRallyClaimRoutine.nextMove(at("rows-two-claims.png", ROWS_Y)));
        assertEquals(ListMove.LATER, HeroRallyClaimRoutine.nextMove(true, false));
        assertEquals(ListMove.EARLIER, HeroRallyClaimRoutine.nextMove(false, true));
        assertEquals(ListMove.DONE, HeroRallyClaimRoutine.nextMove(false, false));
    }

    @Test
    void aClaimChangesItsRowAndANoOpDoesNot() throws Exception {
        BufferedImage two = at("rows-two-claims.png", ROWS_Y);
        BufferedImage one = at("rows-one-claim.png", ROWS_Y);
        BufferedImage none = at("rows-all-claimed.png", ROWS_Y);
        Blob level19 = HeroRallyClaimRoutine.greenPills(two).get(0);
        Blob level20 = HeroRallyClaimRoutine.greenPills(one).get(0);
        assertTrue(HeroRallyClaimRoutine.rowChanged(two, one, level19));
        assertTrue(HeroRallyClaimRoutine.rowChanged(one, none, level20));
        assertFalse(HeroRallyClaimRoutine.rowChanged(two, two, level19));
    }

    /** A strip that moves one saved position per swipe and stays put at either end. */
    private final class FakeStrip implements DealsTabStrip.Walker {
        private final List<String> positions;
        private int at;
        int swipes;
        boolean reachedStart;
        boolean reachedEnd;

        FakeStrip(List<String> positions, int start) {
            this.positions = positions;
            this.at = start;
        }

        @Override
        public ImageSearchResultData locate() {
            try {
                return findTab(strip(positions.get(at)));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public void swipe(boolean towardEnd) {
            swipes++;
            at = Math.max(0, Math.min(positions.size() - 1, at + (towardEnd ? 1 : -1)));
            reachedEnd |= at == positions.size() - 1;
            reachedStart |= at == 0;
        }

        @Override
        public Optional<BufferedImage> frame() {
            try {
                return Optional.of(strip(positions.get(at)));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private ImageSearchResultData findTab(BufferedImage screen) throws IOException {
        return find(screen, TemplatesEnum.HERO_RALLY_DEALS_TAB, CommonGameAreas.DEALS_TAB_STRIP);
    }

    private static ImageSearchResultData find(BufferedImage screen, TemplatesEnum template, AreaData area)
            throws IOException {
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(screen, "png", png);
        return OpenCvPatternLocator.locatePattern(png.toByteArray(), template.getTemplate(),
                area.topLeft(), area.bottomRight(), 85);
    }

    private BufferedImage strip(String name) throws IOException {
        return at(name, STRIP_Y);
    }

    /** A black 720x1280 screen with the saved crop pasted back at its original height. */
    private BufferedImage at(String name, int y) throws IOException {
        BufferedImage crop;
        try (InputStream in = Objects.requireNonNull(getClass().getResourceAsStream(DIR + name), name)) {
            crop = ImageIO.read(in);
        }
        BufferedImage screen = new BufferedImage(720, 1280, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = screen.createGraphics();
        g.drawImage(crop, 0, y, null);
        g.dispose();
        return screen;
    }
}
