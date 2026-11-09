package dev.frostguard.app.panel.today;

/**
 * When the page reloads. Events give the fast path, and the timer catches anything with no event:
 * the daily reset, a streak changed by another process, or an event that fired before its write.
 */
final class TodayRefreshSchedule {

    static final int BACKSTOP_SECONDS = 10;

    enum Action { NONE, RELOAD, DEBOUNCED_RELOAD }

    private int secondsSinceReload;

    /** A change while the page is hidden needs nothing: being shown always reloads. */
    Action onEvent(boolean showing) {
        return showing ? Action.DEBOUNCED_RELOAD : Action.NONE;
    }

    Action onShown() {
        return Action.RELOAD;
    }

    Action onSecond() {
        return ++secondsSinceReload >= BACKSTOP_SECONDS ? Action.RELOAD : Action.NONE;
    }

    void reloaded() {
        secondsSinceReload = 0;
    }
}
