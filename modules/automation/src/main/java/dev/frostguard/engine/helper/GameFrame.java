package dev.frostguard.engine.helper;

import dev.frostguard.api.domain.RawImageData;

/**
 * Tells whether a capture can be the game at all. The game is a portrait 720x1280 screen; the emulator's
 * launcher, and anything else that has taken over, is landscape. Back pressed on the launcher or on a bare
 * game screen can open the exit prompt, so nothing presses it once the game is known to be gone.
 */
public final class GameFrame {

    private GameFrame() {
    }

    /** True only when a valid frame is landscape. A missing or unreadable frame is not proof either way. */
    public static boolean isNotTheGame(RawImageData frame) {
        return frame != null && frame.isValid() && frame.getFrameWidth() > frame.getFrameHeight();
    }
}
