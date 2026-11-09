package dev.frostguard.app.panel.launcher;

import dev.frostguard.engine.service.ProdInstanceGuard;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Runs the production check during the auto-start countdown. The check lists the process table,
 * which takes this PC about 12 seconds, and running it after the countdown made a 45-second start
 * take 57. One check runs at once, for its timing, and a second is placed to finish just before
 * the countdown ends.
 *
 * <p>The process table can only be read slowly, but the list of running Java processes can be read
 * at once. It is noted before each check, so a Java process that appears after the last check
 * makes the countdown ask again and start late rather than start blind.
 */
final class AutoStartGuardWatch {

    /** How long before the end of the countdown the last check should have finished. */
    static final int FINAL_CHECK_MARGIN_SECONDS = 2;

    private static final String UNCONFIRMED = "Could not confirm that production Bearguard is closed, so the bot was not started.";

    private record Check(Set<Long> javaProcessesBefore, String refusal) {
    }

    private final Supplier<String> guard;
    private final Supplier<Set<Long>> javaProcesses;
    private final Executor executor;

    private Check latest;
    private long lastCheckSeconds;
    private boolean checking;
    private boolean finalCheckStarted;
    private boolean stopped;

    AutoStartGuardWatch(Supplier<String> guard, Supplier<Set<Long>> javaProcesses, Executor executor) {
        this.guard = guard;
        this.javaProcesses = javaProcesses;
        this.executor = executor;
    }

    static AutoStartGuardWatch forProduction() {
        return new AutoStartGuardWatch(ProdInstanceGuard::freshStartRefusal, AutoStartGuardWatch::runningJavaProcesses,
                command -> Thread.ofPlatform().daemon().name("Auto-Start-Guard").start(command));
    }

    static Set<Long> runningJavaProcesses() {
        return ProcessHandle.allProcesses()
                .filter(process -> process.info().command().map(AutoStartGuardWatch::isJava).orElse(false))
                .map(ProcessHandle::pid)
                .collect(Collectors.toSet());
    }

    static boolean isJava(String executablePath) {
        String lower = executablePath.toLowerCase(Locale.ROOT);
        return lower.endsWith("\\javaw.exe") || lower.endsWith("\\java.exe");
    }

    void begin() {
        synchronized (this) {
            checking = true;
        }
        executor.execute(this::runCheck);
    }

    /** Called once a second by the countdown. */
    void onSecondsRemaining(int secondsRemaining) {
        synchronized (this) {
            if (stopped || checking || finalCheckStarted
                    || secondsRemaining > lastCheckSeconds + FINAL_CHECK_MARGIN_SECONDS) {
                return;
            }
            finalCheckStarted = true;
            checking = true;
        }
        executor.execute(this::runCheck);
    }

    /** Stops anything further from being asked; a check already under way is left to finish unheard. */
    synchronized void stop() {
        stopped = true;
        notifyAll();
    }

    /**
     * The reason not to start, or null to start. Called when the countdown ends, off the FX thread;
     * waits for a check still under way, so an unfinished check can never read as a pass.
     */
    String verdict() {
        Check check;
        try {
            check = awaitLatest();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return UNCONFIRMED;
        }
        if (check == null || !check.javaProcessesBefore().containsAll(javaProcesses.get())) {
            return ask();
        }
        return check.refusal();
    }

    private synchronized Check awaitLatest() throws InterruptedException {
        while (checking && !stopped) {
            wait();
        }
        return latest;
    }

    private String ask() {
        try {
            return guard.get();
        } catch (RuntimeException failure) {
            return UNCONFIRMED;
        }
    }

    private void runCheck() {
        Set<Long> before = javaProcesses.get();
        long started = System.nanoTime();
        String refusal = ask();
        long seconds = (System.nanoTime() - started + 999_999_999L) / 1_000_000_000L;
        synchronized (this) {
            latest = new Check(before, refusal);
            lastCheckSeconds = seconds;
            checking = false;
            notifyAll();
        }
    }
}
