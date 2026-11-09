package dev.frostguard.engine.service;

import dev.frostguard.api.domain.BotStateData;
import dev.frostguard.api.runtime.WorkspacePaths;
import dev.frostguard.api.runtime.WorkspaceSession;
import dev.frostguard.engine.listener.BotStateListener;
import dev.frostguard.engine.service.ProdInstanceGuard.ProdState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The guard's answers for each production state, and that the engine really asks it before starting. */
class ProdGuardLaunchTest {

    /** Stops at the point where a launch would reach the emulator, whether or not the guard works. */
    private static final class NoEmulatorScheduleService extends ScheduleService {

        private int bridgeAttempts;

        @Override
        boolean initializeBridge() {
            bridgeAttempts++;
            return false;
        }
    }

    @BeforeAll
    static void initializeTestWorkspace() {
        WorkspaceSession.initializeLayout(WorkspacePaths.current());
    }

    @AfterEach
    void clearForcedState() throws ReflectiveOperationException {
        field("cachedState").set(null, null);
    }

    /** Answers from the guard's own cache, so the tests do not depend on what this PC is running. */
    private static void force(ProdState state) throws ReflectiveOperationException {
        assertTrue(ProdInstanceGuard.isSeparateInstance(), "tests must not run from the production folder");
        field("cachedState").set(null, state);
        field("cachedAtNanos").setLong(null, System.nanoTime());
    }

    private static Field field(String name) throws ReflectiveOperationException {
        Field field = ProdInstanceGuard.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    @Test
    void startIsRefusedUnlessProductionIsKnownToBeClosed() throws ReflectiveOperationException {
        force(ProdState.RUNNING);
        assertNotNull(ProdInstanceGuard.startRefusal());
        force(ProdState.UNKNOWN);
        assertNotNull(ProdInstanceGuard.startRefusal());
        force(ProdState.NOT_RUNNING);
        assertNull(ProdInstanceGuard.startRefusal());
    }

    @Test
    void sharedProcessesAreLeftAloneUnlessProductionIsKnownToBeClosed() throws ReflectiveOperationException {
        force(ProdState.RUNNING);
        assertFalse(ProdInstanceGuard.mayAffectSharedProcesses());
        force(ProdState.UNKNOWN);
        assertFalse(ProdInstanceGuard.mayAffectSharedProcesses());
        force(ProdState.NOT_RUNNING);
        assertTrue(ProdInstanceGuard.mayAffectSharedProcesses());
    }

    @Test
    void engineDoesNotReachTheEmulatorWhileProductionRuns() throws ReflectiveOperationException {
        NoEmulatorScheduleService service = new NoEmulatorScheduleService();
        List<BotStateData> states = new ArrayList<>();
        service.addEngineObserver(BotStateListener.fromConsumer(states::add));
        force(ProdState.RUNNING);

        service.launchEngine(true);

        assertEquals(0, service.bridgeAttempts);
        assertEquals(1, states.size());
        assertFalse(states.get(0).isRunning());
    }

    @Test
    void engineGetsAsFarAsTheEmulatorOnceProductionIsClosed() throws ReflectiveOperationException {
        NoEmulatorScheduleService service = new NoEmulatorScheduleService();
        force(ProdState.NOT_RUNNING);

        service.launchEngine(true);

        assertEquals(1, service.bridgeAttempts);
    }
}
