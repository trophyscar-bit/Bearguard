package dev.frostguard.engine.nav;

/**
 * Closes a World map tile popup before anything is tapped beneath it.
 *
 * <p>Tapping a map tile opens a card over the map: a beast with "Possible Drops" and Attack, a
 * player city with Visit Island / Gift / Reinforce, or one of the owner's own gather tiles with
 * Details and <b>Recall</b>. The World anchor still matches with the card open, so the screen reads
 * as a clean root, and the sidebar trigger tap goes into the card instead of opening the panel.
 * Every "Sidebar did not open" frame on 2026-11-13 that was not a Recall confirmation or a march
 * menu showed one of these cards (10 of 14).
 *
 * <p>The only move is back, which closes the card without acting on it. Nothing inside the card is
 * ever tapped: Attack, Recall, Rally, Scout and the rest all spend or cancel something.
 *
 * <p>Back on a clean World map asks to quit the game, so back is only pressed on a positive
 * reading, once per call, and never pressed again on the same reading.
 */
public interface WorldTilePopupGuard {

    /** Whether a tile popup is over the map right now. */
    boolean popupOnScreen();

    /** Presses back once, which closes the popup. */
    void pressBack();

    /**
     * Closes the popup if one is up.
     *
     * @return true when no popup is on screen, false when one is still there and the caller must
     *         not tap anything beneath it.
     */
    default boolean clear() {
        if (!popupOnScreen()) {
            return true;
        }
        pressBack();
        return !popupOnScreen();
    }
}
