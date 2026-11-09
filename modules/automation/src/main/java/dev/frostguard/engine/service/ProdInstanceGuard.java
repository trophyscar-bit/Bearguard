package dev.frostguard.engine.service;

import dev.frostguard.api.runtime.WorkspacePaths;
import dev.frostguard.engine.emulator.BoundedProcessRunner;
import dev.frostguard.engine.emulator.BoundedProcessRunner.ProcessResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Locale;

/**
 * Keeps a side-by-side install of the app from disturbing the production install at
 * {@code C:\Bearguard}. There is one emulator and one game account, so two bots on it would fight
 * over the device, and several things that are harmless alone (Telegram long-polling on the same
 * token, gift-code redemption, the {@code taskkill adb.exe} at shutdown) hit whatever else is
 * using them.
 *
 * <p>Every check is inert when this process <em>is</em> the production workspace, so merging this
 * class changes nothing for production.
 */
public final class ProdInstanceGuard {

    public enum ProdState { RUNNING, NOT_RUNNING, UNKNOWN }

    /** With the trailing separator: {@code C:\Bearguard} is a prefix of every sibling install. */
    static final String PROD_MARKER = "C:\\Bearguard\\";

    private static final Path PROD_ROOT = Paths.get("C:\\Bearguard");
    private static final Duration CACHE_TTL = Duration.ofSeconds(3);
    private static final Duration QUERY_TIMEOUT = Duration.ofSeconds(10);
    // Only Bearguard command lines are printed: BoundedProcessRunner decodes output as strict UTF-8,
    // and an unrelated process with a non-ASCII command line would otherwise fail the whole check.
    private static final String QUERY = "Get-CimInstance Win32_Process | Where-Object { "
            + "$_.Name -in 'javaw.exe','java.exe' -and $_.CommandLine -like '*frostguard.workspace*' } | "
            + "ForEach-Object { $_.CommandLine }";
    private static final Logger logger = LoggerFactory.getLogger(ProdInstanceGuard.class);

    private static ProdState cachedState;
    private static long cachedAtNanos;

    private ProdInstanceGuard() {
    }

    /** True when this process runs from anywhere other than the production folder. */
    public static boolean isSeparateInstance() {
        return isSeparateInstance(WorkspacePaths.current().root());
    }

    static boolean isSeparateInstance(Path workspaceRoot) {
        Path normalized = workspaceRoot.toAbsolutePath().normalize();
        return !normalized.toString().equalsIgnoreCase(PROD_ROOT.toString());
    }

    /** True for a command line launched from the production folder, and only that folder. */
    static boolean commandLineIsProd(String commandLine) {
        if (commandLine == null) {
            return false;
        }
        String lower = commandLine.toLowerCase(Locale.ROOT);
        return lower.contains("frostguard.workspace") && lower.contains(PROD_MARKER.toLowerCase(Locale.ROOT));
    }

    static ProdState stateFromOutput(ProcessResult result) {
        if (result.timedOut() || result.exitCode() != 0) {
            return ProdState.UNKNOWN;
        }
        return result.output().lines().anyMatch(ProdInstanceGuard::commandLineIsProd)
                ? ProdState.RUNNING
                : ProdState.NOT_RUNNING;
    }

    /** Whether production is running now. Blocks on a process query, so keep off the FX thread. */
    public static synchronized ProdState prodState() {
        long now = System.nanoTime();
        if (cachedState != null && now - cachedAtNanos < CACHE_TTL.toNanos()) {
            return cachedState;
        }
        cachedState = queryProdState();
        cachedAtNanos = System.nanoTime();
        return cachedState;
    }

    private static ProdState queryProdState() {
        ProcessBuilder builder = new ProcessBuilder(
                "powershell.exe", "-NoProfile", "-NonInteractive", "-Command", QUERY);
        try {
            return stateFromOutput(BoundedProcessRunner.run(builder, QUERY_TIMEOUT));
        } catch (IOException e) {
            logger.error("Could not check whether production Bearguard is running", e);
            return ProdState.UNKNOWN;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ProdState.UNKNOWN;
        }
    }

    /**
     * The reason this instance must not start the bot right now, or {@code null} when it may.
     * Unknown counts as refused: a false refusal costs a click, a false pass costs a live account.
     */
    public static String startRefusal() {
        if (!isSeparateInstance()) {
            return null;
        }
        return switch (prodState()) {
            case NOT_RUNNING -> null;
            case RUNNING -> "Production Bearguard is running. Close it before starting the bot here: "
                    + "there is one emulator and one game account.";
            case UNKNOWN -> "Could not confirm that production Bearguard is closed, so the bot was not started.";
        };
    }

    /** Whether this instance may do process-wide things that would disturb a running production. */
    public static boolean mayAffectSharedProcesses() {
        return !isSeparateInstance() || prodState() == ProdState.NOT_RUNNING;
    }
}
