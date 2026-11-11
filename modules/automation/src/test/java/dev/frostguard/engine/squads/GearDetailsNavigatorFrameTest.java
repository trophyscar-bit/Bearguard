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
    private static final PointData GEAR = GearScanScreens.GEAR_TAB;
    private static final PointData BOOTS = GearScanScreens.GEAR_TILES.get("Boots");
    private static final PointData GAUNTLETS = GearScanScreens.GEAR_TILES.get("Gauntlets");
    private static final PointData BELT = GearScanScreens.GEAR_TILES.get("Belt");

    @Test
    void walksPastOtherHeroesToTheExactPieceAndBackToTheGrid() throws Exception {
        Rig rig = bahiti();
        GearDetailsNavigator navigator = new GearDetailsNavigator(rig);

        assertEquals(new GearDetails("Sniper Boots", 80, "Enhance"), navigator.open("Bahiti", "Boots"));
        assertEquals("scan-details", rig.screen);
        assertEquals(List.of(CARD_0, CARD_1, CARD_2, CARD_3, GEAR, BOOTS), rig.taps);

        navigator.closeToGrid();

        assertEquals("scan-back-hero", rig.screen);
        assertEquals(5, rig.backs, "three skipped cards, then details and hero page");
    }

    @Test
    void opensSergeysGauntletsOnRealFrames() throws Exception {
        PointData card4 = card(4);
        Rig rig = new Rig(GRID);
        for (PointData card : List.of(CARD_0, CARD_1, CARD_2, CARD_3)) rig.on(GRID, card, "scan-mia");
        rig.onBack("scan-mia", GRID).on(GRID, card4, "navigate-sergey-stats")
                .on("navigate-sergey-stats", GEAR, "quickequip-h04")
                .on("quickequip-h04", GAUNTLETS, "navigate-sergey-gauntlets");

        assertEquals(new GearDetails("Guardian Gauntlets", 76, "Enhance"), new GearDetailsNavigator(rig).open("Sergey", "Gauntlets"));
        assertEquals(List.of(CARD_0, CARD_1, CARD_2, CARD_3, card4, GEAR, GAUNTLETS), rig.taps);
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
    void anArrowWalkToAnotherHeroBeforeTheGearTabStopsBeforeAnyTile() throws Exception {
        PointData card4 = card(4);
        Rig rig = new Rig(GRID);
        for (PointData card : List.of(CARD_0, CARD_1, CARD_2, CARD_3)) rig.on(GRID, card, "scan-mia");
        rig.onBack("scan-mia", GRID).on(GRID, card4, "navigate-sergey-stats")
                .on("navigate-sergey-stats", GEAR, "quickequip-h01");

        assertThrows(Abort.class, () -> new GearDetailsNavigator(rig).open("Sergey", "Gauntlets"));

        assertEquals(List.of(CARD_0, CARD_1, CARD_2, CARD_3, card4, GEAR), rig.taps);
    }

    @Test
    void aTileThatOpensTheWrongPieceStops() throws Exception {
        PointData card4 = card(4);
        Rig rig = new Rig(GRID);
        for (PointData card : List.of(CARD_0, CARD_1, CARD_2, CARD_3)) rig.on(GRID, card, "scan-mia");
        rig.onBack("scan-mia", GRID).on(GRID, card4, "navigate-sergey-stats")
                .on("navigate-sergey-stats", GEAR, "quickequip-h04")
                .on("quickequip-h04", BELT, "navigate-sergey-gauntlets");

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
    void aHeroNotOnTheGridStopsAfterClosingEachCard() {
        FakeScreens screens = new FakeScreens("Natalia", null, "Jessie");

        assertThrows(Abort.class, () -> new GearDetailsNavigator(screens, null).open("Flint", "Gauntlets"));

        assertEquals(3, screens.closes);
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
                .on(GRID, CARD_0, "scan-mia").on(GRID, CARD_1, "scan-mia").on(GRID, CARD_2, "scan-mia")
                .onBack("scan-mia", GRID)
                .on(GRID, CARD_3, "scan-bahiti")
                .on("scan-bahiti", GEAR, "scan-gear")
                .on("scan-gear", BOOTS, "scan-details")
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
