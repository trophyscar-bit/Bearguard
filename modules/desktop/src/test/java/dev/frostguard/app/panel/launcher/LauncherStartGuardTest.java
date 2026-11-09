package dev.frostguard.app.panel.launcher;

import dev.frostguard.api.runtime.WorkspacePaths;
import dev.frostguard.api.runtime.WorkspaceSession;
import dev.frostguard.app.shared.JavaFxToolkit;
import dev.frostguard.engine.service.ProdInstanceGuard;
import dev.frostguard.engine.service.ProdInstanceGuard.ProdState;
import javafx.application.Platform;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LauncherStartGuardTest {

    private final List<String> launches = new ArrayList<>();
    private final List<String> refusals = new ArrayList<>();

    @BeforeAll
    static void start() throws InterruptedException {
        WorkspaceSession.initializeLayout(WorkspacePaths.current());
        JavaFxToolkit.start();
    }

    @AfterEach
    void clearForcedState() throws ReflectiveOperationException {
        field("cachedState").set(null, null);
    }

    private static Field field(String name) throws ReflectiveOperationException {
        Field field = ProdInstanceGuard.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private void startBotWith(ProdState state) throws ReflectiveOperationException, InterruptedException {
        assertTrue(ProdInstanceGuard.isSeparateInstance(), "tests must not run from the production folder");
        field("cachedState").set(null, state);
        field("cachedAtNanos").setLong(null, System.nanoTime());
        LauncherActionController controller = new LauncherActionController(null, () -> launches.add("launch"), refusals::add);

        controller.startBot();

        CountDownLatch drained = new CountDownLatch(1);
        Platform.runLater(drained::countDown);
        assertTrue(drained.await(10, TimeUnit.SECONDS));
    }

    @Test
    void startIsRefusedWhileProductionRuns() throws Exception {
        startBotWith(ProdState.RUNNING);
        assertEquals(List.of(), launches);
        assertEquals(1, refusals.size());
    }

    @Test
    void startIsRefusedWhenProductionCannotBeChecked() throws Exception {
        startBotWith(ProdState.UNKNOWN);
        assertEquals(List.of(), launches);
        assertEquals(1, refusals.size());
    }

    @Test
    void startGoesAheadOnceProductionIsClosed() throws Exception {
        startBotWith(ProdState.NOT_RUNNING);
        assertEquals(List.of("launch"), launches);
        assertEquals(List.of(), refusals);
    }
}
