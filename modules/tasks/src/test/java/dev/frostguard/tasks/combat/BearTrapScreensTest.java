package dev.frostguard.tasks.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import dev.frostguard.api.domain.PointData;
import dev.frostguard.vision.ocr.TextLine;

/** Text-only cases the live frames do not cover: OCR misreads and the refusal message. */
class BearTrapScreensTest {

    @Test
    void aMisreadOneStillFindsTrapOne() {
        List<TextLine> lines = List.of(line("Hunting Trap l", 268), line("Hunting Trap 2", 451));

        assertEquals(Optional.of(new PointData(590, 359)), BearTrapScreens.trapGoButton(lines, 1));
        assertEquals(Optional.of(new PointData(590, 542)), BearTrapScreens.trapGoButton(lines, 2));
    }

    @Test
    void theMapLabelOfTheOtherTrapIsNotMistakenForThisOne() {
        assertEquals(Optional.empty(),
                BearTrapScreens.trapGoButton(List.of(line("[RIP]Hunting Trap 2", 577)), 1));
    }

    @Test
    void parsesCoordinatesWithOcrNoise() {
        assertEquals(Optional.of(new PointData(761, 237)),
                BearTrapScreens.mapCoordinates(List.of(line("ms #3001 X:761 Y:237 VP\" FT", 1054))));
        assertEquals(Optional.of(new PointData(786, 236)),
                BearTrapScreens.mapCoordinates(List.of(line("X:786, Y:236", 380))));
        assertEquals(Optional.empty(), BearTrapScreens.mapCoordinates(List.of(line("#3001", 1054))));
        // White-text reads from 2026-11-10: X misread as K, a stray dot, a stray bar.
        assertEquals(Optional.of(new PointData(786, 236)),
                BearTrapScreens.mapCoordinates(List.of(line("K:786. Y:236", 383))));
        assertEquals(Optional.of(new PointData(752, 234)),
                BearTrapScreens.mapCoordinates(List.of(line("| x:.752 Y:234", 780))));
    }

    @Test
    void rallyOnlyWhenCoordinatesProveItIsTheConfiguredTrap() {
        Optional<PointData> trap1 = Optional.of(new PointData(786, 236));
        Optional<PointData> trap2 = Optional.of(new PointData(752, 234));
        Optional<PointData> none = Optional.empty();

        assertTrue(BearTrapScreens.isConfiguredTrap(trap1, Optional.of(new PointData(787, 236)), none));
        assertFalse(BearTrapScreens.isConfiguredTrap(trap1, trap2, trap1)); // the popup outranks the camera
        assertTrue(BearTrapScreens.isConfiguredTrap(trap1, none, Optional.of(new PointData(786, 235))));
        assertFalse(BearTrapScreens.isConfiguredTrap(trap1, none, Optional.of(new PointData(761, 237))));
        assertFalse(BearTrapScreens.isConfiguredTrap(trap1, none, none));
        assertFalse(BearTrapScreens.isConfiguredTrap(none, trap1, trap1)); // nothing to compare against
    }

    @Test
    void theChatCallToRallyIsNotTheBearPopup() {
        assertFalse(BearTrapScreens.isRagingBearPopup(
                List.of(line("Braslin: Rally and defeat Raging Bear together now!", 1120))));
        assertTrue(BearTrapScreens.isRagingBearPopup(List.of(line("Lv. 1 Raging Bear", 827))));
    }

    @Test
    void spotsTheNotEligibleRefusal() {
        assertTrue(BearTrapScreens.saysNotEligible(List.of(line("You are not eligible to start a rally", 600))));
        assertTrue(BearTrapScreens.saysNotEligible(List.of(line("You are not", 600), line("eligible", 630))));
        assertFalse(BearTrapScreens.saysNotEligible(List.of(line("Damage Rewards", 870))));
    }

    @Test
    void aQuestionSplitAcrossTwoLinesStillCounts() {
        assertTrue(BearTrapScreens.isBattleSkillsConfirmation(
                List.of(line("Use all available", 505), line("Battle Skills?", 530))));
    }

    // Nov 12 live reads (logged by the bot; the frames name players, so only their text is kept here).
    @Test
    void aPopupWhoseLevelWasMisreadStillCounts() {
        assertTrue(BearTrapScreens.isRagingBearPopup(List.of(line("Ly. 1 Raging Bear", 840), line("Damage Rewards", 870))));
        assertTrue(BearTrapScreens.isRagingBearPopup(List.of(line("Lv. 1 Raging Bear", 840))));
        assertTrue(BearTrapScreens.isRagingBearPopup(List.of(line("1 Raging Bear", 840))));
    }

    @Test
    void theMapBehindAnUnfinishedPopupAndTheChatTickerAreNotThePopup() {
        // The 14:00 read, taken before the popup had opened.
        assertFalse(BearTrapScreens.isRagingBearPopup(List.of(
                line("Not garrisoned", 330), line("[RIP]Alliance Banner", 360), line("Ends in: 00:29:", 540))));
        assertFalse(BearTrapScreens.isRagingBearPopup(List.of(line("Rally and defeat Raging Bear together", 1150))));
    }

    @Test
    void aFullMarchPanelIsRecognised() {
        assertTrue(BearTrapScreens.marchQueueFull(List.of(line("Marching 6/6", 238))));
        assertFalse(BearTrapScreens.marchQueueFull(List.of(line("Marching 5/6", 238))));
        assertFalse(BearTrapScreens.marchQueueFull(List.of(line("Marching 0/0", 238))));
        assertFalse(BearTrapScreens.marchQueueFull(List.of(line("Rallying 00:00:51", 283))));
    }

    private static TextLine line(String text, int top) {
        return new TextLine(text, 200, top, 200, 24, 90f);
    }
}
