package dev.frostguard.engine.schedule;

import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.runtime.WorkspacePaths;
import dev.frostguard.api.runtime.WorkspaceSession;
import dev.frostguard.engine.service.ProfileService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskQueueRunNowTest {

    private static final List<DelayedTask> CREATED = new ArrayList<>();
    private static Object previousFactory;

    private static final class PlainTask extends DelayedTask {

        private PlainTask(AccountDescriptor profile, TpDailyTaskEnum type) {
            super(profile, type);
        }

        @Override
        protected void execute() {
        }
    }

    @BeforeAll
    @SuppressWarnings("unchecked")
    static void installFactory() throws ReflectiveOperationException {
        WorkspaceSession.initializeLayout(WorkspacePaths.current());
        previousFactory = factoryField().get(null);
        DelayedTaskRegistry.registerFactory((type, profile) -> {
            DelayedTask task = new PlainTask(profile, type);
            CREATED.add(task);
            return task;
        });
    }

    @AfterAll
    static void restoreFactory() throws ReflectiveOperationException {
        factoryField().set(null, previousFactory);
    }

    private static Field factoryField() throws ReflectiveOperationException {
        Field field = DelayedTaskRegistry.class.getDeclaredField("taskFactory");
        field.setAccessible(true);
        return field;
    }

    private static AccountDescriptor persistedProfile() {
        AccountDescriptor profile = new AccountDescriptor(null, "Run now " + UUID.randomUUID(), "0", false, 100L, 30L);
        assertTrue(ProfileService.obtain().createAccount(profile));
        return profile;
    }

    private static DelayedTask queued(TaskQueue queue, AccountDescriptor profile, TpDailyTaskEnum type, boolean recurring) {
        DelayedTask task = new PlainTask(profile, type);
        task.setRecurring(recurring);
        queue.enqueue(task);
        return task;
    }

    @Test
    void aOneOffRunDoesNotEndTheScheduleOfAQueuedTask() {
        AccountDescriptor profile = persistedProfile();
        TaskQueue queue = new TaskQueue(profile);
        DelayedTask task = queued(queue, profile, TpDailyTaskEnum.GATHER_BOOST, true);

        queue.runNow(TpDailyTaskEnum.GATHER_BOOST, false);

        assertTrue(task.isRecurring());
        assertTrue(queue.isTaskQueued(TpDailyTaskEnum.GATHER_BOOST));
    }

    @Test
    void aQueuedOneOffTaskStaysOneOff() {
        AccountDescriptor profile = persistedProfile();
        TaskQueue queue = new TaskQueue(profile);
        DelayedTask task = queued(queue, profile, TpDailyTaskEnum.GATHER_BOOST, false);

        queue.runNow(TpDailyTaskEnum.GATHER_BOOST, false);

        assertFalse(task.isRecurring());
    }

    @Test
    void aOneOffRunOfATaskNotInTheQueueIsOneOff() {
        AccountDescriptor profile = persistedProfile();
        TaskQueue queue = new TaskQueue(profile);

        queue.runNow(TpDailyTaskEnum.GATHER_BOOST, false);

        assertFalse(CREATED.get(CREATED.size() - 1).isRecurring());
    }

    @Test
    void theSlotHandoverStillQueuesInitializeAsAOneOff() {
        AccountDescriptor profile = persistedProfile();
        TaskQueue queue = new TaskQueue(profile);
        DelayedTask task = queued(queue, profile, TpDailyTaskEnum.INITIALIZE, true);

        queue.runNow(TpDailyTaskEnum.INITIALIZE, false);

        assertFalse(task.isRecurring());
    }
}
