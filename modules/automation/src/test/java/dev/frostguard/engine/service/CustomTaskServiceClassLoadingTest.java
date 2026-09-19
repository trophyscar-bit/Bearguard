package dev.frostguard.engine.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.runtime.WorkspacePaths;
import dev.frostguard.api.runtime.WorkspaceSession;
import dev.frostguard.engine.schedule.DelayedTask;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CustomTaskServiceClassLoadingTest {

    @BeforeAll
    static void initializeTestWorkspace() {
        WorkspaceSession.initializeLayout(WorkspacePaths.current());
    }

    @Test
    void loadingAnotherTaskKeepsNestedClassesOfEarlierTasksLoadable(@TempDir Path sources) throws Exception {
        CustomTaskService service = CustomTaskService.getInstance();
        assertEquals("LoaderProbeFirst", service.compileAndLoad(write(sources, "LoaderProbeFirst", """
                public class LoaderProbeFirst extends DelayedTask {
                    private record Card(String title) {
                    }

                    public LoaderProbeFirst(AccountDescriptor profile, TpDailyTaskEnum task) {
                        super(profile, task);
                    }

                    public String readCard() {
                        return new Card("nested").title();
                    }

                    @Override
                    protected void execute() {
                    }
                }
                """).toFile()));
        AccountDescriptor profile = new AccountDescriptor(
                null, "Loader probe " + UUID.randomUUID(), "0", false, 100L, 30L);
        assertTrue(ProfileService.obtain().createAccount(profile));
        DelayedTask first = service.createTask("LoaderProbeFirst", profile);
        assertNotNull(first);

        assertEquals("LoaderProbeSecond", service.compileAndLoad(write(sources, "LoaderProbeSecond", """
                public class LoaderProbeSecond extends DelayedTask {
                    public LoaderProbeSecond(AccountDescriptor profile, TpDailyTaskEnum task) {
                        super(profile, task);
                    }

                    @Override
                    protected void execute() {
                    }
                }
                """).toFile()));

        // The nested record is first touched only now, after the second task replaced the loader.
        assertEquals("nested", first.getClass().getMethod("readCard").invoke(first));
    }

    private static Path write(Path dir, String className, String body) throws IOException {
        Path file = dir.resolve(className + ".java");
        Files.writeString(file, """
                package dev.frostguard.engine.listener.task.impl;

                import dev.frostguard.api.configs.TpDailyTaskEnum;
                import dev.frostguard.api.domain.AccountDescriptor;
                import dev.frostguard.engine.schedule.DelayedTask;

                """ + body);
        return file;
    }
}
