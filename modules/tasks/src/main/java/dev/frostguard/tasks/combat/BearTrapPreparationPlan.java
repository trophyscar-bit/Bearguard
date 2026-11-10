package dev.frostguard.tasks.combat;

import java.util.ArrayList;
import java.util.List;

/** What Bear does when its window opens, before the event starts, in order. */
final class BearTrapPreparationPlan {

    enum Step {
        /** Alliance auto-join off on its own, used only when gatherers are not recalled. */
        TURN_OFF_AUTOJOIN,
        /** Recall the gatherers, and turn alliance auto-join off while they walk home. */
        RECALL_GATHERERS_AND_TURN_OFF_AUTOJOIN,
        ACTIVATE_PETS,
        MOVE_TO_TRAP
    }

    private BearTrapPreparationPlan() {
    }

    static List<Step> atWindowOpen(boolean recallGatherers, boolean usePets) {
        List<Step> steps = new ArrayList<>();
        steps.add(recallGatherers ? Step.RECALL_GATHERERS_AND_TURN_OFF_AUTOJOIN : Step.TURN_OFF_AUTOJOIN);
        if (usePets) {
            steps.add(Step.ACTIVATE_PETS);
        }
        steps.add(Step.MOVE_TO_TRAP);
        return List.copyOf(steps);
    }
}
