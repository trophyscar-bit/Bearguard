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

    private static TextLine line(String text, int top) {
        return new TextLine(text, 200, top, 200, 24, 90f);
    }
}
