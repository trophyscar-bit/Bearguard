package dev.frostguard.engine.emulator.instance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MuMuManagerProcessTest {

    @TempDir
    Path tempDir;

    @Test
    void acceptsRunningStateWhenManagerExits() throws Exception {
        assertEquals(MuMuEmulatorInstance.ProbeState.RUNNING, MuMuEmulatorInstance.readRunningState(
                childProcess("running", tempDir.resolve("normal.pid")), Duration.ofSeconds(5)));
    }

    @Test
    void reportsStoppedStateWhenManagerExits() throws Exception {
        assertEquals(MuMuEmulatorInstance.ProbeState.STOPPED, MuMuEmulatorInstance.readRunningState(
                childProcess("stopped", tempDir.resolve("stopped.pid")), Duration.ofSeconds(5)));
    }

    @Test
    void reportsUnknownWhenManagerCommandFails() throws Exception {
        assertEquals(MuMuEmulatorInstance.ProbeState.UNKNOWN, MuMuEmulatorInstance.readRunningState(
                childProcess("failed", tempDir.resolve("failed.pid")), Duration.ofSeconds(5)));
    }

    @Test
    void reportsUnknownRatherThanStoppedWhenProbeTimesOut() throws Exception {
        Path pidFile = tempDir.resolve("timeout.pid");
        long startedAt = System.nanoTime();

        MuMuEmulatorInstance.ProbeState state = MuMuEmulatorInstance.readRunningState(
                childProcess("hang", pidFile), Duration.ofSeconds(3));

        // A busy machine is not a dead emulator: scoring this STOPPED is what made
        // callers drop a working device lease.
        assertEquals(MuMuEmulatorInstance.ProbeState.UNKNOWN, state);
        assertTrue(Duration.ofNanos(System.nanoTime() - startedAt).compareTo(Duration.ofSeconds(6)) < 0);
        assertProcessExited(readPid(pidFile));
    }

    @Test
    void interruptionKillsManagerAndReturnsControl() throws Exception {
        Path pidFile = tempDir.resolve("interrupt.pid");
        AtomicBoolean interrupted = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                MuMuEmulatorInstance.readRunningState(
                        childProcess("hang", pidFile), Duration.ofSeconds(30));
            } catch (InterruptedException expected) {
                interrupted.set(true);
            } catch (Throwable unexpected) {
                failure.set(unexpected);
            }
        });

        worker.start();
        long childPid = readPid(pidFile);
        worker.interrupt();
        worker.join(2_000);

        assertFalse(worker.isAlive());
        assertTrue(interrupted.get());
        assertNull(failure.get());
        assertProcessExited(childPid);
    }

    @Test
    void inconclusiveProbeKeepsTheLastStateTheEmulatorGave() {
        assertTrue(MuMuEmulatorInstance.resolveRunning(MuMuEmulatorInstance.ProbeState.UNKNOWN, true));
        assertFalse(MuMuEmulatorInstance.resolveRunning(MuMuEmulatorInstance.ProbeState.UNKNOWN, false));
    }

    @Test
    void conclusiveProbeOverridesTheLastState() {
        assertTrue(MuMuEmulatorInstance.resolveRunning(MuMuEmulatorInstance.ProbeState.RUNNING, false));
        assertFalse(MuMuEmulatorInstance.resolveRunning(MuMuEmulatorInstance.ProbeState.STOPPED, true));
    }

    private ProcessBuilder childProcess(String action, Path pidFile) {
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        return new ProcessBuilder(
                javaExecutable,
                "-cp",
                System.getProperty("java.class.path"),
                ChildProcess.class.getName(),
                action,
                pidFile.toString());
    }

    private long readPid(Path pidFile) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline) {
            if (Files.exists(pidFile)) {
                String pid = Files.readString(pidFile).trim();
                if (!pid.isEmpty()) {
                    return Long.parseLong(pid);
                }
            }
            Thread.sleep(20);
        }
        return fail("child process did not publish its PID before the deadline");
    }

    private void assertProcessExited(long pid) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)
                && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
    }

    static final class ChildProcess {
        public static void main(String[] args) throws Exception {
            Files.writeString(Path.of(args[1]), Long.toString(ProcessHandle.current().pid()));
            if ("stopped".equals(args[0])) {
                System.out.println("state=stopped");
                return;
            }
            System.out.println("state=start_finished");
            System.out.flush();
            if ("failed".equals(args[0])) {
                System.exit(7);
            }
            if ("hang".equals(args[0])) {
                Thread.sleep(30_000);
            }
        }
    }
}
