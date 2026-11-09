package dev.frostguard.tasks.combat;

import static dev.frostguard.tasks.combat.BearTrapPreparationPlan.Step.ACTIVATE_PETS;
import static dev.frostguard.tasks.combat.BearTrapPreparationPlan.Step.MOVE_TO_TRAP;
import static dev.frostguard.tasks.combat.BearTrapPreparationPlan.Step.RECALL_GATHERERS;
import static dev.frostguard.tasks.combat.BearTrapPreparationPlan.Step.TURN_OFF_AUTOJOIN;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

class BearTrapPreparationPlanTest {

    @Test
    void autojoinGoesOffBeforeGatherersAreRecalled() {
        assertEquals(List.of(TURN_OFF_AUTOJOIN, RECALL_GATHERERS, MOVE_TO_TRAP),
                BearTrapPreparationPlan.atWindowOpen(true, false));
    }

    @Test
    void recallAndPetsFollowTheirSettings() {
        assertEquals(List.of(TURN_OFF_AUTOJOIN, MOVE_TO_TRAP), BearTrapPreparationPlan.atWindowOpen(false, false));
        assertEquals(List.of(TURN_OFF_AUTOJOIN, RECALL_GATHERERS, ACTIVATE_PETS, MOVE_TO_TRAP),
                BearTrapPreparationPlan.atWindowOpen(true, true));
    }
}
