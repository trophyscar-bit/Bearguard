package dev.frostguard.tasks.dailies;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.TaskStateData;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.TaskQueue;
import dev.frostguard.engine.service.ProfileService;
import dev.frostguard.engine.service.TaskManagementService;

class IntelCycleSchedulingOrchestrationTest {

    @Test
    void activeCycleFollowUpPersistsFutureScheduleAndDoesNotHotLoopOrReleaseResources() {
        AccountDescriptor profile = new AccountDescriptor(
                null, "Intel cycle " + UUID.randomUUID(), "0", false, 100L, 30L);
        profile.setConfig(ConfigurationKeyEnum.INTEL_BOOL, true);
        assertTrue(ProfileService.obtain().createAccount(profile));

        PolicyDrivenIntelTask task = new PolicyDrivenIntelTask(profile);
        RecordingQueue queue = new RecordingQueue(profile);
        queue.enqueue(task);

        runSchedulerTick(queue);
        assertEquals(1, task.executionCount);
        assertEquals(IntelCyclePolicy.Action.START_AVAILABLE_CYCLE, task.lastAction);

        int executionsBeforeWaitingTick = task.executionCount;
        runSchedulerTick(queue);
        assertEquals(executionsBeforeWaitingTick, task.executionCount,
                "the future Beast ETA must not execute again on the next scheduler tick");

        LocalDateTime beforeResume = LocalDateTime.now();
        task.reschedule(beforeResume.minusSeconds(1));
        runSchedulerTick(queue);
        assertEquals(2, task.executionCount);
        assertEquals(IntelCyclePolicy.Action.RESUME_ACTIVE_CYCLE, task.lastAction);

        // A completed cycle parks on the next Intel refresh, and those are 00:00, 08:00 and 16:00
        // UTC, so the park is minutes away during the hour before one. Assert against the policy
        // instead of a fixed horizon. reschedule() only ever delays, so the stored time cannot
        // precede the refresh resolved before the tick; the second of slack absorbs the
        // millisecond truncation reschedule() applies to its own gap.
        LocalDateTime earliestPark = IntelCyclePolicy
                .nextRefresh(beforeResume, ZoneId.systemDefault())
                .minusSeconds(1);
        assertFalse(task.getScheduled().isBefore(earliestPark),
                "the resumed cycle must park on the next Intel refresh");

        TaskStateData persisted = TaskManagementService.shared().lookupTaskState(
                profile.getId(), TpDailyTaskEnum.INTEL.getId());
        assertNotNull(persisted);
        assertFalse(persisted.getNextExecutionTime().isBefore(earliestPark),
                "the persisted schedule must carry the same parked refresh time");
        assertEquals(0, queue.gameStopCount);
        assertEquals(0, queue.slotReleaseCount);

        int completedExecutions = task.executionCount;
        runSchedulerTick(queue);
        assertEquals(completedExecutions, task.executionCount,
                "the completed cycle must remain parked until its persisted refresh time");
    }

    private static void runSchedulerTick(TaskQueue queue) {
        try {
            Method tick = TaskQueue.class.getDeclaredMethod("runSchedulerTick");
            tick.setAccessible(true);
            tick.invoke(queue);
        } catch (NoSuchMethodException | IllegalAccessException exception) {
            throw new AssertionError(exception);
        } catch (InvocationTargetException exception) {
            throw new AssertionError(exception.getCause());
        }
    }

    private static final class PolicyDrivenIntelTask extends DelayedTask {

        private final IntelCyclePolicy policy = new IntelCyclePolicy();
        private int executionCount;
        private IntelCyclePolicy.Action lastAction;

        private PolicyDrivenIntelTask(AccountDescriptor profile) {
            super(profile, TpDailyTaskEnum.INTEL);
        }

        @Override
        public void run() {
            execute();
        }

        @Override
        protected void execute() {
            executionCount++;
            boolean dailyAvailable = executionCount == 1;
            IntelCyclePolicy.Decision decision = policy.evaluateDailyAvailability(
                    dailyAvailable, LocalDateTime.now(), ZoneId.systemDefault());
            lastAction = decision.action();
            if (decision.action() == IntelCyclePolicy.Action.START_AVAILABLE_CYCLE) {
                reschedule(LocalDateTime.now().plusSeconds(30));
                return;
            }

            policy.completeCycle();
            reschedule(IntelCyclePolicy.nextRefresh(LocalDateTime.now(), ZoneId.systemDefault()));
        }
    }

    private static final class RecordingQueue extends TaskQueue {

        private int gameStopCount;
        private int slotReleaseCount;

        private RecordingQueue(AccountDescriptor profile) {
            super(profile);
        }

        @Override
        protected void acquireSlot() {
            markSlotAcquired();
        }

        @Override
        protected boolean stopBlockedGameProcess(DelayedTask task) {
            gameStopCount++;
            return true;
        }

        @Override
        protected void releaseEmulatorSlotLease() {
            slotReleaseCount++;
        }

        @Override
        protected void handleIdleTransitions() {
        }

        @Override
        protected void sleepSchedulerTick(long millis) {
        }
    }
}
