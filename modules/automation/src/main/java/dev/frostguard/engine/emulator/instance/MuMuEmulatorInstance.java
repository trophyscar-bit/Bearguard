package dev.frostguard.engine.emulator.instance;

import dev.frostguard.engine.emulator.BoundedProcessRunner;
import dev.frostguard.engine.emulator.EmulatorInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Controls MuMu Player emulator instances through the {@code MuMuManager.exe}
 * CLI.  Handles lifecycle operations (boot / shutdown) and ADB serial
 * resolution using MuMu's port-mapping convention.
 */
public class MuMuEmulatorInstance extends EmulatorInstance {

    private static final Logger log = LoggerFactory.getLogger(MuMuEmulatorInstance.class);

    private static final int ADB_BASE_PORT = 16384;
    private static final int ADB_PORT_STRIDE = 32;
    private static final Duration STATE_PROBE_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration ACTION_TIMEOUT = Duration.ofSeconds(45);
    private static final String RUNNING_TOKEN = "state=start_finished";

    /**
     * What a probe actually established. A probe that timed out established nothing,
     * and that is not the same answer as "stopped".
     */
    enum ProbeState { RUNNING, STOPPED, UNKNOWN }

    private final Map<String, Boolean> lastKnownRunning = new ConcurrentHashMap<>();

    public MuMuEmulatorInstance(String executablePath) {
        super(executablePath);
    }

    /**
     * MuMu ADB port formula: {@code 16384 + index × 32}.
     */
    @Override
    protected String getDeviceSerial(String identifier) {
        int idx = Integer.parseInt(identifier);
        return "127.0.0.1:" + (ADB_BASE_PORT + idx * ADB_PORT_STRIDE);
    }

    @Override
    public void launchEmulator(String identifier) {
        executeManagerAction(identifier, "launch_player");
        log.info("Launch requested for MuMu instance {}", identifier);
    }

    @Override
    public void closeEmulator(String identifier) {
        executeManagerAction(identifier, "shutdown_player");
        log.info("Shutdown requested for MuMu instance {}", identifier);
    }

    @Override
    public boolean isRunning(String identifier) {
        ProbeState state = ProbeState.UNKNOWN;
        try {
            state = readRunningState(
                    buildManagerProcess(identifier, "player_state"), STATE_PROBE_TIMEOUT);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.debug("State probe interrupted for MuMu #{}", identifier);
        } catch (IOException ioe) {
            log.error("Cannot query MuMu state for instance #{}", identifier, ioe);
        }
        boolean previous = lastKnownRunning.getOrDefault(identifier, Boolean.FALSE);
        boolean running = resolveRunning(state, previous);
        if (state == ProbeState.UNKNOWN) {
            log.warn("MuMu #{} state probe inconclusive; keeping last known state ({})",
                    identifier, running ? "running" : "not running");
        } else {
            lastKnownRunning.put(identifier, running);
        }
        return running;
    }

    /**
     * What to report for a probe outcome. An inconclusive probe keeps the last answer
     * the emulator actually gave: reporting "stopped" from a probe that never answered
     * makes callers tear down a working device -- EmulatorController drops and
     * re-acquires the slot, TaskQueue declares the device offline and reconnects --
     * and a machine too busy to start a CLI process within the timeout is not evidence
     * that MuMu died.
     */
    static boolean resolveRunning(ProbeState state, boolean lastKnownRunning) {
        return state == ProbeState.UNKNOWN ? lastKnownRunning : state == ProbeState.RUNNING;
    }

    // ── internal helpers ─────────────────────────────────────────────

    private Path resolveManagerBinary() {
        return Paths.get(consolePath, "MuMuManager.exe");
    }

    private ProcessBuilder buildManagerProcess(String instanceId, String action) {
        List<String> cmd = List.of(
                resolveManagerBinary().toString(),
                "api", "-v", instanceId, action);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(Paths.get(consolePath).getParent().toFile());
        return pb;
    }

    private void executeManagerAction(String instanceId, String action) {
        try {
            BoundedProcessRunner.ProcessResult result = BoundedProcessRunner.run(
                    buildManagerProcess(instanceId, action), ACTION_TIMEOUT);
            if (result.timedOut()) {
                log.error("MuMu action '{}' timed out after {} seconds; killed the manager process",
                        action, ACTION_TIMEOUT.toSeconds());
            } else if (result.exitCode() != 0) {
                log.error("MuMu action '{}' exited with code {}", action, result.exitCode());
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.debug("MuMu action '{}' interrupted; killed the manager process", action);
        } catch (IOException ioe) {
            log.error("Failed to execute MuMu action '{}'", action, ioe);
        }
    }

    static ProbeState readRunningState(ProcessBuilder managerProcess, Duration timeout)
            throws IOException, InterruptedException {
        BoundedProcessRunner.ProcessResult result = BoundedProcessRunner.run(managerProcess, timeout);
        if (result.timedOut()) {
            log.warn("MuMu state probe timed out after {} seconds; killed the manager process",
                    timeout.toSeconds());
            return ProbeState.UNKNOWN;
        }
        if (result.exitCode() != 0) {
            log.warn("MuMu state probe exited with code {}", result.exitCode());
            return ProbeState.UNKNOWN;
        }
        return result.output().contains(RUNNING_TOKEN) ? ProbeState.RUNNING : ProbeState.STOPPED;
    }
}
