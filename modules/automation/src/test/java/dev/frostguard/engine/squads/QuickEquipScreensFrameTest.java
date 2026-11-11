package dev.frostguard.engine.squads;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.api.squads.HeroGear;
import dev.frostguard.engine.squads.GearScan.Abort;
import dev.frostguard.engine.squads.QuickEquipPass.GearTab;
import dev.frostguard.engine.squads.QuickEquipPass.Outcome;

/** Drives the walk over saved frames. No lit button has been captured, so lit frames are painted. */
class QuickEquipScreensFrameTest {

    private static final List<String> ROSTER = List.of("Mia", "Flint", "Molly", "Bahiti", "Sergey", "Jessie", "Patrick",
            "Gina", "Zinman", "Eugene", "Charlie", "Alonso", "Jasser", "Smith", "Natalia", "Seo-yoon", "Lumak Bokan",
            "Cloris", "Jeronimo", "Philly", "Ling Xue");
    private static final PointData FIRST_CARD = new PointData(110, 246);
    private static final PointData GEAR = GearScanScreens.GEAR_TAB;
    private static final int RIGHT_BUTTON_OFFSET = 315;
    private static final int[] BLUE = { 79, 165, 252 };

    @TempDir
    Path workspace;

    private static String hero(int index) {
        return String.format("quickequip-h%02d", index);
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20 })
    void everyRecruitedHeroReadsAsAGreyGearTab(int index) throws Exception {
        RawImageData frame = frame(hero(index));

        assertTrue(HeroScreenReader.isGearTab(frame));
        assertEquals(ROSTER.get(index), HeroScreenReader.readName(frame));
        assertFalse(QuickEquipScreens.isLit(frame));
    }

    @Test
    void theUnrecruitedHeroHasNoGearTabAndNoLitButton() throws Exception {
        RawImageData frame = frame("quickequip-stop-21");

        assertFalse(HeroScreenReader.isGearTab(frame));
        assertFalse(QuickEquipScreens.isLit(frame));
    }

    @Test
    void aBlueButtonLabelledQuickEquipIsLit() throws Exception {
        assertTrue(QuickEquipScreens.isLit(raw(painted(hero(0)))));
    }

    @Test
    void aPaintedLitFrameIsStillAGearTab() throws Exception {
        assertTrue(HeroScreenReader.isGearTab(raw(painted(hero(0)))));
    }

    @Test
    void aBlueButtonLabelledUnequipAllIsNotLit() throws Exception {
        BufferedImage image = image(hero(0));
        for (int x = 60; x < 345; x++) {
            for (int y = 1070; y < 1155; y++) image.setRGB(x + RIGHT_BUTTON_OFFSET, y, image.getRGB(x, y));
        }

        assertFalse(QuickEquipScreens.isLit(raw(image)));
    }

    @Test
    void aBlueButtonWithNoLabelIsNotLit() throws Exception {
        BufferedImage image = image(hero(0));
        int blue = (BLUE[0] << 16) | (BLUE[1] << 8) | BLUE[2];
        for (int x = 376; x < 660; x++) {
            for (int y = 1070; y < 1155; y++) image.setRGB(x, y, blue);
        }

        assertFalse(QuickEquipScreens.isLit(raw(image)));
    }

    @Test
    void onlyUnequipAllIsOutOfReach() {
        assertTrue(QuickEquipScreens.reachesUnequipAll(new PointData(202, 1112), 8));
        assertTrue(QuickEquipScreens.reachesUnequipAll(new PointData(350, 1112), 8));
        assertFalse(QuickEquipScreens.reachesUnequipAll(QuickEquipScreens.QUICK_EQUIP, 8));
        assertFalse(QuickEquipScreens.reachesUnequipAll(QuickEquipScreens.NEXT_HERO, 8));
        assertFalse(QuickEquipScreens.reachesUnequipAll(GEAR, 15));
        assertFalse(QuickEquipScreens.reachesUnequipAll(FIRST_CARD, 20));
    }

    @Test
    void readsTheLeadsTilesOffTheirGearTabs() throws Exception {
        Rig rig = walk();
        QuickEquipScreens screens = new QuickEquipScreens(rig);

        assertEquals("Mia", screens.openFirstHero().name());
        assertEquals(List.of(new HeroGear(1, 100, 6), new HeroGear(2, 55, 1), new HeroGear(3, 50, null), new HeroGear(4, 81, 5)),
                screens.readGear("Mia"));
        assertEquals("Flint", screens.nextHero("Mia").name());
        assertTrue(screens.readGear("Flint").stream().allMatch(piece -> piece.enhancement() != null));
        assertEquals("Molly", screens.nextHero("Flint").name());
        assertTrue(screens.readGear("Molly").stream().allMatch(piece -> piece.enhancement() != null));
        assertEquals("Bahiti", screens.nextHero("Molly").name());
        assertTrue(screens.readGear("Bahiti").stream().allMatch(piece -> piece.enhancement() != null));
        assertEquals("Sergey", screens.nextHero("Bahiti").name());
        assertTrue(screens.readGear("Sergey").stream().allMatch(piece -> piece.enhancement() != null));
    }

    @Test
    void aFullGreyWalkPressesNothingAndStopsAtTheUnrecruitedHero() throws Exception {
        Rig rig = walk();

        Outcome outcome = QuickEquipPass.run(new QuickEquipScreens(rig), new HeroGearStore(workspace), 7);

        assertNull(outcome.problem());
        assertFalse(outcome.stopNight());
        assertEquals(21, outcome.heroes());
        assertEquals(0, outcome.presses());
        assertEquals(List.of(), outcome.oddLooks());
        assertEquals(0, Collections.frequency(rig.taps, QuickEquipScreens.QUICK_EQUIP));
        assertEquals("quickequip-stop-21", rig.screen);
    }

    @Test
    void aLitHeroIsPressedOnceAndTheLeadsAreReread() throws Exception {
        Rig rig = walk().paint(hero(5));

        Outcome outcome = QuickEquipPass.run(new QuickEquipScreens(rig), new HeroGearStore(workspace), 7);

        assertNull(outcome.problem());
        assertFalse(outcome.stopNight());
        assertEquals(1, outcome.presses());
        assertEquals(1, Collections.frequency(rig.taps, QuickEquipScreens.QUICK_EQUIP));
        assertEquals(3, Collections.frequency(rig.taps, FIRST_CARD), "baseline, walk, re-read");
    }

    @Test
    void aLeadThatLostGearAfterAPressStopsTheNight() throws Exception {
        BufferedImage stripped = image(hero(0));
        for (int x = 40; x < 160; x++) {
            for (int y = 262; y < 380; y++) stripped.setRGB(x, y, 0x6FA0D0);
        }
        Rig rig = walk().paint(hero(5));
        rig.afterPress = Map.of(hero(0), raw(stripped));

        Outcome outcome = QuickEquipPass.run(new QuickEquipScreens(rig), new HeroGearStore(workspace), 7);

        assertTrue(outcome.stopNight());
        assertTrue(outcome.problem().contains("Mia slot 1 went missing"), outcome.problem());
    }

    @Test
    void aPressThatLosesTheGearTabStops() throws Exception {
        Rig rig = new Rig(hero(5)).paint(hero(5)).on(hero(5), QuickEquipScreens.QUICK_EQUIP, "quickequip-stop-21");
        QuickEquipScreens screens = new QuickEquipScreens(rig);

        assertThrows(Abort.class, () -> screens.press("Jessie"));

        assertEquals(List.of(QuickEquipScreens.QUICK_EQUIP), rig.taps);
    }

    @Test
    void aGreyButtonIsNotPressedEvenIfAsked() throws Exception {
        Rig rig = new Rig(hero(5));

        assertFalse(new QuickEquipScreens(rig).press("Jessie"));

        assertTrue(rig.taps.isEmpty());
    }

    @Test
    void aLitButtonOnTheWrongHeroIsNotPressed() throws Exception {
        Rig rig = new Rig(hero(5)).paint(hero(5));

        assertFalse(new QuickEquipScreens(rig).press("Mia"));

        assertTrue(rig.taps.isEmpty());
    }

    @Test
    void offTheGearTabNeitherControlIsTapped() throws Exception {
        Rig rig = new Rig("quickequip-stop-21");
        QuickEquipScreens screens = new QuickEquipScreens(rig);

        assertThrows(Abort.class, () -> screens.press("Greg"));
        assertThrows(Abort.class, () -> screens.nextHero("Greg"));

        assertTrue(rig.taps.isEmpty());
    }

    @Test
    void anArrowThatDoesNothingEndsTheWalk() throws Exception {
        Rig rig = new Rig(hero(5));

        assertNull(new QuickEquipScreens(rig).nextHero("Jessie"));

        assertEquals(List.of(QuickEquipScreens.NEXT_HERO), rig.taps);
    }

    @Test
    void aFirstCardThatOpensOnStatsMovesToTheGearTab() throws Exception {
        Rig rig = new Rig("scan-grid").on("scan-grid", FIRST_CARD, "scan-mia").on("scan-mia", GEAR, hero(0));

        GearTab tab = new QuickEquipScreens(rig).openFirstHero();

        assertEquals("Mia", tab.name());
        assertEquals(List.of(FIRST_CARD, GEAR), rig.taps);
    }

    @Test
    void aGridScrolledOffItsTopIsRefused() throws Exception {
        Rig rig = new Rig("heroes-grid-bottom");

        assertThrows(Abort.class, () -> new QuickEquipScreens(rig).openFirstHero());

        assertTrue(rig.taps.isEmpty());
    }

    @Test
    void anOddButtonIsReportedAndNotLit() throws Exception {
        BufferedImage image = image(hero(5));
        for (int x = 376; x < 660; x++) {
            for (int y = 1070; y < 1095; y++) image.setRGB(x, y, 0x20C040);
        }
        Rig rig = new Rig("scan-grid").on("scan-grid", FIRST_CARD, hero(5)).replace(hero(5), raw(image));

        GearTab tab = new QuickEquipScreens(rig).openFirstHero();

        assertFalse(tab.lit());
        assertTrue(tab.oddLook() != null && tab.oddLook().contains("32,192,64"), String.valueOf(tab.oddLook()));
    }

    @Test
    void anyConfirmedLowerReadingCountsOnTheRereadButNotTheBaseline() throws Exception {
        RawImageData mia = frame(hero(0));
        RawImageData stripped = stripped();
        List<RawImageData> frames = List.of(mia, mia, stripped, stripped, mia, mia);

        assertEquals(0, new QuickEquipScreens(new Reel(frames)).rereadGear("Mia").getFirst().enhancement());
        assertEquals(100, new QuickEquipScreens(new Reel(frames)).readGear("Mia").getFirst().enhancement());
    }

    @Test
    void aSettledReadThatFailedIsRetriedNotCountedAsDamage() throws Exception {
        RawImageData mia = frame(hero(0));
        RawImageData garbled = garbled();
        List<RawImageData> frames = List.of(garbled, garbled, mia, mia);

        assertEquals(100, new QuickEquipScreens(new Reel(frames)).rereadGear("Mia").getFirst().enhancement());
    }

    @Test
    void aFailedReadBetweenGoodOnesChangesNothing() throws Exception {
        RawImageData mia = frame(hero(0));
        RawImageData garbled = garbled();
        List<RawImageData> frames = List.of(mia, mia, garbled, garbled, mia, mia);

        assertEquals(100, new QuickEquipScreens(new Reel(frames)).rereadGear("Mia").getFirst().enhancement());
    }

    @Test
    void aTileThatNeverReadsStaysUnread() throws Exception {
        assertNull(new QuickEquipScreens(new Reel(List.of(garbled()))).rereadGear("Mia").getFirst().enhancement());
    }

    @Test
    void readsTilesAndTellsBareFromUnread() throws Exception {
        assertEquals(List.of(new HeroGear(1, 100, 6), new HeroGear(2, 55, 1), new HeroGear(3, 50, 0), new HeroGear(4, 81, 5)),
                HeroScreenReader.readTiles(frame(hero(0))));
        assertEquals(List.of(new HeroGear(1, 0, 0), new HeroGear(2, 0, 0), new HeroGear(3, 0, 0), new HeroGear(4, 0, 0)),
                HeroScreenReader.readTiles(frame("hero-gear-bare")));
        assertNull(HeroScreenReader.readTiles(garbled()).getFirst().enhancement());
    }

    @Test
    void thePastStopCheckIsTheOneItWasGiven() throws Exception {
        assertFalse(new QuickEquipScreens(new Reel(List.of())).pastStop());
        assertTrue(new QuickEquipScreens(new Reel(List.of()), () -> true).pastStop());
    }

    @Test
    void aOneFrameMisreadIsNotConfirmed() throws Exception {
        RawImageData mia = frame(hero(0));
        List<RawImageData> frames = List.of(mia, stripped(), mia, mia);

        assertEquals(100, new QuickEquipScreens(new Reel(frames)).rereadGear("Mia").getFirst().enhancement());
    }

    @Test
    void aFrameOfTheWrongHeroIsNotAccepted() throws Exception {
        List<RawImageData> frames = List.of(frame(hero(1)), frame(hero(1)), frame(hero(0)));

        assertEquals(100, new QuickEquipScreens(new Reel(frames)).rereadGear("Mia").getFirst().enhancement());
    }

    @Test
    void onlyTheWrongHeroOnScreenStops() throws Exception {
        QuickEquipScreens screens = new QuickEquipScreens(new Reel(List.of(frame(hero(1)))));

        assertThrows(Abort.class, () -> screens.rereadGear("Mia"));
        assertThrows(Abort.class, () -> screens.readGear("Mia"));
    }

    @Test
    void tilesThatNeverSettleStop() throws Exception {
        RawImageData mia = frame(hero(0));
        RawImageData stripped = stripped();
        List<RawImageData> flicker = new ArrayList<>();
        for (int i = 0; i < 12; i++) flicker.add(i % 2 == 0 ? mia : stripped);

        assertThrows(Abort.class, () -> new QuickEquipScreens(new Reel(flicker)).rereadGear("Mia"));
    }

    // Something white where the badge goes that no read can make sense of.
    private static RawImageData garbled() throws Exception {
        BufferedImage image = image(hero(0));
        for (int x = 92; x < 152; x++) {
            for (int y = 270; y < 298; y++) image.setRGB(x, y, 0xFFFFFF);
        }
        return raw(image);
    }

    private static RawImageData stripped() throws Exception {
        BufferedImage image = image(hero(0));
        for (int x = 40; x < 160; x++) {
            for (int y = 262; y < 380; y++) image.setRGB(x, y, 0x6FA0D0);
        }
        return raw(image);
    }

    /** Plays frames in order and holds the last; reading must not tap anything. */
    private static final class Reel implements GearScanScreens.Device {
        private final List<RawImageData> frames;
        private int index;

        Reel(List<RawImageData> frames) {
            this.frames = frames;
        }

        @Override
        public RawImageData capture() {
            return frames.get(Math.min(index++, frames.size() - 1));
        }

        @Override
        public void tap(PointData point, int radius) {
            throw new AssertionError("tap at " + point);
        }

        @Override
        public void back() {
            throw new AssertionError("back");
        }

        @Override
        public void pause(int millis) {
        }
    }

    private static Rig walk() {
        Rig rig = new Rig("scan-grid").on("scan-grid", FIRST_CARD, hero(0));
        for (int i = 0; i < 21; i++) {
            rig.on(hero(i), QuickEquipScreens.NEXT_HERO, i == 20 ? "quickequip-stop-21" : hero(i + 1));
            rig.onBack(hero(i), "scan-grid");
        }
        return rig.onBack("quickequip-stop-21", "scan-grid");
    }

    private static BufferedImage painted(String name) throws Exception {
        BufferedImage image = image(name);
        int blue = (BLUE[0] << 16) | (BLUE[1] << 8) | BLUE[2];
        for (int x = 376; x < 660; x++) {
            for (int y = 1070; y < 1155; y++) {
                int rgb = image.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
                if (Math.max(r, Math.max(g, b)) < 150) image.setRGB(x, y, blue);
            }
        }
        return image;
    }

    /** Shows one frame per screen, follows only the taps it was told about, and refuses forbidden controls. */
    private static final class Rig implements GearScanScreens.Device {
        private final Map<String, Map<PointData, String>> taps_ = new HashMap<>();
        private final Map<String, String> backs_ = new HashMap<>();
        private final Map<String, RawImageData> replaced = new HashMap<>();
        Map<String, RawImageData> afterPress = Map.of();
        final List<PointData> taps = new ArrayList<>();
        String screen;
        private boolean pressed;

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

        Rig paint(String name) throws Exception {
            replaced.put(name, raw(painted(name)));
            return this;
        }

        Rig replace(String name, RawImageData frame) {
            replaced.put(name, frame);
            return this;
        }

        @Override
        public RawImageData capture() {
            try {
                if (pressed && afterPress.containsKey(screen)) return afterPress.get(screen);
                RawImageData override = replaced.get(screen);
                return override != null ? override : frame(screen);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public void tap(PointData point, int radius) {
            assertFalse("quickequip-stop-21".equals(screen), "tap at " + point + " on the unrecruited hero");
            if (screen.startsWith("quickequip-h")) {
                assertFalse(point.getX() + radius >= 60 && point.getX() - radius <= 345
                        && point.getY() + radius >= 1070 && point.getY() - radius <= 1155,
                        "tap at " + point + " can reach Unequip All");
            }
            taps.add(point);
            if (Objects.equals(point, QuickEquipScreens.QUICK_EQUIP)) {
                replaced.remove(screen);
                pressed = true;
            }
            screen = taps_.getOrDefault(screen, Map.of()).getOrDefault(point, screen);
        }

        @Override
        public void back() {
            screen = backs_.getOrDefault(screen, screen);
        }

        @Override
        public void pause(int millis) {
        }
    }

    private static final Map<String, RawImageData> FRAMES = new HashMap<>();

    private static BufferedImage image(String name) throws Exception {
        return ImageIO.read(Objects.requireNonNull(
                QuickEquipScreensFrameTest.class.getResourceAsStream("/squads/" + name + ".png"), name));
    }

    private static synchronized RawImageData frame(String name) throws Exception {
        RawImageData cached = FRAMES.get(name);
        if (cached != null) return cached;
        RawImageData frame = raw(image(name));
        FRAMES.put(name, frame);
        return frame;
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
