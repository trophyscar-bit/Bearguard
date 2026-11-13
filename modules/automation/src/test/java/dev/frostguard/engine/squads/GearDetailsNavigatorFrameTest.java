package dev.frostguard.engine.squads;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.api.squads.HeroGear;
import dev.frostguard.engine.squads.ForgeDialogReader.ForgeDialog;
import dev.frostguard.engine.squads.ForgeDialogReader.GearDetails;
import dev.frostguard.engine.squads.GearScan.Abort;
import dev.frostguard.engine.squads.GearScan.HeroPage;

/** Real frames where they exist. No Hero Recruitment frame was captured, so that screen is drawn. */
class GearDetailsNavigatorFrameTest {

    private static final String GRID = "scan-grid";
    private static final String RECRUITMENT = "drawn-recruitment";
    private static final PointData CARD_0 = new PointData(110, 246);
    private static final PointData CARD_1 = new PointData(277, 246);
    private static final PointData CARD_2 = new PointData(443, 246);
    private static final PointData CARD_3 = new PointData(609, 246);
    private static final PointData ARROW = new PointData(666, 704);
    private static final PointData GEAR = GearScanScreens.GEAR_TAB;
    private static final PointData BOOTS = GearScanScreens.GEAR_TILES.get("Boots");
    private static final PointData GAUNTLETS = GearScanScreens.GEAR_TILES.get("Gauntlets");
    private static final PointData BELT = GearScanScreens.GEAR_TILES.get("Belt");

    @Test
    void walksByTheRightArrowToTheExactPieceAndBackToTheGrid() throws Exception {
        Rig rig = bahiti();
        GearDetailsNavigator navigator = new GearDetailsNavigator(rig);

        assertEquals(new GearDetails("Sniper Boots", 80, "Enhance"), navigator.open("Bahiti", "Boots"));
        assertEquals("scan-details", rig.screen);
        assertEquals(List.of(CARD_0, ARROW, ARROW, ARROW, BOOTS), rig.taps, "one card on the list, then the arrow; no return to the list between heroes");

        navigator.finishPiece();

        assertEquals("scan-back-details", rig.screen, "Gear Details closed, still on Bahiti's page");
        assertEquals(1, rig.backs);

        navigator.closeToGrid();

        assertEquals("scan-back-hero", rig.screen);
        assertEquals(2, rig.backs, "one more Back to the list at the very end");
    }

    @Test
    void aSecondPieceOfTheSameHeroNeedsNoNavigationAtAll() throws Exception {
        Rig rig = bahiti().on("scan-back-details", GAUNTLETS, "scan-details");
        GearDetailsNavigator navigator = new GearDetailsNavigator(rig);
        navigator.open("Bahiti", "Boots");
        navigator.finishPiece();
        rig.taps.clear();

        // No Bahiti Gauntlets frame exists, so the scripted tap shows the Boots details and the piece check refuses it.
        Abort abort = assertThrows(Abort.class, () -> navigator.open("Bahiti", "Gauntlets"));

        assertTrue(abort.getMessage().contains("opened Sniper Boots"), abort.getMessage());
        assertEquals(List.of(GAUNTLETS), rig.taps, "already on Bahiti's Gear tab: just the tile, no card, no arrow, no Gear tab tap");
    }

    @Test
    void opensSergeysGauntletsOnRealFrames() throws Exception {
        Rig rig = new Rig(GRID).on(GRID, CARD_0, "quickequip-h00");
        for (int hero = 0; hero < 4; hero++) rig.on("quickequip-h0" + hero, ARROW, "quickequip-h0" + (hero + 1));
        rig.on("quickequip-h04", GAUNTLETS, "navigate-sergey-gauntlets");

        assertEquals(new GearDetails("Guardian Gauntlets", 76, "Enhance"), new GearDetailsNavigator(rig).open("Sergey", "Gauntlets"));
        assertEquals(List.of(CARD_0, ARROW, ARROW, ARROW, ARROW, GAUNTLETS), rig.taps);
    }

    @Test
    void aCardThatOpensHeroRecruitmentTapsNothingFurtherAndBacksOut() throws Exception {
        Rig rig = new Rig(GRID).on(GRID, CARD_0, RECRUITMENT).onBack(RECRUITMENT, GRID);
        GearDetailsNavigator navigator = new GearDetailsNavigator(rig);

        assertThrows(Abort.class, () -> navigator.open("Mia", "Boots"));

        assertEquals(List.of(CARD_0), rig.taps);
        assertEquals(RECRUITMENT, rig.screen);

        navigator.closeToGrid();

        assertEquals(GRID, rig.screen);
        assertEquals(List.of(CARD_0), rig.taps);
    }

    @Test
    void theRecruitmentScreenIsNotAnyScreenTheWalkTrusts() throws Exception {
        RawImageData frame = frame(RECRUITMENT);

        assertFalse(HeroCards.isHeroesGrid(frame));
        assertFalse(HeroScreenReader.isHeroPage(frame));
        assertFalse(HeroScreenReader.isGearTab(frame));
        assertFalse(ForgeDialogReader.isGearDetails(frame));
    }

    @Test
    void startingOnHeroRecruitmentIsRefusedWithNoTap() throws Exception {
        Rig rig = new Rig(RECRUITMENT);

        assertThrows(Abort.class, () -> new GearDetailsNavigator(rig).open("Mia", "Boots"));

        assertTrue(rig.taps.isEmpty());
    }

    @Test
    void aScreenTitledHeroesWithNoCardsIsRefusedWithNoTap() throws Exception {
        Rig rig = new Rig("drawn-heroes-title");

        assertThrows(Abort.class, () -> new GearDetailsNavigator(rig).open("Mia", "Boots"));

        assertTrue(rig.taps.isEmpty());
    }

    @Test
    void noGridCardCanReachRecruitHero() throws Exception {
        for (HeroCards.Card card : HeroCards.gridCards(frame(GRID))) {
            assertTrue(card.centreY() + 20 < 1170, card.toString());
        }
    }

    @Test
    void anArrowThatDoesNotReachANewHeroStopsBeforeAnyTile() throws Exception {
        Rig rig = new Rig(GRID).on(GRID, CARD_0, "quickequip-h00").on("quickequip-h00", ARROW, "quickequip-h01");

        Abort abort = assertThrows(Abort.class, () -> new GearDetailsNavigator(rig).open("Sergey", "Gauntlets"));

        assertTrue(abort.getMessage().contains("did not reach a new hero"), abort.getMessage());
        assertEquals(List.of(CARD_0, ARROW, ARROW), rig.taps, "the second arrow changed nothing, so the walk stopped without touching a tile");
    }

    @Test
    void aTileThatOpensTheWrongPieceStops() throws Exception {
        Rig rig = new Rig(GRID).on(GRID, CARD_0, "quickequip-h00");
        for (int hero = 0; hero < 4; hero++) rig.on("quickequip-h0" + hero, ARROW, "quickequip-h0" + (hero + 1));
        rig.on("quickequip-h04", BELT, "navigate-sergey-gauntlets");

        Abort abort = assertThrows(Abort.class, () -> new GearDetailsNavigator(rig).open("Sergey", "Belt"));

        assertTrue(abort.getMessage().contains("Guardian Gauntlets"), abort.getMessage());
    }

    @Test
    void anArrowWalkInsideDetailsToASameSetHeroIsCaughtByTheLevel() {
        FakeScreens screens = new FakeScreens("Flint");
        screens.tiles = List.of(new HeroGear(1, 50, 1), new HeroGear(2, 85, 2), new HeroGear(3, 85, 3), new HeroGear(4, 50, 1));
        screens.details = new GearDetails("Guardian Gauntlets", 76, "Enhance");

        Abort abort = assertThrows(Abort.class, () -> new GearDetailsNavigator(screens, null).open("Flint", "Gauntlets"));

        assertTrue(abort.getMessage().contains("+76"), abort.getMessage());
    }

    @Test
    void anUnreadTileIsNeverOpened() {
        FakeScreens screens = new FakeScreens("Flint");
        screens.tiles = List.of(new HeroGear(1, 50, 1), new HeroGear(2, null, null), new HeroGear(3, 85, 3), new HeroGear(4, 50, 1));

        assertThrows(Abort.class, () -> new GearDetailsNavigator(screens, null).open("Flint", "Gauntlets"));

        assertFalse(screens.detailsOpened);
    }

    @Test
    void aHeroNotOnTheGridStopsAfterClosingEachCardButTheLast() {
        FakeScreens screens = new FakeScreens("Natalia", null, "Jessie");

        assertThrows(Abort.class, () -> new GearDetailsNavigator(screens, null).open("Flint", "Gauntlets"));

        assertEquals(2, screens.closes, "the last card is left for closeToGrid");
    }

    @Test
    void theCardLimitHolds() {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < GearScan.CARD_LIMIT; i++) names.add("Natalia");
        names.add("Flint");
        FakeScreens screens = new FakeScreens(names.toArray(String[]::new));

        assertThrows(Abort.class, () -> new GearDetailsNavigator(screens, null).open("Flint", "Gauntlets"));

        assertEquals(GearScan.CARD_LIMIT, screens.opens);
    }

    @Test
    void anUnknownHeroOrSlotIsRefusedBeforeAnyStep() {
        FakeScreens screens = new FakeScreens("Natalia");
        GearDetailsNavigator navigator = new GearDetailsNavigator(screens, null);

        assertThrows(Abort.class, () -> navigator.open("Natalia", "Boots"));
        assertThrows(Abort.class, () -> navigator.open("Mia", "Hat"));

        assertEquals(0, screens.opens);
    }

    private static PointData card(int index) throws Exception {
        HeroCards.Card card = HeroCards.gridCards(frame(GRID)).get(index);
        return new PointData(card.centreX(), card.centreY());
    }

    private static Rig bahiti() {
        return new Rig(GRID)
                .on(GRID, CARD_0, "quickequip-h00")
                .on("quickequip-h00", ARROW, "quickequip-h01")
                .on("quickequip-h01", ARROW, "quickequip-h02")
                .on("quickequip-h02", ARROW, "quickequip-h03")
                .on("quickequip-h03", BOOTS, "scan-details")
                .onBack("scan-details", "scan-back-details")
                .onBack("scan-back-details", "scan-back-hero");
    }

    private static final class FakeScreens implements GearScan.Screens {
        private final String[] names;
        List<HeroGear> tiles;
        GearDetails details;
        boolean detailsOpened;
        int opens;
        int closes;

        FakeScreens(String... names) {
            this.names = names;
        }

        @Override
        public int openGrid() {
            return names.length;
        }

        @Override
        public String openHero(int index) {
            opens++;
            return names[index];
        }

        @Override
        public HeroPage readHero(String name) {
            throw new AssertionError("the navigator has no reason to read the Stats tab");
        }

        @Override
        public List<HeroGear> openGearTab(String hero) {
            return tiles;
        }

        @Override
        public GearDetails openDetails(String slot) {
            detailsOpened = true;
            return details;
        }

        @Override
        public ForgeDialog openForge() {
            throw new AssertionError("the navigator must stop at Gear Details");
        }

        @Override
        public void closeForge() {
            throw new AssertionError();
        }

        @Override
        public void closeDetails() {
            throw new AssertionError();
        }

        @Override
        public void closeHero() {
            closes++;
        }
    }

    /** Shows one frame per screen and follows only the taps it was told about. */
    private static final class Rig implements GearScanScreens.Device {
        private final Map<String, Map<PointData, String>> taps_ = new HashMap<>();
        private final Map<String, String> backs_ = new HashMap<>();
        final List<PointData> taps = new ArrayList<>();
        int backs;
        String screen;

        Rig(String start) {
            screen = start;
        }

        Rig on(String from, PointData tap, String to) {
            taps_.computeIfAbsent(from, key -> new HashMap<>()).put(tap, to);
            return this;
        }

        Rig onBack(String from, String to) {
            backs_.put(from, to);
            return this;
        }

        @Override
        public RawImageData capture() {
            try {
                return frame(screen);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public void tap(PointData point, int radius) {
            assertFalse(RECRUITMENT.equals(screen), "tap at " + point + " on Hero Recruitment");
            taps.add(point);
            screen = taps_.getOrDefault(screen, Map.of()).getOrDefault(point, screen);
        }

        @Override
        public void back() {
            backs++;
            screen = backs_.getOrDefault(screen, screen);
        }

        @Override
        public void pause(int millis) {
        }
    }

    private static final Map<String, RawImageData> FRAMES = new HashMap<>();

    private static synchronized RawImageData frame(String name) throws Exception {
        RawImageData cached = FRAMES.get(name);
        if (cached != null) return cached;
        BufferedImage image = switch (name) {
            case RECRUITMENT -> drawn("Hero Recruitment");
            case "drawn-heroes-title" -> drawn("Heroes");
            default -> ImageIO.read(Objects.requireNonNull(
                    GearDetailsNavigatorFrameTest.class.getResourceAsStream("/squads/" + name + ".png"), name));
        };
        RawImageData frame = raw(image);
        FRAMES.put(name, frame);
        return frame;
    }

    private static BufferedImage drawn(String title) {
        BufferedImage image = new BufferedImage(720, 1280, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(40, 60, 110));
        g.fillRect(0, 0, 720, 1280);
        g.setColor(Color.WHITE);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 30));
        g.drawString(title, 100, 52);
        g.drawString("Recruit", 300, 1120);
        g.dispose();
        return image;
    }

    private static RawImageData raw(BufferedImage image) {
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
