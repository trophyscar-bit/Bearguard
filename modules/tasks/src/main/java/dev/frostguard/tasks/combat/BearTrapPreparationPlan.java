package dev.frostguard.tasks.combat;

import java.util.ArrayList;
import java.util.List;

/** What Bear does when its window opens, before the event starts, in order. */
final class BearTrapPreparationPlan {

    enum Step {
        TURN_OFF_AUTOJOIN,
        RECALL_GATHERERS,
        ACTIVATE_PETS,
        MOVE_TO_TRAP
    }

    private BearTrapPreparationPlan() {
    }

    static List<Step> atWindowOpen(boolean recallGatherers, boolean usePets) {
        List<Step> steps = new ArrayList<>();
        steps.add(Step.TURN_OFF_AUTOJOIN);
        if (recallGatherers) {
            steps.add(Step.RECALL_GATHERERS);
        }
        if (usePets) {
            steps.add(Step.ACTIVATE_PETS);
        }
        steps.add(Step.MOVE_TO_TRAP);
        return List.copyOf(steps);
    }
}
