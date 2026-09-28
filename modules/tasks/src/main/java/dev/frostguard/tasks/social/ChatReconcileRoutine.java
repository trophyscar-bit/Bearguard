package dev.frostguard.tasks.social;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;

/**
 * A one-off catch-up of chat after the bot was away, run once and then left inert. The first run
 * covered World then Alliance; this one is Alliance alone (see {@link #includesChannel}).
 *
 * <p>It is Chat Capture with three things changed: it reconciles both channels whatever the nightly
 * schedule says, it scrolls for thirty minutes each, and it does not come back. Everything that reads,
 * de-duplicates and files messages is the ordinary routine's -- including estimating a time for
 * what it recovers -- so there is one implementation of that, not two.
 *
 * <p>Thirty minutes is what was asked for. At the measured half a screen a second and about 1.6
 * new messages a screen that is roughly 900 screens a channel, about 1,400 messages. The walk
 * starts from the newest message, so it must first scroll back through everything already stored,
 * and a gap older than the budget reaches shows up in the log as {@code GAP NOT BRIDGED} rather
 * than being reported as recovered.
 *
 * <p>It queues itself because it shares Chat Capture's enable flag, which is why it needs no
 * setting. The guard against repeating is a marker file written before the pass starts: a crash
 * mid-pass must not become a crash loop, since the queue retries a throwing task at once.
 */
public class ChatReconcileRoutine extends ChatCaptureRoutine {

    static final long BUDGET_MS = 30 * 60_000L;

    /** Names the outage this was written for, so a later one can be given its own run. */
    static final String MARKER_NAME = "reconcile-once-2026-09-28-alliance.done";

    public ChatReconcileRoutine(AccountDescriptor profile, TpDailyTaskEnum tpTask) {
        super(profile, tpTask);
    }

    @Override
    protected Object getDistinctKey() {
        return "chat_reconcile";
    }

    @Override
    boolean forcedReconcile() {
        return true;
    }

    /**
     * Alliance only this time. The first run walked World for the full thirty minutes, and Alliance
     * stopped after 108 screens when the game closed the chat panel under it, so only Alliance is
     * owed.
     */
    @Override
    boolean includesChannel(String channel) {
        return "alliance".equals(channel);
    }

    @Override
    void applySettingOverrides() {
        reconcileBudgetMs = BUDGET_MS;
    }

    @Override
    protected void execute() {
        Path marker = marker();
        if (Files.exists(marker)) {
            logInfo("ChatReconcileRoutine | The one-off catch-up already ran; nothing to do.");
            setRecurring(false);
            return;
        }
        try {
            Files.createDirectories(marker.getParent());
            Files.writeString(marker, Instant.now().toString());
        } catch (IOException e) {
            // Without the marker it would repeat at every launch, so it does not run at all.
            logWarning("ChatReconcileRoutine | Could not write " + marker + " (" + e.getMessage()
                    + "); skipping rather than risk repeating.");
            setRecurring(false);
            return;
        }
        logInfo("ChatReconcileRoutine | One-off catch-up starting: " + (BUDGET_MS / 60_000L)
                + " minutes on Alliance.");
        try {
            super.execute();
        } finally {
            // The ordinary routine reschedules itself at the end of a pass. This one must not.
            setRecurring(false);
        }
    }

    /** Beside the transcript, in the folder the reader setting selects -- the ordinary routine's. */
    private Path marker() {
        String choice = profile.getConfig(ConfigurationKeyEnum.CHAT_READER_STRING, String.class);
        String reader = choice == null || choice.isBlank() ? "SERVICE" : choice.trim().toUpperCase();
        return transcriptDir(reader).resolve(MARKER_NAME);
    }
}
