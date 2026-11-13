package dev.frostguard.engine.squads;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.engine.squads.EnhanceDialogReader.Badge;
import dev.frostguard.engine.squads.EnhanceDialogReader.Dialog;
import dev.frostguard.engine.squads.EnhanceDialogReader.TileKind;
import dev.frostguard.engine.squads.ForgeDialogReader.GearDetails;
import dev.frostguard.engine.squads.GearEnhancePass.Outcome;
import dev.frostguard.engine.squads.GearEnhancePass.Stop;
import dev.frostguard.engine.squads.GearEnhancePlanner.Raise;

/** Drives the pass against a scripted game; each test breaks one thing and checks nothing is spent. */
class GearEnhancePassTest {

    private static final PointData DETAILS = EnhanceDialogReader.DETAILS_ENHANCE;
    private static final PointData QUICK = EnhanceDialogReader.QUICK_SELECT;
    private static final PointData ENHANCE = EnhanceDialogReader.ENHANCE;
    private static final Raise ONE = new Raise("Sergey", "Gauntlets", 76, 77);
    private static final Raise TWO = new Raise("Sergey", "Gauntlets", 76, 78);
    private static final Raise FOUR = new Raise("Sergey", "Gauntlets", 76, 80);

    private final Game game = new Game();

    private Outcome run(Raise raise, boolean live) {
        return new GearEnhancePass(game, game).run(raise, live);
    }

    private void assertStoppedUnspent(Outcome outcome, Stop stop) {
        assertEquals(stop, outcome.stop(), outcome.detail());
        assertEquals(0, outcome.raised());
        assertFalse(game.taps.contains(ENHANCE), game.taps.toString());
    }

    @Test
    void raisesEveryLevelInOneVisitWithSelectMoreAndOnePress() {
        Outcome outcome = run(TWO, true);

        assertEquals(new Outcome(78, 2, Stop.TARGET_REACHED, "reached Lv.78"), outcome);
        assertEquals(List.of(DETAILS, QUICK, QUICK, ENHANCE), game.taps, "one visit: Quick Select, Select more, Enhance");
        assertEquals(78, game.level);
        assertEquals(2, game.swipes, "the list is scrolled once for the whole raise, not once a level");
        assertEquals(Screen.DETAILS, game.screen);
    }

    @Test
    void theReasonForARaiseIsReportedBeforeThePressFromWhatTheScreenShows() {
        run(TWO, false);
        run(TWO, true);

        assertEquals(2, game.previews.size(), "once per visit, before the list scan, so a practice night reports it too");
        GearEnhancePass.Preview preview = game.previews.getLast();
        assertEquals(new GearEnhancePass.Preview("Sergey", "Gauntlets", 76, 78, "Marksman Lethality", 60.99, 64.50, 2), preview);
    }

    @Test
    void aLongRaiseStaysInTheDialogForEveryLevel() {
        Outcome outcome = run(new Raise("Sergey", "Gauntlets", 76, 86), true);

        assertEquals(new Outcome(86, 10, Stop.TARGET_REACHED, "reached Lv.86"), outcome);
        assertEquals(1, game.taps.stream().filter(DETAILS::equals).count(), "the hero is opened once");
        assertEquals(10, game.taps.stream().filter(QUICK::equals).count(), "Quick Select once, Select more nine times");
        assertEquals(1, game.taps.stream().filter(ENHANCE::equals).count());
        assertEquals(1, game.backs, "and closed once");
    }

    @Test
    void aPieceAlreadyAtItsTargetIsLeftAlone() {
        Outcome outcome = run(new Raise("Sergey", "Gauntlets", 76, 76), true);

        assertEquals(Stop.TARGET_REACHED, outcome.stop());
        assertTrue(game.taps.isEmpty());
    }

    @Test
    void withLiveOffItClosesWithoutPressing() {
        Outcome outcome = run(TWO, false);

        assertStoppedUnspent(outcome, Stop.DRY_RUN);
        assertTrue(outcome.detail().contains("Lv.78"), outcome.detail());
        assertEquals(List.of(DETAILS, QUICK, QUICK), game.taps);
        assertEquals(Screen.DETAILS, game.screen);
    }

    @Test
    void thePressIsRecordedBeforeItsTapAndClearedOnlyOnceTheLevelShows() {
        Outcome outcome = run(ONE, true);

        assertEquals(Stop.TARGET_REACHED, outcome.stop());
        assertEquals(List.of("record Sergey Gauntlets 76", "press", "confirm"), game.events);
    }

    @Test
    void aRaiseOfSeveralLevelsIsOnePressRecordedFromItsStartingLevel() {
        run(TWO, true);

        assertEquals(List.of("record Sergey Gauntlets 76", "press", "confirm"), game.events);
    }

    @Test
    void whenTheXpItemsRunOutItPressesWhatIsSelectedAndStopsShortOfTheTarget() {
        game.fodderLevels = 3;

        Outcome outcome = run(FOUR, true);

        assertEquals(Stop.QUICK_SELECT_SHORT, outcome.stop(), outcome.detail());
        assertEquals(78, outcome.level());
        assertEquals(2, outcome.raised());
        assertEquals(List.of(DETAILS, QUICK, QUICK, ENHANCE), game.taps, "the parts tile was full after two, so Select more was not pressed again");
        assertEquals(78, game.level);
        assertEquals(List.of("record Sergey Gauntlets 76", "press", "confirm"), game.events);
        assertEquals(Screen.DETAILS, game.screen);
    }

    @Test
    void anotherTapIsMadeOnlyWhileThePartsLeftCoverAnotherLevelAndOne() {
        game.fodderLevels = 2;

        Outcome outcome = run(TWO, true);

        assertEquals(77, outcome.level(), "1 part used for the first level, 1 left: a second level would need spare gear");
        assertEquals(List.of(DETAILS, QUICK, ENHANCE), game.taps);
    }

    @Test
    void quickSelectThatReachesForSpareGearIsAPartsShortStopWithNothingSpentAndNoScan() {
        game.pages = List.of(List.of(new Badge(0, 860, TileKind.XP), new Badge(1, 860, TileKind.GREY)), List.of());

        Outcome outcome = run(ONE, true);

        assertStoppedUnspent(outcome, Stop.PARTS_SHORT);
        assertEquals(0, game.swipes, "stopped on the first look, before any scan of the list");
        assertEquals(List.of(DETAILS, QUICK), game.taps);
        assertEquals(Screen.DETAILS, game.screen);
        assertTrue(outcome.detail().contains("do not cover Lv.76 to Lv.77"), outcome.detail());
    }

    @Test
    void aPartsTileThatWillNotReadStopsTheChoosingAndPressesWhatIsSelected() {
        game.partsUnreadable = true;

        Outcome outcome = run(TWO, true);

        assertEquals(Stop.QUICK_SELECT_SHORT, outcome.stop(), outcome.detail());
        assertEquals(77, outcome.level());
        assertEquals(List.of(DETAILS, QUICK, ENHANCE), game.taps, "a press that cannot be checked might take spare gear");
    }

    @Test
    void selectMoreNeverGoesPastTheTarget() {
        game.selectMoreStep = 2;

        assertStoppedUnspent(run(TWO, true), Stop.PREVIEW_MISMATCH);
        assertEquals(List.of(DETAILS, QUICK, QUICK), game.taps);
        assertEquals(Screen.DETAILS, game.screen);
    }

    @Test
    void aSelectMoreThatClearsTheSelectionStops() {
        game.selectMoreClears = true;

        assertStoppedUnspent(run(TWO, true), Stop.PREVIEW_MISMATCH);
        assertEquals(Screen.DETAILS, game.screen);
    }

    @ParameterizedTest
    @EnumSource(value = TileKind.class, names = { "GREY", "GREEN", "BLUE", "PURPLE", "UNKNOWN" })
    void unsafeFodderPickedBySelectMoreAbandonsEveryLevelSelected(TileKind kind) {
        game.pages = List.of(List.of(new Badge(0, 860, TileKind.XP)), List.of(new Badge(3, 900, kind)), List.of());

        assertStoppedUnspent(run(FOUR, true), Stop.UNSAFE_FODDER);
        assertEquals(List.of(DETAILS, QUICK, QUICK, QUICK, QUICK), game.taps);
        assertEquals(Screen.DETAILS, game.screen);
    }

    @Test
    void timeRunningOutDuringSelectMoreStopsChoosingMore() {
        game.deadlineAtCheck = 3;

        run(FOUR, true);

        assertEquals(List.of(DETAILS, QUICK, QUICK, ENHANCE), game.taps, "one Select more, then the cap ended the choosing");
    }

    @Test
    void anUnconfirmedPressLeavesItsRecord() {
        game.promptAfterPress = true;

        Outcome outcome = run(ONE, true);

        assertEquals(Stop.PRESS_UNCONFIRMED, outcome.stop(), outcome.detail());
        assertEquals(List.of("record Sergey Gauntlets 76", "press"), game.events);
    }

    @Test
    void aRecordThatCannotBeSavedMeansNoPress() {
        game.recordFails = true;

        Outcome outcome = run(ONE, true);

        assertStoppedUnspent(outcome, Stop.MARKER_UNSAVED);
    }

    @Test
    void aRecordThatCannotBeClearedIsTreatedAsUnconfirmed() {
        game.confirmFails = true;

        Outcome outcome = run(TWO, true);

        assertEquals(Stop.PRESS_UNCONFIRMED, outcome.stop(), outcome.detail());
        assertEquals(1, game.taps.stream().filter(ENHANCE::equals).count(), "no further press");
    }

    @Test
    void aDryRunRecordsNothing() {
        run(ONE, false);

        assertTrue(game.events.isEmpty());
    }

    @Test
    void aRaiseEndingAtTheAscendPanelIsConfirmedFromGearDetails() {
        game.closesAfterPress = true;
        game.ascendAtLevel = 77;

        Outcome outcome = run(ONE, true);

        assertEquals(new Outcome(77, 1, Stop.TARGET_REACHED, "reached Lv.77"), outcome);
    }

    @Test
    void anAscendButtonIsNeverTapped() {
        game.label = "Ascend";

        Outcome outcome = run(ONE, true);

        assertStoppedUnspent(outcome, Stop.WRONG_BUTTON);
        assertTrue(game.taps.isEmpty());
    }

    @Test
    void anUnreadableDetailsScreenStops() {
        game.label = "";

        assertStoppedUnspent(run(ONE, true), Stop.DIALOG_UNREADABLE);
        assertTrue(game.taps.isEmpty());
    }

    @Test
    void aReadThatGivesUpReportsItBeforeTheBackOut() {
        game.label = "";

        run(ONE, true);

        assertEquals("readFailed: Gear Details is not on screen", game.events.getFirst());
    }

    @Test
    void aDifferentPieceStops() {
        game.piece = "Guardian Belt";

        assertStoppedUnspent(run(ONE, true), Stop.WRONG_PIECE);
        assertTrue(game.taps.isEmpty());
    }

    @Test
    void aDifferentEnhancementStops() {
        game.detailsLevelDrift = 1;

        assertStoppedUnspent(run(ONE, true), Stop.WRONG_LEVEL);
        assertTrue(game.taps.isEmpty());
    }

    @Test
    void aDialogThatDoesNotOpenStops() {
        game.dialogOpens = false;

        assertStoppedUnspent(run(ONE, true), Stop.DIALOG_UNREADABLE);
        assertEquals(List.of(DETAILS), game.taps);
    }

    @Test
    void aDialogAtTheWrongLevelClosesAndStops() {
        game.dialogLevelDrift = 1;

        assertStoppedUnspent(run(ONE, true), Stop.WRONG_LEVEL);
        assertEquals(List.of(DETAILS), game.taps);
        assertEquals(Screen.DETAILS, game.screen);
    }

    @Test
    void anUnexpectedLeftButtonIsNotPressed() {
        game.leftBefore = EnhanceDialogReader.SELECT_MORE_LABEL;

        assertStoppedUnspent(run(ONE, true), Stop.DIALOG_UNREADABLE);
        assertEquals(List.of(DETAILS), game.taps);
    }

    @Test
    void quickSelectThatCannotFillALevelStops() {
        game.quickSelectFills = false;

        assertStoppedUnspent(run(ONE, true), Stop.QUICK_SELECT_SHORT);
        assertEquals(List.of(DETAILS, QUICK), game.taps);
        assertEquals(Screen.DETAILS, game.screen);
    }

    @Test
    void aPreviewPastTheNextLevelStops() {
        game.previewBump = 2;

        assertStoppedUnspent(run(ONE, true), Stop.PREVIEW_MISMATCH);
        assertEquals(Screen.DETAILS, game.screen);
    }

    @Test
    void anEnhanceButtonThatStayedGreyStops() {
        game.litAfterSelect = false;

        assertStoppedUnspent(run(ONE, true), Stop.PREVIEW_MISMATCH);
    }

    @Test
    void aPromptAfterQuickSelectStops() {
        game.promptAfterSelect = true;

        assertStoppedUnspent(run(ONE, true), Stop.DIALOG_UNREADABLE);
    }

    @ParameterizedTest
    @EnumSource(value = TileKind.class, names = { "GREY", "GREEN", "BLUE", "PURPLE", "UNKNOWN" })
    void aSelectedGearTileOfAnyColourAbandonsTheLevel(TileKind kind) {
        game.pages = List.of(List.of(new Badge(0, 860, TileKind.XP)), List.of(new Badge(3, 900, kind)), List.of());

        assertStoppedUnspent(run(ONE, true), Stop.UNSAFE_FODDER);
        assertEquals(List.of(DETAILS, QUICK), game.taps);
        assertEquals(Screen.DETAILS, game.screen);
    }

    @ParameterizedTest
    @EnumSource(value = TileKind.class, names = { "XP" })
    void enhancementPartsGoThrough(TileKind kind) {
        game.pages = List.of(List.of(new Badge(0, 860, kind)), List.of());

        assertEquals(Stop.TARGET_REACHED, run(ONE, true).stop());
    }

    @Test
    void noBadgesAtAllMeansTheDetectorMissedThem() {
        game.pages = List.of(List.of(), List.of());

        assertStoppedUnspent(run(ONE, true), Stop.LIST_SCAN_FAILED);
    }

    @Test
    void aListNotAtItsTopStops() {
        game.listTop = false;

        assertStoppedUnspent(run(ONE, true), Stop.LIST_SCAN_FAILED);
        assertEquals(0, game.swipes);
    }

    @Test
    void aSwipeThatJumpsPastTheOverlapStops() {
        game.offset = null;

        assertStoppedUnspent(run(ONE, true), Stop.LIST_SCAN_FAILED);
    }

    @Test
    void aSwipeLargerThanTheSafeStepStops() {
        game.offset = EnhanceDialogReader.MAX_SCROLL_STEP + 1;

        assertStoppedUnspent(run(ONE, true), Stop.LIST_SCAN_FAILED);
    }

    @Test
    void aListThatStopsBeforeItsBottomStops() {
        game.listBottom = false;

        assertStoppedUnspent(run(ONE, true), Stop.LIST_SCAN_FAILED);
    }

    @Test
    void aListThatNeverEndsStops() {
        game.endless = true;

        assertStoppedUnspent(run(ONE, true), Stop.LIST_SCAN_FAILED);
    }

    @Test
    void aSwipeThatChangedTheSelectionStops() {
        game.swipeAddsXp = true;

        assertStoppedUnspent(run(ONE, true), Stop.SELECTION_CHANGED);
        assertEquals(Screen.DETAILS, game.screen);
    }

    @Test
    void theDeadlineStopsBeforeALevelStarts() {
        game.deadlineAtCheck = 1;

        assertStoppedUnspent(run(ONE, true), Stop.DEADLINE);
        assertTrue(game.taps.isEmpty());
    }

    @Test
    void theDeadlineStopsDuringTheListScan() {
        game.deadlineAtCheck = 2;

        assertStoppedUnspent(run(ONE, true), Stop.DEADLINE);
        assertEquals(Screen.DETAILS, game.screen);
    }

    @Test
    void theDeadlineStopsBeforeThePress() {
        game.deadlineAtCheck = 4;

        assertStoppedUnspent(run(ONE, true), Stop.DEADLINE);
        assertEquals(Screen.DETAILS, game.screen);
    }

    @Test
    void aPressThatDoesNotRaiseTheLevelStops() {
        game.pressBump = 0;

        Outcome outcome = run(TWO, true);

        assertEquals(Stop.PRESS_UNCONFIRMED, outcome.stop());
        assertEquals(new Outcome(76, 0, Stop.PRESS_UNCONFIRMED, outcome.detail()), outcome);
        assertEquals(1, game.taps.stream().filter(ENHANCE::equals).count());
    }

    @Test
    void aPressThatRaisesTwoLevelsStops() {
        game.pressBump = 2;

        Outcome outcome = run(TWO, true);

        assertEquals(Stop.PRESS_UNCONFIRMED, outcome.stop());
        assertEquals(1, game.taps.stream().filter(ENHANCE::equals).count());
    }

    @Test
    void aPromptAfterThePressStopsWithoutAnotherTap() {
        game.promptAfterPress = true;

        Outcome outcome = run(TWO, true);

        assertEquals(Stop.PRESS_UNCONFIRMED, outcome.stop());
        assertEquals(List.of(DETAILS, QUICK, QUICK, ENHANCE), game.taps);
    }

    @Test
    void aDialogThatClosesItselfAfterThePressStillCounts() {
        game.closesAfterPress = true;

        assertEquals(new Outcome(78, 2, Stop.TARGET_REACHED, "reached Lv.78"), run(TWO, true));
    }

    private enum Screen { DETAILS, DIALOG, PROMPT }

    /** Frames are null: the scripted reads answer from this object's state. */
    private static final class Game implements GearEnhancePass.Device, GearEnhancePass.Reads {
        final List<PointData> taps = new ArrayList<>();
        final List<String> events = new ArrayList<>();
        final List<GearEnhancePass.Preview> previews = new ArrayList<>();
        boolean recordFails;
        boolean confirmFails;
        Screen screen = Screen.DETAILS;
        int level = 76;
        String label = "Enhance";
        String piece = "Guardian Gauntlets";
        int detailsLevelDrift;
        int dialogLevelDrift;
        boolean dialogOpens = true;
        String leftBefore = EnhanceDialogReader.QUICK_SELECT_LABEL;
        boolean quickSelectFills = true;
        int previewBump = 1;
        boolean litAfterSelect = true;
        boolean promptAfterSelect;
        boolean promptAfterPress;
        boolean closesAfterPress;
        int pressBump = 1;
        int fodderLevels = 100;
        boolean partsUnreadable;
        int selectMoreStep = 1;
        boolean selectMoreClears;
        private int selectedLevels = 1;
        List<List<Badge>> pages = List.of(List.of(new Badge(0, 860, TileKind.XP)), List.of());
        boolean listTop = true;
        boolean listBottom = true;
        boolean endless;
        boolean swipeAddsXp;
        Integer offset = 120;
        int deadlineAtCheck = -1;
        int ascendAtLevel = -1;
        int swipes;
        int backs;
        private int page;
        private int deadlineChecks;
        private boolean selected;
        private int xp;

        @Override
        public RawImageData capture() {
            return null;
        }

        @Override
        public void tap(PointData point, int radius) {
            taps.add(point);
            if (point.equals(DETAILS) && dialogOpens) {
                screen = Screen.DIALOG;
                selected = false;
                page = 0;
                xp = 0;
            } else if (point.equals(QUICK)) {
                if (selected) {
                    if (selectMoreClears) selected = false;
                    else if (selectedLevels < fodderLevels) selectedLevels += selectMoreStep;
                } else if (promptAfterSelect) {
                    screen = Screen.PROMPT;
                } else {
                    selected = quickSelectFills;
                    selectedLevels = 1;
                }
            } else if (point.equals(ENHANCE)) {
                events.add("press");
                if (promptAfterPress) {
                    screen = Screen.PROMPT;
                    return;
                }
                level += pressBump * selectedLevels;
                selected = false;
                selectedLevels = 1;
                if (closesAfterPress) screen = Screen.DETAILS;
            }
        }

        @Override
        public void swipe(PointData from, PointData to, int durationMs) {
            swipes++;
            if (swipeAddsXp) xp = 10;
            if (!endless && page < pages.size() - 1) page++;
        }

        @Override
        public void back() {
            backs++;
            screen = Screen.DETAILS;
            selected = false;
        }

        @Override
        public void pause(int millis) {
        }

        @Override
        public boolean pastDeadline() {
            return ++deadlineChecks == deadlineAtCheck;
        }

        @Override
        public void readFailed(RawImageData frame, String failure) {
            events.add("readFailed: " + failure);
        }

        @Override
        public void previewed(GearEnhancePass.Preview preview) {
            previews.add(preview);
        }

        @Override
        public EnhanceDialogReader.Gain previewGain(RawImageData frame) {
            return new EnhanceDialogReader.Gain("Marksman Lethality", 60.99, 64.50);
        }

        @Override
        public void pressing(String hero, String slot, int level) throws IOException {
            if (recordFails) throw new IOException("disk full");
            events.add("record " + hero + " " + slot + " " + level);
        }

        @Override
        public void pressConfirmed() throws IOException {
            if (confirmFails) throw new IOException("disk full");
            events.add("confirm");
        }

        @Override
        public String detailsLabel(RawImageData frame) {
            if (screen != Screen.DETAILS) return "";
            return level == ascendAtLevel ? "Ascend" : label;
        }

        @Override
        public GearDetails details(RawImageData frame) {
            return screen == Screen.DETAILS ? new GearDetails(piece, level + detailsLevelDrift, "Enhance") : null;
        }

        @Override
        public Dialog dialog(RawImageData frame) {
            if (screen != Screen.DIALOG) return null;
            int shown = level + dialogLevelDrift;
            if (selected) {
                return new Dialog(shown, shown + previewBump + selectedLevels - 1, xp, 1310, EnhanceDialogReader.SELECT_MORE_LABEL, litAfterSelect);
            }
            return new Dialog(shown, null, 1050, 1270, leftBefore, false);
        }

        @Override
        public List<Badge> badges(RawImageData frame) {
            return pages.get(page);
        }

        @Override
        public boolean listTop(RawImageData frame) {
            return listTop;
        }

        @Override
        public int[] partsTile(RawImageData frame) {
            return partsUnreadable || !selected ? null : new int[] { selectedLevels, fodderLevels };
        }

        @Override
        public boolean listBottom(RawImageData frame) {
            return listBottom;
        }

        @Override
        public Integer scrollOffset(RawImageData before, RawImageData after) {
            if (offset == null || endless) return offset;
            return page >= pages.size() - 1 && lastSwipeDidNothing() ? Integer.valueOf(0) : offset;
        }

        private int lastPageSwipes;

        private boolean lastSwipeDidNothing() {
            return page >= pages.size() - 1 && ++lastPageSwipes > 1;
        }
    }
}
