package dev.frostguard.engine.nav;

/**
 * Keeps the bot off the march Recall confirmation.
 *
 * <p>"Recall Troops?" is the one dialog on the World screen whose Confirm destroys something the
 * player cannot get back: a gather march part-way through its run, with its haul. On 2026-11-11
 * three saved frames caught the bot sitting on that dialog over live gather marches
 * (20261111T045932, T070403, T121940), each one straight after a sidebar tab tap had gone astray
 * on World. Nothing confirmed them, but only because recovery happened to press back first, and
 * one of those passes injected a template-matched Alliance Help tap while the modal was up.
 *
 * <p>So the rule is explicit rather than incidental: when this dialog is on screen, the only move
 * is back, which is Cancel. Never Confirm, and no other tap until it is gone.
 *
 * <p>The signal is the dialog's own body text, which is identical in all three frames. That makes
 * it an English-client check: a localised client would not match, and would fall back to the
 * behaviour that existed before this guard rather than to anything worse.
 */
public interface RecallConfirmationGuard {

    /** Whether the march Recall confirmation is on screen right now. */
    boolean dialogOnScreen();

    /** Presses back once, which the dialog treats as Cancel. */
    void pressBack();

    /** How many back presses to spend clearing the dialog before giving up on this pass. */
    int MAX_BACK_PRESSES = 2;

    /**
     * Clears the dialog if it is up.
     *
     * @return true when the screen is free of it, false when it is still there and the caller must
     *         not tap anything.
     */
    default boolean clear() {
        for (int attempt = 0; attempt <= MAX_BACK_PRESSES; attempt++) {
            if (!dialogOnScreen()) {
                return true;
            }
            if (attempt == MAX_BACK_PRESSES) {
                break;
            }
            pressBack();
        }
        return false;
    }
}
