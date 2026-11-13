package dev.frostguard.engine.squads;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.api.squads.HeroGear;
import dev.frostguard.engine.squads.EnhanceDialogReader.Badge;
import dev.frostguard.engine.squads.EnhanceDialogReader.Dialog;
import dev.frostguard.engine.squads.EnhanceDialogReader.TileKind;
import dev.frostguard.engine.squads.ForgeDialogReader.GearDetails;
import dev.frostguard.engine.squads.GearEnhancePass.Outcome;
import dev.frostguard.engine.squads.GearEnhancePass.Stop;
import dev.frostguard.engine.squads.GearEnhancePlanner.Raise;

/** Expected values were read by eye from each saved frame. Painted frames say so where they are built. */
class EnhanceDialogReaderFrameTest {

    private static final int BADGE_LEFT = 112;
    private static final int BADGE_TOP = 846;
    private static final int BADGE_SIZE = 40;

    @ParameterizedTest
    @CsvSource({ "enhance-details, Enhance", "enhance-ascend, Ascend" })
    void readsTheDetailsButton(String file, String label) throws Exception {
        assertEquals(label, EnhanceDialogReader.detailsButtonLabel(frame(file)));
    }

    @Test
    void readsTheDialogWithNothingSelected() throws Exception {
        assertEquals(new Dialog(76, null, 1050, 1270, "Quick Select", false), EnhanceDialogReader.read(frame("enhance-before")));
        assertEquals(new Dialog(76, null, 1050, 1270, "Quick Select", false), EnhanceDialogReader.read(frame("enhance-after")));
    }

    @Test
    void readsThePreviewAfterQuickSelect() throws Exception {
        assertEquals(new Dialog(76, 77, 0, 1310, "Select more", true), EnhanceDialogReader.read(frame("enhance-quickselect")));
        assertEquals(new Dialog(76, 77, 10, 1310, "Select more", true), EnhanceDialogReader.read(frame("enhance-tile")));
    }

    // Saved off the live run on 2026-11-12: Molly's Saboteur Goggles, +72 to +73, mid-scan with a selection made.
    @Test
    void readsThePreviewOnTheLiveDialogWhereSelectMoreSits() throws Exception {
        assertEquals(new Dialog(72, 73, 20, 1150, "Select more", true), EnhanceDialogReader.read(frame("enhance-selectmore-72")));
        assertEquals(EnhanceDialogReader.QUICK_SELECT, EnhanceDialogReader.SELECT_MORE, "one button, two labels");
    }

    @Test
    void aRealDryRunThatRunsOutOfFodderStopsChoosingAtWhatIsSelected() throws Exception {
        Rig rig = new Rig(frame("enhance-details"), frame("enhance-before"), raw(bottomOf(image("enhance-quickselect"))));

        Outcome outcome = new GearEnhancePass(rig).run(new Raise("Sergey", "Gauntlets", 76, 79), false);

        assertEquals(Stop.DRY_RUN, outcome.stop(), outcome.detail());
        assertTrue(outcome.detail().contains("Lv.77"), "the preview never rose past the first level: " + outcome.detail());
        assertEquals(List.of(EnhanceDialogReader.DETAILS_ENHANCE, EnhanceDialogReader.QUICK_SELECT),
                rig.taps, "no Select more: the parts left do not cover another level and one");
        assertEquals(1, rig.swipes, "and the list is scrolled once");
    }

    @Test
    void aLostLeftNumberWithASelectionShowingIsNotARead() throws Exception {
        BufferedImage blanked = image("enhance-quickselect");
        int background = blanked.getRGB(405, 426);
        for (int x = 410; x < 500; x++) {
            for (int y = 408; y < 444; y++) blanked.setRGB(x, y, background);
        }

        assertNull(EnhanceDialogReader.read(raw(blanked)));
    }

    @ParameterizedTest
    @CsvSource({ "enhance-details", "enhance-ascend" })
    void otherScreensAreNotTheDialog(String file) throws Exception {
        assertNull(EnhanceDialogReader.read(frame(file)));
    }

    @Test
    void findsTheMinusBadgesAndWhatTheyAreOn() throws Exception {
        assertEquals(List.of(new Badge(0, 860, TileKind.XP)), EnhanceDialogReader.badges(frame("enhance-quickselect")));
        assertEquals(List.of(new Badge(0, 860, TileKind.XP), new Badge(2, 860, TileKind.GREY)),
                EnhanceDialogReader.badges(frame("enhance-tile")));
    }

    @ParameterizedTest
    @CsvSource({ "enhance-before", "enhance-after", "enhance-list1", "enhance-list2", "enhance-list3" })
    void anUnselectedListHasNoBadges(String file) throws Exception {
        assertTrue(EnhanceDialogReader.badges(frame(file)).isEmpty());
    }

    @ParameterizedTest
    @CsvSource({ "0, 864, BLUE", "1, 864, BLUE", "4, 864, BLUE", "0, 1002, PURPLE", "2, 1002, PURPLE", "4, 1002, PURPLE" })
    void aPaintedBadgeOnABlueOrPurpleTileIsRefused(int column, int top, TileKind kind) throws Exception {
        List<Badge> badges = EnhanceDialogReader.badges(withBadge(image("enhance-list3"), column, top));

        assertEquals(List.of(new Badge(column, top, kind)), badges);
        assertFalse(badges.getFirst().kind().safeFodder());
    }

    @Test
    void aPaintedBadgeOnThePurpleXpItemIsStillAnXpItem() throws Exception {
        List<Badge> badges = EnhanceDialogReader.badges(withBadge(image("enhance-before"), 1, 860));

        assertEquals(List.of(new Badge(1, 860, TileKind.XP)), badges);
        assertTrue(badges.getFirst().kind().safeFodder());
    }

    @Test
    void aPaintedBadgeOnGreenGearIsRefusedToo() throws Exception {
        BufferedImage list = image("enhance-list1");
        int top = firstGreenGearTop(list);

        List<Badge> badges = EnhanceDialogReader.badges(withBadge(list, 0, top));

        assertEquals(List.of(new Badge(0, top, TileKind.GREEN)), badges);
        assertFalse(badges.getFirst().kind().safeFodder(), "Select more takes spare gear once the parts are gone; none is ever fed in");
    }

    // Saved off the live run of 2026-11-12: Select more, one Enhancement Part tile that counts up as levels are added.
    @Test
    void readsHowManyPartsAreSelectedOffTheFirstTile() throws Exception {
        assertEquals(List.of(6, 126), java.util.Arrays.stream(EnhanceDialogReader.partsTile(frame("enhance-parts-6of126"))).boxed().toList());
        assertEquals(List.of(9, 126), java.util.Arrays.stream(EnhanceDialogReader.partsTile(frame("enhance-parts-9of126"))).boxed().toList());
    }

    // Saved off the live run of 2026-11-12 (Bahiti's Sniper Boots, +80). Gear power only; no player name or account power.
    @Test
    void thePartsTileWhenFullMeansSelectMoreHasStartedOnSpareGear() throws Exception {
        assertEquals(List.of(88, 88), java.util.Arrays.stream(EnhanceDialogReader.partsTile(frame("enhance-parts-full-88of88"))).boxed().toList());
        assertEquals(List.of(78, 88), java.util.Arrays.stream(EnhanceDialogReader.partsTile(frame("enhance-parts-78of88"))).boxed().toList());
    }

    @Test
    void everyGearTileCarryingAMinusBadgeOnTheFullFrameIsRefusedAndThePartsOnlyFrameIsNot() throws Exception {
        List<Badge> eaten = EnhanceDialogReader.badges(frame("enhance-parts-full-88of88"));
        List<Badge> partsOnly = EnhanceDialogReader.badges(frame("enhance-parts-78of88"));

        assertEquals(10, eaten.size(), "the parts tile and nine gear tiles in view: " + eaten);
        assertEquals(9, eaten.stream().filter(b -> !b.kind().safeFodder()).count(), "Enhance is refused on this frame");
        assertEquals(List.of(new Badge(0, 860, TileKind.XP)), partsOnly);
        assertTrue(partsOnly.stream().allMatch(b -> b.kind().safeFodder()), "Enhance is fine on this frame");
    }

    @Test
    void readsWhatThePreviewAddsOffTheLastStatRow() throws Exception {
        assertEquals(new EnhanceDialogReader.Gain("Marksman Lethality", 60.99, 64.50), EnhanceDialogReader.previewGain(frame("enhance-parts-78of88")));
        assertEquals(new EnhanceDialogReader.Gain("Marksman Lethality", 60.99, 65.90), EnhanceDialogReader.previewGain(frame("enhance-parts-full-88of88")));
        assertEquals(new EnhanceDialogReader.Gain("Lancer Health", 22.00, 23.40), EnhanceDialogReader.previewGain(frame("enhance-parts-9of126")));
        assertEquals(new EnhanceDialogReader.Gain("Lancer Lethality", 48.01, 48.62), EnhanceDialogReader.previewGain(frame("enhance-selectmore-72")));
        assertEquals(3.51, EnhanceDialogReader.previewGain(frame("enhance-parts-78of88")).delta(), 0.001);
        assertNull(EnhanceDialogReader.previewGain(frame("enhance-details")));
    }

    // Saved by the bot on 2026-11-13 02:51 UTC when it refused: all 10 parts selected, and spare gear picked as well.
    @Test
    void tenPartsCannotCoverALevelSoSpareGearIsSelectedAndTheFrameSaysSo() throws Exception {
        assertEquals(List.of(10, 10), java.util.Arrays.stream(EnhanceDialogReader.partsTile(frame("enhance-parts-short-10of10"))).boxed().toList());
        List<Badge> badges = EnhanceDialogReader.badges(frame("enhance-parts-short-10of10"));

        assertTrue(badges.stream().anyMatch(b -> b.kind() == TileKind.GREY), "spare gear carries a minus badge: " + badges);
        assertTrue(badges.stream().anyMatch(b -> !b.kind().safeFodder()), "Enhance is refused on this frame");
    }

    @Test
    void aScrolledListOrAFrameWithoutTheTileGivesNoPartsRead() throws Exception {
        assertNull(EnhanceDialogReader.partsTile(frame("enhance-list2")));
        assertNull(EnhanceDialogReader.partsTile(frame("enhance-details")));
    }

    @Test
    void aTileOfNoKnownColourIsUnknown() throws Exception {
        BufferedImage image = image("enhance-list3");
        for (int x = 33; x < 138; x++) {
            for (int y = 864; y < 970; y++) image.setRGB(x, y, 0xC88020);
        }

        assertEquals(TileKind.UNKNOWN, EnhanceDialogReader.kind(image, 33, 864));
    }

    // Tile tops from well below the list to well above it, one pixel apart.
    private static final int SWEEP_FROM = 780;
    private static final int SWEEP_TO = 1140;

    @Test
    void everyStepThePassAcceptsCatchesEachSelectedTileInSomeFrame() throws Exception {
        int safest = largestStepThatNeverMisses(caughtTops());

        assertTrue(EnhanceDialogReader.MAX_SCROLL_STEP <= safest,
                "a step of " + EnhanceDialogReader.MAX_SCROLL_STEP + " can carry a tile past every frame");
        assertTrue(EnhanceDialogReader.MAX_SCROLL_STEP >= 175, "tonight's 175px step is refused");
    }

    @Test
    void aStepJustPastTheProvenLimitCanHideATile() throws Exception {
        boolean[] caught = caughtTops();

        assertTrue(missesAtSomePhase(caught, largestStepThatNeverMisses(caught) + 1));
    }

    @Test
    void realSwipeStepsAreJudgedByWhatTheyProve() throws Exception {
        assertTrue(EnhanceDialogReader.provesEveryTile(
                EnhanceDialogReader.scrollOffset(frame("enhance-list2"), frame("enhance-list3"))), "a real 199px step");
        assertTrue(EnhanceDialogReader.provesEveryTile(
                EnhanceDialogReader.scrollOffset(frame("enhance-list3"), frame("enhance-list4"))), "the list at rest");
        assertFalse(EnhanceDialogReader.provesEveryTile(
                EnhanceDialogReader.scrollOffset(frame("enhance-before"), frame("enhance-list1"))), "a real 231px step");
        assertFalse(EnhanceDialogReader.provesEveryTile(
                EnhanceDialogReader.scrollOffset(frame("enhance-list1"), frame("enhance-list2"))), "frames that don't overlap");
        assertTrue(EnhanceDialogReader.provesEveryTile(175), "tonight's live step");
    }

    private static boolean[] caught;

    // Which tile tops, on a real list frame, give a badge the reader counts. Worked out once.
    private static synchronized boolean[] caughtTops() throws Exception {
        if (caught == null) caught = sweepTops();
        return caught;
    }

    private static boolean[] sweepTops() throws Exception {
        BufferedImage list = image("enhance-list3");
        boolean[] caught = new boolean[SWEEP_TO - SWEEP_FROM];
        for (int top = SWEEP_FROM; top < SWEEP_TO; top++) {
            BufferedImage copy = new BufferedImage(list.getWidth(), list.getHeight(), BufferedImage.TYPE_INT_RGB);
            copy.getGraphics().drawImage(list, 0, 0, null);
            caught[top - SWEEP_FROM] = !EnhanceDialogReader.badges(withBadge(copy, 0, top)).isEmpty();
        }
        return caught;
    }

    private static int largestStepThatNeverMisses(boolean[] caught) {
        int step = 1;
        while (!missesAtSomePhase(caught, step + 1)) step++;
        return step;
    }

    // A tile carried up the list by step px a frame, from below it to above it, starting at every phase.
    private static boolean missesAtSomePhase(boolean[] caught, int step) {
        for (int phase = 0; phase < step; phase++) {
            boolean seen = false;
            for (int top = caught.length - 1 - phase; top >= 0; top -= step) seen |= caught[top];
            if (!seen) return true;
        }
        return false;
    }

    @Test
    void tellsTheEndsOfTheList() throws Exception {
        assertTrue(EnhanceDialogReader.isListTop(frame("enhance-before")));
        assertTrue(EnhanceDialogReader.isListTop(frame("enhance-quickselect")));
        assertFalse(EnhanceDialogReader.isListTop(frame("enhance-list1")));
        assertFalse(EnhanceDialogReader.isListTop(frame("enhance-list3")));
        assertTrue(EnhanceDialogReader.isListBottom(frame("enhance-list3")));
        assertFalse(EnhanceDialogReader.isListBottom(frame("enhance-before")));
        assertFalse(EnhanceDialogReader.isListBottom(frame("enhance-list1")));
    }

    @Test
    void measuresScrollAndRefusesFramesThatDoNotOverlap() throws Exception {
        assertEquals(0, EnhanceDialogReader.scrollOffset(frame("enhance-list3"), frame("enhance-list4")));
        assertEquals(231, EnhanceDialogReader.scrollOffset(frame("enhance-before"), frame("enhance-list1")));
        assertNull(EnhanceDialogReader.scrollOffset(frame("enhance-list1"), frame("enhance-list2")));
    }

    @Test
    void aRealDryRunTapsOnlyEnhanceAndQuickSelect() throws Exception {
        Rig rig = new Rig(frame("enhance-details"), frame("enhance-before"), raw(bottomOf(image("enhance-quickselect"))));

        Outcome outcome = new GearEnhancePass(rig).run(raise(), false);

        assertEquals(Stop.DRY_RUN, outcome.stop(), outcome.detail());
        assertEquals(76, outcome.level());
        assertEquals(List.of(EnhanceDialogReader.DETAILS_ENHANCE, EnhanceDialogReader.QUICK_SELECT), rig.taps);
        assertEquals(1, rig.swipes);
        assertEquals(1, rig.backs);
    }

    @Test
    void aRealFrameWithABadgeOnPurpleGearIsAbandonedBeforeEnhance() throws Exception {
        BufferedImage selected = bottomOf(image("enhance-quickselect"));
        BufferedImage list = image("enhance-list3");
        for (int x = 33; x < 138; x++) {
            for (int y = 0; y < 106; y++) selected.setRGB(x, 997 + y, list.getRGB(x, 1002 + y));
        }
        Rig rig = new Rig(frame("enhance-details"), frame("enhance-before"), withBadge(selected, 0, 997));

        Outcome outcome = new GearEnhancePass(rig).run(raise(), true);

        assertEquals(Stop.UNSAFE_FODDER, outcome.stop(), outcome.detail());
        assertEquals(List.of(EnhanceDialogReader.DETAILS_ENHANCE, EnhanceDialogReader.QUICK_SELECT), rig.taps);
        assertEquals(0, rig.swipes);
        assertEquals(1, rig.backs);
    }

    @Test
    void aRealAscendButtonGetsNoTap() throws Exception {
        Rig rig = new Rig(frame("enhance-ascend"), frame("enhance-before"), frame("enhance-quickselect"));

        Outcome outcome = new GearEnhancePass(rig).run(new Raise("Mia", "Goggles", 100, 101), true);

        assertEquals(Stop.WRONG_BUTTON, outcome.stop(), outcome.detail());
        assertTrue(outcome.detail().contains("Ascend"), outcome.detail());
        assertTrue(rig.taps.isEmpty());
    }

    @Test
    void readsThePieceFromGearDetails() throws Exception {
        assertEquals(new GearDetails("Guardian Gauntlets", 76, "Enhance"), ForgeDialogReader.readGearDetails(frame("enhance-details")));
        assertEquals(new GearDetails("Saboteur Goggles", 100, "Ascend"), ForgeDialogReader.readGearDetails(frame("enhance-ascend")));
    }

    private static Raise raise() {
        return new Raise("Sergey", "Gauntlets", 76, 77);
    }

    private static final class Rig implements GearEnhancePass.Device {
        private final RawImageData details;
        private final RawImageData dialog;
        private final RawImageData selected;
        private RawImageData screen;
        final List<PointData> taps = new ArrayList<>();
        int swipes;
        int backs;

        Rig(RawImageData details, RawImageData dialog, RawImageData selected) {
            this.details = details;
            this.dialog = dialog;
            this.selected = selected;
            screen = details;
        }

        @Override
        public RawImageData capture() {
            return screen;
        }

        @Override
        public void tap(PointData point, int radius) {
            taps.add(point);
            if (point.equals(EnhanceDialogReader.DETAILS_ENHANCE)) screen = dialog;
            if (point.equals(EnhanceDialogReader.QUICK_SELECT)) screen = selected;
        }

        @Override
        public void swipe(PointData from, PointData to, int durationMs) {
            swipes++;
        }

        @Override
        public void back() {
            backs++;
            screen = details;
        }

        @Override
        public void pause(int millis) {
        }

        @Override
        public boolean pastDeadline() {
            return false;
        }

        @Override
        public void pressing(String hero, String slot, int level) {
        }

        @Override
        public void pressConfirmed() {
        }
    }

    // Makes a list-top frame also read as the list's bottom, for a one-screen list.
    private static BufferedImage bottomOf(BufferedImage image) {
        for (int x = 20; x < 700; x++) {
            for (int y = 1134; y <= 1137; y++) image.setRGB(x, y, 0x20242C);
        }
        return image;
    }

    private static int firstGreenGearTop(BufferedImage image) {
        for (int y = 850; y < 1060; y++) {
            int rgb = image.getRGB(36, y + 50);
            int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
            boolean edge = g > r + 50 && g > b + 50;
            int above = image.getRGB(85, y - 1);
            int here = image.getRGB(85, y);
            if (edge && isDark(above) && !isDark(here)) return y;
        }
        throw new AssertionError("no green tile on this frame");
    }

    private static boolean isDark(int rgb) {
        return ((rgb >> 16) & 0xFF) < 45 && ((rgb >> 8) & 0xFF) < 50 && (rgb & 0xFF) < 60;
    }

    // Copies the real badge off the Quick Select frame onto another tile.
    private static RawImageData withBadge(BufferedImage target, int column, int tileTop) throws Exception {
        BufferedImage source = image("enhance-quickselect");
        int dx = column * 137;
        int dy = tileTop - 860;
        for (int x = BADGE_LEFT; x < BADGE_LEFT + BADGE_SIZE; x++) {
            for (int y = BADGE_TOP; y < BADGE_TOP + BADGE_SIZE; y++) {
                double distance = Math.hypot(x - 132, y - 867);
                if (distance <= 16 && x + dx < target.getWidth()) target.setRGB(x + dx, y + dy, source.getRGB(x, y));
            }
        }
        return raw(target);
    }

    private static RawImageData frame(String name) throws Exception {
        return raw(image(name));
    }

    private static BufferedImage image(String name) throws Exception {
        return ImageIO.read(Objects.requireNonNull(
                EnhanceDialogReaderFrameTest.class.getResourceAsStream("/squads/" + name + ".png"), name));
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
