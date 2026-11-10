package dev.frostguard.engine.schedule;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.TaskStateData;
import dev.frostguard.api.runtime.WorkspacePaths;
import dev.frostguard.api.runtime.WorkspaceSession;
import dev.frostguard.engine.service.ProfileService;
import dev.frostguard.engine.service.TaskManagementService;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** A running task has to be visible where its Today and Tasks row looks for it. */
class TaskQueueLiveStateTest {

    private static final class KeyedTask extends DelayedTask {

        private final String key;

        private KeyedTask(AccountDescriptor profile, TpDailyTaskEnum type, String key) {
            super(profile, type);
            this.key = key;
        }

        @Override
        protected Object getDistinctKey() {
            return key;
        }

        @Override
        protected void execute() {
        }
    }

    @BeforeAll
    static void initializeWorkspace() {
        WorkspaceSession.initializeLayout(WorkspacePaths.current());
    }

    private static AccountDescriptor persistedProfile() {
        AccountDescriptor profile = new AccountDescriptor(null, "Live state " + UUID.randomUUID(), "0", false, 100L, 30L);
        assertTrue(ProfileService.obtain().createAccount(profile));
        return profile;
    }

    private static void assertRunningOnPlainRow(TpDailyTaskEnum type, String key) {
        AccountDescriptor profile = persistedProfile();

        new TaskQueue(profile).recordPreExecution(new KeyedTask(profile, type, key));

        TaskStateData state = TaskManagementService.shared().lookupTaskState(profile.getId(), type.getId());
        assertTrue(state != null && state.isExecuting(), type + " is not running on its row");
        if (key != null) {
            assertNull(TaskManagementService.shared().lookupTaskState(profile.getId(), type.getId(), key));
        }
    }

    @Test
    void chatCaptureRunsOnItsPlainRow() {
        assertRunningOnPlainRow(TpDailyTaskEnum.CHAT_CAPTURE, "chat_capture");
    }

    @Test
    void cryptidHostingRunsOnItsPlainRow() {
        assertRunningOnPlainRow(TpDailyTaskEnum.EVENT_CRYPTID_HOST, "cryptid_host");
    }

    @Test
    void aTaskWithoutADistinctKeyRunsOnItsPlainRow() {
        assertRunningOnPlainRow(TpDailyTaskEnum.MONUMENT, null);
    }

    @Test
    void aCustomTaskKeepsItsOwnRow() {
        AccountDescriptor profile = persistedProfile();

        new TaskQueue(profile).recordPreExecution(new KeyedTask(profile, TpDailyTaskEnum.CUSTOM_TASK, "my.CustomTask"));

        TaskStateData state = TaskManagementService.shared().lookupTaskState(
                profile.getId(), TpDailyTaskEnum.CUSTOM_TASK.getId(), "my.CustomTask");
        assertTrue(state != null && state.isExecuting());
        assertNull(TaskManagementService.shared().lookupTaskState(profile.getId(), TpDailyTaskEnum.CUSTOM_TASK.getId()));
    }
}
