package dev.frostguard.app.panel.scheduler;

import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.DailyTaskStatusData;
import dev.frostguard.engine.schedule.TaskQueue;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunOnceRecurrenceTest {

    private static final class FakeQueue extends TaskQueue {

        private final TpDailyTaskEnum queuedKind;
        private final String queuedKey;

        private FakeQueue(TpDailyTaskEnum queuedKind, String queuedKey) {
            super(new AccountDescriptor(null, "Fake", "0", false, 100L, 30L));
            this.queuedKind = queuedKind;
            this.queuedKey = queuedKey;
        }

        @Override
        public synchronized boolean isTaskQueued(TpDailyTaskEnum kind) {
            return kind == queuedKind;
        }

        @Override
        public synchronized boolean isTaskQueued(String key) {
            return key.equals(queuedKey);
        }
    }

    private static TaskManagerAux row(TpDailyTaskEnum type, boolean scheduled, String custom) {
        return new TaskManagerAux("Task", null, null, type, 1L, 0, false, scheduled, false, custom);
    }

    private static DailyTaskStatusData status(TpDailyTaskEnum type, String label) {
        DailyTaskStatusData status = new DailyTaskStatusData();
        status.setTaskTypeCode(type.getId());
        status.setCustomLabel(label);
        return status;
    }

    @Test
    void aStaleOffRowStillCountsAsScheduledWhileTheQueueHoldsTheTask() {
        assertTrue(TaskManagerActionController.isOnSchedule(row(TpDailyTaskEnum.GATHER_BOOST, false, null),
                new FakeQueue(TpDailyTaskEnum.GATHER_BOOST, null)));
    }

    @Test
    void aStaleOffCustomRowIsCheckedByItsOwnKey() {
        assertTrue(TaskManagerActionController.isOnSchedule(row(TpDailyTaskEnum.CUSTOM_TASK, false, "Mine"),
                new FakeQueue(null, "Mine")));
        assertFalse(TaskManagerActionController.isOnSchedule(row(TpDailyTaskEnum.CUSTOM_TASK, false, "Other"),
                new FakeQueue(null, "Mine")));
    }

    @Test
    void anOffTaskNotInTheQueueIsAOneOff() {
        assertFalse(TaskManagerActionController.isOnSchedule(row(TpDailyTaskEnum.GATHER_BOOST, false, null),
                new FakeQueue(TpDailyTaskEnum.ARENA, null)));
    }

    @Test
    void aScheduledRowIsScheduledWithoutAskingTheQueue() {
        assertTrue(TaskManagerActionController.isOnSchedule(row(TpDailyTaskEnum.GATHER_BOOST, true, null),
                new FakeQueue(null, null)));
    }

    @Test
    void aCustomRowOnlyMatchesItsOwnStoredStatus() {
        DailyTaskStatusData mine = status(TpDailyTaskEnum.CUSTOM_TASK, "Mine");
        DailyTaskStatusData other = status(TpDailyTaskEnum.CUSTOM_TASK, "Other");

        assertSame(mine, TaskManagerLayoutController.storedStatusFor(TpDailyTaskEnum.CUSTOM_TASK, "Mine", List.of(other, mine)));
        assertNull(TaskManagerLayoutController.storedStatusFor(TpDailyTaskEnum.CUSTOM_TASK, "Absent", List.of(other, mine)));
    }

    @Test
    void aStandardRowPrefersTheUnlabelledStatusOfItsOwnType() {
        DailyTaskStatusData labelled = status(TpDailyTaskEnum.ARENA, "Label");
        DailyTaskStatusData plain = status(TpDailyTaskEnum.ARENA, null);
        DailyTaskStatusData wrongType = status(TpDailyTaskEnum.GATHER_BOOST, null);

        assertSame(plain, TaskManagerLayoutController.storedStatusFor(TpDailyTaskEnum.ARENA, null, List.of(wrongType, labelled, plain)));
        assertNull(TaskManagerLayoutController.storedStatusFor(TpDailyTaskEnum.ARENA, null, List.of(wrongType)));
    }
}
