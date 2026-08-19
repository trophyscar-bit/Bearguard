package dev.frostguard.engine.helper;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.engine.emulator.EmulatorController;

import java.util.concurrent.CancellationException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Single shared implementation of the "quit game?" confirmation-dialog recovery, so every Back
 * press in the codebase gets the same protection instead of each call site inventing its own.
 *
 * <p>Dave's #252 review flagged two real gaps in the original version of this guard (which
 * lived only in {@code NavigationHelper}): (1) the dialog was checked for immediately after the
 * ADB Back command with no settle delay, so a dialog that animates in a beat late was missed, and
 * (2) several direct {@code EmulatorController.pressBack(...)} call sites across the codebase
 * (AllianceHelper, CharacterSwitchHelper, IntelScreenHelper, StaminaHelper,
 * FurnaceUpgradeInjectionRule, TaskBuilderService) never went through the guard at all, so
 * "anywhere" coverage wasn't actually anywhere. This class fixes both: one settle delay before the
 * first check, a bounded retry (tap Cancel, re-check, tap again if it's somehow still up), and
 * every Back call site below now routes through here.</p>
 */
public final class QuitDialogGuard {

    private static final Logger log = LoggerFactory.getLogger(QuitDialogGuard.class);

    private QuitDialogGuard() {}

    // Time for the dialog's pop-in animation to finish before the first check is trustworthy.
    private static final long SETTLE_MS = 350L;
    // Cancel button. NOT yet backed by a committed full-frame reference showing the button itself
    // -- the only committed template, quitGameDialog.png (420x75), is a title-only detection crop
    // with no button geometry in it (Dave's #252 re-review). This coordinate needs a live capture
    // of the full dialog to verify or correct; flagging honestly rather than re-guessing it here.
    private static final PointData CANCEL_BUTTON = new PointData(207, 789);
    private static final int MAX_DISMISS_ATTEMPTS = 2;
    private static final long POST_TAP_WAIT_MS = 500L;

    /** Presses Back on the given emulator, then checks for and dismisses the quit-game dialog. */
    public static void pressBackSafely(EmulatorController emu, String device) {
        emu.pressBack(device);
        dismissIfPresent(emu, device);
    }

    /**
     * Checks for the quit-game dialog and dismisses it if present. Safe to call after any action
     * that might have triggered it (not just Back) — a no-op when the dialog isn't there.
     *
     * <p>Dave's #252 re-review: after the last Cancel tap the loop used to exit without re-checking,
     * so a genuinely-stuck dialog was silently reported as handled. Now performs one final detection
     * after the last attempt so the caller's return implies "confirmed gone", not just "we tapped
     * it". Also, {@link #sleep} used to swallow {@link InterruptedException} by only restoring the
     * interrupt flag and continuing, letting screenshot/tap activity carry on during a shutdown or
     * preemption; it now aborts the guard immediately via {@link CancellationException} instead.</p>
     */
    public static void dismissIfPresent(EmulatorController emu, String device) {
        sleep(SETTLE_MS);
        for (int attempt = 1; attempt <= MAX_DISMISS_ATTEMPTS; attempt++) {
            ImageSearchResultData dialog = emu.locatePattern(device, TemplatesEnum.QUIT_GAME_DIALOG, 90);
            if (!dialog.isFound()) {
                if (attempt > 1) {
                    log.info("Quit-game dialog on {} confirmed dismissed after {} attempt(s).", device, attempt - 1);
                }
                return; // not present, or successfully dismissed by a prior attempt in this loop
            }
            log.info("Quit-game dialog detected on {} (attempt {}/{}) -- tapping Cancel.",
                    device, attempt, MAX_DISMISS_ATTEMPTS);
            emu.tapInteractions(device).tapNear(CANCEL_BUTTON);
            sleep(POST_TAP_WAIT_MS);
        }
        // Final verification: confirm the last tap actually closed it rather than assuming success
        // because the attempt budget ran out. Two confirmed-present tap attempts and it's still up
        // -- leave it; a third blind tap risks hitting something else entirely if this isn't
        // actually the dialog.
        ImageSearchResultData stillPresent = emu.locatePattern(device, TemplatesEnum.QUIT_GAME_DIALOG, 90);
        if (stillPresent.isFound()) {
            log.warn("Quit-game dialog still visible on {} after {} dismiss attempts -- leaving it rather than guessing further.",
                    device, MAX_DISMISS_ATTEMPTS);
        } else {
            log.info("Quit-game dialog on {} confirmed dismissed after {} attempt(s).", device, MAX_DISMISS_ATTEMPTS);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancellationException("QuitDialogGuard interrupted");
        }
    }
}
