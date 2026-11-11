package dev.frostguard.engine.squads;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.api.squads.HeroGear;
import dev.frostguard.engine.squads.ForgeDialogReader.ForgeDialog;
import dev.frostguard.engine.squads.ForgeDialogReader.GearDetails;
import dev.frostguard.engine.squads.GearScan.Abort;
import dev.frostguard.engine.squads.GearScan.HeroPage;
import dev.frostguard.engine.squads.HeroScreenReader.HeroXp;

/** Drives the walk over saved frames. Expected values were read by eye from each frame. */
class GearScanScreensFrameTest {

    private static final PointData BAHITI_CARD = new PointData(609, 246);
    private static final PointData MIA_CARD = new PointData(110, 246);
    private static final PointData STATS = new PointData(133, 1237);
    private static final PointData OVERALL = new PointData(617, 1110);
    private static final PointData DISMISS = new PointData(120, 300);
    private static final PointData GEAR = new PointData(586, 1240);
    private static final PointData BOOTS = new PointData(619, 540);
    private static final PointData MASTERY = new PointData(208, 815);

    // {left, top, right, bottom} of each forbidden control, by the screen it is drawn on.
    private static final Map<String, int[][]> FORBIDDEN = Map.of(
            "scan-grid", new int[][] { { 27, 1170, 343, 1252 }, { 377, 1170, 692, 1252 } },
            "scan-bahiti", new int[][] { { 205, 1070, 517, 1155 } },
            "scan-gear", new int[][] { { 60, 1070, 345, 1155 }, { 376, 1070, 660, 1155 } },
            "scan-details", new int[][] { { 515, 243, 650, 358 }, { 369, 772, 655, 858 }, { 597, 1050, 695, 1150 } },
            "enhance-ascend", new int[][] { { 515, 243, 650, 358 }, { 369, 772, 655, 858 }, { 597, 1050, 695, 1150 } },
            "scan-forge", new int[][] { { 216, 938, 504, 1026 } });

    @TempDir
    Path workspace;

    @Test
    void walksOnePieceOnRealFramesAndTapsOnlyTheScanControls() throws Exception {
        Rig rig = bahitiRig();
        GearScanScreens screens = new GearScanScreens(rig);

        screens.openGrid();
        assertEquals("Bahiti", screens.openHero(3));
        // The only saved pop-up frame is Mia's, hence Lancer here.
        assertEquals(new HeroPage("Bahiti", "Lancer", 78, new HeroXp(382_377, 2_100_000)), screens.readHero("Bahiti"));
        assertEquals(List.of(new HeroGear(1, 80, 5), new HeroGear(2, 40, 1), new HeroGear(3, 40, 1), new HeroGear(4, 80, 4)),
                screens.openGearTab("Bahiti"));
        assertEquals(new GearDetails("Sniper Boots", 80, "Enhance"), screens.openDetails("Boots"));
        assertEquals(new ForgeDialog("Sniper Boots", 42, 44, 3, 10), screens.openForge());
        screens.closeForge();
        screens.closeDetails();
        screens.closeHero();

        assertEquals(List.of(BAHITI_CARD, OVERALL, DISMISS, GEAR, BOOTS, MASTERY), rig.taps);
        assertEquals(3, rig.backs);
        assertEquals("scan-back-hero", rig.screen);
    }

    @Test
    void aFullScanGetsPastAnAscendPanelWithoutTappingAscend() throws Exception {
        Rig rig = new Rig("quickequip-h00")
                .on("quickequip-h00", GearScanScreens.GEAR_TILES.get("Goggles"), "enhance-ascend")
                .on("enhance-ascend", MASTERY, "forge-Mia-goggles")
                .onBack("forge-Mia-goggles", "enhance-ascend")
                .onBack("enhance-ascend", "quickequip-h00");

        GearScan.Outcome outcome = GearScan.run(new AscendWalk(new GearScanScreens(rig)), new HeroGearStore(workspace), 7, null,
                java.time.Instant.parse("2026-11-10T07:45:00Z"));

        assertNull(outcome.problem());
        GearRanker.GearPiece goggles = outcome.state().pieces().getFirst();
        assertEquals(100, goggles.enhancement());
        assertEquals(60, goggles.masteryPercent());
        assertEquals(List.of(GearScanScreens.GEAR_TILES.get("Goggles"), MASTERY), rig.taps);
        assertFalse(rig.taps.contains(EnhanceDialogReader.DETAILS_ENHANCE));
        assertEquals("quickequip-h00", rig.screen);
    }

    /** Mia's Goggles go through the real screens on real frames; no other piece has a saved Ascend frame. */
    private static final class AscendWalk implements GearScan.Screens {
        private static final List<String> HEROES = List.of("Mia", "Flint", "Molly", "Bahiti", "Sergey");
        private static final Map<String, String> CLASSES = Map.of("Saboteur", "Lancer", "Guardian", "Infantry", "Sniper", "Marksman");
        private final GearScanScreens real;
        private String hero;
        private String slot;

        AscendWalk(GearScanScreens real) {
            this.real = real;
        }

        private boolean onRealFrames() {
            return "Mia".equals(hero) && "Goggles".equals(slot);
        }

        private String piece() {
            return GearForgeGuard.GEAR_SET.get(hero) + " " + slot;
        }

        @Override
        public int openGrid() {
            return HEROES.size();
        }

        @Override
        public String openHero(int index) {
            hero = HEROES.get(index);
            return hero;
        }

        @Override
        public HeroPage readHero(String name) {
            return new HeroPage(name, CLASSES.get(GearForgeGuard.GEAR_SET.get(name)), 70, null);
        }

        @Override
        public List<HeroGear> openGearTab(String name) {
            return List.of(new HeroGear(1, "Mia".equals(name) ? 100 : null, null), new HeroGear(2, null, null),
                    new HeroGear(3, null, null), new HeroGear(4, null, null));
        }

        @Override
        public GearDetails openDetails(String tapped) {
            slot = tapped;
            return onRealFrames() ? real.openDetails(tapped) : new GearDetails(piece(), 80, "Enhance");
        }

        @Override
        public ForgeDialog openForge() {
            return onRealFrames() ? real.openForge() : new ForgeDialog(piece(), 10, 20, 13, 20);
        }

        @Override
        public void closeForge() {
            if (onRealFrames()) real.closeForge();
        }

        @Override
        public void closeDetails() {
            if (onRealFrames()) real.closeDetails();
        }

        @Override
        public void closeHero() {
        }
    }

    @Test
    void aGlowOverTheTitleStillReadsTheName() throws Exception {
        Rig rig = new Rig("scan-grid")
                .on("scan-grid", MIA_CARD, "scan-mia")
                .on("scan-mia", OVERALL, "hero-overall-stats")
                .on("hero-overall-stats", DISMISS, "scan-mia");
        GearScanScreens screens = new GearScanScreens(rig);

        screens.openGrid();

        assertEquals("Mia", screens.openHero(0));
        assertEquals(new HeroPage("Mia", "Lancer", 79, new HeroXp(382_377, 2_400_000)), screens.readHero("Mia"));
    }

    @Test
    void aPageReopenedOnTheGearTabMovesToStatsBeforeTheStatsButton() throws Exception {
        Rig rig = bahitiRig().on("scan-grid", BAHITI_CARD, "scan-gear").on("scan-gear", STATS, "scan-bahiti");
        GearScanScreens screens = new GearScanScreens(rig);

        screens.openGrid();
        screens.readHero(screens.openHero(3));

        assertEquals(List.of(BAHITI_CARD, STATS, OVERALL, DISMISS), rig.taps);
    }

    @Test
    void aSkippedCardIsOnlyOpenedAndClosedBackToTheGrid() throws Exception {
        Rig rig = bahitiRig().onBack("scan-bahiti", "scan-back-hero");
        GearScanScreens screens = new GearScanScreens(rig);

        screens.openGrid();
        screens.openHero(3);
        screens.closeHero();

        assertEquals(List.of(BAHITI_CARD), rig.taps);
        assertEquals(1, rig.backs);
        assertEquals("scan-back-hero", rig.screen);
    }

    @Test
    void aSkippedCardThatDoesNotCloseToTheGridStopsTheWalk() throws Exception {
        Rig rig = bahitiRig();
        GearScanScreens screens = new GearScanScreens(rig);

        screens.openGrid();
        screens.openHero(3);

        assertThrows(Abort.class, screens::closeHero);
    }

    @Test
    void neverPressesTheStatsButtonWhileTheGearTabIsUp() throws Exception {
        // (617,1110) is Quick Equip on the Gear tab.
        Rig rig = new Rig("scan-grid").on("scan-grid", BAHITI_CARD, "scan-gear");
        GearScanScreens screens = new GearScanScreens(rig);
        screens.openGrid();

        assertThrows(Abort.class, () -> screens.openHero(3));

        assertEquals(List.of(BAHITI_CARD, STATS), rig.taps);
    }

    @Test
    void aCardThatOpensNothingStopsTheWalk() throws Exception {
        Rig rig = new Rig("scan-grid");
        GearScanScreens screens = new GearScanScreens(rig);
        screens.openGrid();

        assertThrows(Abort.class, () -> screens.openHero(0));

        assertEquals(List.of(MIA_CARD), rig.taps);
    }

    @Test
    void aTileThatOpensNothingStopsBeforeMasteryForging() throws Exception {
        Rig rig = new Rig("scan-gear");
        GearScanScreens screens = new GearScanScreens(rig);

        assertThrows(Abort.class, () -> screens.openDetails("Boots"));

        assertEquals(List.of(BOOTS), rig.taps);
    }

    @Test
    void anUnreadableDialogStopsWithoutAnotherTap() throws Exception {
        Rig rig = new Rig("scan-details");
        GearScanScreens screens = new GearScanScreens(rig);

        assertThrows(Abort.class, screens::openForge);

        assertEquals(List.of(MASTERY), rig.taps);
    }

    @Test
    void aTabThatIsNotTheGearTabGetsNoTileTap() throws Exception {
        Rig rig = new Rig("scan-bahiti");
        GearScanScreens screens = new GearScanScreens(rig);

        assertThrows(Abort.class, () -> screens.openDetails("Boots"));

        assertTrue(rig.taps.isEmpty());
    }

    @Test
    void aBackThatLandsOnTheWrongScreenStopsTheWalk() throws Exception {
        Rig rig = new Rig("scan-forge").onBack("scan-forge", "scan-grid");
        GearScanScreens screens = new GearScanScreens(rig);

        assertThrows(Abort.class, screens::closeForge);

        assertEquals(1, rig.backs);
    }

    @Test
    void aGridScrolledOffItsTopIsRefused() throws Exception {
        GearScanScreens screens = new GearScanScreens(new Rig("heroes-grid-bottom"));

        assertThrows(Abort.class, screens::openGrid);
    }

    @Test
    void readsGearDetailsAndRefusesOtherScreens() throws Exception {
        assertEquals(new GearDetails("Sniper Boots", 80, "Enhance"), ForgeDialogReader.readGearDetails(frame("scan-details")));
        assertEquals(new GearDetails("Sniper Boots", 80, "Enhance"), ForgeDialogReader.readGearDetails(frame("scan-back-forge")));
        assertNull(ForgeDialogReader.readGearDetails(frame("scan-forge")));
        assertNull(ForgeDialogReader.readGearDetails(frame("scan-gear")));
    }

    @ParameterizedTest
    @CsvSource({
            "scan-mia, true, false", "scan-bahiti, true, false", "hero-stats-tab, true, false",
            "hero-overall-stats, false, false",
            "scan-gear, false, true", "scan-back-details, false, true", "hero-gear, false, true",
            "scan-details, false, false", "scan-forge, false, false", "scan-grid, false, false",
    })
    void tellsTheHeroTabsApart(String file, boolean stats, boolean gear) throws Exception {
        RawImageData frame = frame(file);

        assertEquals(stats, HeroScreenReader.isStatsTab(frame));
        assertEquals(gear, HeroScreenReader.isGearTab(frame));
    }

    @ParameterizedTest
    @CsvSource({ "scan-mia, Mia", "scan-bahiti, Bahiti", "scan-gear, Bahiti", "hero-gear-white-badges, Flint" })
    void readsTheHeroName(String file, String name) throws Exception {
        assertEquals(name, HeroScreenReader.readName(frame(file)));
    }

    @Test
    void aTitleThatIsNotAKnownHeroHasNoName() throws Exception {
        assertNull(HeroScreenReader.readName(frame("scan-grid")));
    }

    @ParameterizedTest
    @CsvSource({ "scan-mia, 382377, 2400000", "scan-bahiti, 382377, 2100000", "hero-stats-tab, 572351, 1900000" })
    void readsHeroXpOffTheUpgradeButton(String file, long have, long toNext) throws Exception {
        assertEquals(new HeroXp(have, toNext), HeroScreenReader.readHeroXp(frame(file)));
    }

    @Test
    void heroXpThatContradictsItsColourIsUnread() {
        assertEquals(new HeroXp(382_377, 2_400_000), HeroScreenReader.heroXp("/2.4M", "382,377"));
        assertEquals(new HeroXp(3_100_000, 2_400_000), HeroScreenReader.heroXp("3.1M/2.4M", ""));
        assertNull(HeroScreenReader.heroXp("/2.4M", "3,000,000"));
        assertNull(HeroScreenReader.heroXp("100/2.4M", ""));
        assertNull(HeroScreenReader.heroXp("", "382,377"));
    }

    private static Rig bahitiRig() throws Exception {
        return new Rig("scan-grid")
                .on("scan-grid", BAHITI_CARD, "scan-bahiti")
                .on("scan-bahiti", OVERALL, "hero-overall-stats")
                .on("hero-overall-stats", DISMISS, "scan-bahiti")
                .on("scan-bahiti", GEAR, "scan-gear")
                .on("scan-gear", BOOTS, "scan-details")
                .on("scan-details", MASTERY, "scan-forge")
                .onBack("scan-forge", "scan-back-forge")
                .onBack("scan-back-forge", "scan-back-details")
                .onBack("scan-back-details", "scan-back-hero");
    }

    /** A device that shows one saved frame per screen and follows only the taps it was told about. */
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
            for (int[] box : FORBIDDEN.getOrDefault(screen, new int[0][])) {
                boolean inside = point.getX() + radius >= box[0] && point.getX() - radius <= box[2]
                        && point.getY() + radius >= box[1] && point.getY() - radius <= box[3];
                assertFalse(inside, "tap at " + point + " on " + screen + " can reach a forbidden control");
            }
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
        BufferedImage image = ImageIO.read(Objects.requireNonNull(
                GearScanScreensFrameTest.class.getResourceAsStream("/squads/" + name + ".png"), name));
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
        RawImageData frame = RawImageData.capture(rgba, image.getWidth(), image.getHeight(), 32);
        FRAMES.put(name, frame);
        return frame;
    }
}
